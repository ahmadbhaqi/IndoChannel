package com.example

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jsoup.Jsoup

class KuramanimeHydrationRegressionTest {
    private val episodeUrl =
        "https://v19.kuramanime.ing/anime/4432/ugoku-neko-mukashibanashi/episode/41"

    @Test
    fun `kuramanime reads the live check page csrf and secure loader routes`() {
        val document = Jsoup.parse(
            """
            <html>
              <head>
                <meta name="csrf-token" content="csrfCurrent123">
              </head>
              <body>
                <input
                  type="hidden"
                  id="checkEp"
                  value="/anime/4432/episode/41/check-episode"
                >
                <input
                  type="hidden"
                  id="tokenAuthJs"
                  value="/storage/leviathan.js?v=1448"
                >
              </body>
            </html>
            """.trimIndent(),
            episodeUrl
        )

        val metadata = assertNotNull(
            KuramanimeBootstrap.pageMetadata(document, episodeUrl)
        )

        assertEquals(
            "https://v19.kuramanime.ing/anime/4432/episode/41/check-episode",
            metadata.checkUrl
        )
        assertEquals(
            "https://v19.kuramanime.ing/storage/leviathan.js?v=1448",
            metadata.secureLoaderUrl
        )
        assertEquals("csrfCurrent123", metadata.csrfToken)
        assertEquals(4, KuramanimeBootstrap.checkPageValue("\"4\""))
        assertNull(KuramanimeBootstrap.checkPageValue("0"))
        assertNull(KuramanimeBootstrap.checkPageValue("../4"))
    }

    @Test
    fun `kuramanime resolves secure loader authorization by explicit value or known version`() {
        assertEquals(
            "readableRotationToken1234567890",
            KuramanimeBootstrap.secureLoaderAuthorization(
                """
                window.jLoadSecureConfig = {
                    authorization: "readableRotationToken1234567890"
                };
                """.trimIndent(),
                "https://v19.kuramanime.ing/storage/leviathan.js?v=2000"
            )
        )
        assertEquals(
            "kJuHHkaqcBFXiGMHQf6bJw8YAyDcwGD8Ur",
            KuramanimeBootstrap.secureLoaderAuthorization(
                "/* bounded obfuscated loader fixture */",
                "https://v19.kuramanime.ing/storage/leviathan.js?v=1448"
            )
        )
        assertNull(
            KuramanimeBootstrap.secureLoaderAuthorization(
                "/* an unknown rotated loader must not reuse a stale token */",
                "https://v19.kuramanime.ing/storage/leviathan.js?v=2001"
            )
        )
    }

    @Test
    fun `kuramanime hydration request uses checked page and secure post contract`() {
        val config = KuramanimeBootstrapConfig(
            tokenUrl = "https://v19.kuramanime.ing/assets/token.txt",
            authHeader = "headerKey:headerToken",
            pageTokenKey = "pageTokenKey",
            streamServerKey = "serverKey"
        )

        val request = assertNotNull(
            KuramanimeBootstrap.hydrationRequest(
                episodeUrl = episodeUrl,
                accessToken = "accessTokenCurrent",
                configuration = config,
                page = 4,
                secureLoaderAuthorization = "loaderAuthorizationCurrent",
                csrfToken = "csrfCurrent123"
            )
        )

        assertEquals(
            "$episodeUrl?pageTokenKey=accessTokenCurrent&serverKey=kuramadrive&page=4",
            request.url
        )
        assertEquals(
            mapOf("authorization" to "loaderAuthorizationCurrent"),
            request.form
        )
        assertEquals("csrfCurrent123", request.headers["X-CSRF-TOKEN"])
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
        assertEquals("text/html, */*; q=0.01", request.headers["Accept"])
        assertEquals("https://v19.kuramanime.ing", request.headers["Origin"])
        assertTrue(
            request.headers["User-Agent"]?.startsWith("Mozilla/5.0") == true,
            "Kuramanime only returns hydrated media to a browser user agent"
        )
        assertEquals(
            emptyMap(),
            request.cookies,
            "Explicit cookies must not replace the provider session cookie jar"
        )
    }

    @Test
    fun `kuramanime reads direct mp4 sources from the hydrated player fragment`() {
        val html =
            """
            <div id="animeVideoPlayer" class="anime_vid_player">
              <video id="player">
                <source src="https://kitasan.my.id/kdrive/current-720.mp4" size="720">
                <source src="https://kitasan.my.id/kdrive/current-480.mp4" size="480">
                <source src="https://kitasan.my.id/kdrive/current-360.mp4" size="360">
              </video>
            </div>
            """.trimIndent()

        assertEquals(
            listOf(
                "https://kitasan.my.id/kdrive/current-720.mp4",
                "https://kitasan.my.id/kdrive/current-480.mp4",
                "https://kitasan.my.id/kdrive/current-360.mp4"
            ),
            KuramanimeParser.playerUrls(html, episodeUrl)
        )
    }

