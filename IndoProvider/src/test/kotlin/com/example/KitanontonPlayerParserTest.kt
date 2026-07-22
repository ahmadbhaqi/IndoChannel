package com.example

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitanontonPlayerParserTest {
    @Test
    fun `orders decoded Abyss media before unsigned IP mirrors`() {
        val juicy = "https://178.211.139.171/embed/code"
        val unknown = "https://unknown.example/embed/code"
        val abyss = "https://abyssplayer.com/working"

        assertEquals(
            listOf(abyss, unknown, juicy),
            KitanontonPlayerParser.orderPlayerUrls(listOf(juicy, unknown, abyss))
        )
    }

    @Test
    fun `continues through every fallback after an earlier mirror emits`() = runBlocking {
        val visited = mutableListOf<String>()
        val abyss = "https://abyssplayer.com/possibly-expired"
        val unknown = "https://unknown.example/embed/code"
        val juicy = "https://178.211.139.171/embed/code"

        KitanontonPlayerParser.resolveAll(listOf(juicy, unknown, abyss)) { url ->
            visited += url
        }

        assertEquals(listOf(abyss, unknown, juicy), visited)
    }

    @Test
    fun `keeps resolving mirrors until two links are available`() = runBlocking {
        val visited = mutableListOf<String>()
        var linkCount = 0
        val abyss = "https://abyssplayer.com/possibly-expired"
        val firstFallback = "https://unknown.example/embed/code"
        val unusedFallback = "https://178.211.139.171/embed/code"

        KitanontonPlayerParser.resolveUntilTarget(
            urls = listOf(unusedFallback, firstFallback, abyss),
            targetLinkCount = 2,
            linkCount = { linkCount },
            canContinue = { true }
        ) { url ->
            visited += url
            linkCount++
        }

        assertEquals(listOf(abyss, firstFallback), visited)
        assertEquals(2, linkCount)
    }

    @Test
    fun `page retry propagates candidate budget cancellation`() = runBlocking {
        var attempts = 0
        val resolver = LinkResolutionSession(
            api = KitanontonProvider(),
            subtitleCallback = {},
            callback = {},
            candidateTimeoutMs = 100L,
            sessionTimeoutMs = 500L
        )

        val result = resolver.withinBudget {
            KitanontonPlayerParser.retryPageFetch(attempts = 3) {
                attempts++
                // Enter the second attempt deterministically, then let the
                // candidate deadline cancel it. A swallowed cancellation
                // would incorrectly start the third attempt.
                if (attempts == 2) delay(5_000L)
                null
            }
        }

        assertNull(result)
        assertEquals(2, attempts)
    }

    @Test
    fun `page retry accepts the first valid retry`() = runBlocking {
        val attempts = mutableListOf<Int>()

        val result = KitanontonPlayerParser.retryPageFetch(attempts = 2) { attempt ->
            attempts += attempt
            if (attempt == 1) "valid page" else null
        }

        assertEquals("valid page", result)
        assertEquals(listOf(0, 1), attempts)
    }

    @Test
    fun `continues from play route to detail route after an earlier link emits`() = runBlocking {
        val visited = mutableListOf<String>()
        val detail = "https://kitanonton.example/movie"

        KitanontonPlayerParser.resolvePages(listOf("$detail/play", detail)) { page ->
            visited += page
        }

        assertEquals(listOf("$detail/play", detail), visited)
    }

    @Test
    fun `play route keeps query parameters ahead of fragments`() {
        assertEquals(
            "https://kitanonton.example/movie/play?server=2",
            KitanontonPlayerParser.playPageUrl(
                "https://kitanonton.example/movie?server=2#watch"
            )
        )
    }

    @Test
    fun `series watch page groups multiple mirrors without mixing episodes`() {
        val episodeOneA = "https://178.211.139.171/embed/episode-one-a"
        val episodeOneB = "https://199.87.210.226/player/episode-one-b"
        val episodeTwoA = "https://178.211.139.171/embed/episode-two-a"
        val episodeTwoB = "https://abyssplayer.com/episode-two-b"
        val document = Jsoup.parse(
            """
            <div id="list-eps">
              <a class="btn-eps" data-iframe="${encodedUrl(episodeOneA)}">Ep1</a>
              <a class="btn-eps" data-iframe="${encodedUrl(episodeOneB)}">Ep1</a>
              <div data-iframe="${encodedUrl(episodeTwoA)}" title="Episode 2"></div>
              <div data-iframe="$episodeTwoB" title="Episode 2"></div>
              <a class="btn-eps" data-iframe="not-base64">Ep2</a>
            </div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                KitanontonWatchEpisode(1, listOf(episodeOneA, episodeOneB)),
                KitanontonWatchEpisode(2, listOf(episodeTwoA, episodeTwoB))
            ),
            KitanontonPlayerParser.watchEpisodes(document)
        )
        assertEquals(
            listOf(episodeTwoA, episodeTwoB),
            KitanontonPlayerParser.episodePlayerUrls(document, 2)
        )
    }

    @Test
    fun `series detail discovers same-host watch page`() {
        val detailUrl = "https://kitanonton2.casa/series/example/"
        val document = Jsoup.parse(
            """
            <div id="mv-info"><a href="/series/example/watch">Watch</a></div>
            """.trimIndent(),
            detailUrl
        )

        assertEquals(
            "https://kitanonton2.casa/series/example/watch",
            KitanontonPlayerParser.watchPageUrl(document, detailUrl)
        )

        val crossHost = Jsoup.parse(
            """<a class="thumb mvi-cover" href="https://attacker.example/series/example/watch"></a>""",
            detailUrl
        )
        assertNull(KitanontonPlayerParser.watchPageUrl(crossHost, detailUrl))
    }

    @Test
    fun `series detail rehomes legacy surf watch page`() {
        val detailUrl = "https://kitanonton2.casa/series/example/"
        val legacyWatchPage = Jsoup.parse(
            """<div id="mv-info"><a href="https://kitanonton2.surf/series/example/watch">Watch</a></div>""",
            detailUrl
        )

        assertEquals(
            "https://kitanonton2.casa/series/example/watch",
            KitanontonPlayerParser.watchPageUrl(legacyWatchPage, detailUrl)
        )
    }

    @Test
    fun `episode data round trips safely and malformed base64 is rejected`() {
        val detailUrl = "https://kitanonton.example/series/example/"
        val watchUrl = "https://kitanonton.example/series/example/watch"
        val encoded = KitanontonPlayerParser.encodeEpisodeData(detailUrl, watchUrl, 2)

        assertTrue(encoded.startsWith("$watchUrl?kitanonton_episode="))
        assertTrue(KitanontonPlayerParser.isEpisodeData(encoded))
        assertEquals(
            KitanontonEpisodeRequest(detailUrl, watchUrl, 2),
            KitanontonPlayerParser.decodeEpisodeData(encoded)
        )
        assertNull(KitanontonPlayerParser.decodeEpisodeData("kitanonton-episode:not-base64!"))
        assertNull(KitanontonPlayerParser.decodeServerUrl(encodedUrl("javascript:alert(1)")))
        assertNull(KitanontonPlayerParser.decodeServerUrl(encodedUrl("http://127.0.0.1/video")))
    }

    @Test
    fun `legacy episode data prefixed by Cloudstream remains decodable`() {
        val detailUrl = "https://kitanonton.example/series/example/"
        val watchUrl = "https://kitanonton.example/series/example/watch"
        val payload = listOf("3", detailUrl, watchUrl).joinToString("\n")
        val encoded = encodeBase64UrlNoPadding(payload.toByteArray(Charsets.UTF_8))
        val cloudstreamValue = "https://kitanonton.example/kitanonton-episode:$encoded"

        assertEquals(
            KitanontonEpisodeRequest(detailUrl, watchUrl, 3),
            KitanontonPlayerParser.decodeEpisodeData(cloudstreamValue)
        )
    }

    private fun encodedUrl(url: String): String =
        encodeBase64UrlNoPadding(url.toByteArray(Charsets.UTF_8))
}
