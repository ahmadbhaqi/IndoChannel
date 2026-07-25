package com.example

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
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class MovieboxProvider : MainAPI() {
    override var mainUrl = "https://moviebox.ph"
    override var name = "Moviebox"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val playbackApiUrl = "https://fmoviesunblocked.net"

    override val mainPage = mainPageOf(
        "872031290915189720" to "Trending",
        "997144265920760504" to "Film Populer",
        "5283462032510044280" to "Drama Indonesia",
        "6528093688173053896" to "Film Indonesia",
        "4380734070238626200" to "K-Drama",
        "7736026911486755336" to "Serial Barat",
        "5404290953194750296" to "Anime Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(
            "$mainUrl/wefeed-h5-bff/web/ranking-list/content" +
                "?id=${request.data}&page=${page.coerceAtLeast(1)}&perPage=20",
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).parsedSafe<MovieboxMediaResponse>()
        val items = response?.data?.subjectList.orEmpty().mapNotNull(::toSearchResponse)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val body = mapOf(
            "keyword" to query,
            "page" to "1",
            "perPage" to "30",
            "subjectType" to "0"
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val response = app.post(
            "$mainUrl/wefeed-h5-bff/web/subject/search",
            requestBody = body,
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).parsedSafe<MovieboxMediaResponse>()
        return response?.data?.items.orEmpty().mapNotNull(::toSearchResponse)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: return null
        val detail = app.get(
            "$mainUrl/wefeed-h5-bff/web/subject/detail?subjectId=$id",
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).parsedSafe<MovieboxDetailResponse>()?.data ?: return null
        val subject = detail.subject ?: return null
        val title = subject.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val type = if (subject.subjectType == 2) TvType.TvSeries else TvType.Movie
        val poster = subject.cover?.url
        val year = subject.releaseDate?.substringBefore('-')?.toIntOrNull()
        val tags = subject.genre?.split(',')?.map(String::trim)?.filter(String::isNotBlank)
        val score = Score.from10(subject.imdbRatingValue?.toDoubleOrNull())
        val recommendations = try {
            app.get(
                "$mainUrl/wefeed-h5-bff/web/subject/detail-rec?subjectId=$id&page=1&perPage=16",
                timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
            ).parsedSafe<MovieboxMediaResponse>()?.data?.items.orEmpty().mapNotNull(::toSearchResponse)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }

        return if (type == TvType.TvSeries) {
            val episodes = detail.resource?.seasons.orEmpty().flatMap { season ->
                movieboxEpisodeNumbers(season.allEp, season.maxEp).map { episode ->
                    newEpisode(
                        MovieboxLoadData(id, season.season, episode, subject.detailPath).toJson()
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
                plot = subject.description
                this.tags = tags
                this.score = score
                this.recommendations = recommendations
                addTrailer(subject.trailer?.videoAddress?.url, addRaw = true)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                MovieboxLoadData(id, detailPath = subject.detailPath).toJson()
            ) {
                posterUrl = poster
                this.year = year
                plot = subject.description
                this.tags = tags
                this.score = score
                this.recommendations = recommendations
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
        val media = runCatching { parseJson<MovieboxLoadData>(data) }.getOrNull() ?: return false
        val id = media.id ?: return false
        val detailPath = media.detailPath.orEmpty()
        val referer =
            "$playbackApiUrl/spa/videoPlayPage/movies/$detailPath?id=$id&type=/movie/detail&lang=en"
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val streams = try {
            app.get(
                "$playbackApiUrl/wefeed-h5-bff/web/subject/play" +
                    "?subjectId=$id&se=${media.season ?: 0}&ep=${media.episode ?: 0}",
                referer = referer,
                timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
            ).parsedSafe<MovieboxMediaResponse>()?.data?.streams.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }

        streams.asReversed().distinctBy { it.url }.take(48).forEach { stream ->
            if (!resolver.canContinue) return@forEach
            val streamUrl = stream.url ?: return@forEach
            resolver.emitResolved(
                newExtractorLink(name, "$name ${stream.resolutions.orEmpty()}", streamUrl, INFER_TYPE) {
                    this.referer = "$playbackApiUrl/"
                    this.quality = getQualityFromName(stream.resolutions)
                }
            )
        }

        streams.firstOrNull()?.let { stream ->
            try {
                app.get(
                    "$playbackApiUrl/wefeed-h5-bff/web/subject/caption" +
                        "?format=${stream.format.orEmpty()}&id=${stream.id.orEmpty()}&subjectId=$id",
                    referer = referer,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).parsedSafe<MovieboxMediaResponse>()?.data?.captions.orEmpty()
                    .distinctBy { it.url }
                    .take(100)
                    .forEach { caption ->
                    movieboxSubtitleFile(caption.languageName, caption.url)
                        ?.let(subtitleCallback)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Playback remains usable when the optional caption endpoint is unavailable.
            }
        }
        return resolver.loaded
    }

    private fun toSearchResponse(item: MovieboxItem): SearchResponse? {
        val id = item.subjectId ?: return null
        val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val type = if (item.subjectType == 2) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, id, type, false) {
            posterUrl = item.cover?.url
        }
    }
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

internal data class MovieboxLoadData(
    val id: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val detailPath: String? = null
)

internal data class MovieboxMediaResponse(@JsonProperty("data") val data: Data? = null) {
    data class Data(
        @JsonProperty("subjectList") val subjectList: List<MovieboxItem>? = emptyList(),
        @JsonProperty("items") val items: List<MovieboxItem>? = emptyList(),
        @JsonProperty("streams") val streams: List<Stream>? = emptyList(),
        @JsonProperty("captions") val captions: List<Caption>? = emptyList()
    )

    data class Stream(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolutions") val resolutions: String? = null
    )

    data class Caption(
        @JsonProperty("lanName") val languageName: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}

internal data class MovieboxDetailResponse(@JsonProperty("data") val data: Data? = null) {
    data class Data(
        @JsonProperty("subject") val subject: MovieboxItem? = null,
        @JsonProperty("resource") val resource: Resource? = null
    )

    data class Resource(@JsonProperty("seasons") val seasons: List<Season>? = emptyList())

    data class Season(
        @JsonProperty("se") val season: Int? = null,
        @JsonProperty("maxEp") val maxEp: Int? = null,
        @JsonProperty("allEp") val allEp: String? = null
    )
}

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
    @JsonProperty("detailPath") val detailPath: String? = null
) {
    data class Cover(@JsonProperty("url") val url: String? = null)
    data class Trailer(@JsonProperty("videoAddress") val videoAddress: VideoAddress? = null) {
        data class VideoAddress(@JsonProperty("url") val url: String? = null)
    }
}
