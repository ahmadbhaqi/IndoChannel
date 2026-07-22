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
    fun `idlix exposes every Young Sheldon season and episode`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = IdlixProvider()
        val detail = withTimeout(90_000) {
            provider.load("${provider.mainUrl}/series/young-sheldon-2017")
        } as? TvSeriesLoadResponse
        val counts = detail?.episodes.orEmpty()
            .groupingBy { it.season }
            .eachCount()
            .toSortedMap(compareBy(nullsFirst()) { it })

        assertTrue(
            counts == mapOf<Int?, Int>(
                1 to 22,
                2 to 22,
                3 to 21,
                4 to 18,
                5 to 22,
                6 to 22,
                7 to 14
            ),
            "IDLIX Young Sheldon episode matrix was incomplete: $counts"
        )
        val expectedBySeason = mapOf(1 to 22, 2 to 22, 3 to 21, 4 to 18, 5 to 22, 6 to 22, 7 to 14)
        expectedBySeason.forEach { (season, expectedCount) ->
            val actualEpisodes = detail?.episodes.orEmpty()
                .filter { it.season == season }
                .mapNotNull { it.episode }
                .toSet()
            assertTrue(
                actualEpisodes == (1..expectedCount).toSet(),
                "IDLIX Young Sheldon season $season episode numbers were incomplete: $actualEpisodes"
            )
        }
    }

    @Test
    fun `idlix Young Sheldon episode keeps Indonesian subtitles with its stream`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = IdlixProvider()
        val detail = withTimeout(90_000) {
            provider.load("${provider.mainUrl}/series/young-sheldon-2017")
        } as? TvSeriesLoadResponse
        val episode = detail?.episodes.orEmpty().firstOrNull {
            it.season == 1 && it.episode == 1
        }
        assertTrue(episode != null, "IDLIX Young Sheldon S1E1 was missing")

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        val loaded = withTimeout(105_000) {
            provider.loadLinks(episode!!.data, false, subtitles::add, links::add)
        }

        assertTrue(
            loaded && links.any(::isSignedPlaybackLink),
            "IDLIX Young Sheldon S1E1 emitted no signed playback stream"
        )
        assertTrue(
            subtitles.isNotEmpty() && subtitles.all { it.lang == "id" },
            "IDLIX Young Sheldon S1E1 subtitles were missing or not Indonesian: " +
                subtitles.map { it.lang }
        )
        val subtitleCodes = subtitles.map { probeSubtitle(it) }
        assertTrue(
            subtitleCodes.any { it in 200..299 },
            "IDLIX Young Sheldon S1E1 subtitle URLs were unreachable: $subtitleCodes"
        )
    }

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
    fun `idlix current search detail signed media and subtitle playback remain available`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = IdlixProvider()
        val searchSeed = "Inception"
        val searchResults = withTimeout(45_000) {
            provider.search(searchSeed)
        }.filter { it.url.startsWith("${provider.mainUrl}/movie/") }
            .distinctBy { it.url }

        assertTrue(
            searchResults.isNotEmpty(),
            "IDLIX search returned no movie results for the non-dashboard query $searchSeed"
        )

        val attempts = mutableListOf<String>()
        var resolvedPlaybackLink: ExtractorLink? = null
        var resolvedSubtitles = emptyList<SubtitleFile>()
        for (candidate in searchResults.take(4)) {
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
                "subtitles=${subtitles.size} " +
                "failure=${failure?.message}"
            if (loaded && resolvedPlaybackLink != null && subtitles.isNotEmpty()) {
                resolvedSubtitles = subtitles.toList()
                break
            }
        }

        assertTrue(
            resolvedPlaybackLink != null,
            "IDLIX did not resolve a signed, validated master playlist: $attempts"
        )
        assertTrue(
            resolvedSubtitles.isNotEmpty(),
            "IDLIX emitted no subtitles for the search-only movie flow: $attempts"
        )
        assertTrue(
            resolvedSubtitles.all { it.lang == "id" },
            "IDLIX emitted non-Indonesian subtitle tracks: ${resolvedSubtitles.map { it.lang }}"
        )
        assertTrue(
            resolvedSubtitles.all { subtitle ->
                val headers = subtitle.headers.orEmpty()
                headers["User-Agent"].isNullOrBlank().not() &&
                    headers["Referer"]?.startsWith("${provider.mainUrl}/movie/") == true &&
                    headers["Origin"] == provider.mainUrl
            },
            "IDLIX subtitles did not carry the signed playback request context"
        )
        val subtitleCodes = resolvedSubtitles.map { subtitle -> probeSubtitle(subtitle) }
        assertTrue(
            subtitleCodes.any { it in 200..299 },
            "IDLIX emitted subtitles, but none of their signed URLs were reachable: $subtitleCodes"
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
        val mediaCodes: List<Int?>,
        val audioCodes: List<Int?>,
        val audioDeclared: Boolean
    ) {
        fun isReachable(): Boolean =
            playlistCode in 200..299 &&
                isHls &&
                mediaCodes.isNotEmpty() &&
                mediaCodes.all { it in 200..299 } &&
                (!audioDeclared ||
                    (audioCodes.isNotEmpty() && audioCodes.all { it in 200..299 }))
    }

    private enum class ProbeBranch { MEDIA, AUDIO }
    private data class PlaylistLevelProbe(
        val code: Int?,
        val isHls: Boolean,
        val audioDeclared: Boolean
    )

    private suspend fun probeFirstMediaObject(
        playlistUrl: String,
        referer: String,
        headers: Map<String, String>
    ): PlaylistProbe {
        val mediaCodes = mutableListOf<Int?>()
        val audioCodes = mutableListOf<Int?>()
        val root = probePlaylistBranch(
            masterUrl = playlistUrl,
            currentUrl = playlistUrl,
            referer = referer,
            headers = headers,
            depth = 0,
            branch = null,
            mediaCodes = mediaCodes,
            audioCodes = audioCodes
        )
        return PlaylistProbe(
            root.code,
            root.isHls,
            mediaCodes,
            audioCodes,
            root.audioDeclared
        )
    }

    private suspend fun probePlaylistBranch(
        masterUrl: String,
        currentUrl: String,
        referer: String,
        headers: Map<String, String>,
        depth: Int,
        branch: ProbeBranch?,
        mediaCodes: MutableList<Int?>,
        audioCodes: MutableList<Int?>
    ): PlaylistLevelProbe {
        val playlist = try {
            withTimeout(30_000) {
                app.get(
                    currentUrl,
                    referer = referer,
                    headers = headers,
                    timeout = 30L
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return PlaylistLevelProbe(null, false, false)
        when (branch) {
            ProbeBranch.MEDIA -> mediaCodes += playlist.code
            ProbeBranch.AUDIO -> audioCodes += playlist.code
            null -> Unit
        }
        val body = playlist.text
        val isHls = body.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .equals("#EXTM3U", ignoreCase = true)
        if (!isHls) return PlaylistLevelProbe(playlist.code, false, false)
        val audioReferences = Regex(
            "(?i)#EXT-X-MEDIA:[^\\n]*TYPE=AUDIO[^\\n]*URI=\"([^\"]+)\""
        ).findAll(body)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .take(1)
            .toList()
        val integratedAudio = body.lineSequence().any { line ->
            line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) &&
                Regex("(?i)CODECS=\"[^\"]*(?:mp4a|ac-3|ec-3|opus|vorbis)")
                    .containsMatchIn(line)
        }
        val initReference = Regex("(?i)#EXT-X-MAP:[^\\n]*URI=\"([^\"]+)\"")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
        val segmentReference = body.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && !it.startsWith('#') }
        audioReferences.forEach { reference ->
            probeChildReference(
                masterUrl,
                playlist.url,
                reference,
                referer,
                headers,
                depth,
                ProbeBranch.AUDIO,
                mediaCodes,
                audioCodes
            )
        }
        listOfNotNull(initReference, segmentReference).distinct().forEach { reference ->
            probeChildReference(
                masterUrl,
                playlist.url,
                reference,
                referer,
                headers,
                depth,
                branch ?: ProbeBranch.MEDIA,
                mediaCodes,
                audioCodes
            )
        }
        return PlaylistLevelProbe(
            playlist.code,
            true,
            audioReferences.isNotEmpty() || integratedAudio
        )
    }

    private suspend fun probeChildReference(
        masterUrl: String,
        parentUrl: String,
        reference: String,
        referer: String,
        headers: Map<String, String>,
        depth: Int,
        branch: ProbeBranch,
        mediaCodes: MutableList<Int?>,
        audioCodes: MutableList<Int?>
    ) {
        val resolved = URI(parentUrl).resolve(reference).toASCIIString()
        val childUrl = IdlixParser.playbackRequestUrl(masterUrl, resolved)
        val looksLikePlaylist = URI(childUrl).rawPath.orEmpty()
            .matches(Regex("(?i).*\\.(?:json|m3u8)$"))
        if (looksLikePlaylist && depth < 2) {
            val child = probePlaylistBranch(
                masterUrl,
                childUrl,
                referer,
                headers,
                depth + 1,
                branch,
                mediaCodes,
                audioCodes
            )
            if (child.code == null || !child.isHls) {
                when (branch) {
                    ProbeBranch.MEDIA -> mediaCodes += null
                    ProbeBranch.AUDIO -> audioCodes += null
                }
            }
            return
        }
        val code = try {
            withTimeout(30_000) {
                app.get(
                    childUrl,
                    referer = referer,
                    headers = headers + ("Range" to "bytes=0-31"),
                    timeout = 30L
                ).code
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        when (branch) {
            ProbeBranch.MEDIA -> mediaCodes += code
            ProbeBranch.AUDIO -> audioCodes += code
        }
    }

    private suspend fun probeSubtitle(subtitle: SubtitleFile): Int? {
        val headers = subtitle.headers.orEmpty()
        return try {
            withTimeout(30_000) {
                app.get(
                    subtitle.url,
                    referer = headers["Referer"].orEmpty(),
                    headers = headers,
                    timeout = 30L
                ).code
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }
}
