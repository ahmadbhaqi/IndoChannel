package com.example

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PusatfilmCurrentPlayerTest {
    @Test
    fun `extracts current TurboVIP data hash HLS`() {
        val html = """
            <div class="container-fluid">
                <div
                    id="video_player"
                    data-hash="https://cdn.turboviplay.com/data3/current/current.m3u8">
                </div>
            </div>
        """.trimIndent()

        assertEquals(
            "https://cdn.turboviplay.com/data3/current/current.m3u8",
            PusatfilmCurrentPlayerParser.directMediaUrl(html)
        )
    }

    @Test
    fun `rejects unrelated local and oversized data hash values`() {
        assertNull(
            PusatfilmCurrentPlayerParser.directMediaUrl(
                """<div data-hash="https://decoy.example/video.m3u8"></div>"""
            )
        )
        assertNull(
            PusatfilmCurrentPlayerParser.directMediaUrl(
                """<div id="video_player" data-hash="http://127.0.0.1/private.m3u8"></div>"""
            )
        )
        assertNull(
            PusatfilmCurrentPlayerParser.directMediaUrl(
                " ".repeat(2_000_001) +
                    """<div id="video_player" data-hash="https://cdn.example/video.m3u8"></div>"""
            )
        )
    }

    @Test
    fun `current playback follows the bounded Turbo mirror before generic extraction`() = runBlocking {
        val kotak = "https://kotakajaib.me/embed/current"
        val hydrax = "https://playhydrax.com/?v=stale"
        val turbo = "https://emturbovid.com/t/current"
        val media = "https://cdn.turboviplay.com/data3/current/current.m3u8"
        val requests = mutableListOf<Pair<String, String>>()
        val resolvedMedia = mutableListOf<Pair<String, String>>()
        val playback = PusatfilmCurrentPlayback(
            pageFetcher = { url, referer ->
                requests += url to referer
                when (url) {
                    kotak -> """
                        <button class="server-item" data-frame="${encoded(hydrax)}"></button>
                        <button class="server-item" data-frame="${encoded(turbo)}"></button>
                    """.trimIndent()
                    turbo -> """<div id="video_player" data-hash="$media"></div>"""
                    else -> error("Unexpected fetch: $url")
                }
            },
            mediaResolver = { url, referer ->
                resolvedMedia += url to referer
                true
            }
        )

        assertTrue(playback.resolve(kotak, "https://v4.pusatfilm21info.com/current/"))
        assertEquals(
            listOf(
                kotak to "https://v4.pusatfilm21info.com/current/",
                turbo to kotak
            ),
            requests
        )
        assertEquals(listOf(media to turbo), resolvedMedia)
    }

    @Test
    fun `player page policy only admits current HTTPS player hosts`() {
        assertEquals(
            "https://kotakajaib.me/embed/current",
            PusatfilmPlayerPagePolicy.normalize("https://kotakajaib.me/embed/current")
        )
        assertEquals(
            "https://turbovidhls.com/t/current",
            PusatfilmPlayerPagePolicy.normalize("https://turbovidhls.com/t/current")
        )
        assertNull(PusatfilmPlayerPagePolicy.normalize("http://kotakajaib.me/embed/current"))
        assertNull(PusatfilmPlayerPagePolicy.normalize("https://kotakajaib.me.evil.example/embed/current"))
        assertNull(PusatfilmPlayerPagePolicy.normalize("https://127.0.0.1/private"))
    }

    private fun encoded(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
