package com.example

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/**
 * Mirrors the provider test flow used by the CloudStream app:
 * homepage -> three title-word searches -> first three results -> first episode -> loadLinks.
 */
class CloudstreamTesterParityLiveTest {
    @Test
    fun `indomax remains playable through its primary site or exact fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = IndomaxProvider()
        val candidates = provider.search("Supergirl").orEmpty()
            .asSequence()
            .filter { NomatParser.isExactFallbackTitle("Supergirl", it.name) }
            .take(8)
            .toList()
        var selected: Pair<SearchResponse, TvSeriesLoadResponse>? = null
        for (candidate in candidates) {
            val candidateDetail = provider.load(candidate.url) as? TvSeriesLoadResponse
                ?: continue
            if (candidateDetail.year == null || candidateDetail.year == 2015) {
                selected = candidate to candidateDetail
                break
            }
        }
        val (result, detail) = selected
            ?: error("Indomax primary and fallback catalogs missed Supergirl (2015)")
        val firstEpisode = detail.episodes.firstOrNull()
            ?: error("Indomax Supergirl has no real episode")
        if (IndomaxParser.providerPageUrl(result.url, provider.mainUrl) != null) {
            assertTrue(
                firstEpisode.data.contains("/eps/", ignoreCase = true),
                "Indomax selected its View All link instead of an episode: " +
                    firstEpisode.data
            )
        }
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(firstEpisode.data, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Indomax primary/fallback episode returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `moviebox misclassified Alas Roban resolves through an exact fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = MovieboxProvider()
        val result = provider.search("Alas Roban").orEmpty()
            .firstOrNull {
                NomatParser.isExactFallbackTitle("Alas Roban", it.name)
            }
            ?: error("Moviebox Alas Roban regression title is missing")
        val detail = provider.load(result.url)
            ?: error("Moviebox Alas Roban detail is missing")
        val playbackData = when (detail) {
            is MovieLoadResponse -> detail.dataUrl
            is TvSeriesLoadResponse -> detail.episodes.firstOrNull()?.data
            else -> null
        }?.takeIf { it.isNotBlank() }
            ?: error(
                "Moviebox Alas Roban has no playback data; " +
                    "actual=${detail.javaClass.simpleName}:${detail.name}:${detail.year}"
            )
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(playbackData, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Moviebox Alas Roban returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `pencurimovie stale Spider-Verse mirrors resolve through an exact fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = PencurimovieProvider()
        val result = provider.search("Spider-Man:").orEmpty()
            .firstOrNull {
                it.name.contains(
                    "Across the Spider-Verse",
                    ignoreCase = true
                )
            }
            ?: error("Pencurimovie Spider-Verse regression title is missing")
        val detail = provider.load(result.url) as? MovieLoadResponse
            ?: error("Pencurimovie Spider-Verse detail did not load as a movie")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(detail.dataUrl, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Pencurimovie exact fallback returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `pencurimovie Sumpahan Malam Raya resolves after every native mirror fails`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = PencurimovieProvider()
        val result = provider.search("Sumpahan Malam Raya").orEmpty()
            .firstOrNull {
                NomatParser.isExactFallbackTitle("Sumpahan Malam Raya", it.name)
            }
            ?: error("Pencurimovie Sumpahan Malam Raya regression title is missing")
        val detail = provider.load(result.url) as? MovieLoadResponse
            ?: error("Pencurimovie Sumpahan Malam Raya did not load as a movie")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(detail.dataUrl, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Pencurimovie Sumpahan fallback returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `pencurimovie resolves a Malay dub edition through its base title fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = PencurimovieProvider()
        val result = provider.search("Minions").orEmpty()
            .firstOrNull {
                it.name.contains("Minions", ignoreCase = true) &&
                    it.name.contains("MalayDub", ignoreCase = true)
            }
            ?: error("Pencurimovie Minions MalayDub regression title is missing")
        val detail = provider.load(result.url) as? MovieLoadResponse
            ?: error("Pencurimovie Minions MalayDub detail did not load as a movie")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(detail.dataUrl, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Pencurimovie Minions MalayDub returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `pencurimovie numeric title Rumah Sewa RM50 resolves exact playback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val request = NomatFallbackRequest("Rumah Sewa RM50", 2014)
        val provider = PencurimovieProvider()
        val result = provider.search(request.title).orEmpty()
            .firstOrNull {
                NomatParser.isPotentialFallbackTitle(request, it.name)
            }
            ?: error("Pencurimovie Rumah Sewa RM50 (2014) regression title is missing")
        val detail = provider.load(result.url) as? MovieLoadResponse
            ?: error("Pencurimovie Rumah Sewa RM50 (2014) did not load as a movie")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(detail.dataUrl, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Pencurimovie Rumah Sewa RM50 returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `pencurimovie Genesis Paradise Lost resolves after obsolete mirrors`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = PencurimovieProvider()
        val result = provider.search("Genesis: Paradise Lost").orEmpty()
            .firstOrNull {
                NomatParser.isPotentialFallbackTitle(
                    NomatFallbackRequest("Genesis: Paradise Lost", 2017),
                    it.name
                )
            }
            ?: error("Pencurimovie Genesis: Paradise Lost (2017) is missing")
        val detail = provider.load(result.url) as? MovieLoadResponse
            ?: error("Pencurimovie Genesis: Paradise Lost did not load as a movie")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(detail.dataUrl, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Pencurimovie Genesis returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `zoronime special episode resolves after its native mirrors fail`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = ZoronimeProvider()
        val result = provider.search("One Piece: Heroines").orEmpty()
            .firstOrNull {
                NomatParser.isExactFallbackTitle("One Piece: Heroines", it.name)
            }
            ?: error("Zoronime One Piece: Heroines regression title is missing")
        val detail = provider.load(result.url) as? AnimeLoadResponse
            ?: error("Zoronime One Piece: Heroines did not load as anime")
        val playbackData = detail.episodes.values
            .flatten()
            .firstOrNull()
            ?.data
            ?: error("Zoronime One Piece: Heroines has no special episode")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(playbackData, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Zoronime special fallback returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `zoronime Grand Blue Season 3 resolves exact episode playback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = ZoronimeProvider()
        val result = provider.search("Grand Blue Season 3").orEmpty()
            .firstOrNull {
                NomatParser.isExactFallbackTitle("Grand Blue Season 3", it.name)
            }
            ?: error("Zoronime Grand Blue Season 3 regression title is missing")
        val detail = provider.load(result.url) as? AnimeLoadResponse
            ?: error("Zoronime Grand Blue Season 3 did not load as anime")
        val playbackData = detail.episodes.values
            .flatten()
            .firstOrNull()
            ?.data
            ?: error("Zoronime Grand Blue Season 3 has no episode")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(playbackData, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Zoronime Grand Blue Season 3 returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `layarkaca stale Supergirl episodes resolve through an exact fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val targets = setOf(6 to 9, 5 to 16, 4 to 8)
        val provider = LayarKacaProvider()
        val outcomes = mutableMapOf<Pair<Int, Int>, String>()
        targets.forEach { target ->
            withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                val playbackData =
                    "https://tv.nontonfilm.red/eps/" +
                        "supergirl-season-${target.first}-episode-${target.second}/"
                val playbackCoordinate =
                    LayarKacaPlayerParser.episodeCoordinate(playbackData, "")
                if (
                    playbackCoordinate?.season != target.first ||
                    playbackCoordinate.episode != target.second
                ) {
                    outcomes[target] = "wrong playback coordinate=$playbackCoordinate"
                    return@withTimeout
                }
                val detail = provider.load(playbackData)
                val resolvedPlaybackData = (detail as? TvSeriesLoadResponse)
                    ?.episodes
                    ?.singleOrNull { episode ->
                        episode.season == target.first &&
                            episode.episode == target.second
                    }
                    ?.data
                if (resolvedPlaybackData.isNullOrBlank()) {
                    outcomes[target] = "fallback detail or exact episode missing"
                    return@withTimeout
                }
                val links = mutableListOf<ExtractorLink>()
                val loaded = provider.loadLinks(resolvedPlaybackData, false, {}) { link ->
                    assertTrue(
                        link.url.length > 4,
                        "invalid playback URL from LayarKaca: ${link.url}"
                    )
                    links += link
                }
                outcomes[target] = "loaded=$loaded links=${links.size}"
            }
        }

        println("LayarKaca stale Supergirl outcomes=$outcomes")
        assertTrue(
            targets.all { target -> outcomes[target]?.let {
                it.startsWith("loaded=true") && !it.endsWith("links=0")
            } == true },
            "LayarKaca exact fallback did not resolve every stale episode: $outcomes"
        )
    }

    @Test
    fun `layarkaca Toy Boy resolves an exact fallback episode`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = LayarKacaProvider()
        val result = provider.search("Toy Boy").orEmpty()
            .firstOrNull {
                NomatParser.isPotentialFallbackTitle(
                    NomatFallbackRequest("Toy Boy", 2019),
                    it.name
                )
            }
            ?: error("LayarKaca Toy Boy (2019) is missing")
        val detail = provider.load(result.url) as? TvSeriesLoadResponse
            ?: error("LayarKaca Toy Boy did not load as a series")
        val playbackData = detail.episodes.firstOrNull()?.data
            ?: error("LayarKaca Toy Boy has no episode")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(playbackData, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "LayarKaca Toy Boy returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `nomat The Mustang resolves through an exact active mirror`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val request = NomatFallbackRequest("The Mustang", 2019)
        val provider = NomatProvider()
        val result = provider.search(request.title).orEmpty()
            .firstOrNull {
                NomatParser.isPotentialFallbackTitle(request, it.name)
            }
            ?: error("Nomat The Mustang (2019) is missing")
        val detail = provider.load(result.url) as? MovieLoadResponse
            ?: error("Nomat The Mustang (2019) did not load as a movie")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(detail.dataUrl, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "Nomat The Mustang returned loaded=$loaded links=${links.size}"
        )
    }

    @Test
    fun `providers reported by the app satisfy its search and playback contract`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val failures = mutableListOf<String>()
        reportedProviders().forEach { provider ->
            try {
                withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                    verifyProvider(provider, Random(0))
                }
            } catch (error: TimeoutCancellationException) {
                failures += "${provider.name}: timed out"
            } catch (error: Throwable) {
                failures += "${provider.name}: ${error.message ?: error::class.simpleName}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "CloudStream tester parity failures:\n${failures.joinToString("\n")}"
        )
    }

    @Test
    fun `providers survive the official tester concurrent execution model`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val outcomes = supervisorScope {
            reportedProviders().map { provider ->
                async {
                    val error = try {
                        withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                            verifyProvider(provider, Random.Default)
                        }
                        null
                    } catch (error: TimeoutCancellationException) {
                        error
                    } catch (error: Throwable) {
                        error
                    }
                    provider.name to error
                }
            }.awaitAll()
        }
        val failures = outcomes.mapNotNull { (name, error) ->
            error?.let { "$name: ${it.message ?: it::class.simpleName}" }
        }

        assertTrue(
            failures.isEmpty(),
            "Official concurrent tester failures:\n${failures.joinToString("\n")}"
        )
    }

    private suspend fun verifyProvider(provider: MainAPI, random: Random) {
        val page = provider.mainPage.firstOrNull()
            ?: error("has no main-page category")
        val homepage = try {
            provider.getMainPage(
                1,
                MainPageRequest(page.name, page.data, page.horizontalImages)
            )?.items?.flatMap { it.list }.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: NotImplementedError) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }

        val queries = (
            homepage.shuffled(random)
                .take(3)
                .mapNotNull { item -> item.name.firstSearchWord() } +
                listOf("over", "iron", "guy")
            ).take(3)
        val searchResults = queries.firstNotNullOfOrNull { query ->
            try {
                provider.search(query, 1)?.items.orEmpty()
                    .takeIf(List<SearchResponse>::isNotEmpty)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NotImplementedError) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }.orEmpty()
        assertTrue(
            searchResults.isNotEmpty(),
            "did not return search responses for $queries"
        )

        val attempts = mutableListOf<String>()
        val playable = searchResults.take(3).any { result ->
            val detail = provider.load(result.url)
            val playback = when (detail) {
                is AnimeLoadResponse -> {
                    val gotNoEpisodes =
                        detail.episodes.keys.isEmpty() ||
                            detail.episodes.keys.any { key ->
                                detail.episodes[key].isNullOrEmpty()
                            }
                    if (gotNoEpisodes) {
                        null
                    } else {
                        detail.episodes[detail.episodes.keys.firstOrNull()]
                            ?.firstOrNull()
                            ?.data
                            ?.let { data ->
                                PlaybackTarget(
                                    data,
                                    detail.type != com.lagradost.cloudstream3.TvType.CustomMedia
                                )
                            }
                    }
                }

                is MovieLoadResponse -> detail.dataUrl
                    .takeIf { it.isNotBlank() }
                    ?.let { data ->
                        PlaybackTarget(
                            data,
                            detail.type != com.lagradost.cloudstream3.TvType.CustomMedia
                        )
                    }

                is TvSeriesLoadResponse -> detail.episodes
                    .takeIf { it.isNotEmpty() }
                    ?.firstOrNull()
                    ?.data
                    ?.let { data ->
                        PlaybackTarget(
                            data,
                            detail.type != com.lagradost.cloudstream3.TvType.CustomMedia
                        )
                    }

                is LiveStreamLoadResponse -> PlaybackTarget(
                    detail.dataUrl,
                    detail.type != com.lagradost.cloudstream3.TvType.CustomMedia
                )

                else -> null
            }
            if (playback == null) {
                attempts += "${result.name}: no playback data"
                false
            } else if (!playback.shouldLoadLinks) {
                attempts += "${result.name}: custom media link test skipped"
                true
            } else {
                val links = mutableListOf<ExtractorLink>()
                val subtitles = mutableListOf<SubtitleFile>()
                val loaded = provider.loadLinks(
                    playback.data,
                    false,
                    subtitles::add,
                ) { link ->
                    assertTrue(
                        link.url.length > 4,
                        "${provider.name} emitted an invalid URL: ${link.url}"
                    )
                    links += link
                }
                attempts += "${result.name}: loaded=$loaded links=${links.size}"
                assertTrue(
                    loaded,
                    "returns false on loadLinks() with ${links.size} links loaded; $attempts"
                )
                links.isNotEmpty()
            }
        }

        println("${provider.name} queries=$queries attempts=$attempts")
        assertTrue(playable, "failed its first three search results: $attempts")
    }

    private fun String.firstSearchWord(): String? =
        trim().split(Regex("""\s+""")).firstOrNull { it.isNotBlank() }

    private data class PlaybackTarget(
        val data: String,
        val shouldLoadLinks: Boolean
    )

    private fun reportedProviders(): List<MainAPI> = listOf(
        AnimasuProvider(),
        IndomaxProvider(),
        KuramanimeProvider(),
        LayarKacaProvider(),
        MovieboxProvider(),
        NomatProvider(),
        PencurimovieProvider(),
        ZoronimeProvider()
    )

    private companion object {
        const val PROVIDER_TIMEOUT_MILLIS = 180_000L
    }
}
