package com.example

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jsoup.Jsoup

class LayarKacaPlayerParserTest {
    @Test
    fun `episode navigation excludes self and View All Episodes links`() {
        val detailUrl = "https://tv.nontonfilm.red/tv/example/"

        assertFalse(
            LayarKacaPlayerParser.isEpisodeLink(
                "https://tv.nontonfilm.red/tv/example/?tab=episodes",
                "Episode list",
                detailUrl
            )
        )
        assertFalse(
            LayarKacaPlayerParser.isEpisodeLink(
                "https://tv.nontonfilm.red/tv/example/episode-1/",
                "View All Episodes",
                detailUrl
            )
        )
        assertTrue(
            LayarKacaPlayerParser.isEpisodeLink(
                "https://tv.nontonfilm.red/tv/example/episode-1/",
                "Episode 1",
                detailUrl
            )
        )
    }

    @Test
    fun `ajax-only player layout exposes every Muvipro request`() {
        val document = Jsoup.parse(
            """
            <div id="muvipro_player_content_id" data-id="812"></div>
            <div class="tab-content-ajax" id="player1"></div>
            <div class="tab-content-ajax" id="player2"></div>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MuviproAjaxRequest("812", "player1"),
                MuviproAjaxRequest("812", "player2")
            ),
            LayarKacaPlayerParser.ajaxRequests(document)
        )
    }

    @Test
    fun `server page discovery ignores trailer anchors and page controls`() {
        val detailUrl = "https://tv.nontonfilm.red/evil-dead-burn-2026/"
        val document = Jsoup.parse(
            """
            <ul class="gmr-player-nav">
              <li><a href="?player=1">Server 1</a></li>
              <li><a href="?player=2">Server 2</a></li>
              <li><a href="javascript:void(0)">Server menu</a></li>
              <li><a href="#respond">Comments</a></li>
              <li><a href="https://youtube.com/watch?v=trailer">Trailer</a></li>
              <li><a href="#download">Download</a></li>
            </ul>
            """.trimIndent(),
            detailUrl
        )

        assertEquals(
            listOf(
                "$detailUrl?player=1",
                "$detailUrl?player=2"
            ),
            LayarKacaPlayerParser.serverPageUrls(document, detailUrl)
        )
    }

    @Test
    fun `legacy loadProviders menu accepts only same origin server pages`() {
        val detailUrl = "https://tv.nontonfilm.red/legacy-movie/"
        val document = Jsoup.parse(
            """
            <ul id="loadProviders">
              <li><a href="/legacy-movie/server-one/">Server 1</a></li>
              <li><a href="?provider=2">Server 2</a></li>
              <li><a href="https://tv10.lk21official.cc/legacy-movie/?provider=3">Retired host</a></li>
              <li><a href="https://evil.example/server">Foreign</a></li>
              <li><a href="http://tv.nontonfilm.red/downgrade">Downgrade</a></li>
              <li><a href="#comments">Comments</a></li>
            </ul>
            """.trimIndent(),
            detailUrl
        )

        assertEquals(
            listOf(
                "https://tv.nontonfilm.red/legacy-movie/server-one/",
                "https://tv.nontonfilm.red/legacy-movie/?provider=2",
                "https://tv.nontonfilm.red/legacy-movie/?provider=3"
            ),
            LayarKacaPlayerParser.serverPageUrls(document, detailUrl)
        )
    }

    @Test
    fun `inline-only player resolves relative media and excludes assets`() {
        val playerUrl = "https://tv.nontonfilm.red/movie/example/?player=4"
        val document = Jsoup.parse(
            """
            <script>
              jwplayer("player").setup({
                sources: [
                  {file: "/media/current/master.m3u8", type: "hls"},
                  {file: "//cdn.example/current/movie.mp4", type: "video/mp4"},
                  {file: "/subs/id.vtt", type: "text/vtt"}
                ],
                image: "/images/poster.jpg"
              });
              file = "../fallback/backup.m3u8";
            </script>
            """.trimIndent(),
            playerUrl
        )

        assertEquals(
            listOf(
                "https://tv.nontonfilm.red/media/current/master.m3u8",
                "https://cdn.example/current/movie.mp4",
                "https://tv.nontonfilm.red/movie/fallback/backup.m3u8"
            ),
            LayarKacaPlayerParser.pageMediaUrls(document, playerUrl)
        )
    }
}
