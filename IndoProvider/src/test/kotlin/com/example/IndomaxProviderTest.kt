package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jsoup.Jsoup

class IndomaxProviderTest {
    @Test
    fun `provider exposes the verified live domain and catalog route`() {
        val provider = IndomaxProvider()

        assertEquals("Indomax", provider.name)
        assertEquals("https://idmxl.ink", provider.mainUrl)
        assertEquals(
            "category/box-office/page/%d/",
            provider.mainPage.first { it.name == "Box Office" }.data
        )
    }

    @Test
    fun `catalog fixture parses movie and series cards on the redirected owned host`() {
        val pageUrl = "https://akses10.indomax21.xyz/category/box-office/"
        val document = Jsoup.parse(
            """
                <article class="item-infinite">
                  <h2 class="entry-title">
                    <a href="/masters-of-the-universe-2026/">Masters of the Universe (2026)</a>
                  </h2>
                  <img class="wp-post-image"
                       data-src="https://images.example/masters-300x450.jpg">
                  <div class="gmr-quality-item"><a>HD</a></div>
                  <div class="gmr-rating-item">8.4</div>
                </article>
                <article class="item-infinite">
                  <h2 class="entry-title">
                    <a href="/tv/example-series/">Example Series</a>
                  </h2>
                  <img class="wp-post-image" src="https://images.example/series.jpg">
                  <div class="gmr-numbeps"><span>7</span></div>
                </article>
            """.trimIndent(),
            pageUrl
        )

        assertEquals(
            listOf(
                IndomaxCatalogItem(
                    title = "Masters of the Universe (2026)",
                    url = "https://akses10.indomax21.xyz/masters-of-the-universe-2026/",
                    posterUrl = "https://images.example/masters-300x450.jpg",
                    quality = "HD",
                    episodeCount = null,
                    rating = 8.4,
                    isSeries = false
                ),
                IndomaxCatalogItem(
                    title = "Example Series",
                    url = "https://akses10.indomax21.xyz/tv/example-series/",
                    posterUrl = "https://images.example/series.jpg",
                    quality = null,
                    episodeCount = 7,
                    rating = null,
                    isSeries = true
                )
            ),
            IndomaxParser.catalogItems(document, pageUrl)
        )
    }

    @Test
    fun `owned page normalizer preserves exact live aliases and rejects lookalikes`() {
        assertEquals(
            "https://akses7.indomax21.xyz/movie/example/",
            IndomaxParser.providerPageUrl(
                "https://akses7.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertEquals(
            "https://akses8.indomax21.xyz/movie/example/",
            IndomaxParser.providerPageUrl(
                "https://akses8.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertEquals(
            "https://akses10.indomax21.xyz/movie/example/?player=2",
            IndomaxParser.providerPageUrl(
                "https://akses10.indomax21.xyz/movie/example/?player=2",
                "https://idmxl.ink"
            )
        )
        assertEquals(
            "https://akses6.indomax21.xyz/movie/example/",
            IndomaxParser.providerPageUrl(
                "https://akses6.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertEquals(
            "https://akses125.indomax21.xyz/movie/example/",
            IndomaxParser.providerPageUrl(
                "https://akses125.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertNull(
            IndomaxParser.providerPageUrl(
                "https://akses10.indomax21.xyz.attacker.example/private",
                "https://idmxl.ink"
            )
        )
        assertNull(
            IndomaxParser.providerPageUrl(
                "http://akses10.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
        assertNull(
            IndomaxParser.providerPageUrl(
                "https://akses1000.indomax21.xyz/movie/example/",
                "https://idmxl.ink"
            )
        )
    }

    @Test
    fun `player fixture keeps one active player and at most three alternate tabs`() {
        val pageUrl = "https://akses10.indomax21.xyz/movie/example/"
        val document = Jsoup.parse(
            """
                <div class="gmr-embed-responsive">
                  <iframe data-litespeed-src="https://imaxstreams.net/e/active"></iframe>
                </div>
                <ul class="muvipro-player-tabs">
                  <li class="active"><a href="?player=1">Server 1</a></li>
                  <li><a href="?player=2">Server 2</a></li>
                  <li><a href="?player=3">Server 3</a></li>
                  <li><a href="?player=4">Server 4</a></li>
                  <li><a href="?player=5">Server 5</a></li>
                </ul>
            """.trimIndent(),
            pageUrl
        )

        assertEquals(
            "https://imaxstreams.net/e/active",
            IndomaxParser.primaryPlayerUrl(document, pageUrl)
        )
        assertEquals(
            listOf(
                "https://akses10.indomax21.xyz/movie/example/?player=2",
                "https://akses10.indomax21.xyz/movie/example/?player=3",
                "https://akses10.indomax21.xyz/movie/example/?player=4"
            ),
            IndomaxParser.alternateTabUrls(document, pageUrl, remainingPlayers = 3)
        )

        val fragmentPlayer = Jsoup.parse(
            """
                <div class="gmr-embed-responsive">
                  <iframe src="https://ichinime.4meplayer.pro/#e8bn6"></iframe>
                </div>
            """.trimIndent(),
            pageUrl
        )
        assertEquals(
            "https://ichinime.4meplayer.pro/#e8bn6",
            IndomaxParser.primaryPlayerUrl(fragmentPlayer, pageUrl)
        )
    }

    @Test
    fun `imax packer fixture yields only four bounded https hls candidates`() {
        val packed = """
            <script>
            eval(function(p,a,c,k,e,d){return p}('0={1:"2",3:"4",5:"6",7:"8",9:"a"};',62,11,'sources|hls1|https://cdn.example/one/master.m3u8|hls2|https://cdn.example/two/master.m3u8|hls3|https://cdn.example/three/master.m3u8|hls4|https://cdn.example/four/master.m3u8|hls5|https://cdn.example/five/master.m3u8'.split('|'),0,{}))
            </script>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://cdn.example/one/master.m3u8",
                "https://cdn.example/two/master.m3u8",
                "https://cdn.example/three/master.m3u8",
                "https://cdn.example/four/master.m3u8"
            ),
            IndomaxParser.imaxMediaUrls(packed, "https://imaxstreams.net/e/example")
        )
        assertTrue(
            IndomaxParser.imaxMediaUrls(
                "x".repeat(IndomaxParser.MAX_PACKED_INPUT_CHARS + 1),
                "https://imaxstreams.net/e/example"
            ).isEmpty()
        )
        assertTrue(
            IndomaxParser.imaxMediaUrls(packed, "https://attacker.example/e/example").isEmpty()
        )
    }

    @Test
    fun `external page trust requires exact https and leaves public dns validation to safety client`() {
        assertEquals(
            "https://imaxstreams.com/embed/example",
            IndomaxParser.publicHttpsUrl("https://imaxstreams.com/embed/example")
        )
        assertEquals(
            "https://ichinime.4meplayer.pro/#e8bn6",
            IndomaxParser.publicPlayableUrl("https://ichinime.4meplayer.pro/#e8bn6")
        )
        assertNull(IndomaxParser.publicHttpsUrl("https://ichinime.4meplayer.pro/#e8bn6"))
        assertNull(IndomaxParser.publicHttpsUrl("http://imaxstreams.com/embed/example"))
        assertNull(IndomaxParser.publicHttpsUrl("https://127.0.0.1/private"))
        assertNull(IndomaxParser.publicHttpsUrl("https://user@imaxstreams.com/private"))
    }
}
