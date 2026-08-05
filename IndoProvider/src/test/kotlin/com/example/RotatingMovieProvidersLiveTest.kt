package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in end-to-end checks for movie sites whose domains rotate frequently. */
class RotatingMovieProvidersLiveTest {
    @Test
    fun `ngefilm emits reachable media`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }
        verify(NgefilmProvider(), "https://new39.ngefilm.site/senin-harga-naik-2026/")
    }

    @Test
    fun `pusatfilm emits reachable media`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }
        verify(PusatfilmProvider(), "https://v4.pusatfilm21info.com/royal-2025/")
    }

    @Test
    fun `dutamovie emits reachable media`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }
        val provider = DutamovieProvider()
        val page = provider.mainPage.first()
        val catalog = provider.getMainPage(
            1,
            MainPageRequest(page.name, page.data, page.horizontalImages)
        )?.items?.flatMap { it.list }.orEmpty()
        val item = catalog.firstOrNull()
            ?: error("Dutamovie current catalog is empty")
        val detail = provider.load(item.url)
        val playbackData = when (detail) {
            is MovieLoadResponse -> detail.dataUrl
            is TvSeriesLoadResponse -> detail.episodes.maxByOrNull { episode ->
                (episode.season ?: 0) * 10_000 + (episode.episode ?: 0)
            }?.data
            else -> null
        } ?: error("Dutamovie current catalog item has no playback data")
        verify(provider, playbackData)
    }

    @Test
    fun `kebioskop emits reachable media`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }
        verify(
            KeBioskopProvider(),
            "https://kebioskop21.cfd/nonton-film-peaky-blinders-the-immortal-man-2026-sub-indo/"
        )
    }

    private suspend fun verify(provider: MainAPI, pageUrl: String) {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val loaded = runCatching {
            withTimeout(120_000) {
                provider.loadLinks(pageUrl, false, subtitles::add, links::add)
            }
        }.getOrDefault(false)
        val probes = linkedMapOf<String, Int?>()
        for (link in links.take(4)) {
            val code = runCatching {
                withTimeout(30_000) {
                    app.get(
                        link.url,
                        referer = link.referer,
                        headers = link.headers + if (link.type == ExtractorLinkType.M3U8) {
                            emptyMap()
                        } else {
                            mapOf("Range" to "bytes=0-31")
                        },
                        timeout = 30L
                    ).code
                }
            }.getOrNull()
            probes[link.url.safeHost()] = code
            if (code in 200..299) break
        }

        println(
            "${provider.name} loaded=$loaded " +
                "linkHosts=${links.map { it.url.safeHost() }} probes=$probes"
        )
        assertTrue(loaded && links.isNotEmpty(), "${provider.name} emitted no concrete media link")
        assertTrue(
            probes.values.any { it in 200..299 },
            "${provider.name} emitted no reachable media link: $probes"
        )
    }

    private fun String.safeHost(): String = runCatching {
        URI(this).host?.takeIf(String::isNotBlank) ?: "opaque"
    }.getOrDefault("opaque")
}
