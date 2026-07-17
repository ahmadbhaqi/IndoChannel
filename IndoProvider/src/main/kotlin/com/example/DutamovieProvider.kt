package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DutamovieProvider : MainAPI() {
    override var mainUrl = "https://austincomputerworks.org"
    private val legacyHosts = setOf("wavereview.com")
    override var name = "Dutamovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "box-office/page/%d/" to "Box Office",
        "serial-tv/page/%d/" to "TV Series",
        "action/page/%d/" to "Action",
        "comedy/page/%d/" to "Comedy",
        "drama/page/%d/" to "Drama",
        "horror/page/%d/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article.item, article.item-infinite").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = MovieMetadataParser.title(
            selectFirst("h2.entry-title > a")?.text()
        ) ?: return null
        val href = normalizePageUrl(selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster; addQuality(quality) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        return app.get("$mainUrl?s=$encodedQuery&post_type[]=post&post_type[]=tv").document
            .select("article.item-infinite, article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val requestUrl = normalizePageUrl(url)
            ?: throw ErrorLoadingException("Invalid Dutamovie URL")
        val fetch = app.get(requestUrl)
        val document = fetch.document
        val canonicalUrl = normalizePageUrl(fetch.url)
            ?: throw ErrorLoadingException("Dutamovie redirected to a foreign host")
        val rawTitle = document.selectFirst("h1.entry-title, h1[itemprop=name]")?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
        val title = MovieMetadataParser.title(rawTitle)
            ?: MovieMetadataParser.title(document.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: throw ErrorLoadingException("Dutamovie returned a page without a movie title")
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
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
        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors] a")?.map { it.text() }

        return if (tvType == TvType.TvSeries) {
            val episodes = episodeElements.mapNotNull { eps ->
                val href = normalizePageUrl(eps.attr("href")) ?: return@mapNotNull null
                val epNum = Regex("Episode\\s*(\\d+)").find(eps.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) { this.name = "Episode $epNum"; this.episode = epNum; this.posterUrl = poster }
            }.filter { it.episode != null }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; addActors(actors); addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; addActors(actors); addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val requestUrl = normalizePageUrl(data) ?: return false
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val document = fetch.document
        val canonicalUrl = normalizePageUrl(fetch.url) ?: return false
        val baseUrl = getBaseUrl(canonicalUrl)
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        for (server in DutamoviePlayerParser.detailMediaUrls(document, canonicalUrl)) {
            if (!resolver.canContinue || resolver.resolve(server, canonicalUrl)) break
        }
        if (!resolver.loaded) {
            for (download in ProviderHtmlParser.downloadCandidateUrls(document, canonicalUrl)) {
                if (!resolver.canContinue || resolver.resolve(download, canonicalUrl)) break
            }
        }
        if (id.isNullOrEmpty()) {
            val playerPages = document.select("ul.muvipro-player-tabs li a")
                .mapNotNull { normalizePageUrl(it.attr("href")) }
                .let(DutamoviePlayerParser::orderPlayerPages)
            for (playerPage in playerPages) {
                if (resolver.loaded || !resolver.canContinue) break
                val player = try {
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
                if (resolver.loaded || !resolver.canContinue) break
                val server = try {
                    app.post(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "muvipro_player_content", "tab" to ele.attr("id"), "post_id" to "$id"),
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

    private fun Element.getImageAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src"); hasAttr("data-lazy-src") -> attr("abs:data-lazy-src"); else -> attr("abs:src")
    }
    private fun String?.fixImageQuality(): String? { if (this == null) return null; val r = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this; return replace(r, "") }
    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }
    private fun normalizePageUrl(raw: String?): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, legacyHosts)
}

internal object DutamoviePlayerParser {
    fun detailMediaUrls(document: Document, detailUrl: String): List<String> {
        return ProviderHtmlParser.mediaSources(
            document,
            "iframe, div.gmr-embed-responsive iframe"
        ).mapNotNull { ProviderHtmlParser.absoluteUrl(it, detailUrl) }
            .distinct()
    }

    fun orderPlayerPages(urls: List<String>): List<String> {
        return urls.distinct().withIndex()
            .sortedWith(compareBy<IndexedValue<String>> { priority(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    private fun priority(url: String): Int {
        val player = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
            .let { Regex("(?:^|&)player=(\\d+)(?:&|$)").find(it) }
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when (player) {
            2 -> 0 // Abyss currently exposes a complete seekable MP4.
            7 -> 1 // LuluStream is the healthy HLS fallback.
            else -> 2
        }
    }
}
