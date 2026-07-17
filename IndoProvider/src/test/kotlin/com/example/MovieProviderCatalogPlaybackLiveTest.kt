package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in playback matrix that discovers current titles instead of relying on
 * hand-picked slugs which may continue working after a provider changes markup.
 */
class MovieProviderCatalogPlaybackLiveTest {
    @Test
    fun `reported providers resolve current catalog samples`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        listOf(
            ProviderCase(DutamovieProvider()),
            ProviderCase(FilmapikProvider()),
            ProviderCase(KitanontonProvider()),
            ProviderCase(LayarKacaProvider())
        ).forEach { verifyCurrentSamples(it) }
    }

    @Test
    fun `indoxxi resolves current Indonesia catalog samples`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        verifyCurrentSamples(
            ProviderCase(
                provider = IndoxxiProvider(),
                categoryName = "Indonesia",
                sampleSize = 6
            )
        )
    }

    private suspend fun verifyCurrentSamples(case: ProviderCase) {
        val pageData = case.provider.mainPage.firstOrNull { page ->
            case.categoryName == null || page.name.equals(case.categoryName, ignoreCase = true)
        } ?: error("${case.provider.name} has no ${case.categoryName.orEmpty()} category")
        val request = MainPageRequest(pageData.name, pageData.data, pageData.horizontalImages)
        val catalog = withTimeout(45_000) {
            case.provider.getMainPage(1, request)
        }?.items
            ?.flatMap { it.list }
            .orEmpty()
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url }
            .take(case.sampleSize)

        assertTrue(catalog.isNotEmpty(), "${case.provider.name} returned an empty current catalog")

        val outcomes = catalog.map { item ->
            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleFile>()
            var failure: Throwable? = null
            val loaded = try {
                withTimeout(90_000) {
                    val detail = case.provider.load(item.url)
                    val playbackData = (detail as? TvSeriesLoadResponse)
                        ?.episodes
                        ?.firstOrNull()
                        ?.data
                        ?: item.url
                    case.provider.loadLinks(playbackData, false, subtitles::add, links::add)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failure = error
                false
            }
            println(
                "${case.provider.name} sample=${item.name} url=${item.url} loaded=$loaded " +
                    "links=${links.map { it.url }} failure=${failure?.message}"
            )
            loaded && links.isNotEmpty()
        }

        val required = (outcomes.size + 1) / 2
        val successes = outcomes.count { it }
        assertTrue(
            successes >= required,
            "${case.provider.name} emitted links for only $successes/${catalog.size} " +
                "current catalog samples; required $required"
        )
    }

    private data class ProviderCase(
        val provider: MainAPI,
        val categoryName: String? = null,
        val sampleSize: Int = 3
    )
}
