package com.example

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class PencurimovieCatalogFallbackTest {
    @Test
    fun `empty homepage catalog falls back with wrapper ownership`() = runBlocking {
        val source = SourceProvider()

        val results = resolvePencurimovieCatalog(
            primaryItems = emptyList(),
            fallbackProviders = listOf(source),
            owner = "Pencurimovie"
        ) { provider ->
            provider.search("Fallback Movie").orEmpty()
        }

        assertEquals(1, results.size)
        assertEquals("Pencurimovie", results.single().apiName)
    }

    @Test
    fun `search retries empty primary catalog and rebinds fallback ownership`() = runBlocking {
        var attempts = 0
        val source = SourceProvider()

        val results = resolvePencurimovieSearch(
            query = "Fallback Movie",
            primarySearch = {
                attempts++
                emptyList()
            },
            fallbackProviders = listOf(source),
            owner = "Pencurimovie"
        )

        assertEquals(2, attempts)
        assertEquals(1, results.size)
        assertEquals("Pencurimovie", results.single().apiName)
        assertEquals("Fallback Movie", results.single().name)
    }

    @Test
    fun `load delegates foreign detail URLs and rebinds fallback ownership`() = runBlocking {
        val source = SourceProvider()
        val provider = PencurimovieProvider { listOf(source) }

        val detail = provider.load(SourceProvider.DETAIL_URL)

        assertEquals("Pencurimovie", detail?.apiName)
        assertEquals(SourceProvider.PLAYBACK_URL, (detail as MovieLoadResponse).dataUrl)
    }

    @Test
    fun `loadLinks delegates foreign playback URLs to the fallback source`() = runBlocking {
        val source = SourceProvider()
        val provider = PencurimovieProvider { listOf(source) }
        val links = mutableListOf<ExtractorLink>()

        val loaded = provider.loadLinks(
            SourceProvider.PLAYBACK_URL,
            false,
            subtitleCallback = {},
            callback = links::add
        )

        assertTrue(loaded)
        assertEquals(listOf(SourceProvider.MEDIA_URL), links.map { it.url })
    }

    @Test
    fun `primary retries and fallback share one total search deadline`() = runBlocking {
        val elapsed = measureTimeMillis {
            val results = resolvePencurimovieSearch(
                query = "Fallback Movie",
                primarySearch = {
                    delay(80)
                    emptyList()
                },
                fallbackProviders = emptyList(),
                owner = "Pencurimovie",
                totalTimeoutMs = 100
            )

            assertTrue(results.isEmpty())
        }

        assertTrue(elapsed < 180, "shared 100ms deadline took ${elapsed}ms")
    }

    private class SourceProvider : MainAPI() {
        override var mainUrl = "https://source.example"
        override var name = "Source"
        override var lang = "id"
        override val supportedTypes = setOf(TvType.Movie)

        override suspend fun search(query: String): List<SearchResponse> = listOf(
            newMovieSearchResponse("Fallback Movie", DETAIL_URL, TvType.Movie)
        )

        override suspend fun load(url: String): LoadResponse? = newMovieLoadResponse(
            "Fallback Movie",
            DETAIL_URL,
            TvType.Movie,
            PLAYBACK_URL
        )

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = MEDIA_URL,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        companion object {
            const val DETAIL_URL = "https://source.example/movie/fallback"
            const val PLAYBACK_URL = "https://source.example/playback/fallback"
            const val MEDIA_URL = "https://media.example/fallback.mp4"
        }
    }
}
