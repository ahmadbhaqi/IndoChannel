package com.example

import java.util.Base64
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JuicyCodesPlayerParserTest {
    @Test
    fun `decodes current concatenated player configuration`() {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/116.0.0.0"
        val token = Base64.getEncoder().encodeToString(
            "203.0.113.10~~$userAgent".toByteArray(Charsets.UTF_8)
        )
        val config = """
            var config = {"tracks":[{"kind":"captions","label":"Indonesia","file":"https://cdn.example/sub.vtt"}],"sources":{"type":"application/x-mpegURL","file":"https://cdn.example/master.m3u8?token=$token"}};jwplayer.key = 'key';
        """.trimIndent()
        val payload = encode(config)
        val html = """<script>_juicycodes("${payload.take(40)}"+"${payload.drop(40)}");</script>"""

        val playback = JuicyCodesPlayerParser.playback(html)!!

        assertEquals("https://cdn.example/master.m3u8?token=$token", playback.media.single().url)
        assertTrue(playback.media.single().isHls)
        assertEquals(userAgent, playback.media.single().userAgent)
        assertEquals("https://cdn.example/sub.vtt", playback.tracks.single().url)
    }

    @Test
    fun `rejects malformed player payload`() {
        assertNull(JuicyCodesPlayerParser.playback("<script>_juicycodes('broken');</script>"))
    }

    @Test
    fun `accepts current token bound groovy CDN for runtime media verification`() {
        val userAgent = "Mozilla/5.0 (Linux; Android 14) Chrome/126.0.0.0"
        val token = Base64.getEncoder().encodeToString(
            "203.0.113.10~~$userAgent".toByteArray(Charsets.UTF_8)
        )
        val config = """
            var config = {"sources":{"type":"application/x-mpegURL","file":"https://daisy.groovy.monster/stream/master.m3u8?token=$token"}};jwplayer.key = 'key';
        """.trimIndent()
        val html = """<script>_juicycodes("${encode(config)}");</script>"""

        assertTrue(JuicyCodesPlayerParser.recognizes(html))
        val media = JuicyCodesPlayerParser.playback(html)!!.media.single()
        assertEquals(
            "https://daisy.groovy.monster/stream/master.m3u8?token=$token",
            media.url
        )
        assertTrue(media.isHls)
        assertEquals(userAgent, media.userAgent)
    }

    @Test
    fun `Juicy IP player prefers HTTP before certificate-bound HTTPS`() = runBlocking {
        val mediaUrl = "https://daisy.groovy.monster/stream/master.m3u8"
        val config = """
            var config = {"sources":{"type":"application/x-mpegURL","file":"$mediaUrl"}};jwplayer.key = 'key';
        """.trimIndent()
        val html = """<script>_juicycodes("${encode(config)}");</script>"""
        val requests = mutableListOf<String>()
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                requests += url
                if (url.startsWith("https://")) throw IOException("untrusted IP certificate")
                html
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { it }
        )

        assertTrue(
            session.resolve(
                "https://178.211.139.171/embed/current",
                "https://rebahinxxi3.lol/item/"
            )
        )
        assertEquals(
            listOf("http://178.211.139.171/embed/current"),
            requests
        )
        assertEquals(mediaUrl, links.single().url)
        assertEquals("http://178.211.139.171/embed/current", links.single().referer)
        assertEquals("*", links.single().headers["Accept-Language"])
    }

    @Test
    fun `Juicy IP player keeps HTTPS as a fallback when HTTP is unavailable`() = runBlocking {
        val mediaUrl = "https://daisy.groovy.monster/stream/master.m3u8"
        val config = """
            var config = {"sources":{"type":"application/x-mpegURL","file":"$mediaUrl"}};jwplayer.key = 'key';
        """.trimIndent()
        val html = """<script>_juicycodes("${encode(config)}");</script>"""
        val requests = mutableListOf<String>()
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                requests += url
                if (url.startsWith("http://")) throw IOException("HTTP mirror unavailable")
                html
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { it }
        )

        assertTrue(
            session.resolve(
                "https://178.211.139.171/embed/current",
                "https://rebahinxxi3.lol/item/"
            )
        )
        assertEquals(
            listOf(
                "http://178.211.139.171/embed/current",
                "https://178.211.139.171/embed/current"
            ),
            requests
        )
        assertEquals("https://178.211.139.171/embed/current", links.single().referer)
        assertEquals("*", links.single().headers["Accept-Language"])
    }

    @Test
    fun `IP player HTTP fallback preserves encoded path and query`() {
        assertEquals(
            "http://178.211.139.171/embed/a%2Fb?token=x%2Fy",
            publicIpHttpFallback(
                "https://178.211.139.171/embed/a%2Fb?token=x%2Fy"
            )
        )
        assertNull(publicIpHttpFallback("https://192.168.1.3/embed/current"))
    }

    @Test
    fun `Juicy fingerprint header is scoped to public IP player pages`() {
        assertEquals(
            mapOf("Accept-Language" to "*"),
            juicyCodesPlayerPageHeaders("http://178.211.139.171/embed/current")
        )
        assertEquals(
            mapOf("Accept-Language" to "*"),
            juicyCodesPlayerPageHeaders("https://199.87.210.226/embed/current")
        )
        assertTrue(juicyCodesPlayerPageHeaders("https://vidmoly.biz/embed-current.html").isEmpty())
        assertTrue(juicyCodesPlayerPageHeaders("http://192.168.1.3/embed/current").isEmpty())
    }

    private fun encode(decoded: String, salt: Int = 681): String {
        val symbols = "`%-+*\$!_^="
        val digitPayload = buildString(decoded.length * 4) {
            decoded.forEach { char ->
                val value = 1000 + salt + char.code
                value.toString().forEach { digit -> append(symbols[digit.digitToInt()]) }
            }
        }
        val encoded = Base64.getEncoder().encodeToString(digitPayload.toByteArray(Charsets.UTF_8))
        val saltSuffix = salt.toString().map { (it.digitToInt() + 100).toChar() }.joinToString("")
        return encoded + saltSuffix
    }
}
