package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in smoke tests against current provider pages and their real players. */
class IndofilmIndoxxiLiveTest {
    @Test
    fun `indofilm resolves a current player`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        verify(
            IndofilmProvider(),
            "https://indofilm.pics/field-of-screams-2025/"
        )
    }

    @Test
    fun `indoxxi resolves a current Byse HLS mirror`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        val links = verify(
            provider = IndoxxiProvider(),
            pageUrl = "https://filmbioskop21.lk21.in.net/nonton-film-avatar-fire-and-ash-lk21-2025/"
        )
        val byseHls = links.firstOrNull { link ->
            link.type == ExtractorLinkType.M3U8 &&
                link.url.contains(".m3u8", ignoreCase = true) &&
                link.referer.contains("bysebuho.com/e/", ignoreCase = true)
        }
        assertNotNull(byseHls, "Indoxxi did not emit the current Byse HLS source")
        val response = withTimeout(30_000) {
            app.get(
                byseHls.url,
                referer = byseHls.referer,
                headers = byseHls.headers,
                timeout = 30L
            )
        }
        assertTrue(
            response.code in 200..299 && response.text.trimStart().startsWith("#EXTM3U"),
            "Indoxxi Byse source is not a reachable HLS playlist: HTTP ${response.code}"
        )
    }

    @Test
    fun `layarkaca resolves a current alternate server`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        verify(
            LayarKacaProvider(),
            "https://tv.nontonfilm.red/love-you-so-bad-2025/"
        )
    }

    private suspend fun verify(
        provider: com.lagradost.cloudstream3.MainAPI,
        pageUrl: String,
        probeMedia: Boolean = true
    ): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val loaded = withTimeout(90_000) {
            provider.loadLinks(pageUrl, false, subtitles::add, links::add)
        }

        println("${provider.name} loaded=$loaded links=${links.map { it.url }}")
        assertTrue(loaded, "${provider.name} did not report a resolved link")
        assertTrue(
            links.any { link ->
                link.url.startsWith("https://") &&
                    !link.url.contains("/embed/", ignoreCase = true) &&
                    !link.url.contains("/e/", ignoreCase = true)
            },
            "${provider.name} did not emit a concrete media URL: ${links.map { it.url }}"
        )
        if (!probeMedia) return links

        val probes = links.associate { link ->
            val code = runCatching {
                withTimeout(30_000) {
                    app.get(
                        link.url,
                        referer = link.referer,
                        headers = link.headers + ("Range" to "bytes=0-31"),
                        timeout = 30L
                    ).code
                }
            }.getOrNull()
            link.url to code
        }
        println("${provider.name} probes=$probes")
        assertTrue(
            probes.values.any { it in 200..299 },
            "${provider.name} emitted no reachable media URL: $probes"
        )
        return links
    }
}
