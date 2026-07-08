package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
}
