package com.example

import java.util.Base64
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
    fun `recognizes but rejects current token bound groovy cdn`() {
        val config = """
            var config = {"sources":{"type":"video/mp4","file":"https://daisy.groovy.monster/stream/dead?token=Zm9v"}};jwplayer.key = 'key';
        """.trimIndent()
        val html = """<script>_juicycodes("${encode(config)}");</script>"""

        assertTrue(JuicyCodesPlayerParser.recognizes(html))
        assertNull(JuicyCodesPlayerParser.playback(html))
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
