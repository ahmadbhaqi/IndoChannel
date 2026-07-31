package com.example

import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class PencuriNomatPlaybackRegressionTest {
    private val sourceRoot = listOf(
        File("src/main/kotlin/com/example"),
        File("IndoProvider/src/main/kotlin/com/example")
    ).first { it.exists() }

    @Test
    fun `public catalog retries one transient resource failure`() = runBlocking {
        var attempts = 0

        val result = retryPublicCatalogResource(attempts = 2) {
            attempts++
            if (attempts == 1) null else "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `public catalog retries one transient verified media probe`() = runBlocking {
        var probes = 0
        val links = mutableListOf<String>()
        val session = LinkResolutionSession(
            api = PencurimovieProvider(),
            subtitleCallback = {},
            callback = { link -> links += link.url },
            mediaProbeAttempts = 2,
            mediaLinkProbe = { link ->
                probes++
                link.takeIf { probes == 2 }
            }
        )
        val link = newExtractorLink(
            source = "Pencurimovie",
            name = "Internet Archive",
            url = "https://archive.org/download/example/movie.mp4",
            type = ExtractorLinkType.VIDEO
        )

        assertTrue(session.emitResolved(link))
        assertEquals(2, probes)
        assertEquals(listOf(link.url), links)
    }

    @Test
    fun `pencurimovie rewrites active dood aliases for the playmogo extractor`() {
        assertEquals(
            "https://playmogo.com/e/current-id?token=abc",
            PencurimovieParser.extractorCompatibleUrl(
                "https://dsvplay.com/e/current-id?token=abc"
            )
        )
        assertEquals(
            "https://playmogo.com/e/current-id",
            PencurimovieParser.extractorCompatibleUrl(
                "https://ds2play.com/e/current-id"
            )
        )
        assertEquals(
            "https://playmogo.com/e/current-id",
            PencurimovieParser.extractorCompatibleUrl(
                "https://doodstream.com/e/current-id"
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
    fun `pencurimovie races bounded mirror families before exact fallback`() {
        val source = File(sourceRoot, "PencurimovieProvider.kt").readText()

        assertTrue(source.contains("resolver.resolveFirstVerified("))
        assertTrue(source.contains("maxConcurrency = 4"))
        assertFalse(source.contains("for (candidate in candidates)"))
    }

    @Test
    fun `pencurimovie derives an exact movie fallback identity`() {
        val document = Jsoup.parse(
            """
            <div class="mvic-desc">
              <h3>Spider-Man: Across the Spider-Verse (2023)</h3>
            </div>
            <div class="mvic-info">
              <p>Release: <a>2023</a></p>
            </div>
            """.trimIndent()
        )

        assertEquals(
            NomatFallbackRequest(
                title = "Spider-Man: Across the Spider-Verse",
                year = 2023
            ),
            PencurimovieParser.fallbackRequest(document)
        )
    }

    @Test
    fun `pencurimovie removes a separate Malay dub edition tag from fallback identity`() {
        val document = Jsoup.parse(
            """
            <div class="mvic-desc">
              <h3>Minions [MalayDub] (2015)</h3>
            </div>
            <div class="mvic-info">
              <p>Release: <a>2015</a></p>
            </div>
            """.trimIndent()
        )

        assertEquals(
            NomatFallbackRequest(title = "Minions", year = 2015),
            PencurimovieParser.fallbackRequest(document)
        )
    }

    @Test
    fun `pencurimovie preserves numeric movie titles when release metadata differs`() {
        val bladeRunner = Jsoup.parse(
            """
            <div class="mvic-desc"><h3>Blade Runner 2049</h3></div>
            <div class="mvic-info"><p>Release: <a>2017</a></p></div>
            """.trimIndent()
        )
        val nineteenSeventeen = Jsoup.parse(
            """
            <div class="mvic-desc"><h3>1917 (2019)</h3></div>
            <div class="mvic-info"><p>Release: <a>2019</a></p></div>
            """.trimIndent()
        )

        assertEquals(
            NomatFallbackRequest(title = "Blade Runner 2049", year = 2017),
            PencurimovieParser.fallbackRequest(bladeRunner)
        )
        assertEquals(
            NomatFallbackRequest(title = "1917", year = 2019),
            PencurimovieParser.fallbackRequest(nineteenSeventeen)
        )
    }

    @Test
    fun `pencurimovie derives exact series episode fallback coordinates`() {
        val document = Jsoup.parse(
            """
            <div class="mvic-desc">
              <h3>Example Show Season 2 Episode 3 (2024)</h3>
            </div>
            <div class="mvic-info">
              <p>Release: <a>2024</a></p>
            </div>
            """.trimIndent(),
            "https://ww21.pencurimovie.sbs/series/example-show-season-2-episode-3/"
        )

        assertEquals(
            NomatFallbackRequest(
                title = "Example Show",
                year = 2024,
                season = 2,
                episode = 3
            ),
            PencurimovieParser.fallbackRequest(document, document.location())
        )
    }

    @Test
    fun `numeric titles still participate in strict fallback matching`() {
        assertTrue(NomatParser.isExactFallbackTitle("1917", "1917 (2019)"))
        assertTrue(
            NomatParser.isExactFallbackTitle(
                "Blade Runner 2049",
                "Blade Runner 2049 (2017)"
            )
        )
        assertTrue(
            NomatParser.isPotentialFallbackTitle(
                NomatFallbackRequest(title = "Awarapan", year = 2007),
                "Awarapan 2007"
            )
        )
        assertTrue(
            NomatParser.isExactFallbackMatch(
                NomatFallbackRequest(title = "Awarapan", year = 2007),
                "Awarapan 2007",
                2007
            )
        )
    }

    @Test
    fun `pencurimovie derives bounded fallback identities from a blocked url`() {
        assertEquals(
            listOf(
                NomatFallbackRequest(title = "Sumpahan Malam Raya 2023", year = null),
                NomatFallbackRequest(title = "Sumpahan Malam Raya", year = 2023)
            ),
            PencurimovieParser.fallbackRequests(
                Jsoup.parse(""),
                "https://ww21.pencurimovie.sbs/sumpahan-malam-raya-2023/"
            )
        )
    }

    @Test
    fun `pencurimovie series fallback selects one exact season and episode`() {
        val request = NomatFallbackRequest(
            title = "Example Show",
            year = 2024,
            season = 2,
            episode = 3
        )
        val provider = PencurimovieProvider()
        val episodes = listOf(
            provider.newEpisode("https://fallback.example/season-1-episode-3") {
                season = 1
                episode = 3
            },
            provider.newEpisode("https://fallback.example/season-2-episode-3") {
                season = 2
                episode = 3
            }
        )

        assertEquals(
            "https://fallback.example/season-2-episode-3",
            PencurimovieParser.fallbackEpisodeData(
                request,
                "Example Show",
                2024,
                episodes
            )
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
    fun `nomat and pencurimovie share the strict public catalog fallback`() {
        val nomat = File(sourceRoot, "NomatProvider.kt").readText()
        val pencurimovie = File(sourceRoot, "PencurimovieProvider.kt").readText()
        val shared = File(sourceRoot, "PublicCatalogFallbackResolver.kt").readText()

        assertTrue(nomat.contains("loadExactPublicCatalogFallback("))
        assertTrue(pencurimovie.contains("loadExactPublicCatalogFallback("))
        assertTrue(shared.contains("BstationFallbackParser.isExactCandidate"))
        assertTrue(shared.contains("InternetArchiveFallbackParser.isExactCandidate"))
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
