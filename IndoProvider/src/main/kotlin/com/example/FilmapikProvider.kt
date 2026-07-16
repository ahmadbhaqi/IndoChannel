package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmapikProvider : MainAPI() {
    override var mainUrl = "https://filmapik.fitness"
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
        return newMovieSearchResponse(title.cleanTitle(), fixUrl(href), TvType.Movie) {
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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = (
            ProviderHtmlParser.mediaSources(document) +
                document.select("[data-url]").mapNotNull { it.attr("data-url").takeIf { value -> value.isNotBlank() } } +
                document.select("select#player-select option[value]").mapNotNull { it.attr("value").takeIf { value -> value.isNotBlank() } }
            ).distinct()

        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        servers.forEach { raw -> resolver.resolve(raw, data) }
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
        return newMovieSearchResponse(safeTitle, safeUrl, TvType.Movie) {
            posterUrl = img
        }
    }

    private fun String.cleanTitle(): String {
        return replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#8211;", "-")
            .replace("Nonton Film", "")
            .replace("Subtitle Indonesia", "")
            .trim()
    }
}
