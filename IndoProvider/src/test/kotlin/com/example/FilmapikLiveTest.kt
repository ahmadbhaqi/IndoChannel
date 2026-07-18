package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in checks against the current upstream sites. */
class FilmapikLiveTest {
    @Test
    fun `filmapik emits a current media link`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val loaded = withTimeout(90_000) {
            FilmapikProvider().loadLinks(
                "https://filmapik.college/nonton-film-backrooms-2026-subtitle-indonesia",
                false,
                subtitles::add,
                links::add
            )
        }

        val probes = links.associate { link ->
            val code = try {
                withTimeout(30_000) {
                    app.get(
                        link.url,
                        referer = link.referer,
                        headers = link.headers + ("Range" to "bytes=0-31"),
                        timeout = 30L
                    ).code
                }
            } catch (_: Exception) {
                null
            }
            link.url to code
        }
        println("Filmapik loaded=$loaded probes=$probes")
        assertTrue(loaded && links.isNotEmpty(), "Filmapik did not emit a media link")
        assertTrue(
            probes.values.any { it in 200..299 },
            "Filmapik emitted no reachable media URL: $probes"
        )
    }

    @Test
    fun `filmapik resolves multiple current catalog samples`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = FilmapikProvider()
        suspend fun loadCatalog(request: com.lagradost.cloudstream3.MainPageData, limit: Int) =
            withTimeout(45_000) {
                provider.getMainPage(
                    1,
                    MainPageRequest(request.name, request.data, request.horizontalImages)
                )
            }.items.flatMap { it.list }.distinctBy { it.url }.take(limit)

        val movies = loadCatalog(provider.mainPage.first(), 6)
        val series = loadCatalog(provider.mainPage.last(), 2)
        val samples = (movies + series).distinctBy { it.url }

        assertTrue(samples.isNotEmpty(), "Filmapik returned an empty current catalog")
        assertTrue(
            samples.any { it.url.contains("/tvshows/") },
            "Filmapik matrix did not include a current series"
        )
        val failures = mutableListOf<String>()
        samples.forEach { item ->
            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleFile>()
            val playbackData = withTimeout(45_000) {
                (provider.load(item.url) as? TvSeriesLoadResponse)
                    ?.episodes
                    ?.firstOrNull()
                    ?.data
                    ?: item.url
            }
            val loaded = withTimeout(90_000) {
                provider.loadLinks(playbackData, false, subtitles::add, links::add)
            }
            val hosts = links.mapNotNull { link ->
                runCatching { URI(link.url).host }.getOrNull()
            }.distinct()
            println(
                "Filmapik sample=${item.name} playback=$playbackData loaded=$loaded " +
                    "links=${links.size} hosts=$hosts"
            )
            if (!loaded || links.isEmpty()) failures += "${item.name}: no media link"
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }
}
