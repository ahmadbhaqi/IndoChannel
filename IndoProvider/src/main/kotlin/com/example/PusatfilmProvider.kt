package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URI

class PusatfilmProvider : MainAPI() {
    override var mainUrl = "https://v3.pusatfilm21info.com"
    override var name = "Pusatfilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "film-terbaru/page/%d/" to "Terbaru",
        "trending/page/%d/" to "Trending",
        "series-terbaru/page/%d/" to "TV Series",
        "genre/action/page/%d/" to "Action",
        "genre/animation/page/%d/" to "Animation",
        "genre/comedy/page/%d/" to "Comedy",
        "genre/drama/page/%d/" to "Drama",
        "genre/horror/page/%d/" to "Horror",
        "genre/romance/page/%d/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")

        return if (quality.isEmpty()) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        val document = fetch.document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?.substringBefore("Season")?.substringBefore("Episode")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr()?.fixImageQuality())
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull()

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a")
                .map { eps ->
                    val href = fixUrl(eps.attr("href"))
                    val name = eps.text()
                    val episode = name.split(" ").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull()
                    newEpisode(href) {
                        this.name = "Episode $episode"
                        this.episode = episode
                        this.posterUrl = poster
                    }
                }.filter { it.episode != null }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                this.duration = duration ?: 0
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                this.duration = duration ?: 0
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
        val document = app.get(data).document
        val iframeEl = document.selectFirst("div.gmr-embed-responsive iframe, div.movieplay iframe, iframe")
        val iframe = listOf("src", "data-src", "data-litespeed-src")
            .firstNotNullOfOrNull { key -> iframeEl?.attr(key)?.takeIf { it.isNotBlank() } }
            ?.let { httpsify(it) }

        if (!iframe.isNullOrBlank()) {
            val refererBase = runCatching { getBaseUrl(iframe) }.getOrDefault(mainUrl) + "/"
            loadExtractor(iframe, refererBase, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
