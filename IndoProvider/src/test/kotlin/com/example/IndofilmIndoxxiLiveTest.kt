package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in smoke tests against current active provider pages and their real players. */
class IndoxxiLayarKacaLiveTest {
    @Test
    fun `indoxxi resolves a current reachable mirror`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        verify(
            provider = IndoxxiProvider(),
            pageUrl = "https://filmbioskop21.lk21.in.net/" +
                "nonton-film-golden-kamuy-the-abashiri-prison-raid-lk21-2026/"
        )
    }

    @Test
    fun `indoxxi resolves current Indonesia category movie`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        verify(
            provider = IndoxxiProvider(),
            pageUrl = "https://filmbioskop21.lk21.in.net/nonton-film-mothernet-lk21-2026/"
        )
    }

    @Test
    fun `layarkaca prioritizes a reachable current server`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        verify(
            LayarKacaProvider(),
            "https://tv.nontonfilm.red/evil-dead-burn-2026/"
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

        println(
            "${provider.name} loaded=$loaded links=" + links.map { link ->
                "${link.url} referer=${link.referer} headers=${link.headers}"
            }
        )
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

        val probes = linkedMapOf<String, Int?>()
        for (link in links.take(8)) {
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
        println("${provider.name} probes=$probes")
        assertTrue(
            probes.values.any { it in 200..299 },
            "${provider.name} emitted no reachable media URL: $probes"
        )
        return links
    }
}
