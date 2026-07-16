package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in smoke tests which exercise the real provider -> player -> HLS chain.
 * They are skipped in normal builds because the upstream sites are external.
 */
class RebahinFamilyLiveTest {
    @Test
    fun `rebahin family resolves a current AsiaStream player`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        val cases = listOf(
            RebahinProvider() to "https://154.203.167.63/pickleball-pete-2026/",
            CgvindoProvider() to "http://154.93.73.168/pickleball-pete-2026/",
            JuraganFilmProvider() to "https://juraganfilm1.lol/pickleball-pete-2026/"
        )

        cases.forEach { (provider, pageUrl) ->
            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleFile>()
            val loaded = provider.loadLinks(pageUrl, false, subtitles::add, links::add)

            println("${provider.name} loaded=$loaded links=${links.map { it.url }}")
            assertTrue(loaded, "${provider.name} did not report a resolved link")
            assertTrue(
                links.any { it.url.contains("asiastream.cc/m3u8/") },
                "${provider.name} did not resolve the AsiaStream master playlist"
            )
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
                "${provider.name} emitted no reachable AsiaStream URL: $probes"
            )
        }
    }

    @Test
    fun `playsobat follows a current mirror to mp4`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = IndofilmProvider(),
            subtitleCallback = {},
            callback = links::add
        )
        val loaded = withTimeout(90_000) {
            session.resolve(
                "https://playsobat.xyz/e/m4k48958do",
                "https://indofilm.pics/blast-2026/"
            )
        }
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

        println("PlaySobat loaded=$loaded probes=$probes")
        assertTrue(loaded && links.isNotEmpty(), "PlaySobat did not emit a media link")
        assertTrue(
            probes.values.any { it in 200..299 },
            "PlaySobat emitted no reachable media URL: $probes"
        )
    }

}
