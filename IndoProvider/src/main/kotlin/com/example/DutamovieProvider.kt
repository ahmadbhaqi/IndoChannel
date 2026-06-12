package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DutamovieProvider : MainAPI() {
    override var mainUrl = "https://wavereview.com"
    private var directUrl: String? = null
    override var name = "Dutamovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "category/serial-tv/page/%d/" to "TV Series",
        "action/page/%d/" to "Action",
        "comedy/page/%d/" to "Comedy",
        "drama/page/%d/" to "Drama",
        "horror/page/%d/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster; addQuality(quality) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl?s=$encoded&post_type[]=post&post_type[]=tv").document
            .select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        val document = fetch.document
        directUrl = getBaseUrl(fetch.url)
        val title = document.selectFirst("h1.entry-title")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors] a")?.map { it.text() }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a").mapNotNull { eps ->
                val href = fixUrl(eps.attr("href"))
                val epNum = Regex("Episode\\s*(\\d+)").find(eps.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) { this.name = "Episode $epNum"; this.episode = epNum; this.posterUrl = poster }
            }.filter { it.episode != null }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; addActors(actors); addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; addActors(actors); addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return loadGmrLinks(data, directUrl, { fixUrl(it) }, subtitleCallback, callback)
    }

}
