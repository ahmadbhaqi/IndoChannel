package com.example

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull

class RebahinLiveTest {
    @Test
    fun `rebahin current catalog detail and playback remain available`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = RebahinProvider()
        val pageData = provider.mainPage.first()
        val request = MainPageRequest(pageData.name, pageData.data, pageData.horizontalImages)
        val catalog = withTimeout(45_000) {
            provider.getMainPage(1, request)
        }.items.flatMap { it.list }
        assertNotNull(catalog.firstOrNull(), "Rebahin returned an empty current catalog")

        var playableSample: String? = null
        val attempts = mutableListOf<String>()
        for (sample in catalog.take(8)) {
            val detail = withTimeoutOrNull(35_000) { provider.load(sample.url) } ?: continue
            if (detail.name.isBlank()) continue

            val links = mutableListOf<ExtractorLink>()
            val loaded = withTimeoutOrNull(55_000) {
                provider.loadLinks(sample.url, false, {}, links::add)
            } ?: false
            val probes = linkedMapOf<String, Int?>()
            for (link in links.take(6)) {
                val code = withTimeoutOrNull(25_000) {
                    runCatching {
                        app.get(
                            link.url,
                            referer = link.referer,
                            headers = link.headers + ("Range" to "bytes=0-31"),
                            timeout = 25L
                        ).code
                    }.getOrNull()
                }
                probes[link.url] = code
                if (code in 200..299) break
            }
            attempts += "${sample.name}: loaded=$loaded links=${links.size} probes=$probes"
            if (loaded && probes.values.any { it in 200..299 }) {
                playableSample = sample.name
                break
            }
        }
        println("Rebahin attempts=$attempts playable=$playableSample")
        assertNotNull(
            playableSample,
            "No playable Rebahin item was found among current catalog samples: $attempts"
        )
    }
}
