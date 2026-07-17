package com.example

import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jsoup.Jsoup

class RotatingMovieProviderRegressionTest {
    private val sourceRoot = listOf(
        File("src/main/kotlin/com/example"),
        File("IndoProvider/src/main/kotlin/com/example")
    ).first { it.exists() }

    @Test
    fun `legacy detail URLs are rehomed without losing path query or fragment`() {
        val ngefilmLegacyHosts = (33..37).mapTo(mutableSetOf()) { "new$it.ngefilm.site" }
        val cases = (33..37).map { number ->
            Triple(
                "https://new$number.ngefilm.site/tv/example/?player=2#play",
                "https://new38.ngefilm.site",
                ngefilmLegacyHosts
            )
        } + listOf(
            Triple(
                "https://v3.pusatfilm21info.com/royal-2025/?player=2#play",
                "https://v4.pusatfilm21info.com",
                setOf("v3.pusatfilm21info.com")
            ),
            Triple(
                "https://wavereview.com/tv/example/?player=2#play",
                "https://austincomputerworks.org",
                setOf("wavereview.com")
            )
        )

        cases.forEach { (legacyUrl, currentBase, legacyHosts) ->
            val parsed = URI(legacyUrl)
            val expected = buildString {
                append(currentBase)
                append(parsed.rawPath)
                parsed.rawQuery?.let { append('?').append(it) }
                parsed.rawFragment?.let { append('#').append(it) }
            }
            assertEquals(
                expected,
                ProviderHtmlParser.normalizeProviderPageUrl(legacyUrl, currentBase, legacyHosts)
            )
            assertNull(
                ProviderHtmlParser.normalizeProviderPageUrl(
                    "https://unrelated.example/movie/",
                    currentBase,
                    legacyHosts
                )
            )
        }
    }

    @Test
    fun `rotating providers encode search and return canonical URLs`() {
        listOf("NgefilmProvider.kt", "PusatfilmProvider.kt", "DutamovieProvider.kt").forEach { fileName ->
            val provider = File(sourceRoot, fileName).readText()
            assertTrue(provider.contains("URLEncoder.encode(query"), "$fileName must encode search input")
            assertTrue(provider.contains("normalizePageUrl(url)"), "$fileName must rehome cached detail URLs")
            assertTrue(provider.contains("normalizePageUrl(data)"), "$fileName must rehome cached loadLinks data")
            assertTrue(provider.contains("canonicalUrl"), "$fileName must retain the final canonical URL")
            assertTrue(
                provider.contains("newMovieLoadResponse(title, canonicalUrl"),
                "$fileName must expose the canonical movie URL"
            )
            assertTrue(
                provider.contains("newTvSeriesLoadResponse(title, canonicalUrl"),
                "$fileName must expose the canonical series URL"
            )
            assertFalse(provider.contains("trim().toString()"), "$fileName must never turn null into a title")
            assertTrue(
                provider.contains("?: throw ErrorLoadingException"),
                "$fileName must reject block/error pages instead of returning an empty title"
            )
        }
    }

    @Test
    fun `rotating providers also detect series from episode markup`() {
        listOf("NgefilmProvider.kt", "PusatfilmProvider.kt", "DutamovieProvider.kt").forEach { fileName ->
            val provider = File(sourceRoot, fileName).readText()
            assertTrue(
                provider.contains("episodeElements.isNotEmpty()"),
                "$fileName must classify series pages even when their URL has no tv segment"
            )
        }
    }

    @Test
    fun `dutamovie does not infer mirror host from mutable player numbers`() {
        val pages = listOf(
            "https://austincomputerworks.org/movie/?player=1",
            "https://austincomputerworks.org/movie/?player=6",
            "https://austincomputerworks.org/movie/?player=8",
            "https://austincomputerworks.org/movie/?player=4",
            "https://austincomputerworks.org/movie/?player=7"
        )

        assertEquals(
            pages,
            DutamoviePlayerParser.orderPlayerPages(pages)
        )
    }

    @Test
    fun `dutamovie prioritizes discovered Morencius host and defers Abyss`() {
        val urls = listOf(
            "https://abyssplayer.com/current",
            "https://playerp2p.example/embed/current",
            "https://morencius.com/embed/current",
            "https://embedpyrox.xyz/player/current",
            "https://voe.sx/e/current"
        )

        assertEquals(
            listOf(urls[2], urls[3], urls[4], urls[1], urls[0]),
            DutamoviePlayerParser.orderMediaUrls(urls)
        )
    }

    @Test
    fun `Morencius parser follows hls4 fallback expression before dead hls2`() {
        val playerUrl = "https://morencius.com/embed/current"
        val html = """
            <script>
              var links={
                "hls2":"https://edge.acek-cdn.com/hls2/current/master.m3u8?t=signed",
                "hls4":"/stream/token/server/123/456/master.m3u8"
              };
              jwplayer("vplayer").setup({
                sources:[{file:links.hls4||links.hls3||links.hls2,type:"hls"}]
              });
            </script>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://morencius.com/stream/token/server/123/456/master.m3u8",
                "https://edge.acek-cdn.com/hls2/current/master.m3u8?t=signed"
            ),
            MorenciusPlayerParser.mediaUrls(html, playerUrl)
        )
    }

    @Test
    fun `dutamovie collects an inline-only detail iframe`() {
        val detailUrl = "https://austincomputerworks.org/ghost-in-the-cell-2026/"
        val document = Jsoup.parse(
            """
            <article>
                <div class="gmr-embed-responsive">
                    <iframe data-src="https://abyssplayer.com/R4DrMYBr1"></iframe>
                </div>
            </article>
            """.trimIndent(),
            detailUrl
        )

        assertEquals(
            listOf("https://abyssplayer.com/R4DrMYBr1"),
            DutamoviePlayerParser.detailMediaUrls(document, detailUrl)
        )
    }
}
