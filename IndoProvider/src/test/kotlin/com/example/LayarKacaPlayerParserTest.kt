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
}
