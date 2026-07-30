package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class ProviderLiveDiagnosticTest {
    @Test
    fun `layarkaca Backrooms keeps a referer aware Strcloud fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val page = "https://tv.nontonfilm.red/backrooms-2026/"
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(120_000) {
            LayarKacaProvider().loadLinks(page, false, {}, links::add)
        }
        println(
            "LayarKaca Backrooms loaded=$loaded links=" +
                links.map { "${it.url.safeHost()} referer=${it.referer.safeHost()}" }
        )
        assertTrue(
            loaded && links.isNotEmpty(),
            "LayarKaca Backrooms did not resolve JustPlay or referer-aware Strcloud"
        )
    }

    @Test
    fun `dutamovie compact series episodes keep playable data`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = DutamovieProvider()
        val detailUrl = "https://cowboysgab.com/tv/the-east-palace-2026/"
        val detail = withTimeout(45_000) { provider.load(detailUrl) }
        val episodes = (detail as? TvSeriesLoadResponse)?.episodes.orEmpty()
        assertTrue(episodes.isNotEmpty(), "Dutamovie discarded compact S1 Eps1 labels")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(120_000) {
            provider.loadLinks(episodes.first().data, false, {}, links::add)
        }
        println(
            "Dutamovie East Palace episodes=${episodes.size} first=${episodes.first().data} " +
                "loaded=$loaded links=${links.map { it.url }}"
        )
        assertTrue(loaded && links.isNotEmpty(), "Dutamovie first current series episode has no link")
    }

    @Test
    fun `kitanonton series preserves selected episode data and mirrors`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = KitanontonProvider()
        val detailUrl = "https://kitanonton2.casa/series/nonton-film-key-to-the-phoenix-heart-2026/"
        val detail = withTimeout(45_000) { provider.load(detailUrl) }
        val episodes = (detail as? TvSeriesLoadResponse)?.episodes.orEmpty()
        assertTrue(episodes.isNotEmpty(), "KitaNonton series exposed no episodes")

        assertTrue(
            episodes.first().data.startsWith("https://") &&
                KitanontonPlayerParser.isEpisodeData(episodes.first().data),
            "Cloudstream corrupted KitaNonton's selected-episode payload: ${episodes.first().data}"
        )
        val request = KitanontonPlayerParser.decodeEpisodeData(episodes.first().data)
        requireNotNull(request)
        val watchFetch = withTimeout(30_000) {
            app.get(request.watchUrl, referer = request.detailUrl, timeout = 30L)
        }
        val playerUrls = KitanontonPlayerParser.episodePlayerUrls(
            watchFetch.document,
            request.episode
        )
        assertTrue(playerUrls.isNotEmpty(), "KitaNonton selected episode exposed no mirrors")

        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(45_000) {
            provider.loadLinks(episodes.first().data, false, {}, links::add)
        }
        println(
            "KitaNonton series episodes=${episodes.size} first=${episodes.first().name} " +
                "mirrors=${playerUrls.map { it.safeHost() }} loaded=$loaded " +
                "links=${links.map { "${it.url.safeHost()} headers=${it.headers.keys}" }}"
        )
        assertTrue(
            loaded && links.isNotEmpty(),
            "KitaNonton selected episode emitted no playable link from mirrors: " +
                playerUrls.map { it.safeHost() }
        )
    }

    @Test
    fun `kitanonton resolves current catalog samples`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val page = "https://kitanonton2.casa/nonton-one-piece-heroines-2026-sub-indo/"
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
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val loaded = KitanontonProvider().loadLinks(
            "https://kitanonton2.casa/nonton-hold-the-fort-2025-sub-indo/",
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
