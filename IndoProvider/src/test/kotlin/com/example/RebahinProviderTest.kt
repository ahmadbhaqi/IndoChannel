package com.example

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RebahinProviderTest {
    private val mainUrl = "https://rebahinxxi3.lol"

    @Test
    fun `cached Rebahin URLs rehome only confirmed historical hosts`() {
        listOf("154.203.167.63", "178.62.115.110", "156.244.7.27").forEach { historicalHost ->
            assertEquals(
                "$mainUrl/movie/example/?server=2#watch",
                RebahinPageParser.normalizePageUrl(
                    "http://$historicalHost/movie/example/?server=2#watch",
                    mainUrl
                )
            )
        }

        assertEquals(
            "$mainUrl/movie/example/",
            RebahinPageParser.normalizePageUrl("/movie/example/", mainUrl)
        )
        assertNull(RebahinPageParser.normalizePageUrl("https://sohib21.example/movie/example/", mainUrl))
        assertNull(RebahinPageParser.normalizePageUrl("https://foreign.example/movie/example/", mainUrl))
    }

    @Test
    fun `current Rebahin aliases rehome exactly onto configured host`() {
        listOf(
            "rebahinxxi3.lol",
            "rebahinxxi3.hair",
            "rebahinxxi3.click"
        ).forEach { alias ->
            assertEquals(
                "$mainUrl/nonton/example/play/?server=2#watch",
                RebahinPageParser.normalizePageUrl(
                    "https://$alias/nonton/example/play/?server=2#watch",
                    mainUrl
                )
            )
        }
        val activeAlias = "https://rebahinxxi3.hair/nonton/example/"
        assertEquals(
            activeAlias,
            RebahinPageParser.activePageUrl(activeAlias, mainUrl, mainUrl)
        )
        assertEquals(
            "https://rebahinxxi3.hair/nonton/example/play/",
            RebahinPageParser.activePageUrl("play/", activeAlias, mainUrl)
        )
        assertEquals(
            listOf(
                "https://rebahinxxi3.hair/nonton/example/",
                "https://rebahinxxi3.lol/nonton/example/",
                "https://rebahinxxi3.click/nonton/example/"
            ),
            RebahinPageParser.aliasPageUrls(activeAlias, mainUrl)
        )
        assertTrue(
            RebahinPageParser.aliasPageUrls(
                "https://rebahinxxi3.hair/nonton/%E2%9C%93/?token=a%2Fb%2Bc",
                mainUrl
            ).all { it.endsWith("/nonton/%E2%9C%93/?token=a%2Fb%2Bc") }
        )

        assertNull(
            RebahinPageParser.normalizePageUrl(
                "https://rebahinxxi3.lol.evil.example/nonton/example/",
                mainUrl
            )
        )
        assertNull(
            RebahinPageParser.normalizePageUrl(
                "https://rebahinxxi3.example/nonton/example/",
                mainUrl
            )
        )
        assertNull(
            RebahinPageParser.activePageUrl(
                "https://rebahinxxi3.hair.evil.example/nonton/example/",
                mainUrl,
                mainUrl
            )
        )
    }

    @Test
    fun `catalog guard accepts Rebahin identity and rejects foreign or non-content pages`() {
        val valid = Jsoup.parse(
            """
            <html><head>
              <link rel="canonical" href="http://156.244.7.27/movie/example/">
              <meta property="og:url" content="$mainUrl/movie/example/">
              <meta property="og:site_name" content="Rebahin">
            </head><body><article class="item-infinite">Movie</article></body></html>
            """.trimIndent(),
            "$mainUrl/movie/example/"
        )
        assertTrue(RebahinPageParser.isTrustedCatalogDocument(valid, valid.location(), mainUrl))

        val aliasCanonical = Jsoup.parse(
            """
            <html><head>
              <link rel="canonical" href="https://rebahinxxi3.hair/movie/example/">
              <meta property="og:url" content="https://rebahinxxi3.click/movie/example/">
              <meta property="og:site_name" content="Rebahin">
            </head><body><h1 class="entry-title">Movie</h1></body></html>
            """.trimIndent(),
            "$mainUrl/movie/example/"
        )
        assertTrue(
            RebahinPageParser.isTrustedCatalogDocument(
                aliasCanonical,
                aliasCanonical.location(),
                mainUrl
            )
        )

        val foreignCanonical = Jsoup.parse(
            """
            <html><head>
              <link rel="canonical" href="https://sohib21.example/movie/example/">
            </head><body><h1 class="entry-title">Movie</h1></body></html>
            """.trimIndent(),
            "$mainUrl/movie/example/"
        )
        assertFalse(
            RebahinPageParser.isTrustedCatalogDocument(
                foreignCanonical,
                foreignCanonical.location(),
                mainUrl
            )
        )

        val foreignBrand = Jsoup.parse(
            """
            <html><head>
              <link rel="canonical" href="$mainUrl/movie/example/">
              <meta property="og:site_name" content="Sohib21">
            </head><body><h1 class="entry-title">Movie</h1></body></html>
            """.trimIndent(),
            "$mainUrl/movie/example/"
        )
        assertFalse(
            RebahinPageParser.isTrustedCatalogDocument(foreignBrand, foreignBrand.location(), mainUrl)
        )

        val identityLess = Jsoup.parse(
            """<html><body><h1 class="entry-title">Generic movie page</h1></body></html>""",
            "$mainUrl/movie/example/"
        )
        assertFalse(
            RebahinPageParser.isTrustedCatalogDocument(identityLess, identityLess.location(), mainUrl)
        )

        val challenge = Jsoup.parse(
            """<html><head><title>Just a moment...</title></head><body>Enable JavaScript and cookies to continue</body></html>""",
            "$mainUrl/"
        )
        assertFalse(RebahinPageParser.isTrustedCatalogDocument(challenge, challenge.location(), mainUrl))
        val empty = Jsoup.parse("", "$mainUrl/")
        assertFalse(RebahinPageParser.isTrustedCatalogDocument(empty, empty.location(), mainUrl))
        assertFalse(
            RebahinPageParser.isTrustedCatalogDocument(
                valid,
                "https://foreign.example/movie/example/",
                mainUrl
            )
        )
    }

    @Test
    fun `Rebahin media parser returns every iframe meta and video candidate`() {
        val document = Jsoup.parse(
            """
            <html><head>
              <meta property="og:video:url" content="https://video.example/meta/master.m3u8">
            </head><body>
              <div class="gmr-embed-responsive">
                <iframe data-src="https://player.example/lazy"></iframe>
                <iframe src="https://player.example/second"></iframe>
              </div>
              <video src="https://video.example/direct.mp4"></video>
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://player.example/lazy",
                "https://player.example/second",
                "https://video.example/meta/master.m3u8",
                "https://video.example/direct.mp4"
            ),
            RebahinPageParser.mediaSources(document)
        )
    }

    @Test
    fun `current Rebahin cards and detail poster shape are parsed`() {
        val provider = RebahinProvider()
        val card = Jsoup.parse(
            """
            <div class="ml-item">
              <a href="$mainUrl/nonton-aku-sebelum-aku-2026-sub-indo/"
                 title="Aku Sebelum Aku (2026)">
                <img data-original="https://image.example/aku.jpg">
                <span class="mli-info"><h2>Aku Sebelum Aku (2026)</h2></span>
              </a>
            </div>
            """.trimIndent(),
            mainUrl
        ).selectFirst(".ml-item")!!
        val result = provider.run { card.toSearchResult() }
        assertEquals("Aku Sebelum Aku (2026)", result?.name)
        assertEquals(
            "$mainUrl/nonton-aku-sebelum-aku-2026-sub-indo/",
            result?.url
        )

        val detail = Jsoup.parse(
            """
            <html><head>
              <meta property="og:image" content="https://image.example/current.jpg">
            </head><body>
              <div id="mv-info"><a href="/nonton-aku/play/">Play</a></div>
            </body></html>
            """.trimIndent(),
            "$mainUrl/nonton-aku/"
        )
        assertEquals(
            "https://image.example/current.jpg",
            RebahinPageParser.posterUrl(detail)
        )
        assertEquals(
            "$mainUrl/nonton-aku/play/",
            RebahinPageParser.playPageUrl(detail, detail.location())
        )
        assertEquals(
            "$mainUrl/wp-content/uploads/2020/01/jashin.jpg",
            RebahinPageParser.normalizePosterUrl(
                "http://198.54.124.245/wp-content/uploads/2020/01/jashin.jpg",
                mainUrl
            )
        )
        assertNull(
            RebahinPageParser.normalizePosterUrl(
                "https://rebahin.shop/wp-content/uploads/fb-capture.png",
                mainUrl
            )
        )
    }

    @Test
    fun `card poster falls through placeholder attributes and images`() {
        val card = Jsoup.parse(
            """
            <div class="ml-item">
              <img data-src="$mainUrl/wp-content/uploads/placeholder.png">
              <img data-src="$mainUrl/wp-content/uploads/no-image.jpg"
                   data-original="https://image.example/healthy-poster.jpg">
            </div>
            """.trimIndent(),
            "$mainUrl/page/1/"
        ).selectFirst(".ml-item")!!

        assertEquals(
            "https://image.example/healthy-poster.jpg",
            RebahinPageParser.cardPosterUrl(card, mainUrl, card.baseUri())
        )
    }

    @Test
    fun `detail poster prefers TMDB og image and rejects placeholders or invalid URLs`() {
        val tmdbPoster = "https://image.tmdb.org/t/p/w185/current-poster.jpg"
        val detail = Jsoup.parse(
            """
            <html><head>
              <meta itemprop="image" content="https://rebahinxxi3.lol/wp-content/uploads/less-preferred.jpg">
              <meta property="og:image" content="$tmdbPoster">
            </head><body>
              <img class="thumbnail" src="https://rebahinxxi3.lol/wp-content/uploads/fallback.jpg">
            </body></html>
            """.trimIndent(),
            "$mainUrl/nonton/example/"
        )

        assertEquals(tmdbPoster, RebahinPageParser.posterUrl(detail))
        assertEquals(tmdbPoster, RebahinPageParser.normalizePosterUrl(tmdbPoster, mainUrl))
        assertEquals(
            "$mainUrl/wp-content/uploads/current.jpg",
            RebahinPageParser.normalizePosterUrl(
                "https://rebahinxxi3.hair/wp-content/uploads/current.jpg",
                mainUrl
            )
        )
        assertEquals(
            "https://rebahinxxi3.hair/wp-content/uploads/current.jpg",
            RebahinPageParser.normalizePosterUrl(
                "https://rebahinxxi3.lol/wp-content/uploads/current.jpg",
                mainUrl,
                "https://rebahinxxi3.hair/nonton/example/"
            )
        )

        listOf(
            "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==",
            "javascript:alert(1)",
            "https://localhost/poster.jpg",
            "https://127.0.0.1/poster.jpg",
            "https://rebahinxxi3.click/wp-content/uploads/placeholder.png",
            "https://rebahinxxi3.lol/wp-content/uploads/no-image.jpg",
            "https://rebahinxxi3.lol/wp-content/uploads/blank.gif"
        ).forEach { invalid ->
            assertNull(
                RebahinPageParser.normalizePosterUrl(invalid, mainUrl),
                "Expected invalid poster to be rejected: $invalid"
            )
        }
    }

    @Test
    fun `detail poster prefers TMDB from any metadata then falls through placeholder images`() {
        val tmdbPoster = "https://image.tmdb.org/t/p/w500/metadata-poster.jpg"
        val metadataDetail = Jsoup.parse(
            """
            <html><head>
              <meta property="og:image" content="https://image.example/generic-og.jpg">
              <meta itemprop="image" content="$tmdbPoster">
            </head><body></body></html>
            """.trimIndent(),
            "$mainUrl/nonton/example/"
        )
        assertEquals(tmdbPoster, RebahinPageParser.posterUrl(metadataDetail))

        val imageDetail = Jsoup.parse(
            """
            <div class="mvi-cover">
              <img src="$mainUrl/wp-content/uploads/placeholder.png">
              <img data-src="$mainUrl/wp-content/uploads/no-poster.jpg"
                   data-original="https://image.example/detail-poster.jpg">
            </div>
            """.trimIndent(),
            "$mainUrl/nonton/example/"
        )
        assertEquals(
            "https://image.example/detail-poster.jpg",
            RebahinPageParser.normalizedPosterUrl(imageDetail, mainUrl, imageDetail.location())
        )
    }

    @Test
    fun `current Rebahin player prioritizes Abyss before signed IP mirrors`() {
        val ipMirror = "https://178.211.139.171/embed/current"
        val abyssMirror = "https://abyssplayer.com/current"
        val document = Jsoup.parse(
            """
            <div class="server" data-iframe="${encoded(ipMirror)}"></div>
            <div class="server" data-iframe="${encoded(abyssMirror)}"></div>
            """.trimIndent()
        )

        assertEquals(
            listOf(abyssMirror, ipMirror),
            RebahinPageParser.mediaSources(document)
        )
    }

    @Test
    fun `current Rebahin series episode payload round trips safely`() {
        val detail = "$mainUrl/series/example/"
        val watch = "$mainUrl/series/example/watch/"
        val data = RebahinPageParser.encodeEpisodeData(detail, watch, 7)

        assertEquals(
            RebahinEpisodeRequest(detail, watch, 7),
            RebahinPageParser.decodeEpisodeData(data)
        )
        val watchPage = Jsoup.parse(
            """
            <div id="list-eps">
              <a class="btn-eps" data-iframe="${encoded("https://abyssplayer.com/ep7")}">EP 7</a>
              <a class="btn-eps" data-iframe="${encoded("https://178.211.139.171/ep7")}">EP 7</a>
              <div title="Episode 8" data-iframe="${encoded("https://abyssplayer.com/ep8")}"></div>
            </div>
            """.trimIndent()
        )
        assertEquals(listOf(7, 8), RebahinPageParser.watchEpisodes(watchPage))
        assertEquals(
            listOf(
                "https://abyssplayer.com/ep7",
                "https://178.211.139.171/ep7"
            ),
            RebahinPageParser.episodePlayerUrls(watchPage, 7)
        )
    }

    @Test
    fun `series payload rehomes detail when watch succeeds on another alias`() {
        val detail = "https://rebahinxxi3.hair/series/example/"
        val watch = "https://rebahinxxi3.click/series/example/watch/"
        val rehomedDetail = RebahinPageParser.rehomePageUrl(
            detail,
            "https://rebahinxxi3.click"
        )!!

        assertEquals("https://rebahinxxi3.click/series/example/", rehomedDetail)
        assertEquals(
            RebahinEpisodeRequest(rehomedDetail, watch, 3),
            RebahinPageParser.decodeEpisodeData(
                RebahinPageParser.encodeEpisodeData(rehomedDetail, watch, 3)
            )
        )
    }

    @Test
    fun `minimal watch fragment is trusted but foreign identity is rejected`() {
        val watchUrl = "https://rebahinxxi3.hair/series/example/watch/"
        val minimal = Jsoup.parse(
            """
            <div id="list-eps">
              <a class="btn-eps" data-iframe="${encoded("https://abyssplayer.com/ep1")}">EP 1</a>
            </div>
            """.trimIndent(),
            watchUrl
        )
        assertTrue(RebahinPageParser.isTrustedPlayerDocument(minimal, watchUrl, mainUrl))

        val foreign = Jsoup.parse(
            """
            <html><head><meta property="og:url" content="https://evil.example/watch/"></head>
            <body><iframe src="https://player.example/embed/1"></iframe></body></html>
            """.trimIndent(),
            watchUrl
        )
        assertFalse(RebahinPageParser.isTrustedPlayerDocument(foreign, watchUrl, mainUrl))
        assertFalse(
            RebahinPageParser.isTrustedPlayerDocument(
                minimal,
                "https://evil.example/watch/",
                mainUrl
            )
        )
    }

    @Test
    fun `soft 404 player alias falls through to the next healthy alias`() = runBlocking {
        val provider = RebahinProvider()
        val requestedUrl = "https://rebahinxxi3.hair/nonton/example/play/"
        val attempted = mutableListOf<String>()
        val soft404 = Jsoup.parse(
            """
            <html><head><title>404 - Page not found</title></head>
            <body>The requested player page does not exist.</body></html>
            """.trimIndent(),
            requestedUrl
        )
        val healthyUrl = "$mainUrl/nonton/example/play/"
        val healthy = Jsoup.parse(
            """<div class="gmr-embed-responsive"><iframe src="https://abyssplayer.com/embed/example"></iframe></div>""",
            healthyUrl
        )

        val result = provider.selectProviderAliasPage(
            url = requestedUrl,
            requirePlayerIdentity = true
        ) { candidate, _ ->
            attempted += candidate
            when (candidate) {
                requestedUrl -> RebahinProvider.ProviderPage(soft404, candidate)
                healthyUrl -> RebahinProvider.ProviderPage(healthy, candidate)
                else -> null
            }
        }

        assertEquals(listOf(requestedUrl, healthyUrl), attempted)
        assertEquals(healthyUrl, result?.url)
        assertEquals(
            listOf("https://abyssplayer.com/embed/example"),
            result?.document?.let(RebahinPageParser::mediaSources)
        )
    }

    @Test
    fun `alias selection bounds slow attempts while retaining cached origin first`() = runBlocking {
        val provider = RebahinProvider()
        val cachedUrl = "http://156.244.7.27/nonton/example/play/"
        val cachedReferer = "http://156.244.7.27/nonton/example/"
        val attempted = mutableListOf<Pair<String, String?>>()

        val result = withTimeout(1_000) {
            provider.selectProviderAliasPage(
                url = cachedUrl,
                referer = cachedReferer,
                maxAttempts = Int.MAX_VALUE,
                attemptTimeoutMs = 15L
            ) { candidate, candidateReferer ->
                attempted += candidate to candidateReferer
                delay(100)
                null
            }
        }

        assertNull(result)
        assertEquals(REBAHIN_MAX_ALIAS_ATTEMPTS, attempted.size)
        assertEquals(cachedUrl, attempted.first().first)
        assertEquals(cachedReferer, attempted.first().second)
        assertTrue(attempted.drop(1).all { (candidate, referer) ->
            referer != null && URI(candidate).host == URI(referer).host
        })
    }

    @Test
    fun `AJAX response URL resolves relatives but embedding page remains referer`() {
        val embeddingPage = "https://rebahinxxi3.hair/nonton/example/play/"
        val ajaxResponse = "https://rebahinxxi3.lol/wp-admin/admin-ajax.php"
        val document = Jsoup.parse(
            """
            <iframe src="players/embed-one"></iframe>
            <iframe src="https://player.example/embed-two"></iframe>
            """.trimIndent(),
            ajaxResponse
        )

        assertEquals(
            listOf(
                RebahinMediaCandidate(
                    "https://rebahinxxi3.lol/wp-admin/players/embed-one",
                    embeddingPage
                ),
                RebahinMediaCandidate("https://player.example/embed-two", embeddingPage)
            ),
            RebahinPageParser.ajaxMediaCandidates(document, ajaxResponse, embeddingPage)
        )
    }

    @Test
    fun `detail media is resolved before the optional play page is fetched`() = runBlocking {
        val provider = RebahinProvider()
        val detailUrl = "$mainUrl/nonton/example/"
        val playUrl = "$mainUrl/nonton/example/play/"
        val detailSource = "https://abyssplayer.com/embed/detail"
        val playSource = "https://abyssplayer.com/embed/play"
        val detailPage = RebahinProvider.ProviderPage(
            Jsoup.parse("""<iframe src="$detailSource"></iframe>""", detailUrl),
            detailUrl
        )
        val playPage = RebahinProvider.ProviderPage(
            Jsoup.parse("""<iframe src="$playSource"></iframe>""", playUrl),
            playUrl
        )
        val events = mutableListOf<String>()

        val result = provider.discoverPlaybackPages(
            detailPage = detailPage,
            playUrl = playUrl,
            resolvePage = { page ->
                val source = RebahinPageParser.mediaSources(page.document).firstOrNull()
                events += "resolve:$source"
                source == detailSource
            },
            fetchPlayPage = { candidate ->
                events += "fetch:$candidate"
                playPage
            }
        )

        assertTrue(result.loaded)
        assertEquals(listOf(detailPage), result.pages)
        assertEquals(listOf("resolve:$detailSource"), events)
    }

    private fun encoded(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray())
}
