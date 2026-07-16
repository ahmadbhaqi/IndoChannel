package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val apiUrl = "$mainUrl/api"
    private val apiHeaders = mapOf("Accept" to "application/json,text/plain,*/*")

    override val mainPage = mainPageOf(
        "movies?page=%d&limit=24" to "Movies",
        "series?page=%d&limit=24" to "Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = app.get("$apiUrl/${request.data.format(page)}", headers = apiHeaders).text
        return newHomePageResponse(request.name, IdlixApiParser.catalogItems(json).mapNotNull { it.toSearchResponse() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = app.get("$apiUrl/search?q=$encoded&page=1&limit=30", headers = apiHeaders).text
        return IdlixApiParser.catalogItems(json).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() } ?: return null
        val isSeries = url.contains("/series/")
        val json = app.get("$apiUrl/${if (isSeries) "series" else "movies"}/$slug", headers = apiHeaders).text
        val item = IdlixApiParser.catalogItem(json)?.copy(
            contentType = if (isSeries) "tv_series" else "movie"
        ) ?: return null

        return if (item.isSeries) {
            val episodes = IdlixApiParser.seasons(json)
                .flatMap { season ->
                    val seasonJson = try {
                        app.get("$apiUrl/series/${item.slug}/season/${season.seasonNumber}", headers = apiHeaders).text
                    } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    seasonJson?.let { IdlixApiParser.seasonEpisodes(it) }
                        .orEmpty()
                }
                .map { episode ->
                    newEpisode(IdlixStreamData("episode", episode.id, item.slug).serialize()) {
                        name = episode.name
                        season = episode.seasonNumber
                        this.episode = episode.episodeNumber
                        posterUrl = IdlixApiParser.imageUrl(episode.stillPath)
                        description = episode.overview
                    }
                }

            newTvSeriesLoadResponse(item.title, url, TvType.TvSeries, episodes) {
                posterUrl = IdlixApiParser.imageUrl(item.posterPath)
                backgroundPosterUrl = IdlixApiParser.imageUrl(item.backdropPath, "original")
                year = item.year
                plot = item.overview
                tags = item.genres
                addTrailer(item.trailerUrl)
            }
        } else {
            newMovieLoadResponse(item.title, url, TvType.Movie, IdlixStreamData("movie", item.id, item.slug).serialize()) {
                posterUrl = IdlixApiParser.imageUrl(item.posterPath)
                backgroundPosterUrl = IdlixApiParser.imageUrl(item.backdropPath, "original")
                year = item.year
                plot = item.overview
                tags = item.genres
                duration = item.runtime
                addTrailer(item.trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamData = IdlixStreamData.parse(data) ?: return false
        val playInfo = try {
            app.get("$apiUrl/watch/play-info/${streamData.contentType}/${streamData.contentId}", headers = apiHeaders).text
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }

        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        IdlixApiParser.playableUrls(playInfo).forEach { raw ->
            resolver.resolve(raw, mainUrl)
        }
        return resolver.loaded
    }

    private fun IdlixCatalogItem.toSearchResponse(): SearchResponse? {
        val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
        val poster = IdlixApiParser.imageUrl(posterPath)
        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                quality = getQualityFromString(this@toSearchResponse.quality)
            }
        }
    }
}

internal data class IdlixCatalogItem(
    val id: String,
    val title: String,
    val slug: String,
    val contentType: String = "movie",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val quality: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val trailerUrl: String? = null
) {
    val isSeries: Boolean get() = contentType == "tv_series"
    val year: Int? get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

internal data class IdlixSeasonItem(
    val seasonNumber: Int
)

internal data class IdlixEpisodeItem(
    val id: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null
)

internal data class IdlixStreamData(
    val contentType: String,
    val contentId: String,
    val slug: String
) {
    fun serialize(): String = listOf(PREFIX, contentType, contentId, slug).joinToString("|")

    companion object {
        private const val PREFIX = "idlix"

        fun parse(data: String): IdlixStreamData? {
            val parts = data.split("|")
            if (parts.size != 4 || parts[0] != PREFIX) return null
            return IdlixStreamData(parts[1], parts[2], parts[3])
        }
    }
}

internal object IdlixApiParser {
    private const val tmdbImageBase = "https://image.tmdb.org/t/p"
    private val mapper = jacksonObjectMapper()

    fun catalogItems(json: String): List<IdlixCatalogItem> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        val items = when {
            root["data"]?.isArray == true -> root["data"]
            root["results"]?.isArray == true -> root["results"]
            root.isArray -> root
            else -> return emptyList()
        }
        return items.mapNotNull { it.toCatalogItem() }
    }

    fun catalogItem(json: String): IdlixCatalogItem? {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        return root.toCatalogItem()
    }

    fun seasons(json: String): List<IdlixSeasonItem> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        return root["seasons"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { season ->
                season.intOrNull("seasonNumber")?.let { IdlixSeasonItem(it) }
            }
            .orEmpty()
    }

    fun seasonEpisodes(json: String): List<IdlixEpisodeItem> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        val season = root["season"] ?: root
        val seasonNumber = season.intOrNull("seasonNumber")
        return season["episodes"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { episode ->
                val id = episode.textOrNull("id") ?: return@mapNotNull null
                IdlixEpisodeItem(
                    id = id,
                    seasonNumber = seasonNumber,
                    episodeNumber = episode.intOrNull("episodeNumber"),
                    name = episode.textOrNull("name"),
                    overview = episode.textOrNull("overview"),
                    stillPath = episode.textOrNull("stillPath"),
                    airDate = episode.textOrNull("airDate")
                )
            }
            .orEmpty()
    }

    fun playableUrls(json: String): List<String> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        return root.findPlayableUrls().distinct()
    }

    fun imageUrl(path: String?, size: String = "w500"): String? {
        val value = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "$tmdbImageBase/$size$value"
            else -> value
        }
    }

    private fun JsonNode.toCatalogItem(): IdlixCatalogItem? {
        val id = textOrNull("id") ?: return null
        val title = textOrNull("title") ?: return null
        val slug = textOrNull("slug") ?: return null
        return IdlixCatalogItem(
            id = id,
            title = title,
            slug = slug,
            contentType = textOrNull("contentType") ?: "movie",
            posterPath = textOrNull("posterPath"),
            backdropPath = textOrNull("backdropPath"),
            overview = textOrNull("overview"),
            releaseDate = textOrNull("releaseDate"),
            firstAirDate = textOrNull("firstAirDate"),
            quality = textOrNull("quality"),
            genres = genres(),
            runtime = intOrNull("runtime"),
            trailerUrl = textOrNull("trailerUrl")
        )
    }

    private fun JsonNode.genres(): List<String> {
        return get("genres")
            ?.takeIf { it.isArray }
            ?.mapNotNull { genre ->
                if (genre.isObject) genre.textOrNull("name") else genre.asText().takeIf { it.isNotBlank() }
            }
            .orEmpty()
    }

    private fun JsonNode.findPlayableUrls(): List<String> {
        val own = if (isObject) {
            fields().asSequence().mapNotNull { (key, value) ->
                if (key in playableKeys && value.isTextual) value.asText().takeIf { it.isPlayableUrl() } else null
            }.toList()
        } else {
            emptyList()
        }

        val children = elements().asSequence().flatMap { it.findPlayableUrls().asSequence() }.toList()
        return own + children
    }

    private fun String.isPlayableUrl(): Boolean {
        val value = trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        val lower = value.lowercase()
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg").none { lower.contains(it) } &&
            !lower.contains("blogger.googleusercontent.com")
    }

    private fun JsonNode.textOrNull(name: String): String? {
        return get(name)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JsonNode.intOrNull(name: String): Int? {
        return get(name)?.takeIf { !it.isNull }?.asInt()
    }

    private val playableKeys = setOf("url", "src", "file", "link", "embedUrl", "streamUrl", "source")
}
