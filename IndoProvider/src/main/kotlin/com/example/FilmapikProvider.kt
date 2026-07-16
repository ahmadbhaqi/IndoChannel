package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmapikProvider : MainAPI() {
    override var mainUrl = "https://filmapik.college"
    override var name = "Filmapik"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "category/box-office/page/%d" to "Box Office",
        "release-year/2026/page/%d" to "2026",
        "tvshows-genre/k-drama/page/%d" to "K-Drama"
    )

    private val jsonMapper = jacksonObjectMapper()
    private val searchResultType = jsonMapper.typeFactory.constructMapType(
        Map::class.java,
        String::class.java,
        FilmapikSearchItem::class.java
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.takeIf { it.isNotBlank() }?.format(page)?.let { "/$it" }.orEmpty()
        val document = app.get("$mainUrl$path").document
        return newHomePageResponse(request.name, document.toMovieResults())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val nonce = app.get(mainUrl).document
            .html()
            .substringAfter("\"searchNonce\":\"", "")
            .substringBefore("\"")
            .takeIf { it.isNotBlank() }
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = if (nonce != null) {
            val json = app.get("$mainUrl/wp-json/filmapik/search/?keyword=$encodedQuery&nonce=$nonce").text
            runCatching {
                jsonMapper.readValue<Map<String, FilmapikSearchItem>>(json, searchResultType)
            }.getOrNull()
                ?.values
                ?.mapNotNull { it.toSearchResponse() }
                .orEmpty()
        } else {
            emptyList()
        }

        return results.ifEmpty {
            app.get("$mainUrl/?s=$encodedQuery").document.toMovieResults()
        }
    }

    private fun Document.toMovieResults(): List<SearchResponse> {
        return select("a[href*='/nonton-film-']:has(img), a[href*='/tvshows/']:has(img)")
            .mapNotNull { it.toMovieResult() }
            .distinctBy { it.url }
    }

    private fun Element.toMovieResult(): SearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val image = selectFirst("img")
        val title = image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(image))
        val quality = selectFirst(".badge-quality")?.text()?.trim()
        val type = if (href.contains("/tvshows/", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title.cleanTitle(), fixUrl(href), type) {
            posterUrl = poster
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, meta[property=og:title]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = document.selectFirst("script[type='application/ld+json']:contains(image)")
            ?.data()
            ?.let { Regex("\"image\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: fixUrlNull(ProviderHtmlParser.imageSource(document.selectFirst(".detail-poster img, img[alt*='Nonton']")))
        val description = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = document.select("meta[property=article:tag]").mapNotNull {
            it.attr("content").takeIf { tag -> tag.isNotBlank() }
        }

        val isSeries = url.contains("/tvshows/", ignoreCase = true)
        return if (isSeries) {
            val episodes = document.select("a.famv-episode-btn[href]")
                .mapNotNull { link ->
                    val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val episodeNumber = Regex("""(?:episode-|EP)(\d+)""", RegexOption.IGNORE_CASE)
                        .find("$href ${link.text()}")
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    val seasonNumber = link.closest(".famv-season-list")
                        ?.attr("data-season")
                        ?.toIntOrNull()
                        ?: Regex("""season-(\d+)""", RegexOption.IGNORE_CASE)
                            .find(href)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    newEpisode(fixUrl(href)) {
                        name = link.attr("title").takeIf { it.isNotBlank() } ?: link.text()
                        season = seasonNumber
                        episode = episodeNumber
                        posterUrl = poster
                    }
                }
                .distinctBy { it.data }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val pages = listOf(data.trimEnd('/') + "/play", data).distinct()
        pages.forEach { page ->
            try {
                val fetch = app.get(page, referer = data)
                val document = fetch.document
                val servers = (
                    ProviderHtmlParser.mediaSources(document) +
                        document.select("[data-url]").mapNotNull {
                            it.attr("data-url").takeIf { value -> value.isNotBlank() }
                        } +
                        document.select("select#player-select option[value]").mapNotNull {
                            it.attr("value").takeIf { value -> value.isNotBlank() }
                        } +
                        document.select("a.player-option[href]").mapNotNull {
                            it.attr("href").takeIf { value -> value.isNotBlank() }
                        }
                    ).distinct()
                servers.forEach { raw -> resolver.resolve(raw, fetch.url) }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        return resolver.loaded
    }

    data class FilmapikSearchItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    private fun FilmapikSearchItem.toSearchResponse(): SearchResponse? {
        val safeTitle = title?.cleanTitle()?.takeIf { it.isNotBlank() } ?: return null
        val safeUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val type = if (safeUrl.contains("/tvshows/", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(safeTitle, safeUrl, type) {
            posterUrl = img
        }
    }

    private fun String.cleanTitle(): String {
        return replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#8211;", "-")
            .replace("Nonton Film", "")
            .replace(Regex("""^Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace("Subtitle Indonesia", "")
            .replace("Sub Indo", "", ignoreCase = true)
            .trim()
    }
}
