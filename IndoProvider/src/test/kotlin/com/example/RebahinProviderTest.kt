package com.example

import org.jsoup.Jsoup
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
              <a class="thumb mvi-cover" href="/nonton-aku/play/"></a>
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
    fun `current Rebahin player decodes Base64 mirrors and defers Abyss chunks`() {
        val ipMirror = "https://178.211.139.171/embed/current"
        val abyssMirror = "https://abyssplayer.com/current"
        val document = Jsoup.parse(
            """
            <div class="server" data-iframe="${encoded(ipMirror)}"></div>
            <div class="server" data-iframe="${encoded(abyssMirror)}"></div>
            """.trimIndent()
        )

        assertEquals(
            listOf(ipMirror, abyssMirror),
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
              <a class="btn-eps" data-iframe="${encoded("https://abyssplayer.com/ep8")}">EP 8</a>
            </div>
            """.trimIndent()
        )
        assertEquals(listOf(7, 8), RebahinPageParser.watchEpisodes(watchPage))
        assertEquals(
            listOf(
                "https://178.211.139.171/ep7",
                "https://abyssplayer.com/ep7"
            ),
            RebahinPageParser.episodePlayerUrls(watchPage, 7)
        )
    }

    private fun encoded(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray())
}
