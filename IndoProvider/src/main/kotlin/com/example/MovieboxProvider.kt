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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class MovieboxProvider : MainAPI() {
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
        return response?.availableItems().orEmpty().mapNotNull(::toSearchResponse)
    }

    override suspend fun load(url: String): LoadResponse? {
        val request = MovieboxApi.loadData(url) ?: return null
        val id = request.id ?: return null
        val legacyRequest = request.detailPath == null
        val detailUrl = if (legacyRequest) {
            MovieboxApi.legacyDetailUrl(id)
        } else {
            MovieboxApi.detailUrl(mainUrl, request.detailPath ?: return null)
        } ?: return null
        val detail = getApi(
            detailUrl,
            if (legacyRequest) MovieboxApi.legacyApiHeaders else MovieboxApi.apiHeaders,
            MOVIEBOX_JSON_BODY_LIMIT_BYTES
        )?.parse<MovieboxDetailResponse>()?.data ?: return null
        if (detail.isForbid == true) return null
        val subject = detail.subject?.takeIf {
            if (legacyRequest) it.hasResource != false else it.hasResource == true
        } ?: return null
        val detailPath = subject.detailPath
            ?.takeIf(MovieboxApi::isValidDetailPath)
            ?: request.detailPath
            ?: return null
        val title = subject.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val type = if (subject.subjectType == 2) TvType.TvSeries else TvType.Movie
        val poster = subject.cover?.url
        val year = subject.releaseDate?.substringBefore('-')?.toIntOrNull()
        val tags = subject.genre?.split(',')?.map(String::trim)?.filter(String::isNotBlank)
        val score = Score.from10(subject.imdbRatingValue?.toDoubleOrNull())
        val plot = MovieMetadataParser.meaningfulDescription(subject.description)

        return if (type == TvType.TvSeries) {
            val episodes = detail.resource?.seasons.orEmpty().flatMap { season ->
                movieboxEpisodeNumbers(season.allEp, season.maxEp).map { episode ->
                    newEpisode(
                        MovieboxLoadData(id, season.season, episode, detailPath).toJson()
                    ) {
                        this.season = season.season
                        this.episode = episode
                        name = "Episode $episode"
                        posterUrl = poster
                    }
                }
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
                MovieboxLoadData(id, detailPath = detailPath).toJson()
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
        val media = runCatching { parseJson<MovieboxLoadData>(data) }.getOrNull()
            ?: return false
        val id = media.id ?: return false
        val detailPath = media.detailPath ?: return false
        val downloadUrl = MovieboxApi.downloadUrl(
            mainUrl,
            id,
            media.season ?: 0,
            media.episode ?: 0,
            detailPath
        ) ?: return false
        val payload = getApi(
            downloadUrl,
            MovieboxApi.apiHeaders,
            MOVIEBOX_JSON_BODY_LIMIT_BYTES
        )?.parse<MovieboxDownloadResponse>()?.data
            ?.takeIf { it.hasResource == true }
            ?: return false

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
        return resolver.loaded
    }

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

    private inline fun <reified T> ProviderHttpResult.parse(): T? =
        body.takeUnless(ProviderHtmlParser::isNonContentPage)
            ?.let { runCatching { parseJson<T>(it) }.getOrNull() }

    private fun toSearchResponse(item: MovieboxItem): SearchResponse? {
        val id = item.subjectId?.takeIf(MovieboxApi::isValidSubjectId) ?: return null
        val detailPath = item.detailPath?.takeIf(MovieboxApi::isValidDetailPath) ?: return null
        val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (item.hasResource != true) return null
        val data = MovieboxLoadData(id = id, detailPath = detailPath).toJson()
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
        return decoded.copy(id = id, detailPath = detailPath)
    }

    fun isValidSubjectId(value: String): Boolean = subjectIdPattern.matches(value)

    fun isValidDetailPath(value: String): Boolean =
        value.length <= 256 && detailPathPattern.matches(value)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

internal fun movieboxEpisodeNumbers(raw: String?, maxEpisode: Int?): List<Int> {
    val declared = raw.orEmpty()
        .splitToSequence(',')
        .take(10_000)
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..10_000 }
        .toList()
    return declared.distinct().sorted().ifEmpty {
        maxEpisode?.takeIf { it in 1..10_000 }?.let { (1..it).toList() }.orEmpty()
    }
}

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
    val detailPath: String? = null
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
