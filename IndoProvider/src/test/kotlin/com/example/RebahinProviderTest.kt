package com.example

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RebahinProviderTest {
    private val mainUrl = "https://154.203.167.63"

    @Test
    fun `cached Rebahin URLs rehome only confirmed historical hosts`() {
        listOf("178.62.115.110", "156.244.7.27").forEach { historicalHost ->
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
}
