package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup

class ProviderHtmlParserTest {
    @Suppress("DEPRECATION_ERROR")
    private fun directExtractorLink(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
        type: ExtractorLinkType,
        headers: Map<String, String>
    ): ExtractorLink = ExtractorLink(source, name, url, referer, quality, type, headers, null)

    @Test
    fun `firstIframeSource reads lazy iframe attributes before src`() {
        val element = Jsoup.parse(
            """
            <iframe
                src="https://fallback.example/embed"
                data-src="https://data-src.example/embed"
                data-litespeed-src="https://lazy.example/embed">
            </iframe>
            """.trimIndent()
        ).selectFirst("iframe")

        assertEquals("https://lazy.example/embed", ProviderHtmlParser.firstIframeSource(element))
    }

    @Test
    fun `firstIframeSource ignores blank and script iframe values`() {
        val blank = Jsoup.parse("""<iframe data-src=" " src=""></iframe>""").selectFirst("iframe")
        val script = Jsoup.parse("""<iframe src="javascript:void(0)"></iframe>""").selectFirst("iframe")

        assertNull(ProviderHtmlParser.firstIframeSource(blank))
        assertNull(ProviderHtmlParser.firstIframeSource(script))
    }

    @Test
    fun `imageSource falls back from blank lazy attributes to src`() {
        val image = Jsoup.parse(
            """
            <img
                data-src=""
                data-lazy-src=" "
                src="https://image.example/poster.jpg">
            """.trimIndent(),
            "https://provider.example"
        ).selectFirst("img")

        assertEquals("https://image.example/poster.jpg", ProviderHtmlParser.imageSource(image))
    }

    @Test
    fun `imageSource reads srcset before placeholder src`() {
        val image = Jsoup.parse(
            """
            <img
                src="data:image/gif;base64,placeholder"
                srcset="https://image.example/poster-300.jpg 300w, https://image.example/poster-600.jpg 600w">
            """.trimIndent(),
            "https://provider.example"
        ).selectFirst("img")

        assertEquals("https://image.example/poster-300.jpg", ProviderHtmlParser.imageSource(image))
    }

    @Test
    fun `firstImageSource skips theme control icons and returns poster`() {
        val card = Jsoup.parse(
            """
            <article>
                <img src="https://provider.example/assets/images/controls-play.svg">
                <img itemprop="image" src="https://image.example/poster.jpg">
            </article>
            """.trimIndent()
        ).selectFirst("article")

        assertEquals("https://image.example/poster.jpg", ProviderHtmlParser.firstImageSource(card, "img"))
    }

    @Test
    fun `absoluteUrl resolves provider relative links against explicit base url`() {
        assertEquals(
            "https://plus.oploverz.ltd/series/3d-kanojo-real-girl",
            ProviderHtmlParser.absoluteUrl("/series/3d-kanojo-real-girl", "https://plus.oploverz.ltd")
        )
    }

    @Test
    fun `normalizeUrlHost replaces mirror host with active provider host`() {
        assertEquals(
            "https://154.203.167.63/bandits-of-batavia-2027/",
            ProviderHtmlParser.normalizeUrlHost(
                "https://tv5.rebahinxxi.auction/bandits-of-batavia-2027/",
                "https://154.203.167.63",
                "rebahinxxi"
            )
        )
    }

    @Test
    fun `mediaSources includes playable og video url`() {
        val document = Jsoup.parse(
            """
            <html>
                <head>
                    <meta property="og:video:url" content="https://video.example/embed/123">
                </head>
                <body>
                    <iframe src="about:blank"></iframe>
                </body>
            </html>
            """.trimIndent()
        )

        assertEquals(listOf("https://video.example/embed/123"), ProviderHtmlParser.mediaSources(document))
    }

