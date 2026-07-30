package com.example

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
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
    fun `regular player leads but Efek primary stays ahead of download fallbacks`() {
        assertEquals(
            listOf(
                "https://abyssplayer.com/embed/healthy",
                "https://fiilmapik.strp2p.site/#current",
                "https://v2.efek.stream/v/current",
                "https://vidmoly.net/embed/regular-third",
                "https://bysebuho.com/download/backup"
            ),
            FilmapikPlayerParser.orderedPlayerCandidates(
                primary = listOf(
                    "https://v2.efek.stream/v/current",
                    "https://abyssplayer.com/embed/healthy",
                    "https://fiilmapik.strp2p.site/#current",
                    "https://vidmoly.net/embed/regular-third"
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
    fun `raced Filmapik direct candidates retain browser origin`() = runBlocking {
        val referer = "https://filmapik.college/example/play"
        val candidates = FilmapikPlayerParser.resolutionCandidates(
            listOf("https://cdn.example/healthy.mp4"),
            referer
        )
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        val session = LinkResolutionSession(
            api = FilmapikProvider(),
            subtitleCallback = {},
            callback = links::add,
            mediaLinkProbe = { it }
        )

        assertTrue(candidates.single().inline)
        assertTrue(session.resolveFirstVerified(candidates, maxConcurrency = 1))
        assertEquals("https://filmapik.college", links.single().headers["Origin"])
    }

    @Test
    fun `resolution session treats extensionless Efek streams as media`() = runBlocking {
        val playerUrl = "https://v2.efek.stream/v/current"
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        val session = LinkResolutionSession(
            api = FilmapikProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { url, _ ->
                assertEquals(playerUrl, url)
                """
                    <script>
                      const sources = [
                        {'label':'720p','type':'video/mp4',
                         'file':'/stream/720/current/__001'}
                      ];
                    </script>
                """.trimIndent()
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { link ->
                link.takeIf { it.url.startsWith("https://s2.efek.stream/") }
            },
            candidateTimeoutMs = 500L,
            sessionTimeoutMs = 2_000L
        )

        assertTrue(session.resolve(playerUrl, "https://filmapik.college/example/play"))
        assertEquals(
            "https://s2.efek.stream/stream/720/current/__001",
            links.single().url
        )
    }

    @Test
    fun `Efek storage retry starts only after the first attempt fails`() = runBlocking {
        val playerUrl = "https://v2.efek.stream/v/current"
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        val storageCalls = AtomicInteger()
        val activeStorageProbes = AtomicInteger()
        val maxActiveStorageProbes = AtomicInteger()
        val session = LinkResolutionSession(
            api = FilmapikProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ ->
                """
                    <script>
                      const sources = [
                        {'label':'720p','type':'video/mp4',
                         'file':'/stream/720/current/__001'}
                      ];
                    </script>
                """.trimIndent()
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { link ->
                if (!link.url.startsWith("https://s2.efek.stream/")) {
                    null
                } else {
                    val call = storageCalls.incrementAndGet()
                    val active = activeStorageProbes.incrementAndGet()
                    maxActiveStorageProbes.updateAndGet { current -> maxOf(current, active) }
                    delay(50)
                    activeStorageProbes.decrementAndGet()
                    link.takeIf { call == 2 }
                }
            },
            candidateTimeoutMs = 1_000L,
            sessionTimeoutMs = 3_000L
        )

        assertTrue(session.resolve(playerUrl, "https://filmapik.college/example/play"))
        assertEquals(2, storageCalls.get())
        assertEquals(1, maxActiveStorageProbes.get())
        assertEquals(
            "https://s2.efek.stream/stream/720/current/__001",
            links.single().url
        )
    }

    @Test
    fun `slow Efek storage probe cannot block its healthy player sibling`() = runBlocking {
        val playerUrl = "https://v2.efek.stream/v/current"
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        val session = LinkResolutionSession(
            api = FilmapikProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ ->
                """
                    <script>
                      const sources = [
                        {'label':'720p','type':'video/mp4',
                         'file':'/stream/720/current/__001'}
                      ];
                    </script>
                """.trimIndent()
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { link ->
                if (link.url.startsWith("https://s2.efek.stream/")) {
                    delay(1_500)
                    null
                } else {
                    link
                }
            },
            candidateTimeoutMs = 500L,
            sessionTimeoutMs = 3_000L
        )

        assertTrue(session.resolve(playerUrl, "https://filmapik.college/example/play"))
        assertEquals(
            "https://v2.efek.stream/stream/720/current/__001",
            links.single().url
        )
    }

    @Test
    fun `stalled Efek candidate cannot block a healthy raced download`() = runBlocking {
        val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        val session = LinkResolutionSession(
            api = FilmapikProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ ->
                delay(500)
                "<html></html>"
            },
            extractorLoader = { _, _, _, _ -> false },
            mediaLinkProbe = { it },
            candidateTimeoutMs = 100L,
            sessionTimeoutMs = 1_000L
        )

        assertTrue(
            session.resolveFirstVerified(
                listOf(
                    PlayerResolutionCandidate(
                        "https://v2.efek.stream/v/dead",
                        "https://filmapik.college/example/play"
                    ),
                    PlayerResolutionCandidate(
                        "https://cdn.example/healthy.mp4",
                        "https://filmapik.college/example/play"
                    )
                ),
                maxConcurrency = 2
            )
        )
        assertEquals("https://cdn.example/healthy.mp4", links.single().url)
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
