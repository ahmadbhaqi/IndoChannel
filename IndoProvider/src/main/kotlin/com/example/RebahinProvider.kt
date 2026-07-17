package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

open class RebahinProvider : MainAPI() {
    override var mainUrl = "https://154.203.167.63"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage get() = mainPageOf(
        "$mainUrl/page/" to "Terbaru",
        "$mainUrl/rating/page/" to "Rating Tertinggi",
        "$mainUrl/film-action-terbaru/page/" to "Action",
        "$mainUrl/series-update/page/" to "Series Update"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = normalizePageUrl(request.data + page)
            ?: return newHomePageResponse(request.name, emptyList())
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val pageUrl = fetch.url
        if (normalizePageUrl(pageUrl) == null) {
            return newHomePageResponse(request.name, emptyList())
        }
        val document = fetch.document
        if (!RebahinPageParser.isTrustedCatalogDocument(document, pageUrl, mainUrl)) {
            return newHomePageResponse(request.name, emptyList())
        }
        val items = document.select("article.item-infinite, div.ml-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    internal fun Element.toSearchResult(): SearchResponse? {
        val title = MovieMetadataParser.title(
            selectFirst("h2 a, h3.mli-info h2")?.text()
        ) ?: return null
        val href = normalizePageUrl(selectFirst("a")?.attr("href") ?: return null) ?: return null
        val posterUrl = fixUrlNull(ProviderHtmlParser.firstImageSource(this))
        val quality = selectFirst("span.mli-quality, div.gmr-qual")?.text()?.trim()
        val type = if (href.contains("/tv/", ignoreCase = true) ||
            attr("itemtype").contains("TV", ignoreCase = true)
        ) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val requestUrl = normalizePageUrl("$mainUrl/?s=$encoded") ?: return emptyList()
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val pageUrl = fetch.url
        if (normalizePageUrl(pageUrl) == null) return emptyList()
        val document = fetch.document
        if (!RebahinPageParser.isTrustedCatalogDocument(document, pageUrl, mainUrl)) return emptyList()
        return document.select("article.item-infinite, div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val requestUrl = normalizePageUrl(url)
            ?: throw ErrorLoadingException("$name rejected a foreign movie URL")
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val canonicalUrl = fetch.url
        if (normalizePageUrl(canonicalUrl) == null) {
            throw ErrorLoadingException("$name redirected to a foreign movie host")
        }
        val document = fetch.document
        if (!RebahinPageParser.isTrustedCatalogDocument(document, canonicalUrl, mainUrl)) {
            throw ErrorLoadingException("$name returned a foreign or non-content movie page")
        }
        val title = MovieMetadataParser.title(
            document.selectFirst("h1.entry-title, h3[itemprop=name]")?.text()
        ) ?: MovieMetadataParser.title(document.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: throw ErrorLoadingException("$name returned a page without a movie title")
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(document.selectFirst("img.thumbnail, figure.pull-left > img")))
        val description = MovieMetadataParser.synopsis(
            document,
            directSelectors = listOf(
                "[itemprop=reviewBody] p",
                "div.synopsis p",
                "div.synopsis",
                "div.sinopsis p",
                "div.sinopsis"
            )
        )
        val year = document.selectFirst("span.year, a[href*=/year/]")?.text()?.toIntOrNull()
        val tags = document.select("div.gmr-moviedata a[href*=/genre/], span.jptag a").map { it.text() }
        val tvType = if (document.select("div.vid-episodes a, div.gmr-listseries a").isNotEmpty()) TvType.TvSeries else TvType.Movie
        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a").mapNotNull { eps ->
                val epNum = Regex("Episode\\s*(\\d+)").find(eps.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                val href = normalizePageUrl(eps.attr("href")) ?: return@mapNotNull null
                newEpisode(href) { this.episode = epNum; this.name = "Episode $epNum"; this.posterUrl = poster }
            }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) { posterUrl = poster; this.year = year; plot = description; this.tags = tags }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) { posterUrl = poster; this.year = year; plot = description; this.tags = tags }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val requestUrl = normalizePageUrl(data) ?: return false
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val pageUrl = fetch.url
        if (normalizePageUrl(pageUrl) == null) return false
        val document = fetch.document
        if (!RebahinPageParser.isTrustedCatalogDocument(document, pageUrl, mainUrl)) return false
        val directUrl = getBaseUrl(pageUrl)
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)

