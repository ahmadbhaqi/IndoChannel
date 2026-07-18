package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilmapikPlayerParserTest {
    @Test
    fun `slow Efek player is deferred until regular and download fallbacks`() {
        assertEquals(
            listOf(
                "https://abyssplayer.com/embed/healthy",
                "https://bysebuho.com/download/backup",
                "https://v2.efek.stream/v/slow"
            ),
            FilmapikPlayerParser.orderedPlayerCandidates(
                primary = listOf(
                    "https://v2.efek.stream/v/slow",
                    "https://abyssplayer.com/embed/healthy"
                ),
                fallback = listOf(
                    "https://bysebuho.com/download/backup",
                    "https://abyssplayer.com/embed/healthy"
                ),
                pageUrl = "https://filmapik.college/example/play"
            )
        )
    }

    @Test
    fun `Efek player host is rehomed to its storage shard before fallbacks`() {
        assertEquals(
            listOf(
                "https://s2.efek.stream/stream/720/current/__001?token=x",
                "https://v2.efek.stream/stream/720/current/__001?token=x"
            ),
            FilmapikPlayerParser.mediaUrlCandidates(
                "https://v2.efek.stream/stream/720/current/__001?token=x"
            )
        )
    }

    @Test
    fun `Efek authority rewrite preserves encoded path query and fragment`() {
        assertEquals(
            listOf(
                "https://s2.efek.stream/stream/a%2Fb/__001?token=x%2Fy#part%2F1",
                "https://v2.efek.stream/stream/a%2Fb/__001?token=x%2Fy#part%2F1"
            ),
            FilmapikPlayerParser.mediaUrlCandidates(
                "https://v2.efek.stream/stream/a%2Fb/__001?token=x%2Fy#part%2F1"
            )
        )
    }

    @Test
    fun `Efek double digit player uses its same numbered storage shard`() {
        assertEquals(
            listOf(
                "https://s12.efek.stream/stream/1080/current/__001?token=x",
                "https://v12.efek.stream/stream/1080/current/__001?token=x"
            ),
            FilmapikPlayerParser.mediaUrlCandidates(
                "https://v12.efek.stream/stream/1080/current/__001?token=x"
            )
        )
    }

    @Test
    fun `Efek shard fallback rejects zero padded and unbounded host numbers`() {
        listOf(
            "https://v02.efek.stream/stream/current.mp4",
            "https://v1000.efek.stream/stream/current.mp4"
        ).forEach { url ->
            assertEquals(listOf(url), FilmapikPlayerParser.mediaUrlCandidates(url))
        }
    }

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
