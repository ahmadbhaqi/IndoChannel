package com.example

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Mirrors the provider test flow used by the CloudStream app:
 * homepage -> three title-word searches -> first three results -> first episode -> loadLinks.
 */
class CloudstreamTesterParityLiveTest {
    @Test
    fun `layarkaca stale Supergirl episodes resolve through an exact fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val targets = setOf(6 to 9, 5 to 16, 4 to 8)
        val provider = LayarKacaProvider()
        val results = provider.search("Supergirl").orEmpty()
        val outcomes = mutableMapOf<Pair<Int, Int>, String>()
        targets.forEach { target ->
            val marker = "Season ${target.first} Episode ${target.second}"
            val result = results.firstOrNull { it.name.contains(marker, ignoreCase = true) }
            if (result == null) {
                outcomes[target] = "search result missing"
                return@forEach
            }
            withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                val detail = provider.load(result.url)
                val playbackData = when (detail) {
                    is MovieLoadResponse -> detail.dataUrl
                    is TvSeriesLoadResponse -> detail.episodes.firstOrNull()?.data
                    else -> null
                }
                if (playbackData.isNullOrBlank()) {
                    outcomes[target] = "playback data missing"
                    return@withTimeout
                }
                val playbackCoordinate =
                    LayarKacaPlayerParser.episodeCoordinate(playbackData, "")
                if (
                    playbackCoordinate?.season != target.first ||
                    playbackCoordinate.episode != target.second
                ) {
                    outcomes[target] = "wrong playback coordinate=$playbackCoordinate"
                    return@withTimeout
                }
                val links = mutableListOf<ExtractorLink>()
                val loaded = provider.loadLinks(playbackData, false, {}, links::add)
                outcomes[target] = "loaded=$loaded links=${links.size}"
            }
        }

        println("LayarKaca stale Supergirl outcomes=$outcomes")
        assertTrue(
            targets.all { target -> outcomes[target]?.let {
                it.startsWith("loaded=true") && !it.endsWith("links=0")
            } == true },
            "LayarKaca exact fallback did not resolve every stale episode: $outcomes"
        )
    }

    @Test
    fun `providers reported by the app satisfy its search and playback contract`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val failures = mutableListOf<String>()
        reportedProviders().forEach { provider ->
            try {
                withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                    verifyProvider(provider)
                }
            } catch (error: TimeoutCancellationException) {
                failures += "${provider.name}: timed out"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failures += "${provider.name}: ${error.message ?: error::class.simpleName}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "CloudStream tester parity failures:\n${failures.joinToString("\n")}"
        )
    }

    private suspend fun verifyProvider(provider: MainAPI) {
        val page = provider.mainPage.firstOrNull()
            ?: error("has no main-page category")
        val homepage = provider.getMainPage(
            1,
            MainPageRequest(page.name, page.data, page.horizontalImages)
        )?.items?.flatMap { it.list }.orEmpty()
        assertTrue(homepage.isNotEmpty(), "returned an empty homepage")

        val queries = (
            homepage.shuffled(Random(0))
                .take(3)
                .mapNotNull { item -> item.name.firstSearchWord() } +
                listOf("over", "iron", "guy")
            ).take(3)
        val searchResults = queries.firstNotNullOfOrNull { query ->
            provider.search(query).orEmpty().takeIf(List<SearchResponse>::isNotEmpty)
        }.orEmpty()
        assertTrue(
            searchResults.isNotEmpty(),
            "did not return search responses for $queries"
        )

        val attempts = mutableListOf<String>()
        val playable = searchResults.take(3).any { result ->
            try {
                val detail = provider.load(result.url)
                val playbackData = when (detail) {
                    is AnimeLoadResponse ->
                        detail.episodes.keys.firstOrNull()
                            ?.let(detail.episodes::get)
                            ?.firstOrNull()
                            ?.data

                    is MovieLoadResponse -> detail.dataUrl
                    is TvSeriesLoadResponse -> detail.episodes.firstOrNull()?.data
                    else -> null
                }
                if (playbackData.isNullOrBlank()) {
                    attempts += "${result.name}: no playback data"
                    false
                } else {
                    val links = mutableListOf<ExtractorLink>()
                    val subtitles = mutableListOf<SubtitleFile>()
                    val loaded = provider.loadLinks(
                        playbackData,
                        false,
                        subtitles::add,
                        links::add
                    )
                    attempts += "${result.name}: loaded=$loaded links=${links.size}"
                    loaded && links.isNotEmpty()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                attempts += "${result.name}: ${error.message ?: error::class.simpleName}"
                false
            }
        }

        println("${provider.name} queries=$queries attempts=$attempts")
        assertTrue(playable, "failed its first three search results: $attempts")
    }

    private fun String.firstSearchWord(): String? =
        trim().split(Regex("""\s+""")).firstOrNull { it.isNotBlank() }

    private fun reportedProviders(): List<MainAPI> = listOf(
        AnimasuProvider(),
        IndomaxProvider(),
        KuramanimeProvider(),
        LayarKacaProvider(),
        MovieboxProvider(),
        NomatProvider(),
        PencurimovieProvider(),
        ZoronimeProvider()
    )

    private companion object {
        const val PROVIDER_TIMEOUT_MILLIS = 180_000L
    }
}
