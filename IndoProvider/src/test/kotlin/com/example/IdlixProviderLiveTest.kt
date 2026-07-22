package com.example

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

class IdlixProviderLiveTest {
    @Test
    fun `idlix current movie matrix reports every playback outcome`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = IdlixProvider()
        val moviePage = provider.mainPage.first { it.data == "movie" }
        val catalog = withTimeout(45_000) {
            provider.getMainPage(
                1,
                MainPageRequest(moviePage.name, moviePage.data, moviePage.horizontalImages)
            )
        }.items.flatMap { it.list }
            .filter { it.url.startsWith("${provider.mainUrl}/movie/") }
            .distinctBy { it.url }
            .take(6)

        var playable = 0
        for (candidate in catalog) {
            val started = System.nanoTime()
            val detail = withTimeoutOrNull(45_000) { provider.load(candidate.url) } as? MovieLoadResponse
            val links = mutableListOf<ExtractorLink>()
            val loaded = detail?.let {
                withTimeoutOrNull(105_000) {
                    provider.loadLinks(it.dataUrl, false, {}, links::add)
                }
            } == true
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            if (loaded && links.any(::isSignedPlaybackLink)) playable++
            println(
                "IDLIX_MATRIX title=${candidate.name} loaded=$loaded links=${links.size} " +
                    "audio=${links.maxOfOrNull { it.audioTracks.size } ?: 0} elapsedMs=$elapsedMs"
            )
        }

