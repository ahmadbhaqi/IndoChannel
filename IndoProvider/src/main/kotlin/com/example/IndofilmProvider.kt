package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class IndofilmProvider : MainAPI() {
    override var mainUrl = "https://indofilm.pics"
    private val legacyHosts = setOf("indofilm.fit", "yuhhaber.com")
    override var name = "Indofilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Terbaru",
        "$mainUrl/rating/page/" to "Rating",
        "$mainUrl/film-action-terbaru/page/" to "Action",
        "$mainUrl/series-update/page/" to "TV Series",
        "$mainUrl/comedy/page/" to "Comedy",
        "$mainUrl/drama/page/" to "Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = app.get(request.data + page).document
            .select("article.item-infinite, article.item, div.ml-item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        return app.get("$mainUrl/?s=$encodedQuery").document
            .select("article.item-infinite, article.item, div.ml-item")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = ProviderHtmlParser.firstTitledLink(this) ?: return null
        val title = MovieMetadataParser.title(anchor.text()) ?: return null
        val href = providerUrl(anchor.attr("href")) ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.firstImageSource(this))
        val quality = selectFirst("div.gmr-quality-item, span.mli-quality, div.gmr-qual")?.text()?.trim()
        val isSeries = href.contains("/tv/", ignoreCase = true) ||
            selectFirst("div.gmr-numbeps, div.last-episode") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = providerUrl(url) ?: return null
        val fetch = app.get(requestUrl)
        val document = fetch.document
        val canonicalUrl = providerUrl(fetch.url) ?: return null
        val title = MovieMetadataParser.title(
            document.selectFirst("h1.entry-title, h3[itemprop=name]")?.text()
        ) ?: return null
        val poster = fixUrlNull(
            ProviderHtmlParser.imageSource(
                document.selectFirst("img.thumbnail, figure.pull-left > img, img.img-thumbnail")
            )
        )
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
        val year = document.select("a[href*=/year/], span.year")
            .firstNotNullOfOrNull { Regex("(?:19|20)\\d{2}").find(it.text())?.value?.toIntOrNull() }
        val tags = document.select("div.gmr-moviedata a[href*=/category/], div.gmr-moviedata a[href*=/genre/], span.jptag a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val episodeElements = document.select("div.vid-episodes a[href], div.gmr-listseries a[href], div.episode-list a[href]")
        val isSeries = fetch.url.contains("/tv/", ignoreCase = true) || episodeElements.isNotEmpty()

        return if (isSeries) {
            val episodes = episodeElements.mapNotNull { episodeLink ->
                val href = providerUrl(episodeLink.attr("href")) ?: return@mapNotNull null
                val label = episodeLink.attr("title").takeIf { it.isNotBlank() } ?: episodeLink.text().trim()
                val number = Regex("(?i)(?:episode|eps?)\\s*(\\d+)")
                    .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("\\d+").find(label)?.value?.toIntOrNull()
                newEpisode(href) {
                    episode = number
                    name = number?.let { "Episode $it" } ?: label
                    posterUrl = poster
                }
            }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
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
        val requestUrl = providerUrl(data) ?: return false
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val document = fetch.document
        val canonicalUrl = providerUrl(fetch.url) ?: return false
        val baseUrl = baseUrl(canonicalUrl)
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)

        ProviderHtmlParser.mediaSources(document, "iframe, div.gmr-embed-responsive iframe")
            .forEach { resolvePlayer(it, canonicalUrl, resolver) }
        if (!resolver.loaded) {
            for (downloadUrl in ProviderHtmlParser.downloadCandidateUrls(document, canonicalUrl)) {
                if (!resolver.canContinue) break
                resolvePlayer(downloadUrl, canonicalUrl, resolver)
                if (resolver.loaded) break
            }
        }

        for (request in ProviderHtmlParser.muviproAjaxRequests(document)) {
            if (!resolver.canContinue) break
            try {
                val response = app.post(
                    "$baseUrl/wp-admin/admin-ajax.php",
                    data = request.toPostData(),
                    referer = canonicalUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).document
                ProviderHtmlParser.mediaSources(response).forEach {
                    resolvePlayer(it, canonicalUrl, resolver)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Continue with the other configured servers.
            }
        }

        for (link in document.select("ul#player-list > li a[href], ul.muvipro-player-tabs li a[href]")) {
            if (!resolver.canContinue) break
            val playerUrl = ProviderHtmlParser.absoluteUrl(link.attr("href"), canonicalUrl)
                ?: continue
            if (!playerUrl.startsWith("http")) continue
            try {
                val playerDocument = app.get(
                    playerUrl,
                    referer = canonicalUrl,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).document
                ProviderHtmlParser.mediaSources(playerDocument).forEach {
                    resolvePlayer(it, playerUrl, resolver)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Continue with the other configured servers.
            }
        }
        return resolver.loaded
    }

    private suspend fun resolvePlayer(raw: String?, referer: String, resolver: LinkResolutionSession) {
        val url = ProviderHtmlParser.absoluteUrl(raw, referer) ?: return
        if (resolver.resolve(url, referer)) return

        try {
            val html = resolver.withinBudget {
                app.get(
                    url,
                    referer = referer,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).text
            } ?: return
            IndofilmPlayerParser.mediaUrls(html, url).forEach { resolver.resolve(it, url) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // An unavailable mirror is isolated from its siblings.
        }
    }

    private fun providerUrl(raw: String?): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, legacyHosts)

    private fun baseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }
}

internal object IndofilmPlayerParser {
    fun mediaUrls(html: String, playerUrl: String): List<String> {
        val document = Jsoup.parse(html, playerUrl)
        val scriptBodies = document.select("script").flatMap { script ->
            val raw = script.data()
            listOfNotNull(
                raw,
                raw.takeIf { it.contains("eval(function(p,a,c,k,e") }
                    ?.let { runCatching { getAndUnpack(it) }.getOrNull() }
            )
        }
        val inlineUrls = scriptBodies.flatMap { script ->
            val normalized = script.decodeJsUrl()
            Regex("(?i)[\\\"']?(?:file|src)[\\\"']?\\s*[:=]\\s*[\\\"'](https?://[^\\\"']+)[\\\"']")
                .findAll(normalized)
                .map { it.groupValues[1] }
                .toList()
        }
        val sourceUrls = document.select("video[src], video source[src], source[src]").map { it.attr("src") }
        return (inlineUrls + sourceUrls)
            .mapNotNull { ProviderHtmlParser.absoluteUrl(it, playerUrl) }
            .distinct()
    }

    private fun String.decodeJsUrl(): String = replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
}
