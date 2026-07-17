package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class ProviderLiveDiagnosticTest {
    @Test
    fun `kitanonton resolves current catalog samples`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        val page = "https://kitanonton2.surf/nonton-one-piece-heroines-2026-sub-indo/"
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(35_000) {
            KitanontonProvider().loadLinks(page, false, {}, links::add)
        }
        println(
            "KitaNonton page=$page loaded=$loaded links=" +
                links.map { "${it.name}@${it.url.safeHost()} headers=${it.headers.keys}" }
        )
        assertTrue(loaded && links.isNotEmpty(), "KitaNonton failed current page $page")
        assertTrue(
            links.none { it.name.contains("KitaNonton", ignoreCase = true) },
            "KitaNonton provider prefix was not removed: ${links.map { it.name }}"
        )
        assertTrue(
            links.all { link ->
                link.quality !in STANDARD_RESOLUTIONS || link.name.contains("${link.quality}p")
            },
            "KitaNonton known resolution is missing from server name: ${links.map { it.name to it.quality }}"
        )
        val probes = linkedMapOf<String, Int?>()
        for (link in links.take(8)) {
            val code = runCatching {
                withTimeout(20_000) {
                    app.get(
                        link.url,
                        referer = link.referer,
                        headers = link.headers + ("Range" to "bytes=0-31"),
                        timeout = 20L
                    ).code
                }
            }.getOrNull()
            probes[link.url.safeHost()] = code
            if (code in 200..299) break
        }
        println("KitaNonton current probes=$probes")
        assertTrue(
            probes.values.any { it in 200..299 },
            "KitaNonton current media returned no reachable source: $probes"
        )
    }

    @Test
    fun `kitanonton emits a real link`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val loaded = KitanontonProvider().loadLinks(
            "https://kitanonton2.surf/nonton-hold-the-fort-2025-sub-indo/",
            false,
            subtitles::add,
            links::add
        )

        println("KitaNonton loaded=$loaded links=${links.map { it.url.safeHost() }}")
        assertTrue(
            loaded && links.any { it.url.contains(".sssrr.org/") },
            "KitaNonton did not decode a complete Abyss MP4 source"
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
            link.url.safeHost() to code
        }
        println("KitaNonton probes=$probes")
        assertTrue(
            probes.values.any { it in 200..299 },
            "KitaNonton emitted no reachable Abyss media URL: $probes"
        )
    }

    private fun String.safeHost(): String = runCatching { URI(this).host.orEmpty() }
        .getOrDefault("invalid")

    private companion object {
        val STANDARD_RESOLUTIONS = setOf(
            144, 180, 240, 288, 360, 480, 540, 576, 720, 900, 1080, 1440, 2160, 4320
        )
    }
}
