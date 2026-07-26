package com.example

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        val detail = withTimeout(60_000) { provider.load(item.url) }
        assertNotNull(detail, "${provider.name} could not load ${item.url}")
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

        println(
            "${provider.name} item=${item.url} playback=$playbackData loaded=$loaded " +
                "links=${links.map { it.url }} subtitles=${subtitles.size}"
        )
        assertTrue(loaded, "${provider.name} did not report verified playback")
        assertTrue(links.isNotEmpty(), "${provider.name} emitted no verified media link")
    }
}
