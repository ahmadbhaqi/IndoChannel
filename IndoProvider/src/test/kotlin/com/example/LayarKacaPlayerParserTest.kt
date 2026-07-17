package com.example

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