    @Test
    fun `kuramanime current bootstrap hydrates playable media through safe HTTP`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            Assume.assumeTrue(false)
            return@runBlocking
        }

        val currentEpisode = System.getenv("KURAMANIME_LIVE_EPISODE_URL")
            ?.takeIf(String::isNotBlank)
            ?: "https://v19.kuramanime.ing/anime/5071/ibitte-konai-gibo-to-gishi/episode/4"
        val ownedHosts = setOf(
            "v19.kuramanime.ing",
            "v17.kuramanime.ing",
            "v17.kuramanime.tel"
        )
        val safeHttp = ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
        fun normalizer(baseUrl: String) = ProviderUrlNormalizer { candidate ->
            ProviderHtmlParser.preserveProviderPageUrl(candidate, baseUrl, ownedHosts)
        }

        val page = safeHttp.get(currentEpisode, normalizer(currentEpisode))
        assertTrue(page.code in 200..299, "episode page returned HTTP ${page.code}")
        val document = Jsoup.parse(page.body, page.url)
        val metadata = assertNotNull(
            KuramanimeBootstrap.pageMetadata(document, page.url),
            "episode bootstrap metadata was missing"
        )

        val check = safeHttp.get(
            metadata.checkUrl,
            normalizer(page.url),
            referer = page.url,
            maxBodyBytes = 4_096
        )
        assertTrue(check.code in 200..299, "check-page returned HTTP ${check.code}")
        val checkedPage = assertNotNull(
            KuramanimeBootstrap.checkPageValue(check.body),
            "check-page returned an invalid value"
        )

        val loader = safeHttp.get(
            metadata.secureLoaderUrl,
            normalizer(page.url),
            referer = page.url,
            maxBodyBytes = 262_144
        )
        assertTrue(loader.code in 200..299, "secure loader returned HTTP ${loader.code}")
        val authorization = assertNotNull(
            KuramanimeBootstrap.secureLoaderAuthorization(
                loader.body,
                loader.url
            ),
            "secure-loader authorization was not recognized"
        )

        val bootstrapUrl = assertNotNull(
            document.select("script[src*='arc-signal']")
                .mapNotNull {
                    ProviderHtmlParser.absoluteUrl(it.attr("src"), page.url)
                }
                .firstOrNull(),
            "configuration bootstrap script was missing"
        )
        val bootstrap = safeHttp.get(
            bootstrapUrl,
            normalizer(page.url),
            referer = page.url,
            maxBodyBytes = 262_144
        )
        assertTrue(bootstrap.code in 200..299, "configuration bootstrap returned HTTP ${bootstrap.code}")
        val configurationUrl = assertNotNull(
            KuramanimeBootstrap.configurationScriptUrl(
                bootstrap.body,
                bootstrap.url
            ),
            "configuration script URL was not recognized"
        )
        val configurationScript = safeHttp.get(
            configurationUrl,
            normalizer(page.url),
            referer = page.url,
            maxBodyBytes = 262_144
        )
        assertTrue(
            configurationScript.code in 200..299,
            "configuration script returned HTTP ${configurationScript.code}"
        )
        val configuration = assertNotNull(
            KuramanimeBootstrap.configuration(
                configurationScript.body,
                configurationScript.url
            ),
            "configuration variables were not recognized"
        )

        val token = safeHttp.get(
            configuration.tokenUrl,
            normalizer(configurationScript.url),
            headers = mapOf(
                "X-Fuck-ID" to configuration.authHeader,
                "X-Request-ID" to "Ab12Cd",
                "X-Request-Index" to "0"
            ),
            referer = page.url,
            maxBodyBytes = 4_096
        )
        assertTrue(token.code in 200..299, "token endpoint returned HTTP ${token.code}")
        val accessToken = assertNotNull(
            KuramanimeBootstrap.tokenValue(token.body),
            "token endpoint returned an invalid token"
        )
        val hydration = assertNotNull(
            KuramanimeBootstrap.hydrationRequest(
                episodeUrl = page.url,
                accessToken = accessToken,
                configuration = configuration,
                page = checkedPage,
                secureLoaderAuthorization = authorization,
                csrfToken = metadata.csrfToken
            ),
            "hydration request could not be constructed"
        )
        val hydrated = safeHttp.postForm(
            hydration.url,
            hydration.form,
            normalizer(page.url),
            headers = hydration.headers,
            referer = page.url,
            cookies = hydration.cookies
        )
        assertTrue(hydrated.code in 200..299, "hydration returned HTTP ${hydrated.code}")
        val hydratedDocument = Jsoup.parse(hydrated.body, hydrated.url)
        val media = KuramanimeParser.playerUrls(hydrated.body, hydrated.url)
        assertTrue(
            media.isNotEmpty(),
            "hydration response contained no playable media: " +
                "bytes=${hydrated.bodyBytes.size}, " +
                "player=${hydratedDocument.selectFirst("#animeVideoPlayer")
                    ?.text()?.replace(Regex("\\s+"), " ")?.take(240)}, " +
                "defaultHeaders=${app.defaultHeaders.keys.sorted()}"
        )
    }
}
