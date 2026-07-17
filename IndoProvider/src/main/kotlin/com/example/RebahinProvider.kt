package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

open class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.lol"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage get() = mainPageOf(
        "$mainUrl/page/" to "Terbaru",
        "$mainUrl/1000-film-terbaik-sepanjang-masa/page/" to "Rating Tertinggi",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/series/page/" to "Series Update"
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
        val anchor = selectFirst("a[href]") ?: return null
        val title = MovieMetadataParser.title(
            selectFirst(".mli-info h2, h2.entry-title a, h2 a")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").takeIf { it.isNotBlank() }
        ) ?: return null
        val href = normalizePageUrl(anchor.attr("href")) ?: return null
        val posterUrl = RebahinPageParser.normalizePosterUrl(
            ProviderHtmlParser.firstImageSource(this),
            mainUrl
        )
        val quality = selectFirst("span.mli-quality, div.gmr-qual")?.text()?.trim()
        val type = if (href.contains("/tv/", ignoreCase = true) ||
            href.contains("/series/", ignoreCase = true) ||
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
        val poster = RebahinPageParser.normalizePosterUrl(
            RebahinPageParser.posterUrl(document),
            mainUrl
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
        val year = sequenceOf(
            document.selectFirst("meta[itemprop=datePublished]")?.attr("content"),
            document.selectFirst("#mv-release, span.year, a[href*=/year/]")?.text()
        ).mapNotNull { raw -> Regex("(?:19|20)\\d{2}").find(raw.orEmpty())?.value?.toIntOrNull() }
            .firstOrNull()
        val tags = document.select(
            ".mv-stat a[href*=/genre/] span[itemprop=genre], " +
                "div.gmr-moviedata a[href*=/genre/], span.jptag a"
        ).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val isSeries = canonicalUrl.contains("/series/", ignoreCase = true) ||
            canonicalUrl.contains("/tv/", ignoreCase = true)
        val watchUrl = if (isSeries) {
            RebahinPageParser.watchPageUrl(document, canonicalUrl)?.let(::normalizePageUrl)
        } else {
            null
        }
        return if (isSeries) {
            val watchEpisodes = watchUrl?.let { page ->
                try {
                    val watchFetch = app.get(
                        page,
                        referer = canonicalUrl,
                        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                    )
                    if (normalizePageUrl(watchFetch.url) == null) emptyList()
                    else RebahinPageParser.watchEpisodes(watchFetch.document)
                } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            }.orEmpty()
            val episodes = if (watchUrl != null && watchEpisodes.isNotEmpty()) {
                watchEpisodes.map { episode ->
                    newEpisode(
                        RebahinPageParser.encodeEpisodeData(
                            canonicalUrl,
                            watchUrl,
                            episode
                        )
                    ) {
                        this.episode = episode
                        this.name = "Episode $episode"
                        this.posterUrl = poster
                    }
                }
            } else {
                document.select("div.vid-episodes a[href], div.gmr-listseries a[href]")
                    .mapNotNull { episodeLink ->
                        val episode = Regex("(?i)(?:episode|eps?|ep)\\s*(\\d+)")
                            .find(episodeLink.text())
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: return@mapNotNull null
                        val href = normalizePageUrl(episodeLink.attr("href"))
                            ?: return@mapNotNull null
                        newEpisode(href) {
                            this.episode = episode
                            this.name = "Episode $episode"
                            this.posterUrl = poster
                        }
                    }
            }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) { posterUrl = poster; this.year = year; plot = description; this.tags = tags }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) { posterUrl = poster; this.year = year; plot = description; this.tags = tags }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        RebahinPageParser.decodeEpisodeData(data)?.let { episodeRequest ->
            val detailUrl = normalizePageUrl(episodeRequest.detailUrl) ?: return false
            val watchUrl = normalizePageUrl(episodeRequest.watchUrl) ?: return false
            val watchFetch = try {
                app.get(
                    watchUrl,
                    referer = detailUrl,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
                return false
            }
            val watchPageUrl = normalizePageUrl(watchFetch.url) ?: return false
            if (!RebahinPageParser.isTrustedCatalogDocument(watchFetch.document, watchPageUrl, mainUrl)) {
                return false
            }
            for (source in RebahinPageParser.episodePlayerUrls(
                watchFetch.document,
                episodeRequest.episode
            )) {
                if (!resolver.canContinue || resolver.loaded) break
                resolver.resolve(source, watchPageUrl)
            }
            return resolver.loaded
        }

        val requestUrl = normalizePageUrl(data) ?: return false
        val initialFetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val initialPageUrl = normalizePageUrl(initialFetch.url) ?: return false
        if (!RebahinPageParser.isTrustedCatalogDocument(
                initialFetch.document,
                initialPageUrl,
                mainUrl
            )
        ) return false

        val pages = mutableListOf(initialFetch.document to initialPageUrl)
        val playUrl = RebahinPageParser.playPageUrl(initialFetch.document, initialPageUrl)
            ?.let(::normalizePageUrl)
        if (playUrl != null && playUrl != initialPageUrl) {
            try {
                val playFetch = app.get(
                    playUrl,
                    referer = initialPageUrl,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
                val playPageUrl = normalizePageUrl(playFetch.url)
                if (
                    playPageUrl != null &&
                    RebahinPageParser.isTrustedCatalogDocument(
                        playFetch.document,
                        playPageUrl,
                        mainUrl
                    )
                ) {
                    pages += playFetch.document to playPageUrl
                }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
                // Cached detail URLs can still fall through to legacy players.
            }
        }

        for ((document, pageUrl) in pages.asReversed()) {
            for (source in RebahinPageParser.mediaSources(document)) {
                if (!resolver.canContinue || resolver.loaded) break
                val candidate = ProviderHtmlParser.absoluteUrl(source, pageUrl) ?: continue
                resolver.resolve(candidate, pageUrl)
            }
            if (resolver.loaded) return true
        }

        for ((document, pageUrl) in pages) {
            val directUrl = getBaseUrl(pageUrl)
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
                val ajaxResponseUrl = normalizePageUrl(response.url) ?: continue
                for (source in RebahinPageParser.mediaSources(response.document)) {
                    if (!resolver.canContinue || resolver.loaded) break
                    val candidate = ProviderHtmlParser.absoluteUrl(source, ajaxResponseUrl) ?: continue
                    resolver.resolve(candidate, ajaxResponseUrl)
                }
                if (resolver.loaded) return true
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
                val playerPageUrl = normalizePageUrl(response.url) ?: continue
                for (source in RebahinPageParser.mediaSources(response.document)) {
                    if (!resolver.canContinue || resolver.loaded) break
                    val candidate = ProviderHtmlParser.absoluteUrl(source, playerPageUrl) ?: continue
                    resolver.resolve(candidate, playerPageUrl)
                }
                if (resolver.loaded) return true
            }
        }

        for ((document, pageUrl) in pages) {
            for (download in ProviderHtmlParser.downloadCandidateUrls(document, pageUrl)) {
                if (!resolver.canContinue || resolver.loaded) break
                resolver.resolve(download, pageUrl)
            }
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
        "154.203.167.63",
        "178.62.115.110",
        "156.244.7.27",
        "rebahinxxi3.autos",
        "rebahinxxi.coupons"
    )
    private val historicalPosterHosts = historicalHosts + "198.54.124.245"
    private const val EPISODE_QUERY_KEY = "rebahin_episode"
    private val episodeNumber = Regex(
        """\b(?:episode|eps?|ep)\s*\.?\s*(\d+)\b""",
        RegexOption.IGNORE_CASE
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
        return ProviderHtmlParser
            .mediaSources(document, "iframe, div.gmr-embed-responsive iframe")
            .distinct()
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> { sourcePriority(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    fun posterUrl(document: Document): String? {
        document.select(
            "meta[property=og:image][content], meta[itemprop=image][content]"
        ).firstNotNullOfOrNull { meta ->
            meta.attr("content").trim().takeIf(::isUsablePosterAsset)
        }?.let { return it }
        ProviderHtmlParser.imageSource(
            document.selectFirst(
                "img.thumbnail, figure.pull-left > img, .mvi-cover img, .mvic-thumb img"
            )
        )?.takeIf(::isUsablePosterAsset)?.let { return it }
        return document.select(".mvi-cover[style], .mvic-thumb[style]")
            .firstNotNullOfOrNull { element ->
                Regex("""(?i)background-image\s*:\s*url\((['"]?)(.*?)\1\)""")
                    .find(element.attr("style"))
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.trim()
                    ?.takeIf(::isUsablePosterAsset)
            }
    }

    fun normalizePosterUrl(raw: String?, mainUrl: String): String? {
        val absolute = ProviderHtmlParser.absoluteUrl(raw, mainUrl)
            ?.takeIf(::isUsablePosterAsset)
            ?: return null
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val currentHost = runCatching {
            URI(mainUrl).host.orEmpty().lowercase().removePrefix("www.")
        }.getOrDefault("")
        val providerPosterHosts = historicalPosterHosts
            .map { it.lowercase().removePrefix("www.") }
            .toSet() + currentHost
        return if (
            host in providerPosterHosts &&
            uri.path.orEmpty().startsWith("/wp-content/uploads/", ignoreCase = true)
        ) {
            ProviderHtmlParser.normalizeProviderPageUrl(
                absolute,
                mainUrl,
                historicalPosterHosts
            )
        } else {
            absolute.takeIf(::isSafeRemoteHttpUrl)
        }
    }

    fun playPageUrl(document: Document, detailUrl: String): String? {
        document.select("a[href]").firstNotNullOfOrNull { link ->
            ProviderHtmlParser.absoluteUrl(link.attr("href"), detailUrl)
                ?.takeIf { url ->
                    runCatching {
                        URI(url).path.orEmpty().trimEnd('/')
                            .endsWith("/play", ignoreCase = true)
                    }.getOrDefault(false)
                }
        }?.let { return it }
        return appendRoute(detailUrl, "play")
    }

    fun watchPageUrl(document: Document, detailUrl: String): String? {
        document.select("a[href]").firstNotNullOfOrNull { link ->
            ProviderHtmlParser.absoluteUrl(link.attr("href"), detailUrl)
                ?.takeIf { url ->
                    runCatching {
                        URI(url).path.orEmpty().trimEnd('/')
                            .endsWith("/watch", ignoreCase = true)
                    }.getOrDefault(false)
                }
        }?.let { return it }
        return appendRoute(detailUrl, "watch")
    }

    fun watchEpisodes(document: Document): List<Int> {
        return document.select("#list-eps a.btn-eps[data-iframe], a.btn-eps[data-iframe]")
            .mapNotNull { link ->
                episodeNumber.find(
                    listOf(link.text(), link.attr("title")).joinToString(" ")
                )?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    fun episodePlayerUrls(document: Document, episode: Int): List<String> {
        if (episode <= 0) return emptyList()
        val matching = document.select(
            "#list-eps a.btn-eps[data-iframe], a.btn-eps[data-iframe]"
        ).filter { link ->
            episodeNumber.find(
                listOf(link.text(), link.attr("title")).joinToString(" ")
            )?.groupValues?.getOrNull(1)?.toIntOrNull() == episode
        }
        val fragment = Document.createShell("")
        matching.forEach { fragment.body().appendChild(it.clone()) }
        return mediaSources(fragment)
    }

    fun encodeEpisodeData(
        detailUrl: String,
        watchUrl: String,
        episode: Int
    ): String {
        require(episode > 0)
        require(sameHost(detailUrl, watchUrl))
        val payload = listOf(episode.toString(), detailUrl, watchUrl).joinToString("\n")
        val encoded = encodeBase64UrlNoPadding(payload.toByteArray(Charsets.UTF_8))
        val uri = URI(watchUrl)
        return buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.rawAuthority)
            append(uri.rawPath)
            append('?')
            uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append(it).append('&') }
            append(EPISODE_QUERY_KEY).append('=').append(encoded)
        }
    }

    fun decodeEpisodeData(data: String): RebahinEpisodeRequest? {
        return runCatching {
            val encoded = URI(data).rawQuery
                ?.split('&')
                ?.firstNotNullOfOrNull { parameter ->
                    parameter.substringAfter('=', "").takeIf {
                        parameter.substringBefore('=', "") == EPISODE_QUERY_KEY &&
                            it.isNotBlank()
                    }
                }
                ?: return@runCatching null
            val decoded = decodeBase64Compat(encoded) ?: return@runCatching null
            val parts = String(decoded, Charsets.UTF_8).split('\n')
            if (parts.size != 3) return@runCatching null
            val episode = parts[0].toIntOrNull()?.takeIf { it > 0 }
                ?: return@runCatching null
            val detailUrl = parts[1].takeIf(::isSafeRemoteHttpUrl)
                ?: return@runCatching null
            val watchUrl = parts[2].takeIf(::isSafeRemoteHttpUrl)
                ?: return@runCatching null
            if (!sameHost(detailUrl, watchUrl)) return@runCatching null
            RebahinEpisodeRequest(detailUrl, watchUrl, episode)
        }.getOrNull()
    }

    private fun appendRoute(url: String, route: String): String? {
        return runCatching {
            val uri = URI(url)
            if (
                uri.scheme?.lowercase() !in setOf("http", "https") ||
                uri.host.isNullOrBlank()
            ) return@runCatching null
            val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/')
            val currentRoute = path.substringAfterLast('/')
            if (currentRoute.equals(route, ignoreCase = true)) return@runCatching url
            URI(
                uri.scheme.lowercase(),
                uri.rawAuthority,
                "$path/$route/",
                null,
                null
            ).toString().takeIf(::isSafeRemoteHttpUrl)
        }.getOrNull()
    }

    private fun sameHost(first: String, second: String): Boolean {
        return runCatching {
            val firstHost = URI(first).host.orEmpty().lowercase().removePrefix("www.")
            val secondHost = URI(second).host.orEmpty().lowercase().removePrefix("www.")
            firstHost.isNotBlank() && firstHost == secondHost
        }.getOrDefault(false)
    }

    private fun sourcePriority(url: String): Int {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host.startsWith("byse") ||
                host == "freeon.site" || host.endsWith(".freeon.site") ||
                host == "justplay.cam" || host.endsWith(".justplay.cam") -> 0
            host == "abyssplayer.com" || host.endsWith(".abyssplayer.com") ||
                host == "abyss.to" || host.endsWith(".abyss.to") -> 3
            host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) -> 2
            else -> 1
        }
    }

    private fun isUsablePosterAsset(raw: String): Boolean {
        val value = raw.trim()
        if (value.isBlank()) return false
        val path = runCatching { URI(value).path.orEmpty() }
            .getOrDefault(value.substringBefore('?'))
            .lowercase()
        val fileName = path.substringAfterLast('/')
        return !(
            fileName.startsWith("cropped-") ||
                fileName.startsWith("logo-rebahin") ||
                fileName == "fb-capture.png" ||
                fileName.endsWith(".ico") ||
                "ikonatas" in fileName
            )
    }

    private const val CONTENT_MARKERS =
        "article.item-infinite, div.ml-item, h1.entry-title, h3[itemprop=name], " +
            "div#muvipro_player_content_id, ul#player-list, ul.muvipro-player-tabs, " +
            ".mvi-cover, #iframe-embed, [data-iframe], #list-eps, iframe, video, source"
}

internal data class RebahinEpisodeRequest(
    val detailUrl: String,
    val watchUrl: String,
    val episode: Int
)
