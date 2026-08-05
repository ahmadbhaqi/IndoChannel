package com.example

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI
import javax.net.ssl.SSLException
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
    fun `pusatfilm discovery fallback recovers from primary tls failure`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val providerHost = "v4.pusatfilm21info.com"
        val discoveryHost = "pusatfilm.id"
        val requestUrl = "https://$providerHost/film-terbaru/page/1/"
        val normalizer = ProviderUrlNormalizer { candidate ->
            ProviderHtmlParser.preserveProviderPageUrl(
                candidate,
                "https://$providerHost",
                setOf("v3.pusatfilm21info.com")
            )
        }
        val brokenPrimaryAddresses = SystemProviderDnsResolver.resolve(
            "dns.adguard-dns.com"
        )
        assertTrue(brokenPrimaryAddresses.isNotEmpty())
        val realFetcher = NiceHttpProviderFetcher(app)
        val primaryFailure = try {
            ProviderHttpSafetyClient(
                fetcher = realFetcher,
                resolver = ProviderDnsResolver { host ->
                    if (host == providerHost) {
                        brokenPrimaryAddresses
                    } else {
                        SystemProviderDnsResolver.resolve(host)
                    }
                }
            ).get(
                url = requestUrl,
                normalizer = normalizer,
                timeoutSeconds = 30L
            )
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }
        assertTrue(
            generateSequence(primaryFailure) { error -> error.cause }
                .any { error -> error is SSLException },
            "Primary-only pinned route did not fail TLS: ${primaryFailure?.javaClass?.name}"
        )

        var discoveryAddresses = emptyList<java.net.InetAddress>()
        val fetchedUrls = mutableListOf<String>()
        val fetchedAddresses = mutableListOf<List<java.net.InetAddress>>()
        val resolver = ProviderDnsAliasFallbackResolver(
            delegate = ProviderDnsResolver { host ->
                if (host == providerHost) {
                    brokenPrimaryAddresses
                } else {
                    SystemProviderDnsResolver.resolve(host).also { resolved ->
                        if (host == discoveryHost) discoveryAddresses = resolved
                    }
                }
            },
            aliases = mapOf(providerHost to discoveryHost)
        )
        val response = ProviderHttpSafetyClient(
            fetcher = ProviderHttpFetcher { request, addresses ->
                fetchedUrls += request.url
                fetchedAddresses += addresses
                realFetcher.fetch(request, addresses)
            },
            resolver = resolver
        ).get(
            url = requestUrl,
            normalizer = normalizer,
            timeoutSeconds = 30L
        )

        assertTrue(response.code in 200..299)
        assertTrue(discoveryAddresses.isNotEmpty())
        assertTrue(fetchedUrls.isNotEmpty())
        assertTrue(
            fetchedUrls.all { url -> URI(url).host == providerHost },
            "Pusatfilm fallback rewrote the original request host: $fetchedUrls"
        )
        assertTrue(
            fetchedAddresses.first().take(discoveryAddresses.size) == discoveryAddresses,
            "Official discovery addresses were not pinned before primary DNS answers"
        )
        assertTrue(
            response.body.contains("article", ignoreCase = true),
            "Pusatfilm fallback returned no catalog markup"
        )
    }

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
    fun `layarkaca stale Spider-Verse mirrors resolve through an exact fallback`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val provider = LayarKacaProvider()
        val result = provider.search("Spider-Man:").orEmpty()
            .firstOrNull {
                it.name.contains(
                    "Across the Spider-Verse",
                    ignoreCase = true
                )
            }
            ?: error("LayarKaca Spider-Verse regression title is missing")
        val detail = provider.load(result.url) as? MovieLoadResponse
            ?: error("LayarKaca Spider-Verse detail did not load as a movie")
        val links = mutableListOf<ExtractorLink>()
        val loaded = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            provider.loadLinks(detail.dataUrl, false, {}, links::add)
        }

        assertTrue(
            loaded && links.isNotEmpty(),
            "LayarKaca exact fallback returned loaded=$loaded links=${links.size}"
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

    @Test
    fun `four reported providers satisfy the CloudStream tester contract`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val failures = mutableListOf<String>()
        requestedProviders().forEach { provider ->
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
            "Four-provider CloudStream parity failures:\n${failures.joinToString("\n")}"
        )
    }

    @Test
    fun `four reported providers survive concurrent CloudStream tests`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val outcomes = supervisorScope {
            requestedProviders().map { provider ->
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
            "Four-provider concurrent tester failures:\n${failures.joinToString("\n")}"
        )
    }

    @Test
    fun `four reported providers emit direct files safe for parallel download`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val failures = mutableListOf<String>()
        requestedProviders().forEach { provider ->
            try {
                withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                    verifyProvider(provider, Random(0)) { links ->
                        verifyParallelDownloadRanges(provider.name, links)
                    }
                }
            } catch (error: TimeoutCancellationException) {
                failures += "${provider.name}: timed out"
            } catch (error: Throwable) {
                failures += "${provider.name}: ${error.message ?: error::class.simpleName}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Direct-download integrity failures:\n${failures.joinToString("\n")}"
        )
    }

    @Test
    fun `kawanfilm and pusatfilm satisfy the CloudStream tester contract`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val failures = mutableListOf<String>()
        remainingProviders().forEach { provider ->
            try {
                withTimeout(PROVIDER_TIMEOUT_MILLIS) {
                    verifyProvider(provider, Random(0), requireHomepage = true)
                }
            } catch (error: TimeoutCancellationException) {
                failures += "${provider.name}: timed out"
            } catch (error: Throwable) {
                failures += "${provider.name}: ${error.message ?: error::class.simpleName}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Remaining CloudStream parity failures:\n${failures.joinToString("\n")}"
        )
    }

    private suspend fun verifyProvider(
        provider: MainAPI,
        random: Random,
        requireHomepage: Boolean = false,
        linkVerifier: suspend (List<ExtractorLink>) -> Unit = {}
    ) {
        val page = provider.mainPage.firstOrNull()
            ?: error("has no main-page category")
        val homepageRequest = MainPageRequest(page.name, page.data, page.horizontalImages)
        val homepage = if (requireHomepage) {
            provider.getMainPage(
                1,
                homepageRequest
            )?.items?.flatMap { it.list }.orEmpty()
        } else {
            try {
                provider.getMainPage(1, homepageRequest)
                    ?.items?.flatMap { it.list }.orEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: NotImplementedError) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }
        }
        if (requireHomepage) {
            assertTrue(
                homepage.isNotEmpty(),
                "${provider.name} returned an empty homepage for ${page.name}"
            )
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
                if (links.isNotEmpty()) linkVerifier(links)
                attempts += "${result.name}: loaded=$loaded links=${links.size} " +
                    "types=${links.map { it.javaClass.simpleName to it.type }}"
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

    private suspend fun verifyParallelDownloadRanges(
        providerName: String,
        links: List<ExtractorLink>
    ) {
        val directLinks = links
            .asSequence()
            .filter { it.type == ExtractorLinkType.VIDEO }
            .sortedByDescending { it.quality }
            .distinctBy { link ->
                runCatching {
                    URI(link.url).host.orEmpty().lowercase().split('.')
                        .takeLast(2)
                        .joinToString(".")
                }.getOrDefault(link.url)
            }
            .take(8)
            .toList()
        if (directLinks.isEmpty()) return

        val http = ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
        val normalizer = ProviderUrlNormalizer { candidate ->
            candidate.takeIf(::isSafeRemoteHttpUrl)
        }
        val failures = mutableListOf<String>()
        directLinks.forEach { link ->
            val host = runCatching { URI(link.url).host.orEmpty() }.getOrDefault("opaque")
            try {
                val explicitReferer = link.headers.entries
                    .lastOrNull { it.key.equals("Referer", ignoreCase = true) }
                    ?.value
                    ?.takeIf { it.isNotBlank() }
                val referer = explicitReferer ?: link.referer.takeIf { it.isNotBlank() }
                val headers = link.headers.filterKeys { key ->
                    !key.equals("Referer", ignoreCase = true) &&
                        !key.equals("Range", ignoreCase = true)
                }
                val head = withTimeout(30_000) {
                    http.head(
                        link.url,
                        normalizer = normalizer,
                        referer = referer,
                        headers = headers,
                        timeoutSeconds = 30L
                    )
                }
                val zero = withTimeout(30_000) {
                    http.getPrefix(
                        link.url,
                        normalizer = normalizer,
                        referer = referer,
                        headers = headers + ("Range" to "bytes=0-65535"),
                        maxBodyBytes = 65_536,
                        timeoutSeconds = 30L
                    )
                }
                val successfulHead = head.code in 200..299
                val acceptRanges = head.header("Accept-Ranges").takeIf { successfulHead }
                val supportsRanges = when (acceptRanges?.trim()?.lowercase()) {
                    "none" -> false
                    "bytes" -> true
                    else -> zero.code == 206
                }
                if (!supportsRanges && zero.code !in 200..299) {
                    failures += "$host zero=${zero.code}"
                } else if (supportsRanges) {
                    val initialEvidence = DownloadRangeProbeEvidence(
                        requestedStart = 0L,
                        requestedEnd = 65_535L,
                        maxBodyBytes = 65_536,
                        responseCode = zero.code,
                        contentRange = zero.header("Content-Range"),
                        responseContentLength = zero.header("Content-Length")
                            ?.trim()
                            ?.toLongOrNull(),
                        bodyByteCount = zero.bodyBytes.size,
                        bodyTruncated = zero.bodyTruncated
                    )
                    val zeroRange = parseContentRange(initialEvidence.contentRange)
                    liveRangeProbeError(initialEvidence)?.let { error ->
                        failures += "$host zero $error"
                    }
                    val total = zeroRange?.third
                    if (total == null) {
                        failures += "$host zero range has no total"
                    } else if (
                        successfulHead &&
                        head.header("Content-Length")
                            ?.trim()
                            ?.toLongOrNull()
                            ?.takeIf { it > 0L }
                            ?.let { it != total } == true
                    ) {
                        failures += "$host HEAD size=${head.header("Content-Length")} " +
                            "range total=$total"
                    } else {
                        val offset = minOf(10_485_760L, total / 2L)
                            .coerceAtLeast(65_536L)
                            .takeIf { it < total }
                        if (offset == null) {
                            failures += "$host media is too small for a nonzero probe"
                            return@forEach
                        }
                        val nonZero = withTimeout(30_000) {
                            http.getPrefix(
                                link.url,
                                normalizer = normalizer,
                                referer = referer,
                                headers = headers + ("Range" to "bytes=$offset-"),
                                maxBodyBytes = 65_536,
                                timeoutSeconds = 30L
                            )
                        }
                        val nonZeroEvidence = DownloadRangeProbeEvidence(
                            requestedStart = offset,
                            requestedEnd = null,
                            maxBodyBytes = 65_536,
                            responseCode = nonZero.code,
                            contentRange = nonZero.header("Content-Range"),
                            responseContentLength = nonZero.header("Content-Length")
                                ?.trim()
                                ?.toLongOrNull(),
                            bodyByteCount = nonZero.bodyBytes.size,
                            bodyTruncated = nonZero.bodyTruncated
                        )
                        liveRangeProbeError(nonZeroEvidence, total)?.let { error ->
                            failures += "$host nonzero $error"
                        }
                    }
                }
                println(
                    "$providerName download host=$host " +
                        "head=${head.code}/${head.header("Content-Length")}/" +
                        "${head.header("Accept-Ranges")} zero=${zero.code}/" +
                        "${zero.header("Content-Range")}/${zero.bodyBytes.size}/" +
                        "truncated=${zero.bodyTruncated}"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failures += "$host ${error.message ?: error::class.simpleName}"
            }
        }
        assertTrue(failures.isEmpty(), "$providerName unsafe direct links: $failures")
    }

    private fun liveRangeProbeError(
        evidence: DownloadRangeProbeEvidence,
        expectedTotal: Long? = null
    ): String? {
        if (evidence.responseCode != 206) return "status=${evidence.responseCode}"
        val range = parseContentRange(evidence.contentRange)
            ?: return "invalid Content-Range=${evidence.contentRange}"
        if (range.first != evidence.requestedStart) {
            return "start=${range.first}, expected=${evidence.requestedStart}"
        }
        if (expectedTotal != null && range.third != expectedTotal) {
            return "total=${range.third}, expected=$expectedTotal"
        }
        val expectedEnd = evidence.requestedEnd
            ?.let { minOf(it, range.third - 1L) }
            ?: (range.third - 1L)
        if (range.second != expectedEnd) {
            return "end=${range.second}, expected=$expectedEnd"
        }
        val responseLength = range.second - range.first + 1L
        if (
            evidence.responseContentLength != null &&
            evidence.responseContentLength != responseLength
        ) {
            return "Content-Length=${evidence.responseContentLength}, expected=$responseLength"
        }
        val expectedBody = minOf(responseLength, evidence.maxBodyBytes.toLong()).toInt()
        if (evidence.bodyByteCount != expectedBody) {
            return "body=${evidence.bodyByteCount}, expected=$expectedBody"
        }
        val expectedTruncated = responseLength > evidence.maxBodyBytes.toLong()
        if (evidence.bodyTruncated != expectedTruncated) {
            return "truncated=${evidence.bodyTruncated}, expected=$expectedTruncated"
        }
        return null
    }

    private fun parseContentRange(raw: String?): Triple<Long, Long, Long>? {
        val match = raw?.trim()?.let {
            Regex("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+)$").matchEntire(it)
        } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return Triple(start, end, total).takeIf {
            start <= end && end < total
        }
    }

    private fun String.firstSearchWord(): String? =
        trim().split(Regex("""\s+""")).firstOrNull { it.isNotBlank() }

    private data class PlaybackTarget(
        val data: String,
        val shouldLoadLinks: Boolean
    )

    private fun reportedProviders(): List<MainAPI> = listOf(
        AnimasuProvider(),
        DutamovieProvider(),
        IndomaxProvider(),
        KawanfilmProvider(),
        KuronimeProvider(),
        KuramanimeProvider(),
        LayarKacaProvider(),
        MovieboxProvider(),
        NomatProvider(),
        PencurimovieProvider(),
        PusatfilmProvider(),
        ZoronimeProvider()
    )

    private fun requestedProviders(): List<MainAPI> = listOf(
        DutamovieProvider(),
        KuronimeProvider(),
        LayarKacaProvider(),
        PusatfilmProvider()
    )

    private fun remainingProviders(): List<MainAPI> = listOf(
        KawanfilmProvider(),
        PusatfilmProvider()
    )

    private companion object {
        const val PROVIDER_TIMEOUT_MILLIS = 180_000L
    }
}
