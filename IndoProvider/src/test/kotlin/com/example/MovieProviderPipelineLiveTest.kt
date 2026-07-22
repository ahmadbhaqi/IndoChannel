package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in checks for catalog -> metadata on current movie-provider pages. */
class MovieProviderPipelineLiveTest {
    private data class DetailCase(
        val provider: MainAPI,
        val url: String,
        val expectedTitle: String,
        val requiresSynopsis: Boolean = true
    )

    @Test
    fun `active movie providers expose a current catalog`() = runBlocking {
        if (System.getenv("RUN_LIVE_MOVIE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val failures = mutableListOf<String>()
        listOf(
            IndoxxiProvider(),
            LayarKacaProvider(),
            NgefilmProvider(),
            DutamovieProvider(),
            KitanontonProvider(),
            FilmapikProvider(),
            PusatfilmProvider(),
            KeBioskopProvider()
        ).forEach { provider ->
            val pageData = provider.mainPage.first()
            val request = MainPageRequest(pageData.name, pageData.data, pageData.horizontalImages)
            var results = emptyList<SearchResponse>()
            var lastFailure: Throwable? = null
            repeat(2) {
                if (results.isNotEmpty()) return@repeat
                try {
                    val home = withTimeout(45_000) { provider.getMainPage(1, request) }
                    results = home?.items?.flatMap { it.list }.orEmpty()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastFailure = error
                }
            }
            println("${provider.name} catalog=${results.size} first=${results.firstOrNull()?.name}")
            when {
                results.isEmpty() -> failures += buildString {
                    append("${provider.name}: empty catalog")
                    lastFailure?.message?.let { append(" ($it)") }
                }
                results.any { it.name.isBlank() || !it.url.startsWith("http") } ->
                    failures += "${provider.name}: invalid catalog title or URL"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    @Test
    fun `active movie providers parse current title poster and synopsis`() = runBlocking {
        if (System.getenv("RUN_LIVE_MOVIE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val cases = listOf(
            DetailCase(
                IndoxxiProvider(),
                "https://filmbioskop21.lk21.in.net/nonton-film-avatar-fire-and-ash-lk21-2025/",
                "Avatar: Fire and Ash"
            ),
            DetailCase(
                LayarKacaProvider(),
                "https://tv.nontonfilm.red/caught-in-the-net-2026/",
                "Caught in the Net"
            ),
            DetailCase(
                NgefilmProvider(),
                "https://new39.ngefilm.site/gangland-2025/",
                "Gangland"
            ),
            DetailCase(
                DutamovieProvider(),
                "https://restaurantesabadell.com/bagong-tukso-2-2026/",
                "Bagong Tukso 2"
            ),
            DetailCase(
                KitanontonProvider(),
                "https://kitanonton2.casa/nonton-hold-the-fort-2025-sub-indo/",
                "Hold the Fort"
            ),
            DetailCase(
                FilmapikProvider(),
                "https://filmapik.college/nonton-film-summers-last-resort-2026-subtitle-indonesia",
                "Summer's Last Resort"
            ),
            DetailCase(
                PusatfilmProvider(),
                "https://v4.pusatfilm21info.com/royal-2025/",
                "Royal"
            ),
            DetailCase(
                KeBioskopProvider(),
                "https://kebioskop21.cfd/nonton-film-peaky-blinders-the-immortal-man-2026-sub-indo/",
                "Peaky Blinders: The Immortal Man"
            )
        )

        cases.forEach { case ->
            val detail = withTimeout(45_000) { case.provider.load(case.url) }
            assertNotNull(detail, "${case.provider.name} returned no detail response")
            println(
                "${case.provider.name} title=${detail.name} poster=${detail.posterUrl} " +
                    "plot=${detail.plot?.take(160)}"
            )
            assertTrue(
                detail.name.normalizedTitle().contains(case.expectedTitle.normalizedTitle()),
                "${case.provider.name} parsed the wrong title: ${detail.name}"
            )
            assertTrue(
                !detail.posterUrl.isNullOrBlank(),
                "${case.provider.name} did not parse a poster"
            )
            if (case.requiresSynopsis) {
                assertTrue(
                    detail.plot.isUsefulSynopsis(),
                    "${case.provider.name} did not parse a useful synopsis: ${detail.plot}"
                )
            } else {
                assertTrue(
                    detail.plot == null || detail.plot.isUsefulSynopsis(),
                    "${case.provider.name} exposed SEO boilerplate as a synopsis: ${detail.plot}"
                )
            }
        }
    }

    private fun String?.isUsefulSynopsis(): Boolean {
        val value = this?.trim().orEmpty()
        if (value.length < 40) return false
        val boilerplate = listOf(
            "Oleh:",
            "Diposting pada:",
            "Tidak ada voting",
            "Nonton Film",
            "Website streaming",
            "Situs nonton",
            "Streaming film"
        )
        return boilerplate.none { marker -> value.contains(marker, ignoreCase = true) }
    }

    private fun String.normalizedTitle(): String = lowercase()
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace('\u201C', '"')
        .replace('\u201D', '"')
}