        assertTrue(catalog.size >= 4, "IDLIX current catalog was too small for a playback matrix")
        assertTrue(
            playable == catalog.size,
            "IDLIX current movie matrix resolved $playable of ${catalog.size} signed playbacks"
        )
    }

    @Test
    fun `idlix current search detail and signed av playback remain available`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = IdlixProvider()
        val moviePage = provider.mainPage.first { it.data == "movie" }
        val catalog = withTimeout(45_000) {
            provider.getMainPage(
                1,
                MainPageRequest(moviePage.name, moviePage.data, moviePage.horizontalImages)
            )
        }.items
            .flatMap { it.list }
            .filter { it.url.startsWith("${provider.mainUrl}/movie/") }
            .distinctBy { it.url }

        assertTrue(catalog.isNotEmpty(), "IDLIX returned an empty current movie catalog")

        val searchSeed = catalog.first()
        val searchResults = withTimeout(45_000) {
            provider.search(searchSeed.name)
        }.filter { it.url.startsWith("${provider.mainUrl}/movie/") }

        assertTrue(
            searchResults.isNotEmpty(),
            "IDLIX search returned no movie results for a title from its current catalog"
        )

        val attempts = mutableListOf<String>()
        var resolvedPlaybackLink: ExtractorLink? = null
        for (candidate in (searchResults + catalog).distinctBy { it.url }.take(4)) {
            val detail = try {
                withTimeoutOrNull(45_000) { provider.load(candidate.url) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                attempts += "${candidate.name}: detail=${error::class.simpleName}:${error.message}"
                null
            } as? MovieLoadResponse
            if (detail == null) {
                attempts += "${candidate.name}: detail was not a movie response"
                continue
            }

            val links = mutableListOf<ExtractorLink>()
            val subtitles = mutableListOf<SubtitleFile>()
            var failure: Throwable? = null
            val loaded = try {
                withTimeout(105_000) {
                    provider.loadLinks(detail.dataUrl, false, subtitles::add, links::add)
                }
            } catch (error: TimeoutCancellationException) {
                failure = error
                false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failure = error
                false
            }

            resolvedPlaybackLink = links.firstOrNull(::isSignedPlaybackLink)
            attempts += "${candidate.name}: loaded=$loaded links=${links.size} " +
                "audioTracks=${links.maxOfOrNull { it.audioTracks.size } ?: 0} " +
                "failure=${failure?.message}"
            if (loaded && resolvedPlaybackLink != null) break
        }

        assertTrue(
            resolvedPlaybackLink != null,
            "IDLIX did not resolve a signed, validated master playlist: $attempts"
        )
        val playback = resolvedPlaybackLink
        val videoProbe = playback?.let {
            probeFirstMediaObject(it.url, it.referer, it.headers)
        }
        val audioProbes = playback?.let { link ->
            link.audioTracks.map { audio ->
                probeFirstMediaObject(audio.url, link.referer, audio.headers.orEmpty())
            }
        }.orEmpty()
        assertTrue(
            videoProbe?.isReachable() == true &&
                audioProbes.isNotEmpty() &&
                audioProbes.all(PlaylistProbe::isReachable),
            "IDLIX emitted playlists whose init/first segments were not authorized: " +
                "video=$videoProbe audio=$audioProbes"
        )
    }

    @Test
    fun `idlix current series episode resolves signed av playback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = IdlixProvider()
        val seriesPage = provider.mainPage.first { it.data == "series" }
        val catalog = withTimeout(45_000) {
            provider.getMainPage(
                1,
                MainPageRequest(seriesPage.name, seriesPage.data, seriesPage.horizontalImages)
            )
        }.items
            .flatMap { it.list }
            .filter { it.url.startsWith("${provider.mainUrl}/series/") }
            .distinctBy { it.url }

        assertTrue(catalog.isNotEmpty(), "IDLIX returned an empty current series catalog")

        val attempts = mutableListOf<String>()
        var resolvedPlaybackLink: ExtractorLink? = null
        var playbackAttempts = 0
        seriesLoop@ for (candidate in catalog.take(4)) {
            val detail = try {
                withTimeoutOrNull(60_000) { provider.load(candidate.url) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                attempts += "${candidate.name}: detail=${error::class.simpleName}:${error.message}"
                null
            } as? TvSeriesLoadResponse
            if (detail == null || detail.episodes.isEmpty()) {
                attempts += "${candidate.name}: no published playable episodes"
                continue
            }

            for (episode in detail.episodes.take(2)) {
                if (playbackAttempts++ >= 4) break@seriesLoop
                val links = mutableListOf<ExtractorLink>()
                var failure: Throwable? = null
                val loaded = try {
                    withTimeout(105_000) {
                        provider.loadLinks(episode.data, false, {}, links::add)
                    }
                } catch (error: TimeoutCancellationException) {
                    failure = error
                    false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failure = error
                    false
                }
                resolvedPlaybackLink = links.firstOrNull(::isSignedPlaybackLink)
                attempts += "${candidate.name} S${episode.season}E${episode.episode}: " +
                    "loaded=$loaded links=${links.size} " +
                    "audioTracks=${links.maxOfOrNull { it.audioTracks.size } ?: 0} " +
                    "failure=${failure?.message}"
                if (loaded && resolvedPlaybackLink != null) break@seriesLoop
            }
        }

        assertTrue(
            resolvedPlaybackLink != null,
            "IDLIX did not resolve a current series episode with a signed master playlist: $attempts"
        )
    }

    private fun isSignedPlaybackLink(link: ExtractorLink): Boolean =
        IdlixParser.isTrustedPlaybackAsset(link.url) &&
            hasCurrentSignature(link.url) &&
            link.audioTracks.isNotEmpty() &&
            link.audioTracks.all { audio ->
                IdlixParser.isTrustedPlaybackAsset(audio.url) &&
                    hasCurrentSignature(audio.url)
            } &&
            link.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8

    private fun hasCurrentSignature(raw: String): Boolean = runCatching {
        URI(raw).rawQuery.orEmpty().split('&').any { parameter ->
            parameter.substringBefore('=') == "t" && parameter.substringAfter('=', "").isNotBlank()
        }
    }.getOrDefault(false)

    private data class PlaylistProbe(
        val playlistCode: Int?,
        val isHls: Boolean,
        val childCodes: Map<String, Int?>
    ) {
        fun isReachable(): Boolean =
            playlistCode in 200..299 &&
                isHls &&
                childCodes.isNotEmpty() &&
                childCodes.values.all { it in 200..299 }
    }

    private suspend fun probeFirstMediaObject(
        playlistUrl: String,
        referer: String,
        headers: Map<String, String>
    ): PlaylistProbe {
        val playlist = runCatching {
            withTimeout(30_000) {
                app.get(
                    playlistUrl,
                    referer = referer,
                    headers = headers,
                    timeout = 30L
                )
            }
        }.getOrNull() ?: return PlaylistProbe(null, false, emptyMap())
        val body = playlist.text
        val isHls = body.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .equals("#EXTM3U", ignoreCase = true)
        if (!isHls) return PlaylistProbe(playlist.code, false, emptyMap())
        val initReference = Regex("(?i)#EXT-X-MAP:[^\\n]*URI=\"([^\"]+)\"")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
        val segmentReference = body.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && !it.startsWith('#') }
        val childCodes = listOfNotNull(initReference, segmentReference)
            .distinct()
            .associate { reference ->
                val childUrl = URI(playlist.url).resolve(reference).toASCIIString()
                val childCode = runCatching {
                    withTimeout(30_000) {
                        app.get(
                            childUrl,
                            referer = referer,
                            headers = headers + ("Range" to "bytes=0-31"),
                            timeout = 30L
                        ).code
                    }
                }.getOrNull()
                childUrl to childCode
            }
        return PlaylistProbe(playlist.code, true, childCodes)
    }
}
