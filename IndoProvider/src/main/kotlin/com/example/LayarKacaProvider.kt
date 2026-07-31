package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private val LAYARKACA_LEGACY_HOSTS = setOf("parachutedrone.com", "tv10.lk21official.cc")

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv.nontonfilm.red"
    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/genre/box-office/page/" to "Box Office",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/horror/page/" to "Horror",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/animation/page/" to "Animation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = try {
            app.get(
                request.data + page,
                timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
            ).document
                .select("article.item-infinite, article.item, div.ml-item")
                .mapNotNull { it.toSearchResult() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        if (items.isNotEmpty()) {
            return newHomePageResponse(request.name, items)
        }

        val fallbackItems = withTimeoutOrNull(LAYARKACA_CATALOG_FALLBACK_TIMEOUT_MS) {
            firstNonEmptyFallback(fallbackProviders()) { provider ->
                val fallbackNames = fallbackCategoryNames(request.name)
                val fallbackPage = provider.mainPage.firstOrNull { candidate ->
                    fallbackNames.any { it.equals(candidate.name, ignoreCase = true) }
                } ?: provider.mainPage.firstOrNull()
                    ?: return@firstNonEmptyFallback emptyList()
                provider.getMainPage(
                    page,
                    MainPageRequest(
                        fallbackPage.name,
                        fallbackPage.data,
                        fallbackPage.horizontalImages
                    )
                )?.items?.flatMap { it.list }.orEmpty()
            }
        }.orEmpty()
        return newHomePageResponse(request.name, fallbackItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val primaryResults = try {
            app.get(
                "$mainUrl/?s=$encodedQuery",
                timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
            ).document
                .select("article.item-infinite, article.item, div.ml-item")
                .mapNotNull { it.toSearchResult() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        if (primaryResults.isNotEmpty()) return primaryResults

        return withTimeoutOrNull(LAYARKACA_CATALOG_FALLBACK_TIMEOUT_MS) {
            firstNonEmptyFallback(fallbackProviders()) { provider ->
                provider.search(query).orEmpty()
            }
        }.orEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = ProviderHtmlParser.firstTitledLink(this) ?: return null
        val title = MovieMetadataParser.title(anchor.text()) ?: return null
        val href = providerUrl(anchor.attr("href")) ?: return null
        if (SensitiveContentPolicy.isBlockedCatalogCard(this, title, href)) return null
        val poster = fixUrlNull(ProviderHtmlParser.firstImageSource(this))
        val quality = selectFirst("div.gmr-quality-item, div.gmr-qual")?.text()?.trim()
        val isSeries = href.contains("/tv/", ignoreCase = true) ||
            selectFirst("div.gmr-numbeps, div.last-episode") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = providerUrl(url)
            ?: return firstNonEmptyFallback(fallbackProviders()) { provider ->
                listOfNotNull(provider.load(url))
            }.firstOrNull()
        val urlFallbackRequests = LayarKacaPlayerParser.fallbackRequests(
            Jsoup.parse(""),
            requestUrl
        )
        val fetch = try {
            app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return loadFallbackDetail(urlFallbackRequests)
        }
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.text)
        ) {
            return loadFallbackDetail(urlFallbackRequests)
        }
        val document = fetch.document
        val fallbackRequests = (
            LayarKacaPlayerParser.fallbackRequests(document, requestUrl) +
                urlFallbackRequests
            ).distinct()
        val canonicalUrl = providerUrl(fetch.url)
            ?: return loadFallbackDetail(fallbackRequests)
        val title = MovieMetadataParser.title(
            document.selectFirst("h1.entry-title, h3[itemprop=name]")?.text()
        ) ?: return loadFallbackDetail(fallbackRequests)
        val poster = fixUrlNull(
            ProviderHtmlParser.imageSource(
                document.selectFirst("img.thumbnail, figure.pull-left > img, img.img-thumbnail")
            )
        )
        val description = MovieMetadataParser.synopsis(document)
        val year = document.select("a[href*=/year/], span.year")
            .firstNotNullOfOrNull { Regex("(?:19|20)\\d{2}").find(it.text())?.value?.toIntOrNull() }
        val tags = document.select("div.gmr-moviedata a[href*=/genre/], span.jptag a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a").map { it.text().trim() }
        val trailer = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        val episodeElements = document
            .select("div.vid-episodes a[href], div.gmr-listseries a[href], div.episode-list a[href]")
            .filter { episodeLink ->
                val href = providerUrl(episodeLink.attr("href")) ?: return@filter false
                val label = episodeLink.attr("title").ifBlank { episodeLink.text() }
                LayarKacaPlayerParser.isEpisodeLink(href, label, canonicalUrl)
            }
        val requestedCoordinate = LayarKacaPlayerParser.episodeCoordinate(requestUrl, "")
        val isSeries = fetch.url.contains("/tv/", ignoreCase = true) ||
            episodeElements.isNotEmpty() ||
            requestedCoordinate != null

        return if (isSeries) {
            val episodes = LayarKacaPlayerParser.orderedEpisodeCandidates(
                requestUrl = requestUrl,
                candidates = episodeElements.mapNotNull { episodeLink ->
                    val href = providerUrl(episodeLink.attr("href"))
                        ?: return@mapNotNull null
                    val label = episodeLink.attr("title")
                        .takeIf { it.isNotBlank() }
                        ?: episodeLink.text().trim()
                    href to label
                }
            ).map { candidate ->
                newEpisode(candidate.url) {
                    season = candidate.season
                    episode = candidate.episode
                    name = candidate.episode?.let { "Episode $it" } ?: candidate.label
                    posterUrl = poster
                }
            }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(
                title,
                canonicalUrl,
                TvType.Movie,
                LayarKacaPlayerParser.playbackPageUrl(requestUrl, canonicalUrl)
            ) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val requestUrl = providerUrl(data)
        if (requestUrl == null) {
            val delegatedRequests = LayarKacaPlayerParser.fallbackRequests(
                Jsoup.parse(""),
                data
            )
            return withPlaybackFallbackBudget(LAYARKACA_FALLBACK_TIMEOUT_MS) {
                val delegatedLoaded = loadFirstEmittingFallback(
                    candidates = fallbackProviders(),
                    callback = callback
                ) { provider, fallbackCallback ->
                    provider.loadLinks(
                        data,
                        isCasting,
                        subtitleCallback,
                        fallbackCallback
                    )
                }
                if (delegatedLoaded) {
                    true
                } else {
                    delegatedRequests.any { request ->
                        loadFallbackWithinBudget(
                            request = request,
                            isCasting = isCasting,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                }
            }
        }
        val urlFallbackRequests = LayarKacaPlayerParser.fallbackRequests(
            Jsoup.parse(""),
            requestUrl
        )
        val fetch = try {
            app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return loadFallback(
                urlFallbackRequests,
                isCasting,
                subtitleCallback,
                callback
            )
        }
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.text)
        ) {
            return loadFallback(
                urlFallbackRequests,
                isCasting,
                subtitleCallback,
                callback
            )
        }
        val document = fetch.document
        val fallbackRequests = (
            LayarKacaPlayerParser.fallbackRequests(document, requestUrl) +
                urlFallbackRequests
            ).distinct()
        val canonicalUrl = providerUrl(fetch.url) ?: return loadFallback(
            fallbackRequests,
            isCasting,
            subtitleCallback,
            callback
        )
        if (
            !LayarKacaPlayerParser.isPrimaryPlaybackCoordinateSafe(
                requestUrl,
                canonicalUrl,
                document
            )
        ) {
            return loadFallback(
                requests = fallbackRequests,
                isCasting = isCasting,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            inlineSourceParser = LayarKacaPlayerParser::mediaUrls,
            preferInlineSourceParser = true,
            candidateTimeoutMs = LAYARKACA_CANDIDATE_TIMEOUT_MS,
            genericExtractorTimeoutMs = LAYARKACA_EXTRACTOR_TIMEOUT_MS,
            sessionTimeoutMs = LAYARKACA_PRIMARY_SESSION_TIMEOUT_MS
        )
        resolveByPriorityTiers(
            tiers = LayarKacaPlayerParser.playerCandidateTiers(document, canonicalUrl),
            maxConcurrency = 3,
            canContinue = { !resolver.loaded && resolver.canContinue }
        ) { candidate ->
            when (candidate) {
                is LayarKacaPlaybackCandidate.InlinePlayer ->
                    resolver.resolveInline(candidate.url, canonicalUrl)

                is LayarKacaPlaybackCandidate.ServerPage -> try {
                    val playerDocument = app.get(
                        candidate.url,
                        referer = canonicalUrl,
                        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                    ).let { response ->
                        val responseUrl = providerUrl(response.url) ?: return@let null
                        response.document to responseUrl
                    } ?: return@resolveByPriorityTiers false
                    val mediaCandidates = LayarKacaPlayerParser.pageMediaUrls(
                            playerDocument.first,
                            playerDocument.second
                        ).mapNotNull { mediaUrl ->
                            ProviderHtmlParser.absoluteUrl(mediaUrl, playerDocument.second)
                                ?.let { url ->
                                    PlayerResolutionCandidate(
                                        url,
                                        playerDocument.second,
                                        inline = true
                                    )
                                }
                        }
                    resolveByPriorityTiers(
                        tiers = listOf(mediaCandidates),
                        maxConcurrency = 3,
                        canContinue = { !resolver.loaded && resolver.canContinue }
                    ) { media ->
                        resolver.resolveInline(media.url, media.referer)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // One dead server must not hide the remaining mirrors.
                    false
                }
            }
        }

        if (!resolver.loaded) {
            val ajaxUrl = URI(canonicalUrl).let { "${it.scheme}://${it.host}/wp-admin/admin-ajax.php" }
            for (request in LayarKacaPlayerParser.ajaxRequests(document)) {
                if (resolver.loaded || !resolver.canContinue) break
                try {
                    val response = app.post(
                        ajaxUrl,
                        data = request.toPostData(),
                        referer = canonicalUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                    )
                    val responseUrl = providerUrl(response.url) ?: continue
                    resolver.resolveFirstVerified(
                        LayarKacaPlayerParser.pageMediaUrls(
                            response.document,
                            responseUrl
                        ).mapNotNull { mediaUrl ->
                            ProviderHtmlParser.absoluteUrl(mediaUrl, responseUrl)
                                ?.let { url ->
                                    PlayerResolutionCandidate(url, canonicalUrl, inline = true)
                                }
                        }
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep trying the remaining AJAX player tabs.
                }
            }
        }

        if (!resolver.loaded) {
            resolver.resolveFirstVerified(
                ProviderHtmlParser.downloadCandidateUrls(document, canonicalUrl)
                    .map { download ->
                        PlayerResolutionCandidate(download, canonicalUrl)
                    }
            )
        }

        if (resolver.loaded || fallbackRequests.isEmpty()) return resolver.loaded
        return loadFallback(
            requests = fallbackRequests,
            isCasting = isCasting,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun loadFallback(
        requests: List<NomatFallbackRequest>,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withPlaybackFallbackBudget(LAYARKACA_FALLBACK_TIMEOUT_MS) {
        requests.any { request ->
            loadFallbackWithinBudget(
                request = request,
                isCasting = isCasting,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }
    }

    private suspend fun loadFallbackDetail(
        requests: List<NomatFallbackRequest>
    ): LoadResponse? {
        if (requests.isEmpty()) return null
        return withTimeoutOrNull(LAYARKACA_CATALOG_FALLBACK_TIMEOUT_MS) {
            for (request in requests) {
                for (provider in fallbackProviders()) {
                    try {
                        val exactResults = provider.search(request.title).orEmpty()
                            .asSequence()
                            .filter {
                                NomatParser.isPotentialFallbackTitle(request, it.name)
                            }
                            .distinctBy { it.url }
                            .take(LAYARKACA_MAX_FALLBACK_SEARCH_RESULTS)
                            .toList()
                        for (result in exactResults) {
                            val detail = provider.load(result.url) ?: continue
                            if (
                                !NomatParser.isExactFallbackMatch(
                                    request = request,
                                    candidateTitle = detail.name,
                                    candidateYear = detail.year
                                )
                            ) continue
                            val matchesRequestedEpisode = when (detail) {
                                is MovieLoadResponse ->
                                    request.season == null && request.episode == null
                                is TvSeriesLoadResponse ->
                                    LayarKacaPlayerParser.fallbackSeriesMatchesRequest(
                                        request = request,
                                        candidateTitle = detail.name,
                                        candidateYear = detail.year,
                                        episodes = detail.episodes
                                    )

                                else -> false
                            }
                            if (matchesRequestedEpisode) {
                                return@withTimeoutOrNull detail
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // One failed detail source must not hide the next fallback.
                    }
                }
            }
            null
        }
    }

    private suspend fun loadFallbackWithinBudget(
        request: NomatFallbackRequest,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val providers = listOf(FilmapikProvider(), PusatfilmProvider())
        for (provider in providers) {
            var callbackFailure: Throwable? = null
            try {
                val exactResults = provider.search(request.title).orEmpty()
                    .asSequence()
                    .filter { NomatParser.isPotentialFallbackTitle(request, it.name) }
                    .distinctBy { it.url }
                    .take(LAYARKACA_MAX_FALLBACK_SEARCH_RESULTS)
                    .toList()
                for (result in exactResults) {
                    val detail = provider.load(result.url) ?: continue
                    val playbackData = when (detail) {
                        is MovieLoadResponse -> detail.dataUrl.takeIf {
                            request.season == null &&
                                request.episode == null &&
                                NomatParser.isExactFallbackMatch(
                                    request,
                                    detail.name,
                                    detail.year
                                )
                        }

                        is TvSeriesLoadResponse ->
                            LayarKacaPlayerParser.fallbackEpisodeData(
                                request = request,
                                candidateTitle = detail.name,
                                candidateYear = detail.year,
                                episodes = detail.episodes
                            )

                        else -> null
                    } ?: continue
                    if (
                        retryFallbackPlayback(
                            maxAttempts = LAYARKACA_FALLBACK_PLAYBACK_ATTEMPTS,
                            callback = { link ->
                                try {
                                    callback(link)
                                } catch (error: Throwable) {
                                    callbackFailure = error
                                    throw error
                                }
                            }
                        ) { attemptCallback ->
                            provider.loadLinks(
                                playbackData,
                                isCasting,
                                subtitleCallback,
                                attemptCallback
                            )
                        }
                    ) return true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // One fallback provider must not prevent the next exact match.
            }
            callbackFailure?.let { throw it }
        }
        return false
    }

    private fun providerUrl(raw: String?): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, LAYARKACA_LEGACY_HOSTS)

    private fun fallbackProviders(): List<MainAPI> =
        listOf(FilmapikProvider(), PusatfilmProvider())

    private fun fallbackCategoryNames(requestedName: String): Set<String> = when {
        requestedName.equals("Drama Korea", ignoreCase = true) ->
            setOf(requestedName, "K-Drama")

        else -> setOf(requestedName)
    }

    private companion object {
        const val LAYARKACA_CANDIDATE_TIMEOUT_MS = 18_000L
        const val LAYARKACA_EXTRACTOR_TIMEOUT_MS = 8_000L
        const val LAYARKACA_PRIMARY_SESSION_TIMEOUT_MS = 45_000L
        const val LAYARKACA_CATALOG_FALLBACK_TIMEOUT_MS = 60_000L
        const val LAYARKACA_FALLBACK_TIMEOUT_MS = 90_000L
        const val LAYARKACA_MAX_FALLBACK_SEARCH_RESULTS = 8
        const val LAYARKACA_FALLBACK_PLAYBACK_ATTEMPTS = 2
    }
}

internal suspend fun withPlaybackFallbackBudget(
    timeoutMs: Long,
    block: suspend () -> Boolean
): Boolean = withTimeoutOrNull(timeoutMs.coerceIn(1L, 120_000L)) {
    block()
} ?: false

internal suspend fun <Candidate, Result> firstNonEmptyFallback(
    candidates: Iterable<Candidate>,
    attempt: suspend (Candidate) -> List<Result>
): List<Result> {
    for (candidate in candidates) {
        try {
            val results = attempt(candidate)
            if (results.isNotEmpty()) return results
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A failed catalog source must not hide the next bounded fallback.
        }
    }
    return emptyList()
}

internal suspend fun <Candidate, Result> loadFirstEmittingFallback(
    candidates: Iterable<Candidate>,
    callback: (Result) -> Unit,
    attempt: suspend (Candidate, (Result) -> Unit) -> Boolean
): Boolean {
    for (candidate in candidates) {
        var emitted = false
        var callbackFailure: Throwable? = null
        try {
            attempt(candidate) { result ->
                try {
                    callback(result)
                    emitted = true
                } catch (error: Throwable) {
                    callbackFailure = error
                    throw error
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A failed playback source must not hide the next bounded fallback.
        }
        callbackFailure?.let { throw it }
        if (emitted) return true
    }
    return false
}

internal suspend fun <T> retryFallbackPlayback(
    maxAttempts: Int,
    callback: (T) -> Unit,
    load: suspend ((T) -> Unit) -> Boolean
): Boolean {
    repeat(maxAttempts.coerceIn(1, 3)) {
        var emitted = false
        var callbackFailure: Throwable? = null
        try {
            load { value ->
                try {
                    callback(value)
                    emitted = true
                } catch (error: Throwable) {
                    callbackFailure = error
                    throw error
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A transient resolver failure is retried within the strict attempt bound.
        }
        callbackFailure?.let { throw it }
        if (emitted) return true
    }
    return false
}

internal sealed interface LayarKacaPlaybackCandidate {
    data class ServerPage(val url: String) : LayarKacaPlaybackCandidate
    data class InlinePlayer(val url: String) : LayarKacaPlaybackCandidate
}

internal data class LayarKacaEpisodeCoordinate(
    val season: Int?,
    val episode: Int
)

internal data class LayarKacaEpisodeCandidate(
    val url: String,
    val label: String,
    val season: Int?,
    val episode: Int?
)

internal object LayarKacaPlayerParser {
    private val fallbackYearRegex = Regex("""\b(?:19|20)\d{2}\b""")
    private val fallbackParenthesizedYearSuffixRegex =
        Regex("""\s*[\[(](?:19|20)\d{2}[\])]\s*$""")
    private val fallbackTrailingYearRegex = Regex("""(?:^|\s)((?:19|20)\d{2})$""")
    private val fallbackSeasonRegex = Regex("""(?i)\bseason\s*[-:]?\s*(\d+)\b""")
    private val fallbackEpisodeRegex =
        Regex("""(?i)\b(?:episode|eps?\.?)\s*[-:]?\s*(\d+)\b""")
    private val fallbackEpisodePathRegex =
        Regex("""(?i)([^/]+?)-season-(\d+)-episode-(\d+)/?$""")
    private val fallbackDetailPathRegex =
        Regex("""(?i)/(?:movie|tv)/([^/?#]+)/?$""")
    private val fallbackSeriesSuffixRegex = Regex(
        """(?i)\bseason\s*[-:]?\s*\d+\b.*$"""
    )
    private val fallbackWatchPrefixRegex =
        Regex("""(?i)^nonton(?:\s+film)?\s+""")

    fun ajaxRequests(document: Document): List<MuviproAjaxRequest> =
        ProviderHtmlParser.muviproAjaxRequests(document)

    fun playbackPageUrl(requestUrl: String, canonicalUrl: String): String {
        val requestedPath = runCatching { URI(requestUrl).path.orEmpty() }.getOrDefault("")
        return requestUrl.takeIf {
            requestedPath.startsWith("/eps/", ignoreCase = true) &&
                fallbackEpisodePathRegex.containsMatchIn(requestedPath)
        } ?: canonicalUrl
    }

    fun episodeCoordinate(url: String, label: String): LayarKacaEpisodeCoordinate? {
        val pathMatch = runCatching { URI(url).path.orEmpty() }.getOrNull()
            ?.let(fallbackEpisodePathRegex::find)
        val season = (
            pathMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: fallbackSeasonRegex.find(label)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            )?.takeIf { it in 0..10_000 }
        val episode = (
            pathMatch?.groupValues?.getOrNull(3)?.toIntOrNull()
            ?: fallbackEpisodeRegex.find(label)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: label.trim()
                .takeIf { it.matches(Regex("""^\d{1,5}$""")) }
                ?.toIntOrNull()
            )?.takeIf { it in 1..10_000 }
            ?: return null
        return LayarKacaEpisodeCoordinate(season, episode)
    }

    fun isPrimaryPlaybackCoordinateSafe(
        requestUrl: String,
        canonicalUrl: String,
        document: Document
    ): Boolean {
        val requested = episodeCoordinate(requestUrl, "") ?: return true
        val canonical = episodeCoordinate(canonicalUrl, "")
        val documentTitle = pageTitle(document).orEmpty()
        val declared = episodeCoordinate("", documentTitle)
        val observed = listOfNotNull(canonical, declared)
        if (observed.isEmpty()) return false
        if (observed.any { it.episode != requested.episode }) return false
        if (requested.season != null) {
            if (observed.any { it.season != null && it.season != requested.season }) {
                return false
            }
            if (observed.none { it.season == requested.season }) return false
        }

        val requestedTitle = episodeTitle(requestUrl) ?: return false
        val observedTitles = buildList {
            if (canonical != null) episodeTitle(canonicalUrl)?.let(::add)
            fallbackTitle(MovieMetadataParser.title(documentTitle) ?: documentTitle)
                ?.let(::add)
        }
        return observedTitles.isNotEmpty() &&
            observedTitles.all { title ->
                NomatParser.isExactFallbackTitle(requestedTitle, title)
            }
    }

    fun orderedEpisodeCandidates(
        requestUrl: String,
        candidates: List<Pair<String, String>>
    ): List<LayarKacaEpisodeCandidate> {
        val requested = episodeCoordinate(requestUrl, "")
        val parsed = candidates.map { (url, label) ->
            val coordinate = episodeCoordinate(url, label)
            LayarKacaEpisodeCandidate(
                url = url,
                label = label,
                season = coordinate?.season ?: requested?.season,
                episode = coordinate?.episode
            )
        }.distinctBy { it.url }
        if (requested == null) return parsed

        val matches = parsed.filter { candidate ->
            candidate.season == requested.season &&
                candidate.episode == requested.episode
        }
        val requestedCandidates = matches.ifEmpty {
            listOf(
                LayarKacaEpisodeCandidate(
                    url = requestUrl,
                    label = "Episode ${requested.episode}",
                    season = requested.season,
                    episode = requested.episode
                )
            )
        }
        return requestedCandidates + parsed.filterNot { it in matches }
    }

    fun fallbackRequest(document: Document, pageUrl: String? = null): NomatFallbackRequest? =
        fallbackRequests(document, pageUrl).firstOrNull()

    fun fallbackRequests(document: Document, pageUrl: String? = null): List<NomatFallbackRequest> {
        val urlCoordinate = pageUrl?.let { episodeCoordinate(it, "") }
        val urlTitle = pageUrl?.let(::episodeTitle)
        if (urlCoordinate != null && urlTitle != null) {
            return listOf(
                NomatFallbackRequest(
                    title = urlTitle,
                    year = null,
                    season = urlCoordinate.season,
                    episode = urlCoordinate.episode
                )
            )
        }

        val urlDetailRequests = pageUrl?.let(::detailFallbackRequests).orEmpty()
        val rawTitle = pageTitle(document) ?: return urlDetailRequests
        val parsedTitle = MovieMetadataParser.title(rawTitle) ?: return urlDetailRequests
        val coordinate = episodeCoordinate("", parsedTitle)
        val season = coordinate?.season
        val episode = coordinate?.episode
        val year = if (episode != null) {
            null
        } else {
            document.select("a[href*=/year/], span.year")
                .firstNotNullOfOrNull {
                    fallbackYearRegex.find(it.text())?.value?.toIntOrNull()
                } ?: fallbackParenthesizedYearSuffixRegex.find(parsedTitle)
                    ?.let { match -> fallbackYearRegex.find(match.value) }
                    ?.value
                    ?.toIntOrNull()
        }
        val title = fallbackTitle(parsedTitle, year) ?: return urlDetailRequests
        return (
            listOf(NomatFallbackRequest(title, year, season, episode)) +
                urlDetailRequests
            ).distinct()
    }

    private fun detailFallbackRequests(url: String): List<NomatFallbackRequest> {
        val slug = runCatching { URI(url).path.orEmpty() }.getOrNull()
            ?.let(fallbackDetailPathRegex::find)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        val rawTitle = slug.split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { character -> character.titlecase() }
            }
        val season = fallbackSeasonRegex.find(rawTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (season != null) {
            return listOfNotNull(
                fallbackTitle(rawTitle)?.let { title ->
                    NomatFallbackRequest(title, null, season, null)
                }
            )
        }

        val preserved = fallbackTitle(rawTitle)?.let { title ->
            NomatFallbackRequest(title, null)
        }
        val trailingYear = fallbackTrailingYearRegex.find(rawTitle)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val interpretedAsYear = trailingYear?.let { year ->
            fallbackTitle(rawTitle, year)?.let { title ->
                if (title == preserved?.title) null else NomatFallbackRequest(title, year)
            }
        }
        return listOfNotNull(preserved, interpretedAsYear).distinct()
    }

    private fun pageTitle(document: Document): String? = document.selectFirst(
        "h1.entry-title, h1[itemprop=name], h3[itemprop=name]"
    )?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun episodeTitle(url: String): String? {
        val slug = runCatching { URI(url).path.orEmpty() }.getOrNull()
            ?.let(fallbackEpisodePathRegex::find)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return slug.split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { character -> character.titlecase() }
            }
            .takeIf { it.isNotBlank() }
    }

    private fun fallbackTitle(raw: String, releaseYear: Int? = null): String? {
        val cleaned = raw
            .replace(fallbackWatchPrefixRegex, "")
            .replace(fallbackSeriesSuffixRegex, " ")
            .replace(fallbackSeasonRegex, " ")
            .replace(fallbackEpisodeRegex, " ")
            .replace(fallbackParenthesizedYearSuffixRegex, " ")
            .replace(Regex("""(?i)\b(?:subtitle\s+indonesia|sub\s*indo)\b.*$"""), "")
            .replace(Regex("""[()\[\]]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ':', '|')
        val withoutReleaseYear = releaseYear?.let { year ->
            cleaned.replace(Regex("""\s+\Q$year\E\s*$"""), "")
                .trim()
                .takeIf { it.isNotBlank() }
        } ?: cleaned
        return withoutReleaseYear.takeIf { it.isNotBlank() }
    }

    fun fallbackEpisodeData(
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?,
        episodes: List<Episode>
    ): String? {
        if (!NomatParser.isExactFallbackMatch(request, candidateTitle, candidateYear)) {
            return null
        }
        val expectedEpisode = request.episode ?: return null
        return episodes.filter { episode ->
            episode.episode == expectedEpisode &&
                (request.season == null || episode.season == request.season)
        }.singleOrNull()?.data?.takeIf { it.isNotBlank() }
    }

    fun fallbackSeriesMatchesRequest(
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?,
        episodes: List<Episode>
    ): Boolean {
        if (!NomatParser.isExactFallbackMatch(request, candidateTitle, candidateYear)) {
            return false
        }
        if (request.episode != null) {
            return fallbackEpisodeData(
                request = request,
                candidateTitle = candidateTitle,
                candidateYear = candidateYear,
                episodes = episodes
            ) != null
        }
        val requestedSeason = request.season
        return if (requestedSeason == null) {
            episodes.isNotEmpty()
        } else {
            episodes.any { episode ->
                episode.season == requestedSeason && episode.data.isNotBlank()
            }
        }
    }

    fun isEpisodeLink(url: String, label: String, detailUrl: String): Boolean {
        if (label.contains("View All Episodes", ignoreCase = true)) return false
        return normalizePageUrl(url) != normalizePageUrl(detailUrl)
    }

    fun serverPageUrls(document: Document, detailUrl: String): List<String> {
        val playerTabs = document.select(
            "ul.gmr-player-nav a[href], ul.muvipro-player-tabs a[href], ul#player-list a[href]"
        ).mapNotNull { link ->
            normalizeServerPageUrl(link.attr("href"), detailUrl)
                ?.takeIf { isServerPageUrl(it, detailUrl) }
        }
        val providerMenu = document.select("ul#loadProviders > li a[href]").mapNotNull { link ->
            normalizeServerPageUrl(link.attr("href"), detailUrl)
                ?.takeIf { isServerPageUrl(it, detailUrl, requirePlayerQuery = false) }
        }
        return (playerTabs + providerMenu).distinct()
    }

    fun orderedPlayerCandidates(
        document: Document,
        detailUrl: String
    ): List<LayarKacaPlaybackCandidate> =
        playerCandidateTiers(document, detailUrl).flatten()

    fun playerCandidateTiers(
        document: Document,
        detailUrl: String
    ): List<List<LayarKacaPlaybackCandidate>> {
        val (defaultServerPages, alternateServerPages) =
            serverPageUrls(document, detailUrl).partition(::isDefaultServerPage)
        return listOf(
            alternateServerPages.map(LayarKacaPlaybackCandidate::ServerPage),
            pageMediaUrls(document, detailUrl).map(LayarKacaPlaybackCandidate::InlinePlayer),
            defaultServerPages.map(LayarKacaPlaybackCandidate::ServerPage)
        ).map { tier -> tier.distinct() }
            .filter { tier -> tier.isNotEmpty() }
    }

    private fun isDefaultServerPage(url: String): Boolean = runCatching {
        URI(url).rawQuery.orEmpty().split('&').any { parameter ->
            parameter.substringBefore('=').equals("player", ignoreCase = true) &&
                parameter.substringAfter('=', "") == "1"
        }
    }.getOrDefault(false)

    private fun normalizeServerPageUrl(raw: String?, detailUrl: String): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val schemeMatches = runCatching {
            val candidate = URI(value)
            val detail = URI(detailUrl)
            !candidate.isAbsolute || candidate.scheme.equals(detail.scheme, ignoreCase = true)
        }.getOrDefault(false)
        if (!schemeMatches) return null
        return ProviderHtmlParser.normalizeProviderPageUrl(
            value,
            detailUrl,
            LAYARKACA_LEGACY_HOSTS
        )
    }

    private fun isServerPageUrl(
        url: String,
        detailUrl: String,
        requirePlayerQuery: Boolean = true
    ): Boolean {
        return runCatching {
            val candidate = URI(url)
            val detail = URI(detailUrl)
            val candidateScheme = candidate.scheme.orEmpty().lowercase()
            val detailScheme = detail.scheme.orEmpty().lowercase()
            val sameOrigin = candidate.host.orEmpty().equals(
                detail.host.orEmpty(),
                ignoreCase = true
            ) && candidateScheme == detailScheme &&
                effectivePort(candidate, candidateScheme) == effectivePort(detail, detailScheme)
            val hasPlayerQuery = candidate.rawQuery.orEmpty()
                .split('&')
                .any { parameter ->
                    parameter.substringBefore('=').equals("player", ignoreCase = true) &&
                        parameter.substringAfter('=', "").isNotBlank()
                }
            candidateScheme in setOf("http", "https") &&
                sameOrigin &&
                (hasPlayerQuery || !requirePlayerQuery) &&
                candidate.toString().substringBefore('#') != detail.toString().substringBefore('#')
        }.getOrDefault(false)
    }

    private fun effectivePort(uri: URI, scheme: String): Int = when {
        uri.port >= 0 -> uri.port
        scheme == "https" -> 443
        scheme == "http" -> 80
        else -> -1
    }

    fun pageMediaUrls(document: Document, pageUrl: String): List<String> {
        return (
            ProviderHtmlParser.mediaSources(document) +
                mediaUrls(document.outerHtml(), pageUrl)
            ).mapNotNull { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
            .distinct()
    }

    fun mediaUrls(html: String, playerUrl: String): List<String> {
        val document = Jsoup.parse(html, playerUrl)
        val scriptBodies = document.select("script").flatMap { script ->
            val raw = script.data()
            listOfNotNull(
                raw,
                raw.takeIf { it.contains("eval(function(p,a,c,k,e") }
                    ?.let { runCatching { getAndUnpack(it) }.getOrNull() }
            )
        }
        val inlineUrls = scriptBodies.flatMap { script ->
            val normalized = script
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
            val declaredSources = InlineDataParser.playableInlineUrls(normalized)
            val assignmentSources = Regex(
                "(?i)[\\\"']?(?:file|src)[\\\"']?\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"
            )
                .findAll(normalized)
                .map { it.groupValues[1] }
                .filter(::isPlayableMediaPath)
                .toList()
            declaredSources + assignmentSources
        }
        val sourceUrls = document.select("video[src], video source[src], source[src]").map { it.attr("src") }
        return (inlineUrls + sourceUrls)
            .mapNotNull { ProviderHtmlParser.absoluteUrl(it, playerUrl) }
            .distinct()
    }

    private fun isPlayableMediaPath(raw: String): Boolean {
        val path = runCatching { URI(raw).path.orEmpty().lowercase() }
            .getOrDefault(raw.substringBefore('?').lowercase())
        return path.endsWith(".m3u8") || path.endsWith(".mp4")
    }

    private fun normalizePageUrl(url: String): String {
        return url.substringBefore('#').substringBefore('?').trimEnd('/')
    }
}
