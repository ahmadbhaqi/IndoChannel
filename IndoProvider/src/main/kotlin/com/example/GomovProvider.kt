package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

open class GomovProvider : MainAPI() {

    override var mainUrl = "https://gomov.top"

    override var name = "Gomov"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "page/%d/?s&search=advanced&post_type=movie" to "Movies",
        "category/western-series/page/%d/" to "Western Series",
        "tv/page/%d/" to "Tv Shows",
        "category/korean-series/page/%d/" to "Korean Series",
        "category/chinese-series/page/%d/" to "Chinese Series",
        "category/india-series/page/%d/" to "India Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data").document
        val home = document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(ProviderHtmlParser.imageSource(this.selectFirst("a > img"))).fixImageQuality()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            val episode = Regex("Episode\\s?([0-9]+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: this.select("div.gmr-numbeps > span").text().toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = fixUrlNull(ProviderHtmlParser.imageSource(this.selectFirst("a > img")).fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv").document.select("article.item")
            .mapNotNull {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        val document = fetch.document

        val title = document.selectFirst("h1.entry-title")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.trim()
                .toString()
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(document.selectFirst("figure.pull-left > img")))?.fixImageQuality()
        val tags = document.select("span.gmr-movie-genre:contains(Genre:) > a").map { it.text() }

        val year = document.select("span.gmr-movie-genre:contains(Year:) > a").text().trim().toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")

        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }

        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull {
            it.toRecommendResult()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a").map { eps ->
                val href = ProviderHtmlParser.absoluteUrl(eps.attr("href"), fetch.url) ?: eps.attr("href")
                val name = eps.text()
                val episode = name.split(" ").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull()
                val season = name.split(" ").firstOrNull()?.filter { it.isDigit() }?.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.season = if(name.contains(" ")) season else null
                    this.episode = episode
                }
            }.filter { it.episode != null }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                addActors(actors)
                this.recommendations = recommendations
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
        val fetch = app.get(data)
        val document = fetch.document
        val baseUrl = getBaseUrl(fetch.url)
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)

        ProviderHtmlParser.mediaSources(document, "div.gmr-embed-responsive iframe, iframe").forEach { src ->
            resolver.resolve(src, fetch.url)
        }

        document.select("ul.muvipro-player-tabs li a[href]").forEach { ele ->
            val playerUrl = ProviderHtmlParser.absoluteUrl(ele.attr("href"), fetch.url) ?: return@forEach
            val playerFetch = try {
                app.get(playerUrl, referer = fetch.url)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@forEach

            ProviderHtmlParser.mediaSources(playerFetch.document, "div.gmr-embed-responsive iframe, iframe")
                .forEach { iframe -> resolver.resolve(iframe, playerFetch.url) }
        }

        if (!id.isNullOrEmpty()) {
            document.select("div.tab-content-ajax[id]").forEach { ele ->
                try {
                    val response = app.post(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to ele.attr("id"),
                            "post_id" to id
                        ),
                        referer = fetch.url,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).document
                    ProviderHtmlParser.mediaSources(response).forEach { server ->
                        resolver.resolve(server, fetch.url)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep trying the other configured servers.
                }
            }
        }

        return resolver.loaded
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
}