    @Test
    fun `muviproAjaxRequests returns post id and tab pairs`() {
        val document = Jsoup.parse(
            """
            <div id="muvipro_player_content_id" data-id="321"></div>
            <div class="tab-content-ajax" id="player1"></div>
            <div class="tab-content-ajax" id="player2"></div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MuviproAjaxRequest(postId = "321", tab = "player1"),
                MuviproAjaxRequest(postId = "321", tab = "player2")
            ),
            ProviderHtmlParser.muviproAjaxRequests(document)
        )
    }

    @Test
    fun `muviproAjaxRequests returns empty when post id is missing`() {
        val document = Jsoup.parse("""<div class="tab-content-ajax" id="player1"></div>""")

        assertEquals(emptyList(), ProviderHtmlParser.muviproAjaxRequests(document))
    }

    @Test
    fun `oploverzStreamUrls reads the requested episode stream block`() {
        val html = """
            episodeNumber:"2",streamUrl:[{source:"sd",url:"https://wrong.example/embed"}],content:null
            episodeNumber:"1",streamUrl:[{source:"sd",url:"https:\/\/www.blogger.com\/video.g?token=abc\u0026expires=1"}],content:null
        """.trimIndent()

        assertEquals(
            listOf("https://www.blogger.com/video.g?token=abc&expires=1"),
            InlineDataParser.oploverzStreamUrls(html, 1)
        )
    }

    @Test
    fun `miranimeSourceUrls reads escaped React flight source links`() {
        val html = """
            self.__next_f.push([1,"{\"sources\":[{\"link\":\"https:\/\/luluvid.com\/e\/abc\",\"provider\":\"Luluvid\"},{\"link\":\"https:\/\/kuro.example\/file.mp4\",\"provider\":\"GoogleDrive\"}]}"])
        """.trimIndent()

        assertEquals(
            listOf("https://luluvid.com/e/abc", "https://kuro.example/file.mp4"),
            InlineDataParser.miranimeSourceUrls(html)
        )
    }

    @Test
    fun `playSobatUrls decrypts encrypted player payload`() {
        val html = """
            <script>
                window.payload = "{\"iv\":\"ABEiM0RVZneImaq7zN3u/w==\",\"data\":\"jcnQBUKMrJE5BkzD119j3/yizUSXLAM0pS062Yj0wREvec9ySwfrXuSq/IOVVCW6WvsAa7UwxT1hs+oWmuIpcd8GJ1sXubg1CEOd4Yovu7NoyuHwc3ZgZsX48VhsbWie\"}";
            </script>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://abysscdn.com/?v=UDPNmR2acq",
                "https://filemoon.sx/e/v2dt7jq5kxpr"
            ),
            InlineDataParser.playSobatUrls(html)
        )
    }

    @Test
    fun `kuronimeSourceId reads encrypted api id from watch page`() {
        val html = """
            <script>var xenHash = "awar"; var _0xa100d42aa = "encrypted-source-id";</script>
        """.trimIndent()

        assertEquals("encrypted-source-id", InlineDataParser.kuronimeSourceId(html))
    }

    @Test
    fun `kuronimeMirrorUrls decrypts api mirror payload`() {
        val encrypted = "eyJjdCI6Im4xVGEyb2JmWnZZUnlrTDF6dkJBQWJXa3FzNThoTW5OSWk1bE1ERzB0REc4UHBpWjF3Ujl3ZkphZzlROWxoV3RrVFJEblVrcXJMQUtlbFErNHpsOUVVK2hPRGY1WnNDU3NvSlk5Q0JSa05ZeUZ6S29WdUZJT1Qrb3ZTUHI4MkU2emNTQlB3WE5NUkhpRGg2N0pFUjhVRjBDL2I5WTI1UjVBWVJPVXRiRldhYz0iLCJpdiI6ImI5ODJhYjQ1M2VkOGM0OTU2YTNkMjI5MTRhNGYxODVjIiwicyI6IjAwMTEyMjMzNDQ1NTY2NzcifQ=="

        assertEquals(
            listOf(
                "https://filemoon.sx/e/kuronime",
                "https://filelions.to/v/kuronime"
            ),
            InlineDataParser.kuronimeMirrorUrls(encrypted)
        )
    }

    @Test
    fun `idlixCatalogItems reads list and search responses`() {
        val listJson = """
            {
                "data": [
                    {
                        "id": "movie-id",
                        "contentType": "movie",
                        "title": "The Furious",
                        "slug": "the-furious-2026",
                        "posterPath": "/poster.jpg",
                        "releaseDate": "2026-06-10",
                        "quality": "WEB-DL"
                    }
                ]
            }
        """.trimIndent()
        val searchJson = """
            {
                "results": [
                    {
                        "id": "series-id",
                        "contentType": "tv_series",
                        "title": "Fast & Furious Spy Racers",
                        "slug": "fast-and-furious-spy-racers-2019",
                        "posterPath": "/series.jpg",
                        "firstAirDate": "2019-12-26"
                    }
                ]
            }
        """.trimIndent()

        assertEquals(
            listOf(
                IdlixCatalogItem(
                    id = "movie-id",
                    title = "The Furious",
                    slug = "the-furious-2026",
                    contentType = "movie",
                    posterPath = "/poster.jpg",
                    releaseDate = "2026-06-10",
                    quality = "WEB-DL"
                )
            ),
            IdlixApiParser.catalogItems(listJson)
        )
        assertEquals(
            listOf(
                IdlixCatalogItem(
                    id = "series-id",
                    title = "Fast & Furious Spy Racers",
                    slug = "fast-and-furious-spy-racers-2019",
                    contentType = "tv_series",
                    posterPath = "/series.jpg",
                    firstAirDate = "2019-12-26"
                )
            ),
            IdlixApiParser.catalogItems(searchJson)
        )
    }

    @Test
    fun `idlixSeasonEpisodes reads episode ids for load links`() {
        val json = """
            {
                "season": {
                    "seasonNumber": 1,
                    "episodes": [
                        {
                            "id": "episode-id",
                            "episodeNumber": 2,
                            "name": "Connecticut House",
                            "overview": "Lisa's new career gets off to a dubious start.",
                            "stillPath": "/still.jpg",
                            "airDate": "2017-07-14"
                        }
                    ]
                }
            }
        """.trimIndent()

        assertEquals(
            listOf(
                IdlixEpisodeItem(
                    id = "episode-id",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    name = "Connecticut House",
                    overview = "Lisa's new career gets off to a dubious start.",
                    stillPath = "/still.jpg",
                    airDate = "2017-07-14"
                )
            ),
            IdlixApiParser.seasonEpisodes(json)
        )
    }

    @Test
    fun `directMediaType reads media extension from uri path`() {
        assertEquals(
            ExtractorLinkType.M3U8,
            directMediaType("https://cdn.example/video/MASTER.M3U8?token=abc")
        )
        assertEquals(
            ExtractorLinkType.VIDEO,
            directMediaType("https://cdn.example/video/movie.mp4?download=1")
        )
        assertNull(directMediaType("https://player.example/embed/123"))
    }

    @Test
    fun `asiaStreamMasterUrl parses live sniff configuration`() {
        val html = """
            <script>
                sniff("K8OFQSVM","7","51b7dae1031b20174cacc7e69d6e4bf0",null,
                    [{"label":"","file":"/thumbnails.vtt","kind":"thumbnails"}],1,1,false);
            </script>
        """.trimIndent()

        assertEquals(
            "https://watch.asiastream.cc/m3u8/7/51b7dae1031b20174cacc7e69d6e4bf0/master.txt?s=1&cache=1",
            InlineDataParser.asiaStreamMasterUrl(html, "https://watch.asiastream.cc/watch?v=K8OFQSVM")
        )
    }

    @Test
    fun `asiaStreamMasterUrl rejects malformed and cross scheme player urls`() {
        val html = """sniff("slug","7","hash",null,[],1,1,false);"""

        assertNull(InlineDataParser.asiaStreamMasterUrl("no player config", "https://watch.asiastream.cc/watch?v=x"))
        assertNull(InlineDataParser.asiaStreamMasterUrl(html, "javascript:alert(1)"))
    }

    @Test
    fun `asiaStreamMasterUrl accepts uppercase HTTPS scheme`() {
        val html = """sniff("slug","7","hash",null,[],1,1,false);"""

        assertEquals(
            "https://watch.asiastream.cc/m3u8/7/hash/master.txt?s=1&cache=1",
            InlineDataParser.asiaStreamMasterUrl(html, "HTTPS://watch.asiastream.cc/watch?v=x")
        )
    }

    @Test
    fun `toPlayableUrl accepts uppercase HTTP schemes`() {
        val api = RebahinProvider()

        assertEquals("https://video.example/embed", api.toPlayableUrl("HTTPS://video.example/embed"))
        assertEquals("https://video.example/embed", api.toPlayableUrl("HTTP://video.example/embed"))
    }

    @Test
    fun `toPlayableUrl rejects explicit non HTTP schemes`() {
        val api = RebahinProvider()

        assertNull(api.toPlayableUrl("FTP://video.example/movie.mp4"))
        assertNull(api.toPlayableUrl("data:text/html,<iframe></iframe>"))
    }

    @Test
    fun `isNonContentPage recognizes upstream interstitials and errors`() {
        assertTrue(ProviderHtmlParser.isNonContentPage("<title>Internet Positif</title>"))
        assertTrue(ProviderHtmlParser.isNonContentPage("<title>Just a moment...</title><script src='https://challenges.cloudflare.com/x'></script>"))
        assertTrue(ProviderHtmlParser.isNonContentPage("SQLSTATE[HY000] [2006] MySQL server has gone away"))
        assertTrue(ProviderHtmlParser.isNonContentPage("   "))
        assertFalse(ProviderHtmlParser.isNonContentPage("<iframe src='https://video.example/embed'></iframe>"))
    }

    @Test
    fun `resolution session expands PlaySobat payload through extractors`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val extractorRequests = mutableListOf<Pair<String, String?>>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ ->
                """
                    <script>
                        window.payload = "{\"iv\":\"ABEiM0RVZneImaq7zN3u/w==\",\"data\":\"jcnQBUKMrJE5BkzD119j3/yizUSXLAM0pS062Yj0wREvec9ySwfrXuSq/IOVVCW6WvsAa7UwxT1hs+oWmuIpcd8GJ1sXubg1CEOd4Yovu7NoyuHwc3ZgZsX48VhsbWie\"}";
                    </script>
                """.trimIndent()
            },
            extractorLoader = { url, referer, _, callback ->
                extractorRequests += url to referer
                if (url.startsWith("https://abysscdn.com/")) {
                    callback(
                        directExtractorLink(
                            "test",
                            "test",
                            "https://cdn.example/master.m3u8",
                            referer.orEmpty(),
                            0,
                            ExtractorLinkType.M3U8,
                            emptyMap()
                        )
                    )
                    true
                } else {
                    false
                }
            }
        )

        assertTrue(session.resolve("https://playsobat.xyz/embed/1", "https://provider.example/item"))
        assertEquals(
            listOf<Pair<String, String?>>(
                "https://abysscdn.com/?v=UDPNmR2acq" to "https://playsobat.xyz/embed/1",
                "https://filemoon.sx/e/v2dt7jq5kxpr" to "https://playsobat.xyz/embed/1"
            ),
            extractorRequests
        )
        assertEquals("https://cdn.example/master.m3u8", links.single().url)
    }

    @Test
    fun `PlaySobat without emitted nested links falls through extractor and cached generic fallback`() = runBlocking {
        val playerUrl = "https://playsobat.xyz/embed/1"
        val links = mutableListOf<ExtractorLink>()
        val extractorRequests = mutableListOf<String>()
        var playerFetches = 0
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                if (url == playerUrl) {
                    playerFetches++
                    """
                        <script>
                            window.payload = "{\"iv\":\"ABEiM0RVZneImaq7zN3u/w==\",\"data\":\"jcnQBUKMrJE5BkzD119j3/yizUSXLAM0pS062Yj0wREvec9ySwfrXuSq/IOVVCW6WvsAa7UwxT1hs+oWmuIpcd8GJ1sXubg1CEOd4Yovu7NoyuHwc3ZgZsX48VhsbWie\"}";
                        </script>
                        <iframe src="https://cdn.example/fallback.m3u8"></iframe>
                    """.trimIndent()
                } else {
                    "<title>Internet Positif</title>"
                }
            },
            extractorLoader = { url, _, _, _ ->
                extractorRequests += url
                false
            },
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            }
        )

        assertTrue(session.resolve(playerUrl, "https://provider.example/item"))
        assertTrue(playerUrl in extractorRequests)
        assertEquals(1, playerFetches)
        assertEquals("https://cdn.example/fallback.m3u8", links.single().url)
    }

    @Test
    fun `resolution session follows one generic iframe through PlaySobat adapter`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val extractorRequests = mutableListOf<Pair<String, String?>>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                if (url == "https://player.example/embed/1") {
                    "<iframe src='https://playsobat.xyz/embed/1'></iframe>"
                } else {
                    """
                        <script>
                            window.payload = "{\"iv\":\"ABEiM0RVZneImaq7zN3u/w==\",\"data\":\"jcnQBUKMrJE5BkzD119j3/yizUSXLAM0pS062Yj0wREvec9ySwfrXuSq/IOVVCW6WvsAa7UwxT1hs+oWmuIpcd8GJ1sXubg1CEOd4Yovu7NoyuHwc3ZgZsX48VhsbWie\"}";
                        </script>
                    """.trimIndent()
                }
            },
            extractorLoader = { url, referer, _, callback ->
                extractorRequests += url to referer
                if (url.startsWith("https://abysscdn.com/")) {
                    callback(
                        directExtractorLink(
                            "test",
                            "test",
                            "https://cdn.example/master.m3u8",
                            referer.orEmpty(),
                            0,
                            ExtractorLinkType.M3U8,
                            emptyMap()
                        )
                    )
                    true
                } else {
                    false
                }
            }
        )

        assertTrue(session.resolve("https://player.example/embed/1", "https://provider.example/item"))
        assertEquals(
            listOf<Pair<String, String?>>(
                "https://player.example/embed/1" to "https://provider.example/item",
                "https://abysscdn.com/?v=UDPNmR2acq" to "https://playsobat.xyz/embed/1",
                "https://filemoon.sx/e/v2dt7jq5kxpr" to "https://playsobat.xyz/embed/1"
            ),
            extractorRequests
        )
        assertEquals("https://cdn.example/master.m3u8", links.single().url)
    }

    @Test
    fun `resolution session converts AsiaStream watch page to hls`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ ->
                """<script>sniff("slug","7","51b7dae1031b20174cacc7e69d6e4bf0",null,[],1,1,false);</script>"""
            },
            extractorLoader = { _, _, _, _ -> false },
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            }
        )

        assertTrue(session.resolve("https://watch.asiastream.cc/watch?v=K8OFQSVM", "https://provider.example/item"))
        assertEquals(
            "https://watch.asiastream.cc/m3u8/7/51b7dae1031b20174cacc7e69d6e4bf0/master.txt?s=1&cache=1",
            links.single().url
        )
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
        assertEquals("https://watch.asiastream.cc/watch?v=K8OFQSVM", links.single().referer)
    }

    @Test
    fun `malformed AsiaStream sniff falls through extractor and cached generic fallback`() = runBlocking {
        val playerUrl = "https://watch.asiastream.cc/watch?v=K8OFQSVM"
        val links = mutableListOf<ExtractorLink>()
        val extractorRequests = mutableListOf<String>()
        var playerFetches = 0
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                assertEquals(playerUrl, url)
                playerFetches++
                """
                    <script>sniff("slug","invalid uid!","hash",null,[],1,1,false);</script>
                    <iframe src="https://cdn.example/fallback.m3u8"></iframe>
                """.trimIndent()
            },
            extractorLoader = { url, _, _, _ ->
                extractorRequests += url
                false
            },
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            }
        )

        assertTrue(session.resolve(playerUrl, "https://provider.example/item"))
        assertEquals(listOf(playerUrl), extractorRequests)
        assertEquals(1, playerFetches)
        assertEquals("https://cdn.example/fallback.m3u8", links.single().url)
    }

    @Test
    fun `extractor true without callback does not report success`() = runBlocking {
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = {},
            pageFetcher = { _, _ -> "<title>Internet Positif</title>" },
            extractorLoader = { _, _, _, _ -> true }
        )

        assertFalse(session.resolve("https://player.example/embed/1", null))
    }

    @Test
    fun `extractor false with callback reports success`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ -> error("emission should stop generic fallback") },
            extractorLoader = { _, _, _, callback ->
                callback(
                    directExtractorLink(
                        "test",
                        "test",
                        "https://cdn.example/master.m3u8",
                        "",
                        0,
                        ExtractorLinkType.M3U8,
                        emptyMap()
                    )
                )
                false
            }
        )

        assertTrue(session.resolve("https://player.example/embed/1", null))
        assertEquals("https://cdn.example/master.m3u8", links.single().url)
    }

    @Test
    fun `resolution session follows one relative nested iframe`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ -> "<iframe src='/media/master.m3u8?token=abc'></iframe>" },
            extractorLoader = { _, _, _, _ -> false },
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            }
        )

        assertTrue(session.resolve("https://player.example/embed/1", "https://provider.example/item"))
        assertEquals("https://player.example/media/master.m3u8?token=abc", links.single().url)
    }

    @Test
    fun `resolution session bounds iframe cycles`() = runBlocking {
        var fetches = 0
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = {},
            pageFetcher = { url, _ ->
                fetches++
                if (url.endsWith("/a")) "<iframe src='/b'></iframe>" else "<iframe src='/a'></iframe>"
            },
            extractorLoader = { _, _, _, _ -> false },
            maxDepth = 1
        )

        assertFalse(session.resolve("https://player.example/a", null))
        assertEquals(1, fetches)
    }

    @Test
    fun `resolution session emits direct hls only once`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            },
            pageFetcher = { _, _ -> error("direct media must not fetch a player page") },
            extractorLoader = { _, _, _, _ -> false }
        )

        assertTrue(session.resolve("https://cdn.example/master.m3u8?token=abc", "https://provider.example/item"))
        assertFalse(session.resolve("https://cdn.example/master.m3u8?token=abc", "https://provider.example/item"))
        assertTrue(session.loaded)
        assertEquals(1, links.size)
        assertEquals(ExtractorLinkType.M3U8, links.single().type)
        assertEquals("https://provider.example/item", links.single().referer)
    }

    @Test
    fun `failed candidate does not prevent a later direct candidate`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            },
            pageFetcher = { _, _ -> "<title>Internet Positif</title>" },
            extractorLoader = { _, _, _, _ -> false }
        )

        assertFalse(session.resolve("https://unsupported.example/embed", "https://provider.example/item"))
        assertTrue(session.resolve("https://cdn.example/movie.mp4", "https://provider.example/item"))
        assertEquals(listOf("https://cdn.example/movie.mp4"), links.map { it.url })
    }

    @Test
    fun `resolution session rethrows cancellation`() {
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = {},
            pageFetcher = { _, _ -> "" },
            extractorLoader = { _, _, _, _ -> throw CancellationException("cancelled") }
        )

        assertFailsWith<CancellationException> {
            runBlocking { session.resolve("https://unsupported.example/embed", null) }
        }
    }
}