        for (src in RebahinPageParser.mediaSources(document)) {
            if (!resolver.canContinue) break
            val candidate = ProviderHtmlParser.absoluteUrl(src, pageUrl) ?: continue
            resolver.resolve(candidate, pageUrl)
        }

        for (request in ProviderHtmlParser.muviproAjaxRequests(document)) {
            if (!resolver.canContinue) break
            val response = try {
                app.post(
                    "$directUrl/wp-admin/admin-ajax.php",
                    data = request.toPostData(),
                    referer = pageUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: continue
            val ajaxResponseUrl = response.url
            if (normalizePageUrl(ajaxResponseUrl) == null) continue
            for (src in RebahinPageParser.mediaSources(response.document)) {
                if (!resolver.canContinue) break
                val candidate = ProviderHtmlParser.absoluteUrl(src, ajaxResponseUrl) ?: continue
                resolver.resolve(candidate, ajaxResponseUrl)
            }
        }

        for (link in document.select("ul#player-list > li a, ul.muvipro-player-tabs li a")) {
            if (!resolver.canContinue) break
            val playerUrl = normalizePageUrl(link.attr("href")) ?: continue
            val response = try {
                app.get(
                    playerUrl,
                    referer = pageUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: continue
            val playerPageUrl = response.url
            if (normalizePageUrl(playerPageUrl) == null) continue
            for (src in RebahinPageParser.mediaSources(response.document)) {
                if (!resolver.canContinue) break
                val candidate = ProviderHtmlParser.absoluteUrl(src, playerPageUrl) ?: continue
                resolver.resolve(candidate, playerPageUrl)
            }
        }

        for (download in ProviderHtmlParser.downloadCandidateUrls(document, pageUrl)) {
            if (!resolver.canContinue) break
            resolver.resolve(download, pageUrl)
        }
        return resolver.loaded
    }

    private fun normalizePageUrl(raw: String?): String? =
        RebahinPageParser.normalizePageUrl(raw, mainUrl)

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.rawAuthority}" }
    }
}

internal object RebahinPageParser {
    private val historicalHosts = setOf(
        "178.62.115.110",
        "156.244.7.27"
    )

    fun normalizePageUrl(raw: String?, mainUrl: String): String? {
        return ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, historicalHosts)
    }

    fun isTrustedCatalogDocument(document: Document, pageUrl: String, mainUrl: String): Boolean {
        if (normalizePageUrl(pageUrl, mainUrl) == null) return false
        if (ProviderHtmlParser.isNonContentPage(document.outerHtml())) return false
        if (document.select(CONTENT_MARKERS).isEmpty()) return false

        val identityUrls = document
            .select("link[rel=canonical][href], meta[property=og:url][content]")
            .mapNotNull { element ->
                val raw = if (element.tagName().equals("link", ignoreCase = true)) {
                    element.attr("href")
                } else {
                    element.attr("content")
                }
                raw.trim().takeIf { it.isNotBlank() }
            }
        if (identityUrls.any { normalizePageUrl(it, mainUrl) == null }) return false

        val siteNames = document
            .select("meta[property=og:site_name][content], meta[name=application-name][content]")
            .map { it.attr("content").trim() }
            .filter { it.isNotBlank() }
        if (siteNames.any { !it.contains("rebahin", ignoreCase = true) }) return false

        val hasProviderIdentity = identityUrls.isNotEmpty() ||
            siteNames.any { it.contains("rebahin", ignoreCase = true) }
        return hasProviderIdentity
    }

    fun mediaSources(document: Document): List<String> {
        return ProviderHtmlParser.mediaSources(document, "iframe, div.gmr-embed-responsive iframe")
    }

    private const val CONTENT_MARKERS =
        "article.item-infinite, div.ml-item, h1.entry-title, h3[itemprop=name], " +
            "div#muvipro_player_content_id, ul#player-list, ul.muvipro-player-tabs, " +
            "iframe, video, source"
}
