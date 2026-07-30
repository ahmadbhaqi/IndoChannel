package com.example

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Opt-in current-catalog checks for the current provider release. */
class ProviderExpansionLiveTest {
    @Test
    fun `moviebox current catalog resolves playback`() = live(MovieboxProvider())

    @Test
    fun `pencurimovie current catalog resolves playback`() = live(PencurimovieProvider())

    @Test
    fun `sarangfilm current catalog resolves playback`() = live(SarangfilmProvider())

    @Test
    fun `nomat current catalog resolves playback`() = live(NomatProvider())

    @Test
    fun `indomax current catalog resolves playback`() = live(IndomaxProvider())

    @Test
    fun `kawanfilm current catalog resolves playback`() = live(KawanfilmProvider())

    @Test
    fun `kuramanime current catalog resolves playback`() = live(KuramanimeProvider())

    @Test
    fun `animasu current catalog resolves playback`() = live(AnimasuProvider())

    private fun live(provider: MainAPI) = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val page = provider.mainPage.firstOrNull()
            ?: error("${provider.name} has no main-page category")
        val catalog = withTimeout(60_000) {
            provider.getMainPage(
                1,
                MainPageRequest(page.name, page.data, page.horizontalImages)
            )
        }?.items?.flatMap { it.list }.orEmpty()
        val item = catalog.firstOrNull()
            ?: error("${provider.name} returned an empty current catalog")
        assertTrue(item.name.isNotBlank(), "${provider.name} returned a blank catalog title")
        assertTrue(
            !item.posterUrl.isNullOrBlank(),
            "${provider.name} returned no current catalog poster"
        )
        val detail = withTimeout(60_000) { provider.load(item.url) }
        assertNotNull(detail, "${provider.name} could not load ${item.url}")
        assertTrue(detail.name.isNotBlank(), "${provider.name} returned a blank detail title")
        assertTrue(
            !detail.posterUrl.isNullOrBlank(),
            "${provider.name} returned no detail poster for ${item.url}"
        )
        val playbackData = when (detail) {
            is MovieLoadResponse -> detail.dataUrl
            is TvSeriesLoadResponse -> detail.episodes.lastOrNull()?.data
            is AnimeLoadResponse -> detail.episodes.values.flatten().lastOrNull()?.data
            else -> null
        } ?: error("${provider.name} returned no playback data for ${item.url}")
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val loaded = withTimeout(150_000) {
            provider.loadLinks(playbackData, false, subtitles::add, links::add)
        }
        val probes = linkedMapOf<String, Int?>()
        for (link in links.take(MAX_MEDIA_PROBES)) {
            val code = runCatching {
                withTimeout(MEDIA_PROBE_TIMEOUT_MILLIS) {
                    app.get(
                        link.url,
                        referer = link.referer,
                        headers = link.headers + if (link.type == ExtractorLinkType.M3U8) {
                            emptyMap()
                        } else {
                            mapOf("Range" to "bytes=0-31")
                        },
                        timeout = MEDIA_PROBE_TIMEOUT_SECONDS
                    ).code
                }
            }.getOrNull()
            probes[link.url.safeHost()] = code
            if (code in 200..299) break
        }

        println(
            "${provider.name} title=${item.name} poster=${item.posterUrl} " +
                "detail=${detail.name} detailPoster=${detail.posterUrl} " +
                "item=${item.url.safeHost()} playback=${playbackData.safeHost()} loaded=$loaded " +
                "linkHosts=${links.map { it.url.safeHost() }} probes=$probes " +
                "subtitles=${subtitles.size}"
        )
        assertTrue(loaded, "${provider.name} did not report verified playback")
        assertTrue(links.isNotEmpty(), "${provider.name} emitted no verified media link")
        assertTrue(
            probes.values.any { it in 200..299 },
            "${provider.name} emitted no reachable media link: $probes"
        )
    }

    private fun String.safeHost(): String = runCatching {
        URI(this).host?.takeIf(String::isNotBlank) ?: "opaque"
    }.getOrDefault("opaque")

    private companion object {
        const val MAX_MEDIA_PROBES = 4
        const val MEDIA_PROBE_TIMEOUT_MILLIS = 20_000L
        const val MEDIA_PROBE_TIMEOUT_SECONDS = 20L
    }
}
