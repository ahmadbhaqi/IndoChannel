package com.example

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class IndomaxProviderTest {
    @Test
    fun `nonempty primary catalog is interleaved with distinct healthy fallbacks`() {
        assertEquals(
            listOf(
                "primary-stale|same",
                "idlix-series|idlix",
                "filmapik-series|filmapik"
            ),
            interleaveIndomaxCatalogGroups(
                primary = listOf("primary-stale|same"),
                fallbackGroups = listOf(
                    listOf(
                        "filmapik-duplicate|same",
                        "filmapik-series|filmapik"
                    ),
                    listOf("idlix-series|idlix")
                ),
                maxResults = 8,
                keySelector = { value -> value.substringAfter('|') }
            )
        )
    }

    @Test
    fun `catalog fallback interleaves providers so one stale source cannot starve the rest`() {
        assertEquals(
            listOf("filmapik-1", "pusatfilm-1", "kitanonton-1", "filmapik-2", "kitanonton-2"),
            interleaveFallbackResults(
                groups = listOf(
                    listOf("filmapik-1", "filmapik-2", "filmapik-3"),
                    listOf("pusatfilm-1"),
                    listOf("kitanonton-1", "kitanonton-2")
                ),
                maxResults = 5
            )
        )
    }

    @Test
    fun `fallback homepage cards are rebound to Indomax ownership`() {
        val source = RecordingIndomaxFallback()
        val foreign = source.newMovieSearchResponse(
            "Fallback Movie",
            "https://fallback.example/movie/current",
            TvType.Movie
        )

        val owned = ownIndomaxFallbackCatalog(listOf(foreign), "Indomax")

        assertEquals(listOf("Indomax"), owned.map { result -> result.apiName })
    }

    @Test
    fun `healthy primary player skips alternate tab preflight`() = runBlocking {
        val primary = PlayerResolutionCandidate(
            "https://imaxstreams.net/e/primary",
            "https://akses10.indomax21.xyz/movie/example/"
        )
        var fallbackLoads = 0
        val resolvedBatches = mutableListOf<List<PlayerResolutionCandidate>>()

        val resolved = resolveIndomaxPlayerPhases(
            primary = primary,
            loadFallbacks = {
                fallbackLoads++
                listOf(
                    PlayerResolutionCandidate(
                        "https://imaxstreams.net/e/fallback",
                        "https://akses10.indomax21.xyz/movie/example/?player=2"
                    )
                )
            },
            resolveBatch = { candidates ->
                resolvedBatches += candidates
                true
            }
        )

        assertTrue(resolved)
        assertEquals(0, fallbackLoads)
        assertEquals(listOf(listOf(primary)), resolvedBatches)
    }

    @Test
    fun `failed primary loads alternate tabs once and resolves them as one batch`() = runBlocking {
        val primary = PlayerResolutionCandidate(
            "https://imaxstreams.net/e/primary",
            "https://akses10.indomax21.xyz/movie/example/"
        )
        val fallbacks = listOf(
            PlayerResolutionCandidate(
                "https://imaxstreams.net/e/fallback-2",
                "https://akses10.indomax21.xyz/movie/example/?player=2"
            ),
            PlayerResolutionCandidate(
                "https://imaxstreams.net/e/fallback-3",
                "https://akses10.indomax21.xyz/movie/example/?player=3"
            )
        )
        var fallbackLoads = 0
        val resolvedBatches = mutableListOf<List<PlayerResolutionCandidate>>()

        val resolved = resolveIndomaxPlayerPhases(
            primary = primary,
            loadFallbacks = {
                fallbackLoads++
                fallbacks
            },
            resolveBatch = { candidates ->
                resolvedBatches += candidates
                candidates == fallbacks
            }
        )

        assertTrue(resolved)
        assertEquals(1, fallbackLoads)
        assertEquals(listOf(listOf(primary), fallbacks), resolvedBatches)
        assertFalse(resolvedBatches.first() == fallbacks)
    }

    @Test
    fun `provider exposes the verified live domain and catalog route`() {
        val provider = IndomaxProvider()

        assertEquals("Indomax", provider.name)
        assertEquals("https://idmxl.ink", provider.mainUrl)
        assertEquals(
            "category/box-office/page/%d/",
            provider.mainPage.first { it.name == "Box Office" }.data
        )
    }

    @Test
    fun `catalog fixture parses movie and series cards on the redirected owned host`() {
        val pageUrl = "https://akses10.indomax21.xyz/category/box-office/"
        val document = Jsoup.parse(
            """
                <article class="item-infinite">
                  <h2 class="entry-title">
                    <a href="/masters-of-the-universe-2026/">Masters of the Universe (2026)</a>
                  </h2>
                  <img class="wp-post-image"
                       data-src="https://images.example/masters-300x450.jpg">
                  <div class="gmr-quality-item"><a>HD</a></div>
                  <div class="gmr-rating-item">8.4</div>
                </article>
                <article class="item-infinite">
                  <h2 class="entry-title">
                    <a href="/tv/example-series/">Example Series</a>
                  </h2>
                  <img class="wp-post-image" src="https://images.example/series.jpg">
                  <div class="gmr-numbeps"><span>7</span></div>
                </article>
            """.trimIndent(),
            pageUrl
        )

        assertEquals(
            listOf(
                IndomaxCatalogItem(
                    title = "Masters of the Universe (2026)",
                    url = "https://akses10.indomax21.xyz/masters-of-the-universe-2026/",
                    posterUrl = "https://images.example/masters-300x450.jpg",
                    quality = "HD",
                    episodeCount = null,
                    rating = 8.4,
                    isSeries = false
                ),
                IndomaxCatalogItem(
                    title = "Example Series",
                    url = "https://akses10.indomax21.xyz/tv/example-series/",
                    posterUrl = "https://images.example/series.jpg",
                    quality = null,
                    episodeCount = 7,
                    rating = null,
                    isSeries = true
                )
            ),
            IndomaxParser.catalogItems(document, pageUrl)
        )
    }

    @Test
    fun `external fallback result delegates detail and playback to its owner`() = runBlocking {
        val fallback = RecordingIndomaxFallback()
        val provider = IndomaxProvider { listOf(fallback) }
        val resultUrl = "https://fallback.example/movie/current"

        val detail = provider.load(resultUrl) as MovieLoadResponse
        val links = mutableListOf<ExtractorLink>()
        val loaded = provider.loadLinks(detail.dataUrl, false, {}, links::add)

        assertEquals("Fallback Movie", detail.name)
        assertEquals("Indomax", detail.apiName)
        assertTrue(loaded)
        assertEquals(listOf(resultUrl), fallback.loadCalls)
        assertEquals(listOf(resultUrl), fallback.linkCalls)
        assertEquals(listOf("https://cdn.example/fallback.mp4"), links.map { it.url })
    }

    @Test
    fun `delegated catalog skips an empty series shell and tries the next owner`() = runBlocking {
        val stale = EmptySeriesIndomaxFallback()
        val healthy = RecordingIndomaxFallback()
        val provider = IndomaxProvider { listOf(stale, healthy) }
        val resultUrl = "https://fallback.example/movie/current"

        val detail = provider.load(resultUrl)

        assertTrue(detail is MovieLoadResponse)
        assertEquals("Fallback Movie", detail.name)
        assertEquals("Indomax", detail.apiName)
        assertEquals(listOf(resultUrl), stale.loadCalls)
        assertEquals(listOf(resultUrl), healthy.loadCalls)
    }

    @Test
    fun `provider retains a public no argument constructor for CloudStream`() {
        assertEquals(
            "Indomax",
            IndomaxProvider::class.java.getDeclaredConstructor().newInstance().name
        )
    }

    @Test
    fun `series fixture omits view-all self link before the first real episode`() {
        val detailUrl =
            "https://akses10.indomax21.xyz/tv/the-insipid-princes-furtive-grab-for-the-throne-2026/"
        val document = Jsoup.parse(
            """
                <div class="gmr-listseries">
                  <a href="$detailUrl">Lihat Semua Episode</a>
                  <a href="/eps/the-insipid-princes-furtive-grab-for-the-throne-season-1-episode-1/"
                     title="Episode 1">Episode 1</a>
                  <a href="/eps/the-insipid-princes-furtive-grab-for-the-throne-season-1-episode-1/"
                     title="Episode 1 duplicate">Episode 1 duplicate</a>
                  <a href="/eps/the-insipid-princes-furtive-grab-for-the-throne-season-1-episode-2/"
                     title="Episode 2">Episode 2</a>
                </div>
            """.trimIndent(),
            detailUrl
        )

        assertEquals(
            listOf(
                IndomaxEpisodeItem(
                    url = "https://akses10.indomax21.xyz/eps/" +
                        "the-insipid-princes-furtive-grab-for-the-throne-season-1-episode-1/",
                    label = "Episode 1"
                ),
                IndomaxEpisodeItem(
                    url = "https://akses10.indomax21.xyz/eps/" +
                        "the-insipid-princes-furtive-grab-for-the-throne-season-1-episode-2/",
                    label = "Episode 2"
                )
            ),
            IndomaxParser.episodeItems(document, detailUrl)
        )
    }

    @Test
    fun `catalog drops sexual cards while keeping nonsexual mature and ecchi titles`() {
        val pageUrl = "https://akses8.indomax21.xyz/category/box-office/"
        val document = Jsoup.parse(
            """
                <article class="item-infinite">
                  <h2 class="entry-title">
                    <a href="/sex-in-public-2026/">Sex in Public (2026)</a>
                  </h2>
                  <div class="gmr-movie-on">
                    <a href="/category/documentary/" rel="category tag">Documentary</a>
                  </div>
                </article>
                <article class="item-infinite">
                  <h2 class="entry-title">
                    <a href="/booking-2026/">Booking (2026)</a>
                  </h2>
                  <div class="gmr-movie-on">
                    <a href="/category/drama/" rel="category tag">Drama</a>
                    <a href="/category/vivamax/" rel="category tag">Vivamax</a>
                  </div>
                </article>
                <article class="item-infinite">
                  <h2 class="entry-title">
                    <a href="/violent-night/">Violent Night 18+</a>
                  </h2>
                  <div class="gmr-movie-on">
                    <a href="/category/action/" rel="category tag">Action</a>
                  </div>
                </article>
                <article class="item-infinite">
                  <h2 class="entry-title">
                    <a href="/ecchi-comedy/">Ecchi Comedy</a>
                  </h2>
                  <div class="gmr-movie-on">
                    <a href="/category/anime/" rel="category tag">Anime</a>
                    <a href="/category/ecchi/" rel="category tag">Ecchi</a>
                  </div>
                </article>
            """.trimIndent(),
            pageUrl
        )

        assertEquals(
            listOf("Violent Night 18+", "Ecchi Comedy"),
            IndomaxParser.catalogItems(document, pageUrl).map { it.title }
        )
    }

    private class RecordingIndomaxFallback : MainAPI() {
        override var mainUrl = "https://fallback.example"
        override var name = "Fallback"
        override var lang = "id"
        override val supportedTypes = setOf(TvType.Movie)
        val loadCalls = mutableListOf<String>()
        val linkCalls = mutableListOf<String>()

        override suspend fun load(url: String): LoadResponse? {
            loadCalls += url
            if (url != "$mainUrl/movie/current") return null
            return newMovieLoadResponse("Fallback Movie", url, TvType.Movie, url)
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            linkCalls += data
            if (data != "$mainUrl/movie/current") return false
            callback(
                newExtractorLink(
                    name,
                    name,
                    "https://cdn.example/fallback.mp4",
                    ExtractorLinkType.VIDEO
                )
            )
            return true
        }
    }

    private class EmptySeriesIndomaxFallback : MainAPI() {
        override var mainUrl = "https://fallback.example"
        override var name = "Empty Series"
        override var lang = "id"
        override val supportedTypes = setOf(TvType.TvSeries)
        val loadCalls = mutableListOf<String>()

        override suspend fun load(url: String): LoadResponse? {
            loadCalls += url
            return newTvSeriesLoadResponse(
                "Supergirl",
                url,
                TvType.TvSeries,
                emptyList()
            )
        }
    }

    @Test
    fun `owned page normalizer preserves exact live aliases and rejects lookalikes`() {
        assertEquals(
            "https://akses7.indomax21.xyz/movie/example/",
            IndomaxParser.providerPageUrl(
                "https://akses7.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertEquals(
            "https://akses8.indomax21.xyz/movie/example/",
            IndomaxParser.providerPageUrl(
                "https://akses8.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertEquals(
            "https://akses10.indomax21.xyz/movie/example/?player=2",
            IndomaxParser.providerPageUrl(
                "https://akses10.indomax21.xyz/movie/example/?player=2",
                "https://idmxl.ink"
            )
        )
        assertEquals(
            "https://akses6.indomax21.xyz/movie/example/",
            IndomaxParser.providerPageUrl(
                "https://akses6.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertEquals(
            "https://akses125.indomax21.xyz/movie/example/",
            IndomaxParser.providerPageUrl(
                "https://akses125.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertNull(
            IndomaxParser.providerPageUrl(
                "https://akses10.indomax21.xyz.attacker.example/private",
                "https://idmxl.ink"
            )
        )
        assertNull(
            IndomaxParser.providerPageUrl(
                "http://akses10.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertNull(
            IndomaxParser.providerPageUrl(
                "https://akses1000.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
    }

    @Test
    fun `player fixture keeps one active player and at most three alternate tabs`() {
        val pageUrl = "https://akses10.indomax21.xyz/movie/example/"
        val document = Jsoup.parse(
            """
                <div class="gmr-embed-responsive">
                  <iframe data-litespeed-src="https://imaxstreams.net/e/active"></iframe>
                </div>
                <ul class="muvipro-player-tabs">
                  <li class="active"><a href="?player=1">Server 1</a></li>
                  <li><a href="?player=2">Server 2</a></li>
                  <li><a href="?player=3">Server 3</a></li>
                  <li><a href="?player=4">Server 4</a></li>
                  <li><a href="?player=5">Server 5</a></li>
                </ul>
            """.trimIndent(),
            pageUrl
        )

        assertEquals(
            "https://imaxstreams.net/e/active",
            IndomaxParser.primaryPlayerUrl(document, pageUrl)
        )
        assertEquals(
            listOf(
                "https://akses10.indomax21.xyz/movie/example/?player=2",
                "https://akses10.indomax21.xyz/movie/example/?player=3",
                "https://akses10.indomax21.xyz/movie/example/?player=4"
            ),
            IndomaxParser.alternateTabUrls(document, pageUrl, remainingPlayers = 3)
        )

        val fragmentPlayer = Jsoup.parse(
            """
                <div class="gmr-embed-responsive">
                  <iframe src="https://ichinime.4meplayer.pro/#e8bn6"></iframe>
                </div>
            """.trimIndent(),
            pageUrl
        )
        assertEquals(
            "https://ichinime.4meplayer.pro/#e8bn6",
            IndomaxParser.primaryPlayerUrl(fragmentPlayer, pageUrl)
        )
    }

    @Test
    fun `imax packer fixture yields only four bounded https hls candidates`() {
        val packed = """
            <script>
            eval(function(p,a,c,k,e,d){return p}('0={1:"2",3:"4",5:"6",7:"8",9:"a"};',62,11,'sources|hls1|https://cdn.example/one/master.m3u8|hls2|https://cdn.example/two/master.m3u8|hls3|https://cdn.example/three/master.m3u8|hls4|https://cdn.example/four/master.m3u8|hls5|https://cdn.example/five/master.m3u8'.split('|'),0,{}))
            </script>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://cdn.example/one/master.m3u8",
                "https://cdn.example/two/master.m3u8",
                "https://cdn.example/three/master.m3u8",
                "https://cdn.example/four/master.m3u8"
            ),
            IndomaxParser.imaxMediaUrls(packed, "https://imaxstreams.net/e/example")
        )
        assertTrue(
            IndomaxParser.imaxMediaUrls(
                "x".repeat(IndomaxParser.MAX_PACKED_INPUT_CHARS + 1),
                "https://imaxstreams.net/e/example"
            ).isEmpty()
        )
        assertTrue(
            IndomaxParser.imaxMediaUrls(packed, "https://attacker.example/e/example").isEmpty()
        )
    }

    @Test
    fun `imax packer accepts current master txt hls fallback but rejects ordinary text files`() {
        val packed = """
            <script>
            eval(function(p,a,c,k,e,d){return p}('0={1:"2",3:"4"};',62,5,'sources|hls3|https://cdn.example/current/master.txt|notes|https://cdn.example/current/release-notes.txt'.split('|'),0,{}))
            </script>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example/current/master.txt"),
            IndomaxParser.imaxMediaUrls(packed, "https://imaxstreams.net/e/example")
        )
    }

    @Test
    fun `external page trust requires exact https and leaves public dns validation to safety client`() {
        assertEquals(
            "https://imaxstreams.com/embed/example",
            IndomaxParser.publicHttpsUrl("https://imaxstreams.com/embed/example")
        )
        assertEquals(
            "https://ichinime.4meplayer.pro/#e8bn6",
            IndomaxParser.publicPlayableUrl("https://ichinime.4meplayer.pro/#e8bn6")
        )
        assertNull(IndomaxParser.publicHttpsUrl("https://ichinime.4meplayer.pro/#e8bn6"))
        assertNull(IndomaxParser.publicHttpsUrl("http://imaxstreams.com/embed/example"))
        assertNull(IndomaxParser.publicHttpsUrl("https://127.0.0.1/private"))
        assertNull(IndomaxParser.publicHttpsUrl("https://user@imaxstreams.com/private"))
    }
}
