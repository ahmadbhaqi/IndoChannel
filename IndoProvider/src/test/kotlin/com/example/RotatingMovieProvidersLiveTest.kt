package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
        verify(NgefilmProvider(), "https://new38.ngefilm.site/gangland-2025/")
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
        verify(DutamovieProvider(), "https://austincomputerworks.org/lunok-2026/")
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
            probes[link.url] = code
            if (code in 200..299) break
        }

        println("${provider.name} loaded=$loaded links=${links.map { it.url }} probes=$probes")
        assertTrue(loaded && links.isNotEmpty(), "${provider.name} emitted no concrete media link")
        assertTrue(
            probes.values.any { it in 200..299 },
            "${provider.name} emitted no reachable media link: $probes"
        )
    }
}
