package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
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

        verifyCases(
            listOf(
                ProviderCase(DutamovieProvider()),
                ProviderCase(FilmapikProvider()),
                ProviderCase(LayarKacaProvider()),
                ProviderCase(NgefilmProvider()),
                ProviderCase(PusatfilmProvider()),
                ProviderCase(KeBioskopProvider())
            )
        )
    }

    @Test
    fun `reported series providers resolve every current catalog sample`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        verifyCases(
            listOf(
                ProviderCase(DutamovieProvider(), categoryName = "TV Series"),
                ProviderCase(FilmapikProvider(), categoryName = "K-Drama"),
                ProviderCase(KitanontonProvider(), categoryName = "Series"),
                ProviderCase(NgefilmProvider(), categoryName = "TV Series"),
                ProviderCase(PusatfilmProvider(), categoryName = "TV Series")
            )
        )
    }

    @Test
    fun `kitanonton resolves every current catalog sample`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        verifyCurrentSamples(
            ProviderCase(
                provider = KitanontonProvider(),
                sampleSize = 3
            )
        )
    }

    @Test
    fun `kitanonton resolves sampled titles from both movie rows`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        listOf("Movies", "Film Terbaru").forEach { category ->
            verifyCurrentSamples(
                ProviderCase(
                    provider = KitanontonProvider(),
                    categoryName = category,
                    sampleSize = 6
                )
            )
        }
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

    private suspend fun verifyCases(cases: List<ProviderCase>) {
        val failures = mutableListOf<String>()
        cases.forEach { case ->
            try {
                verifyCurrentSamples(case)
            } catch (error: TimeoutCancellationException) {
                failures += "${case.provider.name}: ${error.message ?: "timed out"}"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failures += "${case.provider.name}: ${error.message}"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Current catalog playback failures:\n${failures.joinToString("\n")}"
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

        assertTrue(
            catalog.size == case.sampleSize,
            "${case.provider.name} returned only ${catalog.size}/${case.sampleSize} requested " +
                "current catalog samples"
        )

        val outcomes = catalog.map { item ->
            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleFile>()
            var failure: Throwable? = null
            val loaded = try {
                withTimeout(90_000) {
                    val detail = case.provider.load(item.url)
                    val playbackData = (detail as? TvSeriesLoadResponse)
                        ?.episodes
                        ?.maxByOrNull { episode ->
                            (episode.season ?: 0) * 10_000 + (episode.episode ?: 0)
                        }
                        ?.data
                        ?: item.url
                    case.provider.loadLinks(playbackData, false, subtitles::add, links::add)
                }
            } catch (error: TimeoutCancellationException) {
                failure = error
                false
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
            item.name to (loaded && links.isNotEmpty())
        }

        val successes = outcomes.count { it.second }
        val failedSamples = outcomes
            .filterNot { it.second }
            .joinToString { it.first }
        assertTrue(
            successes == outcomes.size,
            "${case.provider.name} emitted links for only $successes/${catalog.size} " +
                "current catalog samples; every sample is required. " +
                "Failed samples: $failedSamples"
        )
    }

    private data class ProviderCase(
        val provider: MainAPI,
        val categoryName: String? = null,
        val sampleSize: Int = 3
    )
}
