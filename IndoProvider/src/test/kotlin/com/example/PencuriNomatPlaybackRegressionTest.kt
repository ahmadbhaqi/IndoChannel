package com.example

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jsoup.Jsoup

class PencuriNomatPlaybackRegressionTest {
    private val sourceRoot = listOf(
        File("src/main/kotlin/com/example"),
        File("IndoProvider/src/main/kotlin/com/example")
    ).first { it.exists() }

    @Test
    fun `pencurimovie rewrites dsvplay embeds for the registered playmogo extractor`() {
        assertEquals(
            "https://playmogo.com/e/current-id?token=abc",
            PencurimovieParser.extractorCompatibleUrl(
                "https://dsvplay.com/e/current-id?token=abc"
            )
        )
        assertEquals(
            "https://voe.sx/e/current-id",
            PencurimovieParser.extractorCompatibleUrl("https://voe.sx/e/current-id")
        )
    }

    @Test
    fun `pencurimovie tries one mirror from each family before duplicate mirrors`() {
        val ordered = PencurimovieParser.orderedPlayerCandidates(
            listOf(
                "https://dsvplay.com/e/dood-one",
                "https://dsvplay.com/e/dood-two",
                "https://hgcloud.to/e/hg-one",
                "https://hgcloud.to/e/hg-two",
                "https://voe.sx/e/voe-one",
                "https://voe.sx/e/voe-two",
                "https://streamtape.com/e/tape-one"
            )
        )

        assertEquals(
            listOf(
                "https://voe.sx/e/voe-one",
                "https://playmogo.com/e/dood-one",
                "https://hgcloud.to/e/hg-one",
                "https://streamtape.com/e/tape-one",
                "https://voe.sx/e/voe-two",
                "https://playmogo.com/e/dood-two",
                "https://hgcloud.to/e/hg-two"
            ),
            ordered
        )
    }

    @Test
    fun `nomat box office uses the current live route`() {
        val route = NomatProvider().mainPage.first { it.name == "Box Office" }.data

        assertEquals("slug/film-box-office-terkini/%d/", route)
    }

    @Test
    fun `nomat extracts strict fallback identity and player link`() {
        val document = Jsoup.parse(
            """
            <div class="video-title">
                <h1>Nonton Awarapan (2007) Subtitle Indonesia</h1>
            </div>
            <a href="/category/year/2007/">2007</a>
            <div class="video-wrapper">
                <a href="https://nontonhemat.link/?id=m8x88">Play</a>
            </div>
            """.trimIndent(),
            "https://nomat.shop/play/nonton-awarapan-2007-subtitle-indonesia-m8x88"
        )

        assertEquals(
            NomatFallbackRequest(title = "Awarapan", year = 2007),
            NomatParser.fallbackRequest(document)
        )
        assertEquals(
            listOf("https://nontonhemat.link/?id=m8x88"),
            NomatParser.playerUrls(
                document,
                "https://nomat.shop/play/nonton-awarapan-2007-subtitle-indonesia-m8x88"
            )
        )
    }

    @Test
    fun `nomat discovers base64 server buttons on the nested player page`() {
        val pageUrl = "https://nontonhemat.link/?id=fixture"
        val fileMoon = "https://filemoon.sx/e/current"
        val streamHide = "https://streamhide.to/e/current"
        val document = Jsoup.parse(
            """
            <div class="server-item active" data-url="${encodeBase64NoPadding(fileMoon.toByteArray())}">
                FMOON [1080p]
            </div>
            <div class="server-item" data-url="${encodeBase64NoPadding(streamHide.toByteArray())}">
                STREAMH [1080p]
            </div>
            """.trimIndent(),
            pageUrl
        )

        assertEquals(
            listOf(fileMoon, streamHide),
            NomatParser.playerUrls(document, pageUrl)
        )
    }

    @Test
    fun `nomat wires nested player discovery into the shared resolver`() {
        val source = File(sourceRoot, "NomatProvider.kt").readText()

        assertTrue(source.contains("inlineSourceParser = { html, playerUrl ->"))
        assertTrue(source.contains("NomatParser.playerUrls(Jsoup.parse(html, playerUrl), playerUrl)"))
    }

    @Test
    fun `otakudesu uses the bounded resolver instead of stopping at registry host aliases`() {
        val source = File(sourceRoot, "OtakudesuProvider.kt").readText()

        assertTrue(source.contains("LinkResolutionSession("))
        assertFalse(source.contains("loadExtractor(server, referer"))
    }

    @Test
    fun `nomat fallback matching rejects similar titles and wrong years`() {
        val request = NomatFallbackRequest(title = "Awarapan", year = 2007)

        assertTrue(NomatParser.isExactFallbackMatch(request, "Awarapan (2007)", 2007))
        assertFalse(NomatParser.isExactFallbackMatch(request, "Awarapan 2", 2007))
        assertFalse(NomatParser.isExactFallbackMatch(request, "Awarapan", 2025))
        assertFalse(NomatParser.isExactFallbackMatch(request, "Awarapan", null))
        assertTrue(
            NomatParser.isExactFallbackMatch(
                NomatFallbackRequest(
                    title = "Supergirl",
                    year = null,
                    season = 6,
                    episode = 9
                ),
                "Nonton Supergirl (2015)",
                2015
            )
        )
    }
}
