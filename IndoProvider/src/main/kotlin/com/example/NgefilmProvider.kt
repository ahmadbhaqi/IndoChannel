package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Element

class NgefilmProvider : MainAPI() {
    override var mainUrl = "https://new38.ngefilm.site"
    private val legacyHosts = (33..37).mapTo(mutableSetOf()) { number ->
        "new$number.ngefilm.site"
    }
    override var name = "Ngefilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "year/2026/page/%d/" to "Terbaru",
        "page/%d/?s=&search=advanced&post_type=tv" to "TV Series",
        "Genre/action/page/%d/" to "Action",
        "Genre/adventure/page/%d/" to "Adventure",
        "Genre/animation/page/%d/" to "Animation",
        "Genre/fantasy/page/%d/" to "Fantasy",
        "country/japan/page/%d/" to "Japan",
        "country/indonesia/page/%d/" to "Indonesia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article.item-infinite, article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = MovieMetadataParser.title(
            selectFirst("h2.entry-title > a")?.text()
        ) ?: return null
        val href = normalizePageUrl(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        val eps = selectFirst(".gmr-numbeps span")?.text()?.trim()?.toIntOrNull()
        val isSeries = eps != null

        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(eps)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val document = app.get("$mainUrl?s=$encodedQuery&post_type[]=post&post_type[]=tv").document
        return document.select("article.item-infinite, article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val requestUrl = normalizePageUrl(url)
            ?: throw ErrorLoadingException("Invalid Ngefilm URL")
        val fetch = app.get(requestUrl)
        val document = fetch.document
        val canonicalUrl = normalizePageUrl(fetch.url) ?: requestUrl

        val rawTitle = document.selectFirst("h1.entry-title, h1[itemprop=name]")?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
        val title = MovieMetadataParser.title(rawTitle)
            ?: MovieMetadataParser.title(document.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: throw ErrorLoadingException("Ngefilm returned a page without a movie title")
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
            ?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val episodeElements = document.select("div.vid-episodes a, div.gmr-listseries a")
        val tvType = if (canonicalUrl.contains("/tv/") || episodeElements.isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        val description = MovieMetadataParser.synopsis(document)
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull()

        return if (tvType == TvType.TvSeries) {
            val episodes = episodeElements
                .mapNotNull { eps ->
                    val href = normalizePageUrl(eps.attr("href")) ?: return@mapNotNull null
                    val rawTitle = eps.attr("title").takeIf { it.isNotBlank() } ?: eps.text()
                    val cleanTitle = rawTitle.replaceFirst(Regex("(?i)Permalink ke\\s*"), "").trim()
                    val epNum = Regex("Episode\\s*(\\d+)").find(cleanTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: cleanTitle.split(" ").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull()
                    val formattedName = epNum?.let { "Episode $it" } ?: cleanTitle

                    newEpisode(href) {
                        this.name = formattedName
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                }.filter { it.episode != null }

            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                addActors(actors)
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                addActors(actors)
                this.duration = duration ?: 0
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
        val requestUrl = normalizePageUrl(data) ?: return false
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val document = fetch.document
        val canonicalUrl = normalizePageUrl(fetch.url) ?: requestUrl
        val baseUrl = getBaseUrl(canonicalUrl)
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)

        if (id.isNullOrEmpty()) {
            for (ele in document.select("ul.muvipro-player-tabs li a")) {
                if (resolver.loaded) break
                val player = try {
                    val playerPage = normalizePageUrl(ele.attr("href")) ?: continue
                    val response = app.get(
                        playerPage,
                        referer = canonicalUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                    )
                    val iframe = response.document
                        .selectFirst("div.gmr-embed-responsive iframe")
                        ?.let { ProviderHtmlParser.firstIframeSource(it) }
                        ?: continue
                    iframe to (normalizePageUrl(response.url) ?: playerPage)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                    ?: continue
                resolver.resolve(player.first, player.second)
            }
        } else {
            for (ele in document.select("div.tab-content-ajax")) {
                if (resolver.loaded) break
                val server = try {
                    app.post(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to ele.attr("id"),
                            "post_id" to "$id"
                        ),
                        referer = canonicalUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                    ).document.selectFirst("iframe")
                        ?.let { ProviderHtmlParser.firstIframeSource(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                    ?: continue
                resolver.resolve(server, canonicalUrl)
            }
        }
        return resolver.loaded
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun normalizePageUrl(raw: String?): String? {
        return ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, legacyHosts)
    }
}
