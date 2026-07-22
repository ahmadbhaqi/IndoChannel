package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in smoke tests against current active provider pages and their real players. */
class IndoxxiLayarKacaLiveTest {
    @Test
    fun `indoxxi emits a current concrete mirror`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        verify(
            provider = IndoxxiProvider(),
            pageUrl = "https://filmbioskop21.lk21.in.net/" +
                "nonton-film-golden-kamuy-the-abashiri-prison-raid-lk21-2026/",
            probeMedia = false
        )
    }

    @Test
    fun `indoxxi emits a current Indonesia category movie mirror`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        verify(
            provider = IndoxxiProvider(),
            pageUrl = "https://filmbioskop21.lk21.in.net/nonton-film-mothernet-lk21-2026/",
            probeMedia = false
        )
    }

    @Test
    fun `indoxxi exposes the trusted Gofile fallback on an older Indonesia movie`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val pageUrl =
            "https://filmbioskop21.lk21.in.net/nonton-film-sweet-dreams-lk21-2023/"
        val fetch = withTimeout(45_000) {
            app.get(pageUrl, timeout = 45L)
        }
        val candidates = ProviderHtmlParser.downloadCandidateUrls(
            fetch.document,
            fetch.url
        )
        assertTrue(
            candidates.any { it.startsWith("https://gofile.io/d/") },
            "Indoxxi did not expose the Sweet Dreams Gofile fallback: $candidates"
        )
        // Playback is intentionally not asserted: Gofile's current public web
        // flow creates a guest account and requires Bearer/X-Website-Token.
    }

    @Test
    fun `layarkaca emits a current concrete server`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        verify(
            LayarKacaProvider(),
            "https://tv.nontonfilm.red/evil-dead-burn-2026/",
            probeMedia = false
        )
    }

    @Test
    fun `layarkaca resolves the current Firestream fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        repeat(2) { attempt ->
            val links = verify(
                LayarKacaProvider(),
                "https://tv.nontonfilm.red/scary-movie-2026/",
                probeMedia = false
            )
            val firestream = links.firstOrNull { link ->
                runCatching {
                    val uri = URI(link.url)
                    uri.scheme.equals("https", ignoreCase = true) &&
                        (uri.host.equals("firestream.to", ignoreCase = true) ||
                            uri.host.orEmpty().endsWith(".firestream.to", ignoreCase = true))
                }.getOrDefault(false)
            }
            assertTrue(
                firestream != null,
                "LayarKaca attempt ${attempt + 1} did not emit the Firestream fallback: " +
                    links.map { it.url }
            )
            val code = firestream?.let { link ->
                runCatching {
                    withTimeout(30_000) {
                        app.get(
                            link.url,
                            referer = link.referer,
                            headers = link.headers + ("Range" to "bytes=0-31"),
                            timeout = 30L
                        ).code
                    }
                }.getOrNull()
            }
            assertTrue(
                code in 200..299,
                "LayarKaca attempt ${attempt + 1} emitted an unreachable " +
                    "Firestream media URL (HTTP $code)"
            )
        }
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
