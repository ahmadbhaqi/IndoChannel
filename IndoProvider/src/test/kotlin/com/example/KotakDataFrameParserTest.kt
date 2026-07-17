package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotakDataFrameParserTest {
    @Test
    fun `decodes current Kotakajaib server buttons`() {
        val html = """
            <button class="server-item" id="hydrax"
                data-frame="aHR0cHM6Ly9wbGF5aHlkcmF4LmNvbS8/dj1yOENWeUdSZmw=">HYDRAX</button>
            <button class="server-item active" id="turbovip"
                data-frame="aHR0cHM6Ly9lbXR1cmJvdmlkLmNvbS90LzZhNThlMDQyOTYzNGM=">TURBOVIP</button>
            <button class="server-item" data-frame="aHR0cHM6Ly9wbGF5aHlkcmF4LmNvbS8/dj1yOENWeUdSZmw=">duplicate</button>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://playhydrax.com/?v=r8CVyGRfl",
                "https://emturbovid.com/t/6a58e0429634c"
            ),
            KotakDataFrameParser.urls(html)
        )
    }

    @Test
    fun `rejects malformed relative and local destinations`() {
        val candidates = listOf(
            "not base64!",
            encoded("&button=no&subtitle="),
            encoded("javascript:alert(1)"),
            encoded("http://localhost/player"),
            encoded("http://127.0.0.1/player"),
            encoded("http://10.0.0.8/player"),
            encoded("http://[::1]/player")
        )
        val html = candidates.joinToString("") { payload ->
            "<button class=\"server-item\" data-frame=\"$payload\"></button>"
        }

        assertTrue(KotakDataFrameParser.urls(html).isEmpty())
    }

    @Test
    fun `rejects oversized html and payloads`() {
        val oversizedHtml = " ".repeat(2_000_001) +
            "<button class=\"server-item\" data-frame=\"${encoded("https://example.com/video")}\"></button>"
        val oversizedPayload = "<button class=\"server-item\" data-frame=\"${"A".repeat(8_193)}\"></button>"

        assertTrue(KotakDataFrameParser.urls(oversizedHtml).isEmpty())
        assertTrue(KotakDataFrameParser.urls(oversizedPayload).isEmpty())
    }

    @Test
    fun `caps the number of decoded server buttons`() {
        val html = (1..40).joinToString("") { index ->
            "<button class=\"server-item\" data-frame=\"${encoded("https://media$index.example/video")}\"></button>"
        }

        assertEquals(32, KotakDataFrameParser.urls(html).size)
    }

    @Test
    fun `extracts current TurboVIP direct MP4 and rejects non-video values`() {
        assertEquals(
            "https://e04.etvp.cc/uploads/6a58e0429634c.mp4",
            TurboVipPlayerParser.directUrl(
                "<script>var urlPlay = 'https://e04.etvp.cc/uploads/6a58e0429634c.mp4';</script>"
            )
        )
        assertTrue(
            TurboVipPlayerParser.directUrl(
                "<script>var urlPlay = 'https://edge.example/current/master.m3u8';</script>"
            ) == null
        )
        assertTrue(
            TurboVipPlayerParser.directUrl(
                "<script>var urlPlay = 'http://127.0.0.1/private.mp4';</script>"
            ) == null
        )
    }

    @Test
    fun `link session emits TurboVIP MP4 without generic extractor`() = runBlocking {
        val player = "https://emturbovid.com/t/current"
        val direct = "https://e04.etvp.cc/uploads/current.mp4"
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = PusatfilmProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                if (url == player) "<script>var urlPlay = '$direct';</script>" else error(url)
            },
            extractorLoader = { _, _, _, _ -> error("Generic extractor should not run") },
            mediaLinkProbe = { it }
        )

        assertTrue(session.resolve(player, "https://kotakajaib.me/embed/current"))
        assertEquals(direct, links.single().url)
        assertEquals(ExtractorLinkType.VIDEO, links.single().type)
    }

    @Test
    fun `link session advances across decoded Kotakajaib mirrors`() = runBlocking {
        val hydrax = "https://playhydrax.com/?v=dead"
        val turbo = "https://emturbovid.com/t/working"
        val html = listOf(hydrax, turbo).joinToString("") { url ->
            "<button class=\"server-item\" data-frame=\"${encoded(url)}\"></button>"
        }
        val requestedMirrors = mutableListOf<String>()
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = PusatfilmProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                if (url == "https://kotakajaib.me/embed/current") html else "<html></html>"
            },
            extractorLoader = { url, _, _, callback ->
                requestedMirrors += url
                if (url == turbo) {
                    callback(link("https://media.example/current/master.m3u8"))
                    true
                } else {
                    false
                }
            }
        )

        assertTrue(session.resolve("https://kotakajaib.me/embed/current", "https://provider.example/movie"))
        assertEquals(listOf(hydrax, turbo), requestedMirrors)
        assertEquals("https://media.example/current/master.m3u8", links.single().url)
    }

    private fun encoded(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    @Suppress("DEPRECATION_ERROR")
    private fun link(url: String): ExtractorLink = ExtractorLink(
        "test",
        "test",
        url,
        "",
        0,
        ExtractorLinkType.M3U8,
        emptyMap(),
        null
    )
}
