package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private val LAYARKACA_LEGACY_HOSTS = setOf("parachutedrone.com", "tv10.lk21official.cc")

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv.nontonfilm.red"
    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/genre/box-office/page/" to "Box Office",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/horror/page/" to "Horror",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/animation/page/" to "Animation"
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
        val quality = selectFirst("div.gmr-quality-item, div.gmr-qual")?.text()?.trim()
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
        val description = MovieMetadataParser.synopsis(document)
        val year = document.select("a[href*=/year/], span.year")
            .firstNotNullOfOrNull { Regex("(?:19|20)\\d{2}").find(it.text())?.value?.toIntOrNull() }
        val tags = document.select("div.gmr-moviedata a[href*=/genre/], span.jptag a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a").map { it.text().trim() }
        val trailer = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        val episodeElements = document
            .select("div.vid-episodes a[href], div.gmr-listseries a[href], div.episode-list a[href]")
            .filter { episodeLink ->
                val href = providerUrl(episodeLink.attr("href")) ?: return@filter false
                val label = episodeLink.attr("title").ifBlank { episodeLink.text() }
                LayarKacaPlayerParser.isEpisodeLink(href, label, canonicalUrl)
            }
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
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
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
        val requestUrl = providerUrl(data) ?: return false
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val document = fetch.document
        val canonicalUrl = providerUrl(fetch.url) ?: return false
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            inlineSourceParser = LayarKacaPlayerParser::mediaUrls
        )
        for (candidate in LayarKacaPlayerParser.orderedPlayerCandidates(document, canonicalUrl)) {
            if (resolver.loaded || !resolver.canContinue) break
            when (candidate) {
                is LayarKacaPlaybackCandidate.InlinePlayer ->
                    resolvePlayer(candidate.url, canonicalUrl, resolver)

                is LayarKacaPlaybackCandidate.ServerPage -> try {
                    val playerDocument = app.get(
                        candidate.url,
                        referer = canonicalUrl,
                        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                    ).let { response ->
                        val responseUrl = providerUrl(response.url) ?: return@let null
                        response.document to responseUrl
                    } ?: continue
                    for (mediaUrl in LayarKacaPlayerParser.pageMediaUrls(
                        playerDocument.first,
                        playerDocument.second
                    )) {
                        if (resolver.loaded) break
                        resolvePlayer(mediaUrl, playerDocument.second, resolver)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // One dead server must not hide the remaining mirrors.
                }
            }
        }

        if (!resolver.loaded) {
            val ajaxUrl = URI(canonicalUrl).let { "${it.scheme}://${it.host}/wp-admin/admin-ajax.php" }
            for (request in LayarKacaPlayerParser.ajaxRequests(document)) {
                if (resolver.loaded || !resolver.canContinue) break
                try {
                    val response = app.post(
                        ajaxUrl,
                        data = request.toPostData(),
                        referer = canonicalUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                    )
                    val responseUrl = providerUrl(response.url) ?: continue
                    for (mediaUrl in LayarKacaPlayerParser.pageMediaUrls(
                        response.document,
                        responseUrl
                    )) {
                        if (resolver.loaded || !resolver.canContinue) break
                        resolvePlayer(
                            ProviderHtmlParser.absoluteUrl(mediaUrl, responseUrl),
                            canonicalUrl,
                            resolver
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep trying the remaining AJAX player tabs.
                }
            }
        }

        if (!resolver.loaded) {
            for (download in ProviderHtmlParser.downloadCandidateUrls(document, canonicalUrl)) {
                if (!resolver.canContinue || resolver.resolve(download, canonicalUrl)) break
            }
        }

        return resolver.loaded
    }

    private suspend fun resolvePlayer(raw: String?, referer: String, resolver: LinkResolutionSession) {
        val url = ProviderHtmlParser.absoluteUrl(raw, referer) ?: return
        resolver.resolveInline(url, referer)
    }

    private fun providerUrl(raw: String?): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, LAYARKACA_LEGACY_HOSTS)
}

internal sealed interface LayarKacaPlaybackCandidate {
    data class ServerPage(val url: String) : LayarKacaPlaybackCandidate
    data class InlinePlayer(val url: String) : LayarKacaPlaybackCandidate
}

internal object LayarKacaPlayerParser {
    fun ajaxRequests(document: Document): List<MuviproAjaxRequest> =
        ProviderHtmlParser.muviproAjaxRequests(document)

    fun isEpisodeLink(url: String, label: String, detailUrl: String): Boolean {
        if (label.contains("View All Episodes", ignoreCase = true)) return false
        return normalizePageUrl(url) != normalizePageUrl(detailUrl)
    }

