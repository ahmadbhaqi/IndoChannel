package com.example

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jsoup.Jsoup

class ProviderHtmlParserTest {
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
    fun `isNonContentPage recognizes upstream interstitials and errors`() {
        assertTrue(ProviderHtmlParser.isNonContentPage("<title>Internet Positif</title>"))
        assertTrue(ProviderHtmlParser.isNonContentPage("<title>Just a moment...</title><script src='https://challenges.cloudflare.com/x'></script>"))
        assertTrue(ProviderHtmlParser.isNonContentPage("SQLSTATE[HY000] [2006] MySQL server has gone away"))
        assertTrue(ProviderHtmlParser.isNonContentPage("   "))
        assertFalse(ProviderHtmlParser.isNonContentPage("<iframe src='https://video.example/embed'></iframe>"))
    }
}
