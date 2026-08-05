package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class ProviderResponseOwnershipTest {
    @Test
    fun `rebound search response keeps its subtype data and uses the wrapper owner`() {
        val source = object : MainAPI() {
            override var mainUrl = "https://source.example"
            override var name = "Source"
            override var lang = "id"
            override val supportedTypes = setOf(TvType.Movie)
        }
        val response = source.newMovieSearchResponse(
            "Fallback Movie",
            "https://source.example/movie/current",
            TvType.Movie
        ) {
            year = 2024
            posterUrl = "https://source.example/poster.jpg"
        }

        val rebound = response.withProviderOwner("LayarKaca")

        assertEquals("LayarKaca", rebound.apiName)
        val movie = assertIs<MovieSearchResponse>(rebound)
        assertEquals("Fallback Movie", movie.name)
        assertEquals(2024, movie.year)
        assertEquals("https://source.example/poster.jpg", movie.posterUrl)
    }

    @Test
    fun `rebound load response updates owner without changing playback data`() = runBlocking {
        val source = object : MainAPI() {
            override var mainUrl = "https://source.example"
            override var name = "Source"
            override var lang = "id"
            override val supportedTypes = setOf(TvType.Movie)
        }
        val response = source.newMovieLoadResponse(
            "Fallback Movie",
            "https://source.example/movie/current",
            TvType.Movie,
            "https://source.example/movie/current"
        )

        val rebound = response.withProviderOwner("LayarKaca")

        assertEquals("LayarKaca", rebound.apiName)
        assertEquals(
            "https://source.example/movie/current",
            assertIs<MovieLoadResponse>(rebound).dataUrl
        )
    }

    @Test
    fun `layarkaca delegated detail is owned by layarkaca`() = runBlocking {
        val source = SourceProvider()
        val provider = LayarKacaProvider { listOf(source) }

        val detail = provider.load("https://source.example/movie/current")

        assertEquals("LayarKaca", detail?.apiName)
    }

    private class SourceProvider : MainAPI() {
        override var mainUrl = "https://source.example"
        override var name = "Source"
        override var lang = "id"
        override val supportedTypes = setOf(TvType.Movie)

        override suspend fun load(url: String): LoadResponse? = newMovieLoadResponse(
            "Fallback Movie",
            url,
            TvType.Movie,
            url
        )

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean = false
    }
}
