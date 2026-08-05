package com.example

import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class LayarKacaPlayerParserTest {
    @Test
    fun `episode navigation excludes self and View All Episodes links`() {
        val detailUrl = "https://tv.nontonfilm.red/tv/example/"

        assertFalse(
            LayarKacaPlayerParser.isEpisodeLink(
                "https://tv.nontonfilm.red/tv/example/?tab=episodes",
                "Episode list",
                detailUrl
            )
        )
        assertFalse(
            LayarKacaPlayerParser.isEpisodeLink(
                "https://tv.nontonfilm.red/tv/example/episode-1/",
                "View All Episodes",
                detailUrl
            )
        )
        assertTrue(
            LayarKacaPlayerParser.isEpisodeLink(
                "https://tv.nontonfilm.red/tv/example/episode-1/",
                "Episode 1",
                detailUrl
            )
        )
    }

    @Test
    fun `ajax-only player layout exposes every Muvipro request`() {
        val document = Jsoup.parse(
            """
            <div id="muvipro_player_content_id" data-id="812"></div>
            <div class="tab-content-ajax" id="player1"></div>
            <div class="tab-content-ajax" id="player2"></div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MuviproAjaxRequest("812", "player1"),
                MuviproAjaxRequest("812", "player2")
            ),
            LayarKacaPlayerParser.ajaxRequests(document)
        )
    }

    @Test
    fun `server page discovery ignores trailer anchors and page controls`() {
        val detailUrl = "https://tv.nontonfilm.red/evil-dead-burn-2026/"
        val document = Jsoup.parse(
            """
            <ul class="gmr-player-nav">
              <li><a href="?player=1">Server 1</a></li>
              <li><a href="?player=2">Server 2</a></li>
              <li><a href="javascript:void(0)">Server menu</a></li>
              <li><a href="#respond">Comments</a></li>
              <li><a href="https://youtube.com/watch?v=trailer">Trailer</a></li>
              <li><a href="#download">Download</a></li>
            </ul>
            """.trimIndent(),
            detailUrl
        )

        assertEquals(
            listOf(
                "$detailUrl?player=1",
                "$detailUrl?player=2"
            ),
            LayarKacaPlayerParser.serverPageUrls(document, detailUrl)
        )
    }

    @Test
    fun `alternate server pages run before the slow default embed`() {
        val detailUrl = "https://tv.nontonfilm.red/scary-movie-2026/"
        val defaultEmbed = "https://justplay.one/e/default"
        val document = Jsoup.parse(
            """
            <iframe src="$defaultEmbed"></iframe>
            <ul class="gmr-player-nav">
              <li><a href="?player=1">Default</a></li>
              <li><a href="?player=2">Vidmoly</a></li>
              <li><a href="?player=3">Firestream</a></li>
            </ul>
            """.trimIndent(),
            detailUrl
        )

        assertEquals(
            listOf(
                LayarKacaPlaybackCandidate.ServerPage("$detailUrl?player=2"),
                LayarKacaPlaybackCandidate.ServerPage("$detailUrl?player=3"),
                LayarKacaPlaybackCandidate.InlinePlayer(defaultEmbed),
                LayarKacaPlaybackCandidate.ServerPage("$detailUrl?player=1")
            ),
            LayarKacaPlayerParser.orderedPlayerCandidates(document, detailUrl)
        )
        assertEquals(
            listOf(
                listOf(
                    LayarKacaPlaybackCandidate.ServerPage("$detailUrl?player=2"),
                    LayarKacaPlaybackCandidate.ServerPage("$detailUrl?player=3")
                ),
                listOf(LayarKacaPlaybackCandidate.InlinePlayer(defaultEmbed)),
                listOf(LayarKacaPlaybackCandidate.ServerPage("$detailUrl?player=1"))
            ),
            LayarKacaPlayerParser.playerCandidateTiers(document, detailUrl)
        )
    }

    @Test
    fun `legacy loadProviders menu accepts only same origin server pages`() {
        val detailUrl = "https://tv.nontonfilm.red/legacy-movie/"
        val document = Jsoup.parse(
            """
            <ul id="loadProviders">
              <li><a href="/legacy-movie/server-one/">Server 1</a></li>
              <li><a href="?provider=2">Server 2</a></li>
              <li><a href="https://tv10.lk21official.cc/legacy-movie/?provider=3">Retired host</a></li>
              <li><a href="https://evil.example/server">Foreign</a></li>
              <li><a href="http://tv.nontonfilm.red/downgrade">Downgrade</a></li>
              <li><a href="#comments">Comments</a></li>
            </ul>
            """.trimIndent(),
            detailUrl
        )

        assertEquals(
            listOf(
                "https://tv.nontonfilm.red/legacy-movie/server-one/",
                "https://tv.nontonfilm.red/legacy-movie/?provider=2",
                "https://tv.nontonfilm.red/legacy-movie/?provider=3"
            ),
            LayarKacaPlayerParser.serverPageUrls(document, detailUrl)
        )
    }

    @Test
    fun `inline-only player resolves relative media and excludes assets`() {
        val playerUrl = "https://tv.nontonfilm.red/movie/example/?player=4"
        val document = Jsoup.parse(
            """
            <script>
              jwplayer("player").setup({
                sources: [
                  {file: "/media/current/master.m3u8", type: "hls"},
                  {file: "//cdn.example/current/movie.mp4", type: "video/mp4"},
                  {file: "/subs/id.vtt", type: "text/vtt"}
                ],
                image: "/images/poster.jpg"
              });
              file = "../fallback/backup.m3u8";
            </script>
            """.trimIndent(),
            playerUrl
        )

        assertEquals(
            listOf(
                "https://tv.nontonfilm.red/media/current/master.m3u8",
                "https://cdn.example/current/movie.mp4",
                "https://tv.nontonfilm.red/movie/fallback/backup.m3u8"
            ),
            LayarKacaPlayerParser.pageMediaUrls(document, playerUrl)
        )
    }

    @Test
    fun `fallback request extracts exact series coordinates from a stale episode page`() {
        val document = Jsoup.parse(
            """
            <h1 class="entry-title">
              Supergirl Season 6 Episode 1 - 20 Subtitle Indonesia
            </h1>
            <span class="year">2026</span>
            """.trimIndent()
        )

        assertEquals(
            NomatFallbackRequest(
                title = "Supergirl",
                year = null,
                season = 6,
                episode = 9
            ),
            LayarKacaPlayerParser.fallbackRequest(
                document,
                "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-9/"
            )
        )
    }

    @Test
    fun `fallback request keeps the requested series title across a wrong title redirect`() {
        val document = Jsoup.parse(
            """<h1 class="entry-title">The Flash Season 6 Episode 9</h1>"""
        )

        assertEquals(
            NomatFallbackRequest(
                title = "Supergirl",
                year = null,
                season = 6,
                episode = 9
            ),
            LayarKacaPlayerParser.fallbackRequest(
                document,
                "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-9/"
            )
        )
    }

    @Test
    fun `fallback request survives an episode page without title markup`() {
        assertEquals(
            NomatFallbackRequest(
                title = "Supergirl",
                year = null,
                season = 6,
                episode = 9
            ),
            LayarKacaPlayerParser.fallbackRequest(
                Jsoup.parse("<html><body></body></html>"),
                "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-9/"
            )
        )
    }

    @Test
    fun `fallback request derives blocked movie and series details from their urls`() {
        val blockedDocument = Jsoup.parse("")

        assertEquals(
            NomatFallbackRequest(
                title = "The Devils Mouth 2026",
                year = null
            ),
            LayarKacaPlayerParser.fallbackRequest(
                blockedDocument,
                "https://tv.nontonfilm.red/movie/the-devils-mouth-2026/"
            )
        )
        assertEquals(
            listOf(
                NomatFallbackRequest(title = "Class Of 1984", year = null),
                NomatFallbackRequest(title = "Class Of", year = 1984)
            ),
            LayarKacaPlayerParser.fallbackRequests(
                blockedDocument,
                "https://tv.nontonfilm.red/movie/class-of-1984/"
            )
        )
        assertEquals(
            NomatFallbackRequest(
                title = "Supergirl",
                year = null,
                season = 6,
                episode = null
            ),
            LayarKacaPlayerParser.fallbackRequest(
                blockedDocument,
                "https://tv.nontonfilm.red/tv/nonton-film-supergirl-season-6-subtitle-indonesia/"
            )
        )
    }

    @Test
    fun `fallback requests derive a movie from the current root detail route`() {
        assertEquals(
            listOf(
                NomatFallbackRequest(
                    title = "Spider Man Across The Spider Verse 2023",
                    year = null
                ),
                NomatFallbackRequest(
                    title = "Spider Man Across The Spider Verse",
                    year = 2023
                )
            ),
            LayarKacaPlayerParser.fallbackRequests(
                Jsoup.parse(""),
                "https://tv.nontonfilm.red/spider-man-across-the-spider-verse-2023/"
            )
        )
    }

    @Test
    fun `blocked movie url does not mistake a numeric title for its release year`() {
        val blockedDocument = Jsoup.parse("")

        assertEquals(
            NomatFallbackRequest(
                title = "Blade Runner 2049",
                year = null
            ),
            LayarKacaPlayerParser.fallbackRequest(
                blockedDocument,
                "https://tv.nontonfilm.red/movie/blade-runner-2049/"
            )
        )
        assertEquals(
            NomatFallbackRequest(
                title = "1917",
                year = null
            ),
            LayarKacaPlayerParser.fallbackRequest(
                blockedDocument,
                "https://tv.nontonfilm.red/movie/1917/"
            )
        )
        assertEquals(
            listOf(
                NomatFallbackRequest(title = "Blade Runner 2049", year = null),
                NomatFallbackRequest(title = "Blade Runner", year = 2049)
            ),
            LayarKacaPlayerParser.fallbackRequests(
                blockedDocument,
                "https://tv.nontonfilm.red/movie/blade-runner-2049/"
            )
        )
    }

    @Test
    fun `episode playback keeps the requested coordinate across a misleading redirect`() {
        val requested =
            "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-9/"
        val redirected =
            "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-1/"

        assertEquals(
            requested,
            LayarKacaPlayerParser.playbackPageUrl(requested, redirected)
        )
        assertEquals(
            "https://tv.nontonfilm.red/movie/current/",
            LayarKacaPlayerParser.playbackPageUrl(
                "https://tv.nontonfilm.red/movie/legacy/",
                "https://tv.nontonfilm.red/movie/current/"
            )
        )
    }

    @Test
    fun `primary playback rejects redirects or documents for another episode`() {
        val requested =
            "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-9/"
        val redirected =
            "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-1/"
        val wrongDocument = Jsoup.parse(
            """<h1 class="entry-title">Supergirl Season 6 Episode 1 - 20</h1>"""
        )
        val correctDocument = Jsoup.parse(
            """<h1 class="entry-title">Supergirl Season 6 Episode 9</h1>"""
        )

        assertFalse(
            LayarKacaPlayerParser.isPrimaryPlaybackCoordinateSafe(
                requested,
                redirected,
                wrongDocument
            )
        )
        assertFalse(
            LayarKacaPlayerParser.isPrimaryPlaybackCoordinateSafe(
                requested,
                requested,
                wrongDocument
            )
        )
        assertFalse(
            LayarKacaPlayerParser.isPrimaryPlaybackCoordinateSafe(
                requested,
                "https://tv.nontonfilm.red/watch/current/",
                Jsoup.parse(
                    """<h1 class="entry-title">Supergirl Episode 9</h1>"""
                )
            )
        )
        assertFalse(
            LayarKacaPlayerParser.isPrimaryPlaybackCoordinateSafe(
                requested,
                "https://tv.nontonfilm.red/eps/the-flash-season-6-episode-9/",
                Jsoup.parse(
                    """<h1 class="entry-title">The Flash Season 6 Episode 9</h1>"""
                )
            )
        )
        assertFalse(
            LayarKacaPlayerParser.isPrimaryPlaybackCoordinateSafe(
                requested,
                requested,
                Jsoup.parse("""<h1 class="entry-title">The Flash</h1>""")
            )
        )
        assertTrue(
            LayarKacaPlayerParser.isPrimaryPlaybackCoordinateSafe(
                requested,
                requested,
                correctDocument
            )
        )
        assertTrue(
            LayarKacaPlayerParser.isPrimaryPlaybackCoordinateSafe(
                "https://tv.nontonfilm.red/movie/current/",
                "https://tv.nontonfilm.red/movie/current/",
                Jsoup.parse("""<h1 class="entry-title">Current Movie (2026)</h1>""")
            )
        )
    }

    @Test
    fun `numeric only episode labels remain bounded and playable`() {
        assertEquals(
            LayarKacaEpisodeCoordinate(season = null, episode = 9),
            LayarKacaPlayerParser.episodeCoordinate(
                "https://tv.nontonfilm.red/eps/special/",
                "9"
            )
        )
        assertNull(
            LayarKacaPlayerParser.episodeCoordinate(
                "https://tv.nontonfilm.red/eps/special/",
                "10001"
            )
        )
    }

    @Test
    fun `requested episode is injected first when the redirected page omits it`() {
        val requested =
            "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-9/"
        val candidates = listOf(
            "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-1/" to "Episode 1",
            "https://tv.nontonfilm.red/eps/supergirl-season-6-episode-2/" to "Episode 2"
        )

        assertEquals(
            listOf(
                LayarKacaEpisodeCandidate(
                    url = requested,
                    label = "Episode 9",
                    season = 6,
                    episode = 9
                ),
                LayarKacaEpisodeCandidate(
                    url = candidates[0].first,
                    label = "Episode 1",
                    season = 6,
                    episode = 1
                ),
                LayarKacaEpisodeCandidate(
                    url = candidates[1].first,
                    label = "Episode 2",
                    season = 6,
                    episode = 2
                )
            ),
            LayarKacaPlayerParser.orderedEpisodeCandidates(requested, candidates)
        )
    }

    @Test
    fun `fallback episode selection requires one exact season and episode`() {
        val provider = LayarKacaProvider()
        val request = NomatFallbackRequest(
            title = "Supergirl",
            year = 2015,
            season = 6,
            episode = 9
        )
        val episodes = listOf(
            provider.newEpisode("https://fallback.example/season-5-episode-9") {
                season = 5
                episode = 9
            },
            provider.newEpisode("https://fallback.example/season-6-episode-9") {
                season = 6
                episode = 9
            }
        )

        assertEquals(
            "https://fallback.example/season-6-episode-9",
            LayarKacaPlayerParser.fallbackEpisodeData(
                request = request,
                candidateTitle = "Supergirl (2015)",
                candidateYear = 2015,
                episodes = episodes
            )
        )
        assertNull(
            LayarKacaPlayerParser.fallbackEpisodeData(
                request = request,
                candidateTitle = "Supergirl 2",
                candidateYear = 2015,
                episodes = episodes
            )
        )
        assertNull(
            LayarKacaPlayerParser.fallbackEpisodeData(
                request = request,
                candidateTitle = "Supergirl",
                candidateYear = 2015,
                episodes = episodes + episodes.last()
            )
        )
    }

    @Test
    fun `season-only fallback requires the requested season to exist`() {
        val provider = LayarKacaProvider()
        val request = NomatFallbackRequest(
            title = "Supergirl",
            year = 2015,
            season = 6
        )
        val seasonFive = listOf(
            provider.newEpisode("https://fallback.example/season-5-episode-1") {
                season = 5
                episode = 1
            }
        )
        val seasonSix = provider.newEpisode(
            "https://fallback.example/season-6-episode-1"
        ) {
            season = 6
            episode = 1
        }

        assertFalse(
            LayarKacaPlayerParser.fallbackSeriesMatchesRequest(
                request,
                "Supergirl",
                2015,
                seasonFive
            )
        )
        assertTrue(
            LayarKacaPlayerParser.fallbackSeriesMatchesRequest(
                request,
                "Supergirl",
                2015,
                seasonFive + seasonSix
            )
        )
    }

    @Test
    fun `fallback playback retries until a provider actually emits a link`() = runBlocking {
        var attempts = 0
        val emitted = mutableListOf<String>()

        val loaded = retryFallbackPlayback(
            maxAttempts = 2,
            callback = emitted::add
        ) { callback ->
            attempts += 1
            if (attempts == 1) {
                false
            } else {
                callback("verified-link")
                true
            }
        }

        assertTrue(loaded)
        assertEquals(2, attempts)
        assertEquals(listOf("verified-link"), emitted)
    }

    @Test
    fun `fallback retry propagates callback failures without reporting success`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            retryFallbackPlayback(
                maxAttempts = 2,
                callback = { error("consumer rejected link") }
            ) { callback ->
                callback("verified-link")
                true
            }
        }
        Unit
    }

    @Test
    fun `fallback budget cancels the whole provider chain`() = runBlocking {
        var finished = false

        val loaded = withPlaybackFallbackBudget(timeoutMs = 25) {
            delay(250)
            finished = true
            true
        }

        assertFalse(loaded)
        assertFalse(finished)
    }

    @Test
    fun `catalog fallback skips failed and empty sources`() = runBlocking {
        val attempts = mutableListOf<String>()

        val results = firstNonEmptyFallback(
            candidates = listOf("failed", "empty", "working")
        ) { candidate ->
            attempts += candidate
            when (candidate) {
                "failed" -> error("temporary failure")
                "empty" -> emptyList()
                else -> listOf("playable")
            }
        }

        assertEquals(listOf("failed", "empty", "working"), attempts)
        assertEquals(listOf("playable"), results)
    }

    @Test
    fun `link fallback requires a provider to emit a link`() = runBlocking {
        val attempts = mutableListOf<String>()
        val emitted = mutableListOf<String>()

        val loaded = loadFirstEmittingFallback(
            candidates = listOf("false-positive", "working"),
            callback = emitted::add
        ) { candidate, callback ->
            attempts += candidate
            if (candidate == "working") callback("verified-link")
            true
        }

        assertTrue(loaded)
        assertEquals(listOf("false-positive", "working"), attempts)
        assertEquals(listOf("verified-link"), emitted)
    }

    @Test
    fun `link fallback never swallows callback failures`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            loadFirstEmittingFallback(
                candidates = listOf("working"),
                callback = { error("consumer rejected link") }
            ) { _, callback ->
                callback("invalid-link")
                true
            }
        }
        Unit
    }

    @Test
    fun `vidmoly mixed quote source bypasses the incompatible generic jw parser`() = runBlocking {
        val detailUrl = "https://tv.nontonfilm.red/movie/current/"
        val playerUrl = "https://vidmoly.example/embed-current.html"
        val mediaUrl =
            "https://box.example/hls2/current/master.m3u8?t=signed-token&s=123&e=43200"
        var genericExtractorCalls = 0
        val links = mutableListOf<ExtractorLink>()
        val resolver = LinkResolutionSession(
            api = LayarKacaProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, referer ->
                assertEquals(playerUrl, url)
                assertEquals(detailUrl, referer)
                """
                <script>
                  const sources = [{ "file": '$mediaUrl' }];
                </script>
                """.trimIndent()
            },
            extractorLoader = { _, _, _, _ ->
                genericExtractorCalls++
                false
            },
            inlineSourceParser = LayarKacaPlayerParser::mediaUrls,
            preferInlineSourceParser = true,
            mediaLinkProbe = { it.takeIf { link -> link.url == mediaUrl } },
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                newExtractorLink(source, name, url, type) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = headers
                }
            }
        )

        assertTrue(resolver.resolve(playerUrl, detailUrl))
        assertEquals(listOf(mediaUrl), links.map { it.url })
        assertEquals(0, genericExtractorCalls)
    }
}
