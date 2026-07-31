package com.example

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MovieboxFallbackTest {
    @Test
    fun `bounded release drift recovers an exact title without accepting a different movie`() {
        val media = MovieboxLoadData(
            id = "1979270827040298488",
            title = "Alas Roban",
            year = 2024,
            subjectType = 2
        )
        val request = NomatFallbackRequest("Alas Roban", 2024)

        assertTrue(
            MovieboxFallbackMatcher.isPotentialTitle(request, "Alas Roban (2026)")
        )
        assertTrue(
            MovieboxFallbackMatcher.acceptsMovie(
                media,
                request,
                "Alas Roban",
                2026
            )
        )
        assertTrue(
            MovieboxFallbackMatcher.acceptsMovie(
                media,
                request,
                "Alas Roban (2026)",
                2026
            )
        )
        assertTrue(
            MovieboxFallbackMatcher.acceptsMovie(
                media.copy(season = 1, episode = 1),
                request.copy(season = 1, episode = 1),
                "Alas Roban (2026)",
                2026
            )
        )
        assertFalse(
            MovieboxFallbackMatcher.acceptsMovie(
                media,
                request,
                "Alas Roban",
                2021
            )
        )
        assertFalse(
            MovieboxFallbackMatcher.acceptsMovie(
                media,
                request,
                "Setan Alas!",
                2026
            )
        )
    }

    @Test
    fun `load data preserves exact fallback identity from search to playback`() {
        val encoded = MovieboxLoadData(
            id = "12345",
            season = 2,
            episode = 7,
            detailPath = "alas-roban-abc123456",
            title = "Alas Roban",
            year = 2025,
            subjectType = 2
        ).toJson()

        assertEquals(
            MovieboxLoadData(
                id = "12345",
                season = 2,
                episode = 7,
                detailPath = "alas-roban-abc123456",
                title = "Alas Roban",
                year = 2025,
                subjectType = 2
            ),
            MovieboxApi.loadData(encoded)
        )
    }

    @Test
    fun `external fallback result delegates detail and playback to its owner`() = runBlocking {
        val fallback = RecordingMovieboxFallback()
        val provider = MovieboxProvider { listOf(fallback) }
        val resultUrl = "https://fallback.example/movie/current"

        val detail = provider.load(resultUrl) as MovieLoadResponse
        val links = mutableListOf<ExtractorLink>()
        val loaded = provider.loadLinks(detail.dataUrl, false, {}, links::add)

        assertEquals("Fallback Movie", detail.name)
        assertTrue(loaded)
        assertEquals(listOf(resultUrl), fallback.loadCalls)
        assertEquals(listOf(resultUrl), fallback.linkCalls)
        assertEquals(listOf("https://cdn.example/fallback.mp4"), links.map { it.url })
    }

    @Test
    fun `provider retains a public no argument constructor for CloudStream`() {
        assertEquals(
            "Moviebox",
            MovieboxProvider::class.java.getDeclaredConstructor().newInstance().name
        )
    }

    private class RecordingMovieboxFallback : MainAPI() {
        override var mainUrl = "https://fallback.example"
        override var name = "Fallback"
        override var lang = "id"
        override val supportedTypes = setOf(TvType.Movie)
        val loadCalls = mutableListOf<String>()
        val linkCalls = mutableListOf<String>()

        override suspend fun load(url: String): LoadResponse? {
            loadCalls += url
            if (url != "$mainUrl/movie/current") return null
            return newMovieLoadResponse("Fallback Movie", url, TvType.Movie, url)
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            linkCalls += data
            if (data != "$mainUrl/movie/current") return false
            callback(
                newExtractorLink(
                    name,
                    name,
                    "https://cdn.example/fallback.mp4",
                    ExtractorLinkType.VIDEO
                )
            )
            return true
        }
    }
}
