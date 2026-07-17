package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilmapikPlayerParserTest {
    @Test
    fun `cached detail URLs are rehomed to the current Filmapik domain`() {
        val current = "https://filmapik.college"

        assertEquals(
            "$current/nonton-film-example-2026?server=2#play",
            FilmapikPlayerParser.normalizePageUrl(
                "https://filmapik.to/nonton-film-example-2026?server=2#play",
                current
            )
        )
        assertEquals(
            "$current/tvshows/example/episode-1/",
            FilmapikPlayerParser.normalizePageUrl(
                "https://filmapik.fitness/tvshows/example/episode-1/",
                current
            )
        )
        assertNull(
            FilmapikPlayerParser.normalizePageUrl(
                "https://unrelated.example/nonton-film-example-2026",
                current
            )
        )
    }

    @Test
    fun `play route preserves query but never appends after a fragment`() {
        assertEquals(
            "https://filmapik.college/nonton-film-example-2026/play?server=2",
            FilmapikPlayerParser.playPageUrl(
                "https://filmapik.college/nonton-film-example-2026?server=2#watch"
            )
        )
        assertEquals(
            "https://filmapik.college/nonton-film-example-2026/play",
            FilmapikPlayerParser.playPageUrl(
                "https://filmapik.college/nonton-film-example-2026/play"
            )
        )
    }
}
