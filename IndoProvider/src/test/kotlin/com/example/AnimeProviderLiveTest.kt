package com.example

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in end-to-end checks for provider page -> callback resolution. */
class AnimeProviderLiveTest {
    @Test
    fun `animeindo emits extensionless upstream media`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val links = mutableListOf<ExtractorLink>()
        val loaded = AnimeindoProvider().loadLinks(
            "https://anime-indo.lol/dogulwang-episode-2/",
            false,
            {},
            links::add
        )

        assertTrue(loaded, "Animeindo did not report a resolved link")
        assertTrue(
            links.any { it.url.contains("/play.php?") || it.url.contains("/videoplayback?") },
            "Animeindo did not emit its current XtWap/Blogger media"
        )
        assertReachable("Animeindo", links)
    }

    @Test
    fun `oploverz resolves current blogger rpc`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val pageUrl = "https://oploverz.org/anime/dr-stone-season-4-science-future-episode-26-subtitle-indonesia/"
        val links = mutableListOf<ExtractorLink>()
        val loaded = OploverzProvider().loadLinks(
            pageUrl,
            false,
            {},
            links::add
        )

        assertTrue(loaded, "Oploverz did not report a resolved link")
        assertTrue(
            links.any { it.url.contains("googlevideo.com/videoplayback?") },
            "Oploverz did not turn the WcwnYd response into a Blogger video"
        )
        assertReachable("Oploverz", links)
    }

    @Test
    fun `samehadaku resolves a current catalog episode`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        assertCurrentCatalogPlayable(SamehadakuProvider())
    }

    @Test
    fun `anoboy resolves a current catalog episode`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        assertCurrentCatalogPlayable(AnoboyProvider())
    }

    @Test
    fun `otakudesu resolves a current catalog episode`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        assertCurrentCatalogPlayable(OtakudesuProvider())
    }

    @Test
    fun `zoronime resolves a current catalog episode`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        assertCurrentCatalogPlayable(ZoronimeProvider())
    }

    @Test
    fun `kuronime emits reachable media after mirror fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val links = mutableListOf<ExtractorLink>()
        val loaded = KuronimeProvider().loadLinks(
            "https://kuronime.sbs/nonton-hanazakari-no-kimitachi-e-season-2-episode-4/",
            false,
            {},
            links::add
        )

        assertTrue(loaded, "Kuronime did not report a resolved link")
        assertTrue(links.isNotEmpty(), "Kuronime emitted no media after trying its mirrors")
        assertReachable("Kuronime", links)
    }

    private suspend fun assertCurrentCatalogPlayable(provider: MainAPI) {
        val page = provider.mainPage.firstOrNull()
            ?: error("${provider.name} has no main-page category")
        val response = withTimeout(45_000) {
            provider.getMainPage(
                1,
                MainPageRequest(page.name, page.data, page.horizontalImages)
            )
        }
        val item = response?.items
            ?.flatMap { it.list }
            .orEmpty()
            .firstOrNull { it.url.startsWith("http") }
            ?: error("${provider.name} returned an empty current catalog")
        val links = mutableListOf<ExtractorLink>()
        val detail = withTimeout(45_000) { provider.load(item.url) }
        val playbackData = when (detail) {
            is AnimeLoadResponse -> detail.episodes.values.flatten()
                .maxByOrNull { it.episode ?: Int.MIN_VALUE }
                ?.data
            is TvSeriesLoadResponse -> detail.episodes
                .maxByOrNull { it.episode ?: Int.MIN_VALUE }
                ?.data
            else -> null
        } ?: item.url
        println("${provider.name} current=${item.url} playback=$playbackData")
        val loaded = try {
            withTimeout(120_000) {
                provider.loadLinks(playbackData, false, {}, links::add)
            }
        } catch (error: TimeoutCancellationException) {
            println("${provider.name} timed out with links=${links.map { it.url }}")
            throw error
        }

        println("${provider.name} current=${item.url} loaded=$loaded links=${links.map { it.url }}")
        assertTrue(loaded, "${provider.name} did not resolve its current catalog episode: ${item.url}")
        assertTrue(links.isNotEmpty(), "${provider.name} emitted no media for ${item.url}")
        assertReachable(provider.name, links)
    }

    private suspend fun assertReachable(label: String, links: List<ExtractorLink>) {
        val probes = mutableMapOf<String, Int?>()
        for (link in links) {
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
            probes[link.url] = code
            if (code != null && code in 200..299) break
        }
        println("$label probes=$probes")
        assertTrue(probes.values.any { it in 200..299 }, "$label emitted no reachable media URL: $probes")
    }
}
