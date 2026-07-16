package com.example

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in end-to-end checks for provider page -> callback resolution. */
class AnimeProviderLiveTest {
    @Test
    fun `animeindo emits extensionless upstream media`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

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
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        val links = mutableListOf<ExtractorLink>()
        val loaded = OploverzProvider().loadLinks(
            "https://oploverz.org/anime/dr-stone-season-4-science-future-episode-26-subtitle-indonesia/",
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
    fun `kuronime emits decrypted kuroplayer hls`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") return@runBlocking

        val links = mutableListOf<ExtractorLink>()
        val loaded = KuronimeProvider().loadLinks(
            "https://kuronime.sbs/nonton-hanazakari-no-kimitachi-e-season-2-episode-4/",
            false,
            {},
            links::add
        )

        assertTrue(loaded, "Kuronime did not report a resolved link")
        assertTrue(
            links.any { it.url.contains(".kuroplayer.xyz/") && it.url.contains(".m3u8") },
            "Kuronime did not emit its decrypted Kuroplayer HLS"
        )
        assertReachable("Kuronime", links)
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
