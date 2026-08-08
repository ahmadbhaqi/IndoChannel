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
    fun `nomat current JAV results resolve verified playback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = NomatProvider()
        val items = withTimeout(60_000) {
            provider.search("masturbating")
        }.take(MAX_NOMAT_JAV_PROBES)
        assertTrue(
            items.size >= MIN_NOMAT_JAV_PROBES,
            "Nomat returned too few current JAV regression items: ${items.map { it.name }}"
        )

        val failures = mutableListOf<String>()
        items.forEach { item ->
            val outcome = runCatching {
                withTimeout(180_000) {
                    val detail = provider.load(item.url)
                        ?: error("detail did not load")
                    val playbackData = when (detail) {
                        is MovieLoadResponse -> detail.dataUrl
                        is TvSeriesLoadResponse -> detail.episodes.lastOrNull()?.data
                        is AnimeLoadResponse -> detail.episodes.values.flatten().lastOrNull()?.data
                        else -> null
                    } ?: error("detail returned no playback data")
                    val links = mutableListOf<ExtractorLink>()
                    val loaded = provider.loadLinks(playbackData, false, {}, links::add)
                    if (!loaded || links.isEmpty()) {
                        error("loaded=$loaded links=${links.size}")
                    }
                    val reachable = links.take(MAX_MEDIA_PROBES).any { link ->
                        runCatching {
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
                        }.getOrNull() in 200..299
                    }
                    if (!reachable) error("no reachable link from ${links.map { it.url.safeHost() }}")
                    "links=${links.map { it.url.safeHost() }}"
                }
            }
            println("Nomat JAV title=${item.name} outcome=${outcome.getOrNull()}")
            outcome.exceptionOrNull()?.let { error ->
                failures += "${item.name}: ${error.message ?: error::class.simpleName}"
            }
        }

        assertTrue(failures.isEmpty(), "Nomat JAV playback failures:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `indomax current catalog resolves playback`() = live(IndomaxProvider())

    @Test
    fun `kawanfilm current catalog resolves playback`() = live(KawanfilmProvider())

    @Test
    fun `pusatfilm current posters use a reachable independent image host`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = PusatfilmProvider()
        val page = provider.mainPage.first()
        val posters = withTimeout(60_000) {
            provider.getMainPage(
                1,
                MainPageRequest(page.name, page.data, page.horizontalImages)
            )
        }?.items?.flatMap { row -> row.list }
            .orEmpty()
            .mapNotNull { item -> item.posterUrl }
            .distinct()
            .take(MAX_POSTER_PROBES)
        assertTrue(posters.isNotEmpty(), "Pusatfilm returned no current catalog posters")

        val providerImageHosts = setOf(
            "v4.pusatfilm21info.com",
            "v3.pusatfilm21info.com",
            "cdn.pusatfilm21info.com"
        )
        val probes = posters.associateWith { poster ->
            val host = runCatching { URI(poster).host?.lowercase() }.getOrNull()
            assertTrue(
                host !in providerImageHosts,
                "Pusatfilm exposed an AdGuard-broken poster host: $poster"
            )
            runCatching {
                withTimeout(MEDIA_PROBE_TIMEOUT_MILLIS) {
                    app.get(
                        poster,
                        headers = mapOf("Range" to "bytes=0-31"),
                        timeout = MEDIA_PROBE_TIMEOUT_SECONDS
                    ).code
                }
            }.getOrNull()
        }

        println("Pusatfilm poster probes=$probes")
        assertTrue(
            probes.values.all { code -> code in 200..299 },
            "Pusatfilm emitted an unreachable current poster: $probes"
        )
    }

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
        const val MAX_POSTER_PROBES = 6
        const val MAX_NOMAT_JAV_PROBES = 5
        const val MIN_NOMAT_JAV_PROBES = 3
        const val MEDIA_PROBE_TIMEOUT_MILLIS = 20_000L
        const val MEDIA_PROBE_TIMEOUT_SECONDS = 20L
    }
}