    fun serverPageUrls(document: Document, detailUrl: String): List<String> {
        val playerTabs = document.select(
            "ul.gmr-player-nav a[href], ul.muvipro-player-tabs a[href], ul#player-list a[href]"
        ).mapNotNull { link ->
            normalizeServerPageUrl(link.attr("href"), detailUrl)
                ?.takeIf { isServerPageUrl(it, detailUrl) }
        }
        val providerMenu = document.select("ul#loadProviders > li a[href]").mapNotNull { link ->
            normalizeServerPageUrl(link.attr("href"), detailUrl)
                ?.takeIf { isServerPageUrl(it, detailUrl, requirePlayerQuery = false) }
        }
        return (playerTabs + providerMenu).distinct()
    }

    fun orderedPlayerCandidates(
        document: Document,
        detailUrl: String
    ): List<LayarKacaPlaybackCandidate> {
        val (defaultServerPages, alternateServerPages) =
            serverPageUrls(document, detailUrl).partition(::isDefaultServerPage)
        return (
            alternateServerPages.map(LayarKacaPlaybackCandidate::ServerPage) +
                pageMediaUrls(document, detailUrl).map(LayarKacaPlaybackCandidate::InlinePlayer) +
                defaultServerPages.map(LayarKacaPlaybackCandidate::ServerPage)
            ).distinct()
    }

    private fun isDefaultServerPage(url: String): Boolean = runCatching {
        URI(url).rawQuery.orEmpty().split('&').any { parameter ->
            parameter.substringBefore('=').equals("player", ignoreCase = true) &&
                parameter.substringAfter('=', "") == "1"
        }
    }.getOrDefault(false)

    private fun normalizeServerPageUrl(raw: String?, detailUrl: String): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val schemeMatches = runCatching {
            val candidate = URI(value)
            val detail = URI(detailUrl)
            !candidate.isAbsolute || candidate.scheme.equals(detail.scheme, ignoreCase = true)
        }.getOrDefault(false)
        if (!schemeMatches) return null
        return ProviderHtmlParser.normalizeProviderPageUrl(
            value,
            detailUrl,
            LAYARKACA_LEGACY_HOSTS
        )
    }

    private fun isServerPageUrl(
        url: String,
        detailUrl: String,
        requirePlayerQuery: Boolean = true
    ): Boolean {
        return runCatching {
            val candidate = URI(url)
            val detail = URI(detailUrl)
            val candidateScheme = candidate.scheme.orEmpty().lowercase()
            val detailScheme = detail.scheme.orEmpty().lowercase()
            val sameOrigin = candidate.host.orEmpty().equals(
                detail.host.orEmpty(),
                ignoreCase = true
            ) && candidateScheme == detailScheme &&
                effectivePort(candidate, candidateScheme) == effectivePort(detail, detailScheme)
            val hasPlayerQuery = candidate.rawQuery.orEmpty()
                .split('&')
                .any { parameter ->
                    parameter.substringBefore('=').equals("player", ignoreCase = true) &&
                        parameter.substringAfter('=', "").isNotBlank()
                }
            candidateScheme in setOf("http", "https") &&
                sameOrigin &&
                (hasPlayerQuery || !requirePlayerQuery) &&
                candidate.toString().substringBefore('#') != detail.toString().substringBefore('#')
        }.getOrDefault(false)
    }

    private fun effectivePort(uri: URI, scheme: String): Int = when {
        uri.port >= 0 -> uri.port
        scheme == "https" -> 443
        scheme == "http" -> 80
        else -> -1
    }

    fun pageMediaUrls(document: Document, pageUrl: String): List<String> {
        return (
            ProviderHtmlParser.mediaSources(document) +
                mediaUrls(document.outerHtml(), pageUrl)
            ).mapNotNull { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
            .distinct()
    }

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
            val normalized = script
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
            val declaredSources = InlineDataParser.playableInlineUrls(normalized)
            val assignmentSources = Regex(
                "(?i)[\\\"']?(?:file|src)[\\\"']?\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"
            )
                .findAll(normalized)
                .map { it.groupValues[1] }
                .filter(::isPlayableMediaPath)
                .toList()
            declaredSources + assignmentSources
        }
        val sourceUrls = document.select("video[src], video source[src], source[src]").map { it.attr("src") }
        return (inlineUrls + sourceUrls)
            .mapNotNull { ProviderHtmlParser.absoluteUrl(it, playerUrl) }
            .distinct()
    }

    private fun isPlayableMediaPath(raw: String): Boolean {
        val path = runCatching { URI(raw).path.orEmpty().lowercase() }
            .getOrDefault(raw.substringBefore('?').lowercase())
        return path.endsWith(".m3u8") || path.endsWith(".mp4")
    }

    private fun normalizePageUrl(url: String): String {
        return url.substringBefore('#').substringBefore('?').trimEnd('/')
    }
}
