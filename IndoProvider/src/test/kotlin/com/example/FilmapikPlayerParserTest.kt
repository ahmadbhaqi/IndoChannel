package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FilmapikPlayerParserTest {
    @Test
    fun `failed Byse shell is terminal so a later fallback keeps its budget`() = runBlocking {
        val byseUrl = "https://byseqekaho.com/e/dead"
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        var genericAttempts = 0
        var bysePageFetches = 0
        val session = LinkResolutionSession(
            api = FilmapikProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                if (url == byseUrl) bysePageFetches++
                error("Byse shell HTML must not be fetched")
            },
            byseApiFetcher = { _, _ -> "{}" },
            extractorLoader = { _, _, _, _ ->
                genericAttempts++
                false
            },
            mediaLinkProbe = { it },
            candidateTimeoutMs = 500L,
            sessionTimeoutMs = 3_000L
        )

        assertFalse(session.resolve(byseUrl, "https://filmapik.college/example/play"))
        assertEquals(0, genericAttempts)
        assertEquals(0, bysePageFetches)

        // The same session must still have room to accept a healthy later
        // candidate after the dead Byse shell is classified as terminal.
        assertTrue(
            session.resolve(
                "https://cdn.example/video.mp4",
                "https://filmapik.college/example/play"
            )
        )
        assertEquals(1, links.size)
    }

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
