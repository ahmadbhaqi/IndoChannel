package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Film21LayarKacaLiveTest {
    @Test
    fun `film21 resolves a current Indonesia movie`() = verify(Film21Provider(), "Indonesia")

    @Test
    fun `layarkaca resolves a current native catalog movie`() = verify(LayarKacaProvider(fallbackProviderFactory = { emptyList() }), "Action")

    private fun verify(provider: MainAPI, category: String) = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }
        val row = provider.mainPage.first { it.name == category }
        val item = withTimeout(45_000) {
            provider.getMainPage(1, MainPageRequest(row.name, row.data, row.horizontalImages))
        }?.items?.flatMap { it.list }?.firstOrNull()
        assertNotNull(item, "${provider.name} returned an empty current $category catalog")
        assertTrue(item.url.startsWith(provider.mainUrl), "${provider.name} returned a foreign catalog item")
        val detail = withTimeout(45_000) { provider.load(item.url) }
        val data = when (detail) {
            is MovieLoadResponse -> detail.dataUrl
            is TvSeriesLoadResponse -> detail.episodes.firstOrNull()?.data
            else -> null
        }
        assertNotNull(data, "${provider.name} returned no current playback data")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(150_000) { provider.loadLinks(data, false, {}, links::add) }
        assertTrue(loaded && links.isNotEmpty(), "${provider.name} emitted no current media")
        val probes = links.take(4).map { link ->
            runCatching {
                withTimeout(20_000) {
                    app.get(
                        link.url,
                        referer = link.referer,
                        headers = link.headers + if (link.type == ExtractorLinkType.M3U8) {
                            emptyMap()
                        } else {
                            mapOf("Range" to "bytes=0-31")
                        },
                        timeout = 20L
                    ).code
                }
            }.getOrNull()
        }
        assertTrue(probes.any { it in 200..299 }, "${provider.name} emitted no reachable media: $probes")
    }
}
