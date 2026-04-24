package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

open class RebahinProvider : MainAPI() {
    override var mainUrl = "http://156.244.7.27"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage get() = mainPageOf(
        "$mainUrl/trending/page/" to "Trending",
        "$mainUrl/terbaru/page/" to "Terbaru",
        "$mainUrl/rating/page/" to "Rating Tertinggi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val items = document.select("article.item-infinite, div.ml-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    internal fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2 a, h3.mli-info h2")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        val quality = selectFirst("span.mli-quality, div.gmr-qual")?.text()?.trim()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl; this.quality = getQualityFromString(quality) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("article.item-infinite, div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h3[itemprop=name]")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("img.thumbnail, figure.pull-left > img")?.attr("src")
        val description = document.selectFirst("div[itemprop=description], div.synopsis")?.text()?.trim()
        val year = document.selectFirst("span.year, a[href*=/year/]")?.text()?.toIntOrNull()
        val tags = document.select("div.gmr-moviedata a[href*=/genre/], span.jptag a").map { it.text() }
        val tvType = if (document.select("div.vid-episodes a, div.gmr-listseries a").isNotEmpty()) TvType.TvSeries else TvType.Movie
        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a").mapNotNull { eps ->
                val epNum = Regex("Episode\\s*(\\d+)").find(eps.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(fixUrl(eps.attr("href"))) { this.episode = epNum; this.name = "Episode $epNum"; this.posterUrl = poster }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.year = year; plot = description; this.tags = tags }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; this.year = year; plot = description; this.tags = tags }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("iframe, div.gmr-embed-responsive iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.startsWith("http")) loadExtractor(src, data, subtitleCallback, callback)
        }
        document.select("ul#player-list > li a, ul.muvipro-player-tabs li a").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                try {
                    val iframeSrc = app.get(fixUrl(href)).document.selectFirst("iframe")?.attr("src")
                    if (!iframeSrc.isNullOrBlank()) loadExtractor(iframeSrc, data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }
        return true
    }
}
