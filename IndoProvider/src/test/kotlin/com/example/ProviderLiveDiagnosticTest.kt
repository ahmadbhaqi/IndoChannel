package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class ProviderLiveDiagnosticTest {
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

        println("KitaNonton loaded=$loaded links=${links.map { it.url }}")
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
            link.url to code
        }
        println("KitaNonton probes=$probes")
        assertTrue(
            probes.values.any { it in 200..299 },
            "KitaNonton emitted no reachable Abyss media URL: $probes"
        )
    }
}
