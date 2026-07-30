package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.newSubtitleFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
            "https://oploverz.org/dr-stone-season-4/",
            ProviderHtmlParser.absoluteUrl("/dr-stone-season-4/", "https://oploverz.org")
        )
        assertEquals(
            "https://player.example/embed/media.mp4",
            ProviderHtmlParser.absoluteUrl(
                "media.mp4",
                "https://player.example/embed/current"
            )
        )
    }

    @Test
    fun `downloadCandidateUrls accepts numbered labels and provider download lists`() {
        val document = Jsoup.parse(
            """
            <main>
              <a href="https://gofile.io/d/current"> Link <span>Download</span> 1 </a>
              <a href="/download/current" title="Link Download 2"><span class="icon"></span></a>
              <div class="gmr-download-list">
                <a href="//pixeldrain.com/u/current-file">Primary mirror</a>
              </div>
              <a href="https://gofile.io/d/current" title="Link Download 3">Duplicate mirror</a>
            </main>
            """.trimIndent(),
            "https://provider.example/movie/current/"
        )

        assertEquals(
            listOf(
                "https://gofile.io/d/current",
                "https://provider.example/download/current",
                "https://pixeldrain.com/u/current-file"
            ),
            ProviderHtmlParser.downloadCandidateUrls(
                document,
                "https://provider.example/movie/current/"
            )
        )
    }

    @Test
    fun `downloadCandidateUrls rejects arbitrary remote hosts and caps the result`() {
        val links = (1..12).joinToString("") { index ->
            """<a href="https://gofile.io/d/$index" title="Link Download $index">Mirror</a>"""
        }
        val document = Jsoup.parse(
            """
            <main>
              <a href="https://untrusted.example/file" title="Link Download 99">Untrusted</a>
              $links
            </main>
            """.trimIndent(),
            "https://provider.example/movie/current/"
        )

        assertEquals(
            (1..8).map { "https://gofile.io/d/$it" },
            ProviderHtmlParser.downloadCandidateUrls(
                document,
                "https://provider.example/movie/current/"
            )
        )
    }

    @Test
    fun `downloadCandidateUrls rejects unrelated hidden taxonomy trailer and share links`() {
        val document = Jsoup.parse(
            """
            <main>
              <a href="https://files.example/current">Download Film Current</a>
              <a href="javascript:alert(1)" title="Link Download 1">Script</a>
              <a href="http://127.0.0.1/private" title="Link Download 2">Private host</a>
              <a href="https://provider.example/tag/download-film-current/" title="Link Download 3">Tag</a>
              <a href="https://www.youtube.com/watch?v=trailer" title="Link Download 4">Trailer</a>
              <a href="https://api.whatsapp.com/send?text=movie" title="Link Download 5">Share</a>
              <a hidden href="https://files.example/hidden" title="Link Download 6">Hidden</a>
              <div class="download-links">
                <a href="https://t.me/share/url?url=movie">Telegram</a>
                <a href="https://provider.example/genre/action/">Action</a>
              </div>
            </main>
            """.trimIndent(),
            "https://provider.example/movie/current/"
        )

        assertEquals(
            emptyList(),
            ProviderHtmlParser.downloadCandidateUrls(
                document,
                "https://provider.example/movie/current/"
            )
        )
    }

    @Test
    fun `normalizeProviderPageUrl rewrites only provider-owned legacy hosts`() {
        val current = "https://current.example"
        val legacy = setOf("old.example", "www.older.example")

        assertEquals(
            "https://current.example/movie/current?server=2#play",
            ProviderHtmlParser.normalizeProviderPageUrl(
                "https://old.example/movie/current?server=2#play",
                current,
                legacy
            )
        )
        assertEquals(
            "https://current.example/movie/relative",
            ProviderHtmlParser.normalizeProviderPageUrl("/movie/relative", current, legacy)
        )
        assertNull(
            ProviderHtmlParser.normalizeProviderPageUrl(
                "https://unrelated.example/movie/current",
                current,
                legacy
            )
        )
        assertNull(ProviderHtmlParser.normalizeProviderPageUrl("javascript:alert(1)", current, legacy))
    }

    @Test
    fun `preserveProviderPageUrl keeps an owned redirect host without allowing lookalikes`() {
        val current = "https://current.example"
        val owned = setOf("old.example", "older.example")

        assertEquals(
            "https://old.example/movie/current?server=2",
            ProviderHtmlParser.preserveProviderPageUrl(
                "https://old.example/movie/current?server=2",
                current,
                owned
            )
        )
        assertEquals(
            "https://current.example/movie/relative",
            ProviderHtmlParser.preserveProviderPageUrl("/movie/relative", current, owned)
        )
        assertEquals(
            "https://current.example/assets/next.js",
            ProviderHtmlParser.preserveProviderPageUrl(
                "next.js",
                "https://current.example/assets/config.js",
                owned
            )
        )
        assertNull(
            ProviderHtmlParser.preserveProviderPageUrl(
                "https://old.example.attacker.example/movie/current",
                current,
                owned
            )
        )
        assertNull(
            ProviderHtmlParser.preserveProviderPageUrl(
                "http://old.example/movie/current",
                current,
                owned
            )
        )
        assertNull(
            ProviderHtmlParser.preserveProviderPageUrl(
                "https://old.example:8443/movie/current",
                current,
                owned
            )
        )
    }

    @Test
    fun `cached movie URLs survive known provider domain rotations`() {
        val cases = listOf(
            Triple("https://comblank.com/movie/", "https://filmbioskop21.lk21.in.net", setOf("comblank.com")),
            Triple("https://indofilm.fit/movie/", "https://indofilm.pics", setOf("indofilm.fit", "yuhhaber.com")),
            Triple("https://yuhhaber.com/movie/", "https://indofilm.pics", setOf("indofilm.fit", "yuhhaber.com")),
            Triple("https://parachutedrone.com/movie/", "https://tv.nontonfilm.red", setOf("parachutedrone.com", "tv10.lk21official.cc")),
            Triple("https://tv10.lk21official.cc/movie/", "https://tv.nontonfilm.red", setOf("parachutedrone.com", "tv10.lk21official.cc"))
        )

        cases.forEach { (cachedUrl, currentBase, legacyHosts) ->
            assertEquals(
                "$currentBase/movie/",
                ProviderHtmlParser.normalizeProviderPageUrl(cachedUrl, currentBase, legacyHosts)
            )
        }
    }

    @Test
    fun `firstTitledLink skips an empty poster bookmark before the visible title`() {
        val article = Jsoup.parse(
            """
            <article>
              <a rel="bookmark" href="https://provider.example/movie"><img src="poster.jpg"></a>
              <h2 class="entry-title">
                <a rel="bookmark" href="https://provider.example/movie">Visible Movie (2026)</a>
              </h2>
            </article>
            """.trimIndent()
        ).selectFirst("article")

        val link = ProviderHtmlParser.firstTitledLink(article)
        assertEquals("Visible Movie (2026)", link?.text())
        assertEquals("https://provider.example/movie", link?.attr("href"))
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
    fun `mediaSources includes Vidcuy direct video element`() {
        val document = Jsoup.parse(
            """
            <video controls>
                <source src="https://r2.cdnvidcuy.xyz/current/movie.mp4" type="video/mp4">
            </video>
            """.trimIndent(),
            "https://crotvids.xyz/e/current"
        )

        assertEquals(
            listOf("https://r2.cdnvidcuy.xyz/current/movie.mp4"),
            ProviderHtmlParser.mediaSources(document)
        )
    }

    @Test
    fun `mediaSources decodes bounded Base64 data iframe buttons`() {
        val playerUrl = "https://abyssplayer.com/current"
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(playerUrl.toByteArray(Charsets.UTF_8))
        val document = Jsoup.parse(
            """
            <button data-iframe="$encoded"></button>
            <button data-iframe="not base64!"></button>
            """.trimIndent()
        )

        assertEquals(listOf(playerUrl), ProviderHtmlParser.mediaSources(document))
    }

    @Test
    fun `media sniffer rejects HTML errors and recognizes actual manifests and MP4`() {
        assertNull(
            sniffMediaType(
                "<html><body>silence is golden !</body></html>".toByteArray(),
                "video/mp4"
            )
        )
        assertEquals(
            ExtractorLinkType.M3U8,
            sniffMediaType(
                "#EXTM3U\n#EXT-X-VERSION:3\nsegment-001.ts\n".toByteArray(),
                "text/plain"
            )
        )
        val mp4 = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x79, 0x70,
            0x69, 0x73, 0x6f, 0x6d,
            0x00, 0x00, 0x00, 0x00,
            0x69, 0x73, 0x6f, 0x6d,
            0x6d, 0x70, 0x34, 0x32,
            0x00, 0x00, 0x00, 0x00,
            0x6d, 0x64, 0x61, 0x74
        )
        assertEquals(
            ExtractorLinkType.VIDEO,
            sniffMediaType(mp4, "application/octet-stream")
        )
        assertNull(
            sniffMediaType(
                byteArrayOf(
                    0x99.toByte(), 0x61, 0xe8.toByte(), 0x32,
                    0x91.toByte(), 0x05, 0xaf.toByte(), 0xc4.toByte()
                ),
                "video/mp4"
            ),
            "An asserted video Content-Type must not make encrypted/random bytes playable"
        )
        assertNull(
            sniffMediaType("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()),
            "An HLS header without a media or variant URI is not playable"
        )
        listOf(
            "#EXTM3U\nAccess denied\n",
            "#EXTM3U\n#EXT-X-VERSION:3\nAccess denied\n",
            "#EXTM3U\n#EXT-X-VERSION:3\n<html>\n"
        ).forEach { fakeManifest ->
            assertNull(
                sniffMediaType(fakeManifest.toByteArray(), "application/vnd.apple.mpegurl"),
                "An error page prefixed with EXTM3U must not reach the player"
            )
        }
        assertEquals(
            ExtractorLinkType.M3U8,
            sniffMediaType(
                """
                #EXTM3U
                #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=64000,URI="iframe/playlist.m3u8"
                """.trimIndent().toByteArray()
            )
        )
        assertNull(
            sniffMediaType(mp4.copyOfRange(0, 12), "video/mp4"),
            "A truncated ftyp header is not a playable MP4"
        )
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
    fun `kuronimeMirrorUrls decrypts legacy api mirror payload`() {
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
    fun `kuronimeMirrorUrls decrypts current api source payload`() {
        val encrypted = "eyJjdCI6ImhkRDhKUmRyRlQ1Z1QzZTBINHRKL0hkV0RQb3ZWSTFYdGlYZyszTnJ2V09Ua1V0eXkwZk1MYWxJZ0NDMDZoTHh2RExKZkxicC9UT2FROXdZMGp1U1ZRPT0iLCJpdiI6ImNmYTEzMWNlNWQ2YmIyZmViM2RlMDI1MzJhZTY1MjlkIiwicyI6Ijg4OTlhYWJiY2NkZGVlZmYifQ=="

        assertEquals(
            listOf("https://edge.kuroplayer.xyz/hls/1080p/index.m3u8"),
            InlineDataParser.kuronimeMirrorUrls(encrypted)
        )
    }

    @Test
    fun `directMediaType reads media extension from uri path`() {
        assertEquals(
            ExtractorLinkType.M3U8,
            directMediaType("https://cdn.example/video/MASTER.M3U8?token=abc")
        )
        assertEquals(
            ExtractorLinkType.M3U8,
            directMediaType("https://cdn.example/video/master.txt?token=abc")
        )
        assertNull(directMediaType("https://cdn.example/video/release-notes.txt"))
        assertEquals(
            ExtractorLinkType.VIDEO,
            directMediaType("https://cdn.example/video/movie.mp4?download=1")
        )
        assertNull(directMediaType("https://player.example/embed/123"))
        assertNull(
            directMediaType("https://strcloud.in/e/DGpWQAxqqqtAB7/Love.You.So.Bad.2025.mp4")
        )
        assertEquals(
            ExtractorLinkType.VIDEO,
            directMediaType("https://cdn.example/media/embed/movie.mp4")
        )
    }

    @Test
    fun `streamtape parser decodes current robot link assignment`() {
        val html = """
            <div id="robotlink"></div>
            <script>
                document.getElementById('robotlink').innerHTML = '//strcloud.in/get_video'+ ('xcd?id=DGpWQAxqqqtAB7&expires=1784264233&token=abc').substring(2).substring(1);
            </script>
        """.trimIndent()

        assertEquals(
            "https://strcloud.in/get_video?id=DGpWQAxqqqtAB7&expires=1784264233&token=abc",
            StreamTapePlayerParser.directUrl(
                html,
                "https://strcloud.in/e/DGpWQAxqqqtAB7/movie.mp4"
            )
        )
    }

    @Test
    fun `streamtape parser evaluates fragments and substring in order`() {
        val html = """
            <div id="ideoolink"></div>
            <script>
                document.getElementById("ideoolink").innerHTML = 'not;a-video';
                document.getElementById("ideoolink").innerHTML =
                    '//strcloud.in/' + 'get_' + 'video' + ('xx?id=current').substring(2) + '&token=abc';
            </script>
        """.trimIndent()

        assertEquals(
            "https://strcloud.in/get_video?id=current&token=abc",
            StreamTapePlayerParser.directUrl(html, "https://strcloud.in/e/current/movie.mp4")
        )
    }

    @Test
    fun `streamtape parser decodes current Strcloud bot link assignment`() {
        val html = """
            <div id="botlink"></div>
            <script>
                document.getElementById('botlink').innerHTML =
                    '//strcloud.i' + ('xyzan/get_video?id=KPyZMwqMqqi0G9g&expires=1784264233&ip=192.0.2.1&token=abc').substring(4);
            </script>
        """.trimIndent()

        assertEquals(
            "https://strcloud.in/get_video?id=KPyZMwqMqqi0G9g&expires=1784264233&ip=192.0.2.1&token=abc",
            StreamTapePlayerParser.directUrl(
                html,
                "https://strcloud.in/e/KPyZMwqMqqi0G9g"
            )
        )
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
    fun `toPlayableUrl rejects local and private network targets`() {
        val api = RebahinProvider()

        assertNull(api.toPlayableUrl("http://127.0.0.1/video.mp4"))
        assertNull(api.toPlayableUrl("http://10.0.0.8/video.mp4"))
        assertNull(api.toPlayableUrl("http://169.254.169.254/latest/meta-data"))
        assertNull(api.toPlayableUrl("http://[::1]/video.mp4"))
        assertNull(api.toPlayableUrl("http://[0:0:0:0:0:0:0:1]/video.mp4"))
        assertNull(api.toPlayableUrl("http://[::ffff:7f00:1]/video.mp4"))
        assertNull(api.toPlayableUrl("http://[ff02::1]/video.mp4"))
        assertNull(api.toPlayableUrl("http://[fc00::1]/video.mp4"))
        assertNull(api.toPlayableUrl("http://[fd12:3456::1]/video.mp4"))
        assertNull(api.toPlayableUrl("http://127.1/video.mp4"))
        assertNull(api.toPlayableUrl("http://2130706433/video.mp4"))
        assertEquals(
            "https://154.203.167.63/video.mp4",
            api.toPlayableUrl("https://154.203.167.63/video.mp4")
        )
    }

    @Test
    fun `isNonContentPage recognizes upstream interstitials and errors`() {
        assertTrue(ProviderHtmlParser.isNonContentPage("<title>Internet Positif</title>"))
        assertTrue(ProviderHtmlParser.isNonContentPage("<title>Just a moment...</title><script src='https://challenges.cloudflare.com/x'></script>"))
        assertTrue(ProviderHtmlParser.isNonContentPage("SQLSTATE[HY000] [2006] MySQL server has gone away"))
        assertTrue(ProviderHtmlParser.isNonContentPage("<title>File Error</title><h3>Please try again later</h3>"))
        assertTrue(
            ProviderHtmlParser.isNonContentPage(
                "<title>Redirecting...</title><script>fetch('https://router.parklogic.com/file/id')</script>"
            )
        )
        assertTrue(ProviderHtmlParser.isNonContentPage("   "))
        assertFalse(ProviderHtmlParser.isNonContentPage("<iframe src='https://video.example/embed'></iframe>"))
    }

    @Test
    fun `resolution session routes a PlaySobat mirror through extractors`() = runBlocking {
        val nestedPlayer = "https://generic-player.example/embed/UDPNmR2acq"
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
                if (url == nestedPlayer) {
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
            },
            playSobatUrlParser = { listOf(nestedPlayer) }
        )

        assertTrue(session.resolve("https://playsobat.xyz/embed/1", "https://provider.example/item"))
        assertEquals(
            listOf<Pair<String, String?>>(
                nestedPlayer to "https://playsobat.xyz/embed/1"
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
        val nestedPlayer = "https://generic-player.example/embed/UDPNmR2acq"
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
                if (url == nestedPlayer) {
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
            },
            playSobatUrlParser = { listOf(nestedPlayer) }
        )

        assertTrue(session.resolve("https://player.example/embed/1", "https://provider.example/item"))
        assertEquals(
            listOf<Pair<String, String?>>(
                "https://player.example/embed/1" to "https://provider.example/item",
                nestedPlayer to "https://playsobat.xyz/embed/1"
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
    fun `extractor callback survives a later extractor exception`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ -> error("verified extractor callback must stop HTML fallback") },
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
                error("later extractor request failed")
            }
        )

        assertTrue(session.resolve("https://player.example/embed/1", null))
        assertEquals("https://cdn.example/master.m3u8", links.single().url)
    }

    @Test
    fun `hanging generic extractor cannot hide healthy inline media`() = runBlocking {
        val playerUrl = "https://player.example/embed/current"
        val mediaUrl = "https://cdn.example/movie.mp4"
        var extractorCancelled = false
        var playerFetches = 0
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                assertEquals(playerUrl, url)
                playerFetches++
                """<source src="$mediaUrl">"""
            },
            extractorLoader = { _, _, _, _ ->
                try {
                    delay(1_000)
                    false
                } finally {
                    extractorCancelled = true
                }
            },
            candidateTimeoutMs = 250,
            genericExtractorTimeoutMs = 25
        )

        assertTrue(session.resolve(playerUrl, "https://provider.example/item"))
        assertTrue(extractorCancelled)
        assertEquals(1, playerFetches)
        assertEquals(mediaUrl, links.single().url)
    }

    @Test
    fun `inline source parser reuses the resolver player fetch`() = runBlocking {
        val playerUrl = "https://player.example/embed/1"
        val mediaUrl = "https://cdn.example/movie.mp4"
        var playerFetches = 0
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = IndoxxiProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                assertEquals(playerUrl, url)
                playerFetches++
                """<script>window.player = {file: "$mediaUrl"};</script>"""
            },
            extractorLoader = { _, _, _, _ -> false },
            inlineSourceParser = IndoxxiPlayerParser::mediaUrls
        )

        assertTrue(session.resolve(playerUrl, "https://provider.example/item"))
        assertEquals(1, playerFetches)
        assertEquals(mediaUrl, links.single().url)
        assertEquals("https://player.example", links.single().headers["Origin"])
        assertEquals(playerUrl, links.single().headers["Referer"])
    }

    @Test
    fun `provider declared direct media carries response origin and referer`() = runBlocking {
        val referer = "https://provider.example/player/server-one/"
        val mediaUrl = "https://cdn.example/current/master.m3u8"
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = DutamovieProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ -> error("direct media must not be fetched as HTML") },
            extractorLoader = { _, _, _, _ -> error("direct media must not use a generic extractor") },
            mediaLinkProbe = { it }
        )

        assertTrue(session.resolveInline(mediaUrl, referer))
        assertEquals(mediaUrl, links.single().url)
        assertEquals(referer, links.single().headers["Referer"])
        assertEquals("https://provider.example", links.single().headers["Origin"])
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
    fun `resolution session follows a two wrapper chain to media`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val fetched = mutableListOf<String>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                fetched += url
                when (url) {
                    "https://player.example/first" -> "<iframe src='/second'></iframe>"
                    "https://player.example/second" -> "<source src='https://cdn.example/movie.mp4'>"
                    else -> error("unexpected fetch: $url")
                }
            },
            extractorLoader = { _, _, _, _ -> false },
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            }
        )

        assertTrue(session.resolve("https://player.example/first", "https://provider.example/item"))
        assertEquals(
            listOf("https://player.example/first", "https://player.example/second"),
            fetched
        )
        assertEquals("https://cdn.example/movie.mp4", links.single().url)
    }

    @Test
    fun `same candidate can retry with a different referer`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val playerUrl = "https://player.example/embed/current"
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, referer ->
                if (referer == "https://provider.example/good") {
                    "<source src='https://cdn.example/movie.mp4'>"
                } else {
                    "<title>Internet Positif</title>"
                }
            },
            extractorLoader = { _, _, _, _ -> false },
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            }
        )

        assertFalse(session.resolve(playerUrl, "https://provider.example/bad"))
        assertTrue(session.resolve(playerUrl, "https://provider.example/good"))
        assertEquals("https://cdn.example/movie.mp4", links.single().url)
    }

    @Test
    fun `same media url can be emitted with distinct referers`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ -> error("direct media must not fetch a page") },
            extractorLoader = { _, _, _, _ -> false },
            directLinkFactory = { source, name, url, referer, quality, type, headers ->
                directExtractorLink(source, name, url, referer, quality, type, headers)
            }
        )

        assertTrue(session.resolve("https://cdn.example/movie.mp4", "https://provider.example/one"))
        assertTrue(session.resolve("https://cdn.example/movie.mp4", "https://provider.example/two"))
        assertEquals(
            listOf("https://provider.example/one", "https://provider.example/two"),
            links.map { it.referer }
        )
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

    @Test
    fun `failed direct suffix probe falls through an HTML wrapper`() = runBlocking {
        val wrapper = "https://wrapper.example/current.mp4"
        val media = "https://cdn.example/current.mp4"
        val fetched = mutableListOf<String>()
        val probes = mutableListOf<String>()
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                fetched += url
                """<iframe src="$media"></iframe>"""
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { link ->
                probes += link.url
                link.takeIf { it.url == media }
            }
        )

        assertTrue(session.resolveInline(wrapper, "https://provider.example/item"))
        assertEquals(listOf(wrapper), fetched)
        assertEquals(listOf(wrapper, media), probes)
        assertEquals(media, links.single().url)
    }

    @Test
    fun `resolution session bounds a stalled candidate`() = runBlocking {
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = {},
            pageFetcher = { _, _ ->
                delay(1_000)
                ""
            },
            extractorLoader = { _, _, _, _ -> false },
            candidateTimeoutMs = 25
        )

        assertFalse(session.resolve("https://player.example/embed/stalled", null))
    }

    @Test
    fun `bounded mirror scheduler returns first verified link and cancels stalled sibling`() = runBlocking {
        val slowPlayer = "https://slow-player.example/embed/current"
        val healthyPlayer = "https://healthy-player.example/embed/current"
        val media = "https://cdn.example/current.mp4"
        val referer = "https://provider.example/item"
        val started = mutableSetOf<String>()
        var slowCancelled = false
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                started += url
                if (url == slowPlayer) {
                    try {
                        delay(5_000)
                    } catch (error: CancellationException) {
                        slowCancelled = true
                        throw error
                    }
                    ""
                } else {
                    """<source src="$media">"""
                }
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { link -> link.takeIf { it.url == media } },
            directLinkFactory = { source, name, url, linkReferer, quality, type, headers ->
                directExtractorLink(source, name, url, linkReferer, quality, type, headers)
            },
            candidateTimeoutMs = 5_000,
            sessionTimeoutMs = 6_000
        )

        val loaded = withTimeout(1_000) {
            session.resolveFirstVerified(
                candidates = listOf(
                    PlayerResolutionCandidate(slowPlayer, referer),
                    PlayerResolutionCandidate(healthyPlayer, referer)
                ),
                maxConcurrency = 2
            )
        }

        assertTrue(loaded)
        assertEquals(setOf(slowPlayer, healthyPlayer), started)
        assertTrue(slowCancelled, "winning mirror must cancel its stalled sibling")
        assertEquals(listOf(media), links.map { it.url })
    }

    @Test
    fun `mirror scheduler returns after the first verified source inside a multi source winner`() = runBlocking {
        val winnerPlayer = "https://winner-player.example/embed/current"
        val stalledPlayer = "https://stalled-player.example/embed/current"
        val fastMedia = "https://cdn.example/fast.mp4"
        val slowMedia = "https://cdn.example/slow.mp4"
        val referer = "https://provider.example/item"
        var slowProbeStarted = false
        var slowProbeCancelled = false
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                if (url == stalledPlayer) {
                    delay(5_000)
                    ""
                } else {
                    """
                        <video>
                          <source src="$fastMedia">
                          <source src="$slowMedia">
                        </video>
                    """.trimIndent()
                }
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { link ->
                if (link.url == slowMedia) {
                    slowProbeStarted = true
                    try {
                        delay(5_000)
                    } catch (error: CancellationException) {
                        slowProbeCancelled = true
                        throw error
                    }
                }
                link
            },
            directLinkFactory = { source, name, url, linkReferer, quality, type, headers ->
                directExtractorLink(source, name, url, linkReferer, quality, type, headers)
            },
            candidateTimeoutMs = 5_000,
            sessionTimeoutMs = 6_000
        )

        val loaded = withTimeout(1_000) {
            session.resolveFirstVerified(
                candidates = listOf(
                    PlayerResolutionCandidate(winnerPlayer, referer),
                    PlayerResolutionCandidate(stalledPlayer, referer)
                ),
                maxConcurrency = 2
            )
        }

        assertTrue(loaded)
        assertTrue(slowProbeStarted)
        assertTrue(slowProbeCancelled)
        assertEquals(listOf(fastMedia), links.map { it.url })
    }

    @Test
    fun `parallel mirrors serialize subtitle callbacks`() = runBlocking {
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val activeCallbacks = java.util.concurrent.atomic.AtomicInteger()
        val maxActiveCallbacks = java.util.concurrent.atomic.AtomicInteger()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {
                val active = activeCallbacks.incrementAndGet()
                maxActiveCallbacks.updateAndGet { current -> maxOf(current, active) }
                try {
                    Thread.sleep(50)
                } finally {
                    activeCallbacks.decrementAndGet()
                }
            },
            callback = {},
            pageFetcher = { _, _ -> "" },
            extractorLoader = { url, _, subtitle, _ ->
                withContext(Dispatchers.Default) {
                    barrier.await()
                    subtitle(
                        newSubtitleFile(
                            "Indonesia",
                            "https://subtitle.example/${url.substringAfterLast('/')}.vtt"
                        )
                    )
                }
                false
            },
            candidateTimeoutMs = 2_000,
            sessionTimeoutMs = 3_000
        )

        assertFalse(
            session.resolveFirstVerified(
                candidates = listOf(
                    PlayerResolutionCandidate("https://mirror-a.example/embed/a", null),
                    PlayerResolutionCandidate("https://mirror-b.example/embed/b", null)
                ),
                maxConcurrency = 2
            )
        )
        assertEquals(1, maxActiveCallbacks.get())
    }

    @Test
    fun `priority scheduler races siblings without entering a lower tier`() = runBlocking {
        val started = java.util.Collections.synchronizedList(mutableListOf<String>())
        var slowCancelled = false

        val resolved = withTimeout(1_000) {
            resolveByPriorityTiers(
                tiers = listOf(
                    listOf("slow", "healthy"),
                    listOf("lower-priority")
                ),
                maxConcurrency = 2
            ) { candidate ->
                started += candidate
                when (candidate) {
                    "slow" -> {
                        try {
                            delay(5_000)
                        } catch (error: CancellationException) {
                            slowCancelled = true
                            throw error
                        }
                        false
                    }
                    "healthy" -> {
                        delay(25)
                        true
                    }
                    else -> true
                }
            }
        }

        assertTrue(resolved)
        assertTrue("slow" in started)
        assertTrue("healthy" in started)
        assertFalse("lower-priority" in started)
        assertTrue(slowCancelled)
    }

    @Test
    fun `priority scheduler advances only after the current tier exhausts`() = runBlocking {
        val completedFirstTier = java.util.concurrent.atomic.AtomicInteger()
        var lowerTierObservedCompleted = -1

        val resolved = resolveByPriorityTiers(
            tiers = listOf(
                listOf("first-a", "first-b"),
                listOf("second")
            ),
            maxConcurrency = 2
        ) { candidate ->
            if (candidate.startsWith("first")) {
                delay(20)
                completedFirstTier.incrementAndGet()
                false
            } else {
                lowerTierObservedCompleted = completedFirstTier.get()
                true
            }
        }

        assertTrue(resolved)
        assertEquals(2, lowerTierObservedCompleted)
    }

    @Test
    fun `recursive media sources cannot target a local network address`() = runBlocking {
        val fetched = mutableListOf<String>()
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = RebahinProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                fetched += url
                "<source src='http://127.0.0.1/private.mp4'>"
            },
            extractorLoader = { _, _, _, _ -> false }
        )

        assertFalse(session.resolve("https://player.example/embed/current", null))
        assertEquals(listOf("https://player.example/embed/current"), fetched)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `resolution session resolves a same origin rotating Freeon clone`() = runBlocking {
        val playerUrl = "https://strplay.drama21.top/embed/current"
        val apiUrl = "https://strplay.drama21.top/api/?signed=1"
        val mediaUrl = "https://web.opendrive.com/api/v1/download/file.json/id?inline=1"
        val token0 = 161.toChar()
        val token1 = 162.toChar()
        val packed = """
            eval(function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(c/a))+String.fromCharCode(c%a+161)};return p}('$token0 $token1="//strplay.drama21.top/api/?signed=1";',95,2,'var|url'.split('|')))
        """.trimIndent()
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = IndoxxiProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                when (url) {
                    playerUrl -> packed
                    apiUrl -> """{"status":"ok","sources":[{"file":"$mediaUrl","type":"video/mp4","label":"Original"}]}"""
                    else -> error("unexpected fetch: $url")
                }
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { link -> link.takeIf { it.url == mediaUrl } }
        )

        assertTrue(session.resolve(playerUrl, "https://provider.example/item"))
        assertEquals(listOf(mediaUrl), links.map { it.url })
        assertEquals(playerUrl, links.single().referer)
    }

    @Test
    fun `Freeon api fetch rethrows cancellation`() {
        val playerUrl = "https://plyr.freeon.site/embed/id"
        val apiUrl = "https://plyr.freeon.site/api/?signed=1"
        val token0 = 161.toChar()
        val token1 = 162.toChar()
        val packed = """
            eval(function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(c/a))+String.fromCharCode(c%a+161)};return p}('$token0 $token1="//plyr.freeon.site/api/?signed=1";',95,2,'var|url'.split('|')))
        """.trimIndent()
        val session = LinkResolutionSession(
            api = IndoxxiProvider(),
            subtitleCallback = {},
            callback = {},
            pageFetcher = { url, _ ->
                if (url == playerUrl) packed
                else if (url == apiUrl) throw CancellationException("cancelled")
                else error("unexpected fetch: $url")
            },
            extractorLoader = { _, _, _, _ -> false }
        )

        assertFailsWith<CancellationException> {
            runBlocking { session.resolve(playerUrl, null) }
        }
    }
}
