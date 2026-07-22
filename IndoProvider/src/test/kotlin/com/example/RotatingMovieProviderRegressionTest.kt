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
        val ngefilmLegacyHosts = (33..38).mapTo(mutableSetOf()) { "new$it.ngefilm.site" }
        val cases = (33..38).map { number ->
            Triple(
                "https://new$number.ngefilm.site/tv/example/?player=2#play",
                "https://new39.ngefilm.site",
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
    fun `dutamovie parses compact Eps labels and episode slugs`() {
        assertEquals(
            DutamoviePlayerParser.EpisodeNumbers(season = 1, episode = 1),
            DutamoviePlayerParser.episodeNumbers(
                "https://austincomputerworks.org/eps/the-east-palace-season-1-episode-1/",
                "S1 Eps1"
            )
        )
        assertEquals(
            DutamoviePlayerParser.EpisodeNumbers(season = 2, episode = 7),
            DutamoviePlayerParser.episodeNumbers(
                "https://austincomputerworks.org/eps/example-season-2-episode-7/",
                "Lihat Episode"
            )
        )
        assertEquals(
            DutamoviePlayerParser.EpisodeNumbers(season = null, episode = null),
            DutamoviePlayerParser.episodeNumbers(
                "https://austincomputerworks.org/tv/the-east-palace-2026/",
                "Lihat Semua Episode"
            )
        )
        assertEquals(
            DutamoviePlayerParser.EpisodeNumbers(season = null, episode = null),
            DutamoviePlayerParser.episodeNumbers(
                "https://austincomputerworks.org/the-steps-2/",
                "The Steps 2"
            )
        )
        assertEquals(
            DutamoviePlayerParser.EpisodeNumbers(season = 1, episode = 2),
            DutamoviePlayerParser.episodeNumbers(
                "https://austincomputerworks.org/eps/example/s1/ep-2/",
                "Episode berikutnya"
            )
        )
    }

    @Test
    fun `ngefilm and pusatfilm keep episode links when only the slug has season and episode`() {
        val providers = listOf(
            Triple("NgefilmProvider.kt", "https://new39.ngefilm.site", NgefilmProvider()),
            Triple("PusatfilmProvider.kt", "https://v4.pusatfilm21info.com", PusatfilmProvider())
        )

        providers.forEach { (fileName, baseUrl, provider) ->
            val episodeUrl = "$baseUrl/eps/example-season-2-episode-7/"
            val parsed = DutamoviePlayerParser.newEpisode(
                api = provider,
                href = episodeUrl,
                label = "Lihat Episode",
                poster = "https://image.example/poster.jpg"
            )

            assertEquals(episodeUrl, parsed?.data)
            assertEquals("Episode 7", parsed?.name)
            assertEquals(2, parsed?.season)
            assertEquals(7, parsed?.episode)
            assertEquals("https://image.example/poster.jpg", parsed?.posterUrl)

            val source = File(sourceRoot, fileName).readText()
            assertTrue(
                source.contains("DutamoviePlayerParser.newEpisode(this, href, label, poster)"),
                "$fileName must use the shared, directly tested episode mapper"
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
    fun `dutamovie prioritizes verified Morencius Abyss and Embed4me adapters`() {
        val urls = listOf(
            "https://abyssplayer.com/current",
            "https://edge.playerp2p.online/#current",
            "https://morencius.com/embed/current",
            "https://embedpyrox.xyz/player/current",
            "https://voe.sx/e/current"
        )

        assertEquals(
            listOf(urls[2], urls[0], urls[1], urls[3], urls[4]),
            DutamoviePlayerParser.orderMediaUrls(urls)
        )
    }

    @Test
    fun `dutamovie bounds initial probes and defers remaining mirrors until discovery`() {
        val urls = listOf(
            "https://generic-one.example/embed/current",
            "https://voe.sx/e/current",
            "https://abyssplayer.com/current",
            "https://morencius.com/embed/current",
            "https://edge.playerp2p.online/#current"
        )

        val schedule = DutamoviePlayerParser.initialMediaSchedule(urls)

        assertEquals(
            listOf(
                "https://morencius.com/embed/current",
                "https://abyssplayer.com/current"
            ),
            schedule.eager
        )
        assertEquals(
            listOf(
                "https://edge.playerp2p.online/#current",
                "https://voe.sx/e/current",
                "https://generic-one.example/embed/current"
            ),
            schedule.deferred
        )
        assertTrue(schedule.eager.size < urls.size)
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

    @Test
    fun `dutamovie player response keeps every playable source and excludes assets`() {
        val responseUrl = "https://austincomputerworks.org/player/server-one/"
        val document = Jsoup.parse(
            """
            <div class="gmr-embed-responsive">
              <iframe src="https://morencius.com/embed/one"></iframe>
              <iframe data-src="https://abyssplayer.com/embed/two"></iframe>
            </div>
            <video><source src="/media/direct.mp4"></video>
            <script>
              const sources = [
                {file: "../hls/master.m3u8", type: "hls"},
                {file: "../subs/id.vtt", type: "text/vtt"},
                {src: "../images/poster.jpg", type: "image/jpeg"}
              ];
            </script>
            """.trimIndent(),
            responseUrl
        )

        assertEquals(
            listOf(
                "https://morencius.com/embed/one",
                "https://abyssplayer.com/embed/two",
                "https://austincomputerworks.org/media/direct.mp4",
                "https://austincomputerworks.org/player/hls/master.m3u8"
            ),
            DutamoviePlayerParser.pageMediaUrls(document, responseUrl)
        )
    }
}
