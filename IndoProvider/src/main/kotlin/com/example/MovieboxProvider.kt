package com.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class MovieboxProvider(
    private val fallbackProviderFactory: () -> List<MainAPI> = {
        listOf(PusatfilmProvider(), KitanontonProvider(), FilmapikProvider())
    }
) : MainAPI() {
    override var mainUrl = "https://h5-api.aoneroom.com"
    override var name = "Moviebox"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "0" to "Trending",
        "1" to "Film",
        "2" to "Serial TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val response = getApi(
            MovieboxApi.homeUrl(mainUrl),
            MovieboxApi.apiHeaders,
            MOVIEBOX_HOME_BODY_LIMIT_BYTES
        )?.parse<MovieboxHomeResponse>()
        val subjectType = request.data.toIntOrNull()?.takeIf { it != 0 }
        val items = response?.availableItems().orEmpty()
            .asSequence()
            .filter { subjectType == null || it.subjectType == subjectType }
            .mapNotNull(::toSearchResponse)
            .toList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return resolveMovieboxSearchCandidates(
            query = query,
            remoteSearch = { fetchSearchItems(query) },
            homepageFallback = { fetchHomeItems() }
        ).mapNotNull(::toSearchResponse)
    }

    private suspend fun fetchSearchItems(query: String): List<MovieboxItem> {
        val authorization = fetchAuthorization(query) ?: return emptyList()
        val body = mapOf(
            "keyword" to query,
            "page" to 1,
            "perPage" to 30,
            "subjectType" to 0
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val response = postApi(
            MovieboxApi.searchUrl(mainUrl),
            body,
            MovieboxApi.apiHeaders + ("Authorization" to authorization),
            MOVIEBOX_JSON_BODY_LIMIT_BYTES
        )?.parse<MovieboxSearchResponse>()
        return response?.availableItems().orEmpty()
    }

    private suspend fun fetchHomeItems(): List<MovieboxItem> {
        return getApi(
            MovieboxApi.homeUrl(mainUrl),
            MovieboxApi.apiHeaders,
            MOVIEBOX_HOME_BODY_LIMIT_BYTES
        )?.parse<MovieboxHomeResponse>()?.availableItems().orEmpty()
    }

    override suspend fun load(url: String): LoadResponse? {
        val request = MovieboxApi.loadData(url) ?: return loadDelegatedDetail(url)
        val id = request.id ?: return loadFallbackDetail(request)
        val legacyRequest = request.detailPath == null
        val detailUrl = if (legacyRequest) {
            MovieboxApi.legacyDetailUrl(id)
        } else {
            MovieboxApi.detailUrl(
                mainUrl,
                request.detailPath ?: return loadFallbackDetail(request)
            )
        } ?: return loadFallbackDetail(request)
        val detail = getApi(
            detailUrl,
            if (legacyRequest) MovieboxApi.legacyApiHeaders else MovieboxApi.apiHeaders,
            MOVIEBOX_JSON_BODY_LIMIT_BYTES
        )?.parse<MovieboxDetailResponse>()?.data ?: return loadFallbackDetail(request)
        if (detail.isForbid == true) return loadFallbackDetail(request)
        val subject = detail.subject?.takeIf {
            if (legacyRequest) it.hasResource != false else it.hasResource == true
        } ?: return loadFallbackDetail(request)
        val detailPath = subject.detailPath
            ?.takeIf(MovieboxApi::isValidDetailPath)
            ?: request.detailPath
            ?: return loadFallbackDetail(request)
        val title = subject.title?.trim()?.takeIf { it.isNotBlank() }
            ?: request.title
            ?: return loadFallbackDetail(request)
        val type = if (subject.subjectType == 2) TvType.TvSeries else TvType.Movie
        val poster = subject.cover?.url
        val year = subject.releaseDate?.substringBefore('-')?.toIntOrNull() ?: request.year
        val tags = subject.genre?.split(',')?.map(String::trim)?.filter(String::isNotBlank)
        val score = Score.from10(subject.imdbRatingValue?.toDoubleOrNull())
        val plot = MovieMetadataParser.meaningfulDescription(subject.description)
        val enrichedRequest = request.copy(
            detailPath = detailPath,
            title = title,
            year = year,
            subjectType = subject.subjectType ?: request.subjectType
        )

        return if (type == TvType.TvSeries) {
            val episodes = movieboxEpisodeCoordinates(detail.resource?.seasons)
                .map { (season, episode) ->
                    newEpisode(
                        enrichedRequest.copy(
                            season = season,
                            episode = episode
                        ).toJson()
                    ) {
                        this.season = season
                        this.episode = episode
                        name = "Episode $episode"
                        posterUrl = poster
                    }
                }
            if (episodes.isEmpty()) {
                loadFallbackDetail(enrichedRequest)?.let { return it }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                addTrailer(subject.trailer?.videoAddress?.url, addRaw = true)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                enrichedRequest.copy(season = null, episode = null).toJson()
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                addTrailer(subject.trailer?.videoAddress?.url, addRaw = true)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val media = MovieboxApi.loadData(data)
            ?: return loadDelegatedLinks(data, isCasting, subtitleCallback, callback)
        val id = media.id
            ?: return loadFallback(media, isCasting, subtitleCallback, callback)
        val detailPath = media.detailPath
            ?: return loadFallback(media, isCasting, subtitleCallback, callback)
        val downloadUrl = MovieboxApi.downloadUrl(
            mainUrl,
            id,
            media.season ?: 0,
            media.episode ?: 0,
            detailPath
        ) ?: return loadFallback(media, isCasting, subtitleCallback, callback)
        val payload = getApi(
            downloadUrl,
            MovieboxApi.apiHeaders,
            MOVIEBOX_JSON_BODY_LIMIT_BYTES
        )?.parse<MovieboxDownloadResponse>()?.data
            ?.takeIf { it.hasResource == true }
            ?: return loadFallback(media, isCasting, subtitleCallback, callback)

        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        payload.downloads.orEmpty()
            .asSequence()
            .filter { it.url?.let(::isSafeRemoteHttpUrl) == true }
            .distinctBy { it.url }
            .sortedByDescending { it.resolution ?: 0 }
            .take(48)
            .forEach { download ->
                if (!resolver.canContinue) return@forEach
                val streamUrl = download.url ?: return@forEach
                val resolution = download.resolution
                resolver.emitResolved(
                    newExtractorLink(
                        name,
                        resolution?.let { "$name ${it}p" } ?: name,
                        streamUrl,
                        INFER_TYPE
                    ) {
                        referer = MovieboxApi.referer
                        headers = MovieboxApi.mediaHeaders
                        quality = getQualityFromName(resolution?.toString())
                    }
                )
            }

        payload.captions.orEmpty()
            .distinctBy { it.url }
            .take(100)
            .forEach { caption ->
                movieboxSubtitleFile(caption.languageName, caption.url)?.let(subtitleCallback)
            }
        if (resolver.loaded) return true
        return loadFallback(media, isCasting, subtitleCallback, callback)
    }

    private suspend fun loadDelegatedDetail(url: String): LoadResponse? =
        withTimeoutOrNull(MOVIEBOX_CATALOG_FALLBACK_TIMEOUT_MS) {
            firstNonEmptyFallback(fallbackProviders()) { provider ->
                listOfNotNull(provider.load(url))
            }.firstOrNull()
        }

    private suspend fun loadFallbackDetail(media: MovieboxLoadData): LoadResponse? {
        val request = media.fallbackRequest() ?: return null
        return withTimeoutOrNull(MOVIEBOX_CATALOG_FALLBACK_TIMEOUT_MS) {
            for (provider in fallbackProviders()) {
                try {
                    val exactResults = provider.search(request.title).orEmpty()
                        .asSequence()
                        .filter {
                            MovieboxFallbackMatcher.isPotentialTitle(request, it.name)
                        }
                        .distinctBy { result -> result.url }
                        .take(MOVIEBOX_MAX_FALLBACK_RESULTS)
                        .toList()
                    for (result in exactResults) {
                        val detail = provider.load(result.url) ?: continue
                        if (media.matchesFallbackDetail(request, detail)) {
                            return@withTimeoutOrNull detail
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A failed fallback source must not hide the next exact provider.
                }
            }
            null
        }
    }

    private suspend fun loadDelegatedLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withPlaybackFallbackBudget(MOVIEBOX_PLAYBACK_FALLBACK_TIMEOUT_MS) {
        loadFirstEmittingFallback(
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
    }

    private suspend fun loadFallback(
        media: MovieboxLoadData,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = media.fallbackRequest() ?: return false
        return withPlaybackFallbackBudget(MOVIEBOX_PLAYBACK_FALLBACK_TIMEOUT_MS) {
            loadFirstEmittingFallback(
                candidates = fallbackProviders(),
                callback = callback
            ) { provider, fallbackCallback ->
                val exactResults = provider.search(request.title).orEmpty()
                    .asSequence()
                    .filter {
                        MovieboxFallbackMatcher.isPotentialTitle(request, it.name)
                    }
                    .distinctBy { result -> result.url }
                    .take(MOVIEBOX_MAX_FALLBACK_RESULTS)
                    .toList()
                for (result in exactResults) {
                    val detail = provider.load(result.url) ?: continue
                    val playbackData = when (detail) {
                        is MovieLoadResponse -> detail.dataUrl.takeIf {
                            MovieboxFallbackMatcher.acceptsMovie(
                                media,
                                request,
                                detail.name,
                                detail.year
                            )
                        }

                        is TvSeriesLoadResponse -> {
                            if (media.subjectType != null && media.subjectType != 2) {
                                null
                            } else {
                                MovieboxFallbackMatcher.episodeData(
                                    request = request,
                                    candidateTitle = detail.name,
                                    candidateYear = detail.year,
                                    episodes = detail.episodes
                                )
                            }
                        }

                        else -> null
                    } ?: continue
                    if (
                        provider.loadLinks(
                            playbackData,
                            isCasting,
                            subtitleCallback,
                            fallbackCallback
                        )
                    ) return@loadFirstEmittingFallback true
                }
                false
            }
        }
    }

    private fun MovieboxLoadData.fallbackRequest(): NomatFallbackRequest? {
        val normalizedTitle = MovieMetadataParser.title(title)
            ?.let { value ->
                year?.let { releaseYear ->
                    value.replace(
                        Regex("""\s*[\[(]\Q$releaseYear\E[\])]\s*$"""),
                        ""
                    ).trim()
                } ?: value
            }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return NomatFallbackRequest(
            title = normalizedTitle,
            year = year,
            season = season,
            episode = episode
        )
    }

    private fun MovieboxLoadData.matchesFallbackDetail(
        request: NomatFallbackRequest,
        detail: LoadResponse
    ): Boolean = when (detail) {
        is MovieLoadResponse ->
            MovieboxFallbackMatcher.acceptsMovie(
                this,
                request,
                detail.name,
                detail.year
            )

        is TvSeriesLoadResponse ->
            (subjectType == null || subjectType == 2) &&
                MovieboxFallbackMatcher.seriesMatchesRequest(
                    request,
                    detail.name,
                    detail.year,
                    detail.episodes
                )

        else -> false
    }

    private fun fallbackProviders(): List<MainAPI> = fallbackProviderFactory()

    private suspend fun fetchAuthorization(query: String): String? {
        val body = mapOf(
            "keyword" to query.ifBlank { "avatar" },
            "perPage" to 0
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val response = postApi(
            MovieboxApi.searchSuggestUrl(mainUrl),
            body,
            MovieboxApi.apiHeaders,
            MOVIEBOX_AUTH_BODY_LIMIT_BYTES
        ) ?: return null
        return MovieboxApi.authorizationHeader(response.header("x-user"))
    }

    private suspend fun getApi(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int
    ): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer { MovieboxApi.apiUrl(it, mainUrl) },
            headers = headers,
            maxBodyBytes = maxBodyBytes,
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).takeIf { it.code in 200..299 }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun postApi(
        url: String,
        body: okhttp3.RequestBody,
        headers: Map<String, String>,
        maxBodyBytes: Int
    ): ProviderHttpResult? = try {
        safeHttp.postBody(
            url = url,
            requestBody = body,
            normalizer = ProviderUrlNormalizer { MovieboxApi.apiUrl(it, mainUrl) },
            headers = headers,
            maxBodyBytes = maxBodyBytes,
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).takeIf { it.code in 200..299 }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private inline fun <reified T : Any> ProviderHttpResult.parse(): T? =
        body.takeUnless(ProviderHtmlParser::isNonContentPage)
            ?.let { runCatching { parseJson<T>(it) }.getOrNull() }

    private fun toSearchResponse(item: MovieboxItem): SearchResponse? {
        val id = item.subjectId?.takeIf(MovieboxApi::isValidSubjectId) ?: return null
        val detailPath = item.detailPath?.takeIf(MovieboxApi::isValidDetailPath) ?: return null
        val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (item.hasResource != true) return null
        val year = item.releaseDate?.substringBefore('-')?.toIntOrNull()
        val data = MovieboxLoadData(
            id = id,
            detailPath = detailPath,
            title = title,
            year = year,
            subjectType = item.subjectType
        ).toJson()
        if (
            SensitiveContentPolicy.isBlocked(
                title = title,
                url = detailPath,
                categories = item.genre.orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
            )
        ) return null
        return if (item.subjectType == 2) {
            newTvSeriesSearchResponse(title, data, TvType.TvSeries, false) {
                posterUrl = item.cover?.url
            }
        } else {
            newMovieSearchResponse(title, data, TvType.Movie, false) {
                posterUrl = item.cover?.url
            }
        }
    }

    private companion object {
        const val MOVIEBOX_AUTH_BODY_LIMIT_BYTES = 256_000
        const val MOVIEBOX_JSON_BODY_LIMIT_BYTES = 2_000_000
        const val MOVIEBOX_HOME_BODY_LIMIT_BYTES = 4_000_000
        const val MOVIEBOX_CATALOG_FALLBACK_TIMEOUT_MS = 60_000L
        const val MOVIEBOX_PLAYBACK_FALLBACK_TIMEOUT_MS = 90_000L
        const val MOVIEBOX_MAX_FALLBACK_RESULTS = 8
    }
}

internal object MovieboxApi {
    private const val PREFIX = "/wefeed-h5api-bff"
    private const val LEGACY_API_URL = "https://h5.aoneroom.com"
    private val subjectIdPattern = Regex("""^\d{1,32}$""")
    private val detailPathPattern = Regex("""^[\w-]+-\w{9,13}$""")
    const val referer = "https://videodownloader.site/"

    val apiHeaders = mapOf(
        "X-Client-Info" to """{"timezone":"Africa/Nairobi"}""",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept" to "application/json",
        "User-Agent" to
            "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Referer" to referer
    )

    val mediaHeaders = mapOf(
        "Accept" to "*/*",
        "User-Agent" to
            "Mozilla/5.0 (X11; Linux x86_64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Origin" to "https://videodownloader.site",
        "Referer" to referer
    )

    val legacyApiHeaders = apiHeaders + ("Referer" to "$LEGACY_API_URL/")

    fun homeUrl(mainUrl: String): String =
        "${mainUrl.trimEnd('/')}$PREFIX/home?host=moviebox.ph"

    fun searchUrl(mainUrl: String): String =
        "${mainUrl.trimEnd('/')}$PREFIX/subject/search"

    fun searchSuggestUrl(mainUrl: String): String =
        "${mainUrl.trimEnd('/')}$PREFIX/subject/search-suggest"

    fun apiUrl(raw: String?, mainUrl: String): String? {
        val value = raw?.trim()?.takeIf {
            it.isNotEmpty() &&
                it.length <= 8_192 &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
        } ?: return null
        return runCatching {
            val uri = URI(value)
            val current = URI(mainUrl)
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return@runCatching null
            val allowedHosts = setOf(
                current.host?.lowercase()?.trimEnd('.'),
                URI(LEGACY_API_URL).host?.lowercase()
            ).filterNotNull()
            if (
                uri.scheme?.lowercase() != "https" ||
                host !in allowedHosts ||
                uri.userInfo != null ||
                uri.rawFragment != null ||
                uri.port !in setOf(-1, 443)
            ) return@runCatching null
            uri.toASCIIString()
        }.getOrNull()
    }

    fun detailUrl(mainUrl: String, detailPath: String): String? {
        if (!isValidDetailPath(detailPath)) return null
        return "${mainUrl.trimEnd('/')}$PREFIX/detail?detailPath=${encode(detailPath)}"
    }

    fun legacyDetailUrl(subjectId: String): String? {
        if (!isValidSubjectId(subjectId)) return null
        return "$LEGACY_API_URL/wefeed-h5-bff/web/subject/detail?subjectId=${encode(subjectId)}"
    }

    fun downloadUrl(
        mainUrl: String,
        subjectId: String,
        season: Int,
        episode: Int,
        detailPath: String
    ): String? {
        if (!isValidSubjectId(subjectId) || !isValidDetailPath(detailPath)) return null
        if (season !in 0..10_000 || episode !in 0..10_000) return null
        return "${mainUrl.trimEnd('/')}$PREFIX/subject/download" +
            "?subjectId=${encode(subjectId)}&se=$season&ep=$episode" +
            "&detailPath=${encode(detailPath)}"
    }

    fun authorizationHeader(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.length in 2..4_096 } ?: return null
        val token = runCatching { parseJson<MovieboxUserInfo>(value).token }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.length in 1..2_048 }
            ?.takeIf { it.all { character -> character.code in 0x21..0x7e } }
            ?: return null
        return "Bearer $token"
    }

    fun loadData(raw: String): MovieboxLoadData? {
        val value = raw.trim().takeIf { it.isNotEmpty() && it.length <= 4_096 } ?: return null
        if (isValidSubjectId(value)) return MovieboxLoadData(id = value)
        val decoded = runCatching { parseJson<MovieboxLoadData>(value) }.getOrNull()
            ?: return null
        val id = decoded.id?.takeIf(::isValidSubjectId) ?: return null
        val detailPath = decoded.detailPath?.takeIf(::isValidDetailPath)
        if (decoded.detailPath != null && detailPath == null) return null
        val season = decoded.season?.takeIf { it in 0..10_000 }
        if (decoded.season != null && season == null) return null
        val episode = decoded.episode?.takeIf { it in 0..10_000 }
        if (decoded.episode != null && episode == null) return null
        val title = decoded.title?.trim()?.takeIf {
            it.isNotBlank() &&
                it.length <= 512 &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
        }
        if (decoded.title != null && title == null) return null
        val year = decoded.year?.takeIf { it in 1800..2200 }
        if (decoded.year != null && year == null) return null
        val subjectType = decoded.subjectType?.takeIf { it in 0..10 }
        if (decoded.subjectType != null && subjectType == null) return null
        return decoded.copy(
            id = id,
            season = season,
            episode = episode,
            detailPath = detailPath,
            title = title,
            year = year,
            subjectType = subjectType
        )
    }

    fun isValidSubjectId(value: String): Boolean = subjectIdPattern.matches(value)

    fun isValidDetailPath(value: String): Boolean =
        value.length <= 256 && detailPathPattern.matches(value)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

internal object MovieboxFallbackMatcher {
    private const val MAX_RELEASE_YEAR_DRIFT = 2
    private val trailingReleaseYearRegex = Regex(
        """\s*(?:[\[(]\s*((?:19|20)\d{2})\s*[\])]|[-–—|:]\s*((?:19|20)\d{2}))\s*$"""
    )

    fun isPotentialTitle(request: NomatFallbackRequest, candidateTitle: String): Boolean {
        if (NomatParser.isPotentialFallbackTitle(request, candidateTitle)) return true
        val expectedYear = request.year ?: return false
        val parsedTitle = MovieMetadataParser.title(candidateTitle) ?: return false
        val yearMatch = trailingReleaseYearRegex.find(parsedTitle) ?: return false
        val candidateYear = yearMatch.groupValues
            .drop(1)
            .firstNotNullOfOrNull(String::toIntOrNull)
            ?: return false
        if (kotlin.math.abs(candidateYear - expectedYear) > MAX_RELEASE_YEAR_DRIFT) {
            return false
        }
        val withoutYear = parsedTitle.removeRange(yearMatch.range).trim()
        return NomatParser.isExactFallbackTitle(request.title, withoutYear)
    }

    fun acceptsMovie(
        media: MovieboxLoadData,
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?
    ): Boolean {
        if (!isExactMatch(request, candidateTitle, candidateYear)) return false
        val provenTypeDrift = media.subjectType == 2 &&
            hasReleaseYearDrift(request, candidateTitle, candidateYear)
        if (
            (request.season != null || request.episode != null) &&
            !provenTypeDrift
        ) return false
        return media.subjectType != 2 || provenTypeDrift
    }

    fun episodeData(
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?,
        episodes: List<Episode>
    ): String? {
        if (!isExactMatch(request, candidateTitle, candidateYear)) return null
        val expectedEpisode = request.episode ?: return null
        return episodes.filter { episode ->
            episode.episode == expectedEpisode &&
                (request.season == null || episode.season == request.season)
        }.singleOrNull()?.data?.takeIf { it.isNotBlank() }
    }

    fun seriesMatchesRequest(
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?,
        episodes: List<Episode>
    ): Boolean {
        if (!isExactMatch(request, candidateTitle, candidateYear)) return false
        if (request.episode != null) {
            return episodeData(
                request,
                candidateTitle,
                candidateYear,
                episodes
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

    private fun isExactMatch(
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?
    ): Boolean {
        if (!isPotentialTitle(request, candidateTitle)) return false
        val expectedYear = request.year ?: return true
        val resolvedYear = candidateYear ?: releaseYearInTitle(candidateTitle)
        return resolvedYear != null &&
            kotlin.math.abs(resolvedYear - expectedYear) <= MAX_RELEASE_YEAR_DRIFT
    }

    private fun hasReleaseYearDrift(
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?
    ): Boolean {
        val expectedYear = request.year ?: return false
        val resolvedYear = candidateYear ?: releaseYearInTitle(candidateTitle) ?: return false
        return resolvedYear != expectedYear &&
            kotlin.math.abs(resolvedYear - expectedYear) <= MAX_RELEASE_YEAR_DRIFT
    }

    private fun releaseYearInTitle(rawTitle: String): Int? {
        val parsedTitle = MovieMetadataParser.title(rawTitle) ?: return null
        return trailingReleaseYearRegex.find(parsedTitle)
            ?.groupValues
            ?.drop(1)
            ?.firstNotNullOfOrNull(String::toIntOrNull)
    }
}

private const val MOVIEBOX_MAX_SEASONS = 100
private const val MOVIEBOX_MAX_EPISODES_PER_SEASON = 2_000
private const val MOVIEBOX_MAX_TOTAL_EPISODES = 5_000
private const val MOVIEBOX_SEARCH_ATTEMPTS = 2
private const val MOVIEBOX_SEARCH_ATTEMPT_TIMEOUT_MS = 30_000L
private const val MOVIEBOX_SEARCH_RESULT_LIMIT = 30

internal suspend fun resolveMovieboxSearchCandidates(
    query: String,
    remoteSearch: suspend () -> List<MovieboxItem>,
    homepageFallback: suspend () -> List<MovieboxItem>
): List<MovieboxItem> {
    repeat(MOVIEBOX_SEARCH_ATTEMPTS) {
        val candidates = try {
            withTimeoutOrNull(MOVIEBOX_SEARCH_ATTEMPT_TIMEOUT_MS) {
                remoteSearch()
            }.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }.filter(::isUsableMovieboxSearchItem)
            .distinctBy { it.subjectId }
            .take(MOVIEBOX_SEARCH_RESULT_LIMIT)

        if (candidates.isNotEmpty()) return candidates
    }

    val queryTokens = movieboxSearchTokens(query)
    if (queryTokens.isEmpty()) return emptyList()
    val homepageItems = try {
        withTimeoutOrNull(MOVIEBOX_SEARCH_ATTEMPT_TIMEOUT_MS) {
            homepageFallback()
        }.orEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }
    return homepageItems.asSequence()
        .filter(::isUsableMovieboxSearchItem)
        .filter { item ->
            val titleTokens = movieboxSearchTokens(item.title.orEmpty()).toSet()
            queryTokens.all(titleTokens::contains)
        }
        .distinctBy { it.subjectId }
        .take(MOVIEBOX_SEARCH_RESULT_LIMIT)
        .toList()
}

private fun isUsableMovieboxSearchItem(item: MovieboxItem): Boolean =
    item.hasResource == true &&
        item.subjectId?.let(MovieboxApi::isValidSubjectId) == true &&
        item.detailPath?.let(MovieboxApi::isValidDetailPath) == true &&
        !item.title.isNullOrBlank()

private fun movieboxSearchTokens(raw: String): List<String> =
    Regex("""[\p{L}\p{N}]+""")
        .findAll(raw.lowercase())
        .map { it.value }
        .filter { it.isNotBlank() }
        .take(12)
        .toList()

internal fun movieboxEpisodeNumbers(raw: String?, maxEpisode: Int?): List<Int> {
    val declared = raw.orEmpty()
        .splitToSequence(',')
        .take(MOVIEBOX_MAX_EPISODES_PER_SEASON)
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..MOVIEBOX_MAX_EPISODES_PER_SEASON }
        .toList()
    return declared.distinct().sorted().ifEmpty {
        maxEpisode
            ?.takeIf { it > 0 }
            ?.coerceAtMost(MOVIEBOX_MAX_EPISODES_PER_SEASON)
            ?.let { (1..it).toList() }
            .orEmpty()
    }
}

internal fun movieboxEpisodeCoordinates(
    seasons: List<MovieboxDetailResponse.Season>?
): List<Pair<Int?, Int>> = seasons.orEmpty()
    .asSequence()
    .take(MOVIEBOX_MAX_SEASONS)
    .filter { season -> season.season == null || season.season in 0..10_000 }
    .flatMap { season ->
        movieboxEpisodeNumbers(season.allEp, season.maxEp)
            .asSequence()
            .map { episode -> season.season to episode }
    }
    .distinct()
    .take(MOVIEBOX_MAX_TOTAL_EPISODES)
    .toList()

internal suspend fun movieboxSubtitleFile(language: String?, rawUrl: String?): SubtitleFile? {
    val url = rawUrl?.takeIf(::isSafeRemoteHttpUrl) ?: return null
    return newSubtitleFile(language?.takeIf { it.isNotBlank() } ?: "Subtitle", url)
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovieboxUserInfo(
    @JsonProperty("token") val token: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovieboxLoadData(
    val id: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val detailPath: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val subjectType: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovieboxHomeResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("data") val data: Data? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Data(
        @JsonProperty("operatingList") val operatingList: List<Operating>? = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Operating(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("subjects") val subjects: List<MovieboxItem>? = emptyList(),
        @JsonProperty("banner") val banner: Banner? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Banner(@JsonProperty("items") val items: List<BannerItem>? = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BannerItem(@JsonProperty("subject") val subject: MovieboxItem? = null)

    fun availableItems(): List<MovieboxItem> {
        return data?.operatingList.orEmpty()
            .flatMap { operating ->
                operating.subjects.orEmpty() +
                    operating.banner?.items.orEmpty().mapNotNull { it.subject }
            }
            .filter { it.hasResource == true }
            .distinctBy { it.subjectId }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovieboxSearchResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("data") val data: Data? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Data(@JsonProperty("items") val items: List<MovieboxItem>? = emptyList())

    fun availableItems(): List<MovieboxItem> =
        data?.items.orEmpty().filter { it.hasResource == true }.distinctBy { it.subjectId }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovieboxDetailResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("data") val data: Data? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Data(
        @JsonProperty("subject") val subject: MovieboxItem? = null,
        @JsonProperty("resource") val resource: Resource? = null,
        @JsonProperty("isForbid") val isForbid: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Resource(@JsonProperty("seasons") val seasons: List<Season>? = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(
        @JsonProperty("se") val season: Int? = null,
        @JsonProperty("maxEp") val maxEp: Int? = null,
        @JsonProperty("allEp") val allEp: String? = null
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovieboxDownloadResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("data") val data: Data? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Data(
        @JsonProperty("downloads") val downloads: List<Download>? = emptyList(),
        @JsonProperty("captions") val captions: List<Caption>? = emptyList(),
        @JsonProperty("limited") val limited: Boolean? = null,
        @JsonProperty("limitedCode") val limitedCode: String? = null,
        @JsonProperty("hasResource") val hasResource: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Download(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: Int? = null,
        @JsonProperty("size") val size: Any? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Caption(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("lan") val language: String? = null,
        @JsonProperty("lanName") val languageName: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("size") val size: Any? = null,
        @JsonProperty("delay") val delay: Int? = null
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MovieboxItem(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("subjectType") val subjectType: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("cover") val cover: Cover? = null,
    @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
    @JsonProperty("trailer") val trailer: Trailer? = null,
    @JsonProperty("detailPath") val detailPath: String? = null,
    @JsonProperty("hasResource") val hasResource: Boolean? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Cover(@JsonProperty("url") val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Trailer(@JsonProperty("videoAddress") val videoAddress: VideoAddress? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class VideoAddress(@JsonProperty("url") val url: String? = null)
    }
}
