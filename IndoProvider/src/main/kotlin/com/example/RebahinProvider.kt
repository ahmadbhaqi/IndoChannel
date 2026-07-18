package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
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
        val fetch = fetchProviderPage(requestUrl, requireCatalogIdentity = true)
            ?: return newHomePageResponse(request.name, emptyList())
        val pageUrl = fetch.url
        val document = fetch.document
        val items = document.select("article.item-infinite, div.ml-item")
            .mapNotNull { it.toSearchResult(pageUrl) }
        return newHomePageResponse(request.name, items)
    }

    internal fun Element.toSearchResult(pageUrl: String = mainUrl): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val title = MovieMetadataParser.title(
            selectFirst(".mli-info h2, h2.entry-title a, h2 a")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").takeIf { it.isNotBlank() }
        ) ?: return null
        val href = normalizePageUrl(anchor.attr("href")) ?: return null
        val posterUrl = RebahinPageParser.cardPosterUrl(
            this,
            mainUrl,
            pageUrl
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
        val fetch = fetchProviderPage(requestUrl, requireCatalogIdentity = true) ?: return emptyList()
        val pageUrl = fetch.url
        val document = fetch.document
        return document.select("article.item-infinite, div.ml-item")
            .mapNotNull { it.toSearchResult(pageUrl) }
    }

    override suspend fun load(url: String): LoadResponse {
        val requestUrl = normalizePageUrl(url)
            ?: throw ErrorLoadingException("$name rejected a foreign movie URL")
        val fetch = fetchProviderPage(requestUrl, requireCatalogIdentity = true)
            ?: throw ErrorLoadingException("$name redirected to a foreign movie host")
        val pageUrl = fetch.url
        val canonicalUrl = normalizePageUrl(pageUrl)
            ?: throw ErrorLoadingException("$name could not canonicalize its movie URL")
        val document = fetch.document
        val title = MovieMetadataParser.title(
            document.selectFirst("h1.entry-title, h3[itemprop=name]")?.text()
        ) ?: MovieMetadataParser.title(document.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: throw ErrorLoadingException("$name returned a page without a movie title")
        val poster = RebahinPageParser.normalizedPosterUrl(
            document,
            mainUrl,
            pageUrl
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
            RebahinPageParser.watchPageUrl(document, pageUrl)
                ?.let { activePageUrl(it, pageUrl) }
        } else {
            null
        }
        return if (isSeries) {
            var resolvedWatchUrl = watchUrl
            val watchEpisodes = watchUrl?.let { page ->
                try {
                    val watchFetch = fetchProviderPage(
                        page,
                        referer = pageUrl,
                        requirePlayerIdentity = true
                    ) ?: return@let emptyList()
                    resolvedWatchUrl = watchFetch.url
                    RebahinPageParser.watchEpisodes(watchFetch.document)
                } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            }.orEmpty()
            val episodeDetailUrl = resolvedWatchUrl?.let { activeWatchUrl ->
                RebahinPageParser.rehomePageUrl(pageUrl, getBaseUrl(activeWatchUrl))
            }
            val episodes = if (
                resolvedWatchUrl != null &&
                episodeDetailUrl != null &&
                watchEpisodes.isNotEmpty()
            ) {
                watchEpisodes.map { episode ->
                    newEpisode(
                        RebahinPageParser.encodeEpisodeData(
                            episodeDetailUrl,
                            resolvedWatchUrl!!,
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
                        val href = activePageUrl(episodeLink.attr("href"), pageUrl)
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
        fun newResolver() = LinkResolutionSession(
            api = this,
            subtitleCallback = subtitleCallback,
            callback = callback,
            inlineSourceParser = { html, _ -> InlineDataParser.playableInlineUrls(html) }
        )
        RebahinPageParser.decodeEpisodeData(data)?.let { episodeRequest ->
            val detailUrl = activePageUrl(episodeRequest.detailUrl, mainUrl) ?: return false
            val watchUrl = activePageUrl(episodeRequest.watchUrl, detailUrl) ?: return false
            val watchFetch = try {
                fetchProviderPage(
                    watchUrl,
                    referer = detailUrl,
                    requirePlayerIdentity = true
                ) ?: return false
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
                return false
            }
            val watchPageUrl = watchFetch.url
            // Alias discovery is provider I/O, not link resolution. Start the finite
            // resolver budget only after a trusted watch page has answered.
            val resolver = newResolver()
            for (source in RebahinPageParser.episodePlayerUrls(
                watchFetch.document,
                episodeRequest.episode
            )) {
                if (!resolver.canContinue || resolver.loaded) break
                resolver.resolveInline(source, watchPageUrl)
            }
            return resolver.loaded
        }

        val requestUrl = normalizePageUrl(data) ?: return false
        val initialFetch = fetchProviderPage(requestUrl, requireCatalogIdentity = true) ?: return false
        val initialPageUrl = initialFetch.url
        // Do not let a slow/dead provider alias consume the media-resolution budget.
        val resolver = newResolver()

        suspend fun resolvePageSources(page: ProviderPage): Boolean {
            for (source in RebahinPageParser.mediaSources(page.document)) {
                if (!resolver.canContinue || resolver.loaded) break
                val candidate = ProviderHtmlParser.absoluteUrl(source, page.url) ?: continue
                resolver.resolveInline(candidate, page.url)
            }
            return resolver.loaded
        }

        val playUrl = RebahinPageParser.playPageUrl(initialFetch.document, initialPageUrl)
            ?.let { activePageUrl(it, initialPageUrl) }
        val playback = discoverPlaybackPages(
            detailPage = initialFetch,
            playUrl = playUrl,
            resolvePage = ::resolvePageSources
        ) { candidate ->
            fetchProviderPage(
                candidate,
                referer = initialPageUrl,
                requirePlayerIdentity = true
            )
        }
        if (playback.loaded) return true
        val pages = playback.pages

        for (page in pages) {
            val document = page.document
            val pageUrl = page.url
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
                val ajaxResponseUrl = activePageUrl(response.url, pageUrl) ?: continue
                for (media in RebahinPageParser.ajaxMediaCandidates(
                    response.document,
                    relativeBaseUrl = ajaxResponseUrl,
                    embeddingPageUrl = pageUrl
                )) {
                    if (!resolver.canContinue || resolver.loaded) break
                    resolver.resolveInline(media.url, media.referer)
                }
                if (resolver.loaded) return true
            }

            for (link in document.select("ul#player-list > li a, ul.muvipro-player-tabs li a")) {
                if (!resolver.canContinue) break
                val playerUrl = activePageUrl(link.attr("href"), pageUrl) ?: continue
                val response = try {
                    fetchProviderPage(
                        playerUrl,
                        referer = pageUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        requirePlayerIdentity = true
                    ) ?: continue
                } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                } ?: continue
                val playerPageUrl = response.url
                for (source in RebahinPageParser.mediaSources(response.document)) {
                    if (!resolver.canContinue || resolver.loaded) break
                    val candidate = ProviderHtmlParser.absoluteUrl(source, playerPageUrl) ?: continue
                    resolver.resolveInline(candidate, playerPageUrl)
                }
                if (resolver.loaded) return true
            }
        }

        for (page in pages) {
            val document = page.document
            val pageUrl = page.url
            for (download in ProviderHtmlParser.downloadCandidateUrls(document, pageUrl)) {
                if (!resolver.canContinue || resolver.loaded) break
                resolver.resolve(download, pageUrl)
            }
        }
        return resolver.loaded
    }

    internal data class ProviderPage(
        val document: Document,
        val url: String
    )

    internal data class PlaybackDiscovery(
        val pages: List<ProviderPage>,
        val loaded: Boolean
    )

    internal suspend fun discoverPlaybackPages(
        detailPage: ProviderPage,
        playUrl: String?,
        resolvePage: suspend (ProviderPage) -> Boolean,
        fetchPlayPage: suspend (String) -> ProviderPage?
    ): PlaybackDiscovery {
        if (resolvePage(detailPage)) {
            return PlaybackDiscovery(listOf(detailPage), loaded = true)
        }
        if (playUrl == null || playUrl == detailPage.url) {
            return PlaybackDiscovery(listOf(detailPage), loaded = false)
        }

        val playPage = try {
            fetchPlayPage(playUrl)
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return PlaybackDiscovery(listOf(detailPage), loaded = false)

        return PlaybackDiscovery(
            pages = listOf(detailPage, playPage),
            loaded = resolvePage(playPage)
        )
    }

    private suspend fun fetchProviderPage(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        requireCatalogIdentity: Boolean = false,
        requirePlayerIdentity: Boolean = false
    ): ProviderPage? = selectProviderAliasPage(
        url = url,
        referer = referer,
        requireCatalogIdentity = requireCatalogIdentity,
        requirePlayerIdentity = requirePlayerIdentity
    ) { candidate, candidateReferer ->
        val response = app.get(
            candidate,
            referer = candidateReferer,
            headers = headers,
            timeout = REBAHIN_ALIAS_HTTP_TIMEOUT_SECONDS
        )
        if (response.code !in 200..399) null
        else ProviderPage(response.document, response.url)
    }

    internal suspend fun selectProviderAliasPage(
        url: String,
        referer: String? = null,
        requireCatalogIdentity: Boolean = false,
        requirePlayerIdentity: Boolean = false,
        maxAttempts: Int = REBAHIN_MAX_ALIAS_ATTEMPTS,
        attemptTimeoutMs: Long = REBAHIN_ALIAS_ATTEMPT_TIMEOUT_MS,
        fetchCandidate: suspend (candidate: String, referer: String?) -> ProviderPage?
    ): ProviderPage? {
        require(!(requireCatalogIdentity && requirePlayerIdentity)) {
            "A Rebahin page cannot require both catalog and player identity"
        }
        val boundedAttempts = maxAttempts.coerceIn(1, REBAHIN_MAX_ALIAS_ATTEMPTS)
        val boundedTimeoutMs = attemptTimeoutMs.coerceIn(1L, REBAHIN_ALIAS_ATTEMPT_TIMEOUT_MS)
        for (candidate in RebahinPageParser.aliasPageUrls(url, mainUrl).take(boundedAttempts)) {
            val candidateReferer = referer?.let {
                RebahinPageParser.rehomePageUrl(it, getBaseUrl(candidate))
            }
            val fetched = try {
                withTimeoutOrNull(boundedTimeoutMs) {
                    fetchCandidate(candidate, candidateReferer)
                }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            } ?: continue
            val pageUrl = activePageUrl(fetched.url, candidate) ?: continue
            val document = fetched.document
            if (ProviderHtmlParser.isNonContentPage(document.outerHtml())) continue
            if (
                requireCatalogIdentity &&
                !RebahinPageParser.isTrustedCatalogDocument(document, pageUrl, mainUrl)
            ) continue
            if (
                requirePlayerIdentity &&
                !RebahinPageParser.isTrustedPlayerDocument(document, pageUrl, mainUrl)
            ) continue
            return ProviderPage(document, pageUrl)
        }
        return null
    }

    private fun normalizePageUrl(raw: String?): String? =
        RebahinPageParser.normalizePageUrl(raw, mainUrl)

    private fun activePageUrl(raw: String?, baseUrl: String): String? =
        RebahinPageParser.activePageUrl(raw, baseUrl, mainUrl)

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.rawAuthority}" }
    }
}

internal const val REBAHIN_MAX_ALIAS_ATTEMPTS = 3
internal const val REBAHIN_ALIAS_ATTEMPT_TIMEOUT_MS = 8_000L
private const val REBAHIN_ALIAS_HTTP_TIMEOUT_SECONDS = 8L

internal data class RebahinMediaCandidate(
    val url: String,
    val referer: String
)

internal object RebahinPageParser {
    private val currentAliasHosts = setOf(
        "rebahinxxi3.lol",
        "rebahinxxi3.hair",
        "rebahinxxi3.click"
    )
    private val historicalHosts = currentAliasHosts + setOf(
        "154.203.167.63",
        "178.62.115.110",
        "156.244.7.27",
        "rebahinxxi3.autos",
        "rebahinxxi.coupons"
    )
    private val historicalPosterHosts = historicalHosts + "198.54.124.245"
    private val placeholderPosterName = Regex(
        """(?:^|[-_.])(?:placeholder|no[-_]?image|no[-_]?poster|no[-_]?thumbnail|nothumb|blank|spacer|transparent|default[-_]?poster|1x1)(?:[-_.]|$)""",
        RegexOption.IGNORE_CASE
    )
    private const val EPISODE_QUERY_KEY = "rebahin_episode"
    private val episodeNumber = Regex(
        """\b(?:episode|eps?|ep)\s*\.?\s*(\d+)\b""",
        RegexOption.IGNORE_CASE
    )

    fun normalizePageUrl(raw: String?, mainUrl: String): String? {
        return ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, historicalHosts)
    }

    /** Validates an alias redirect while preserving the origin that answered. */
    fun activePageUrl(raw: String?, baseUrl: String, mainUrl: String): String? {
        val absolute = ProviderHtmlParser.absoluteUrl(raw, baseUrl) ?: return null
        return runCatching {
            val uri = URI(absolute)
            val scheme = uri.scheme.orEmpty().lowercase()
            val host = uri.host.orEmpty().lowercase().removePrefix("www.")
            val mainHost = URI(mainUrl).host.orEmpty().lowercase().removePrefix("www.")
            val allowedHosts = historicalHosts
                .map { it.lowercase().removePrefix("www.") }
                .toSet() + mainHost
            val defaultPort = when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> return@runCatching null
            }
            absolute.takeIf {
                uri.userInfo == null &&
                    host in allowedHosts &&
                    (uri.port == -1 || uri.port == defaultPort) &&
                    isSafeRemoteHttpUrl(it)
            }
        }.getOrNull()
    }

    fun aliasPageUrls(raw: String?, mainUrl: String): List<String> {
        val active = activePageUrl(raw, mainUrl, mainUrl) ?: return emptyList()
        return runCatching {
            val uri = URI(active)
            val variants = mutableListOf(active)
            currentAliasHosts.forEach { host ->
                variants += buildString {
                    append("https://").append(host)
                    append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
                    uri.rawQuery?.let { append('?').append(it) }
                    uri.rawFragment?.let { append('#').append(it) }
                }
            }
            variants.distinct()
        }.getOrDefault(emptyList())
    }

    fun rehomePageUrl(raw: String?, activeBaseUrl: String): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, activeBaseUrl, historicalHosts)

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

    /** Accepts minimal watch/player fragments without weakening provider-origin checks. */
    fun isTrustedPlayerDocument(document: Document, pageUrl: String, mainUrl: String): Boolean {
        if (activePageUrl(pageUrl, mainUrl, mainUrl) == null) return false
        if (ProviderHtmlParser.isNonContentPage(document.outerHtml())) return false

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

        val hasPlayerMarkers = document.select(
            "#list-eps [data-iframe], a.btn-eps[data-iframe], [data-iframe], " +
                "iframe[src], iframe[data-src], video[src], source[src], " +
                "#muvipro_player_content_id, .tab-content-ajax, " +
                "ul#player-list a[href], ul.muvipro-player-tabs a[href]"
        ).isNotEmpty()
        return hasPlayerMarkers || InlineDataParser.playableInlineUrls(document.outerHtml()).isNotEmpty()
    }

    fun mediaSources(document: Document): List<String> {
        return (
            ProviderHtmlParser.mediaSources(
                document,
                "iframe, div.gmr-embed-responsive iframe"
            ) + InlineDataParser.playableInlineUrls(document.outerHtml())
            )
            .distinct()
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> { sourcePriority(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    fun ajaxMediaCandidates(
        document: Document,
        relativeBaseUrl: String,
        embeddingPageUrl: String
    ): List<RebahinMediaCandidate> {
        if (!isSafeRemoteHttpUrl(relativeBaseUrl) || !isSafeRemoteHttpUrl(embeddingPageUrl)) {
            return emptyList()
        }
        return mediaSources(document).mapNotNull { source ->
            val absolute = ProviderHtmlParser.absoluteUrl(source, relativeBaseUrl)
                ?.takeIf(::isSafeRemoteHttpUrl)
                ?: return@mapNotNull null
            RebahinMediaCandidate(absolute, embeddingPageUrl)
        }.distinctBy { it.url }
    }

    fun cardPosterUrl(element: Element, mainUrl: String, pageUrl: String): String? {
        return imagePosterCandidates(element.select("img"))
            .firstNotNullOfOrNull { candidate ->
                normalizePosterUrl(candidate, mainUrl, pageUrl)
            }
    }

    fun posterUrl(document: Document): String? {
        return posterCandidates(document).firstOrNull()
    }

    fun normalizedPosterUrl(document: Document, mainUrl: String, pageUrl: String): String? {
        return posterCandidates(document).firstNotNullOfOrNull { candidate ->
            normalizePosterUrl(candidate, mainUrl, pageUrl)
        }
    }

    private fun posterCandidates(document: Document): List<String> {
        val ogPosters = document.select("meta[property][content]")
            .filter { meta -> meta.attr("property").startsWith("og:image", ignoreCase = true) }
            .mapNotNull { meta -> meta.attr("content").trim().takeIf(::isUsablePosterAsset) }
        val itemPropPosters = document.select("meta[itemprop][content]")
            .filter { meta -> meta.attr("itemprop").equals("image", ignoreCase = true) }
            .mapNotNull { meta -> meta.attr("content").trim().takeIf(::isUsablePosterAsset) }
        val twitterPosters = document.select("meta[name][content]")
            .filter { meta -> meta.attr("name").startsWith("twitter:image", ignoreCase = true) }
            .mapNotNull { meta -> meta.attr("content").trim().takeIf(::isUsablePosterAsset) }
        val metadataPosters = ogPosters + itemPropPosters + twitterPosters
        val orderedMetadata = buildList {
            metadataPosters.firstOrNull(::isTmdbPosterAsset)?.let { add(it) }
            addAll(ogPosters)
            addAll(itemPropPosters)
            addAll(twitterPosters)
        }
        val imagePosters = imagePosterCandidates(
            document.select(
                "img.thumbnail, figure.pull-left > img, .mvi-cover img, .mvic-thumb img, " +
                    "img[itemprop=image], [itemprop=image] img"
            )
        )
        val backgroundPosters = document.select(".mvi-cover[style], .mvic-thumb[style]")
            .mapNotNull { element ->
                Regex("""(?i)background-image\s*:\s*url\((['"]?)(.*?)\1\)""")
                    .find(element.attr("style"))
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.trim()
                    ?.takeIf(::isUsablePosterAsset)
            }
        return (orderedMetadata + imagePosters + backgroundPosters)
            .filter(::isUsablePosterAsset)
            .distinct()
    }

    private fun imagePosterCandidates(images: Iterable<Element>): List<String> {
        val srcsetAttributes = listOf("data-litespeed-srcset", "data-srcset", "srcset")
        val sourceAttributes = listOf(
            "data-litespeed-src",
            "data-src",
            "data-lazy-src",
            "data-original",
            "src"
        )
        return images.flatMap { image ->
            val srcsetCandidates = srcsetAttributes.flatMap { attribute ->
                image.attr(attribute)
                    .split(',')
                    .map { entry -> entry.trim().substringBefore(' ').trim() }
            }
            val sourceCandidates = sourceAttributes.map { attribute ->
                image.attr(attribute).trim()
            }
            srcsetCandidates + sourceCandidates
        }.filter(::isUsablePosterAsset).distinct()
    }

    fun normalizePosterUrl(
        raw: String?,
        mainUrl: String,
        pageUrl: String = mainUrl
    ): String? {
        val activeBaseUrl = activePageUrl(pageUrl, mainUrl, mainUrl) ?: mainUrl
        val absolute = ProviderHtmlParser.absoluteUrl(raw, activeBaseUrl)
            ?.takeIf(::isUsablePosterAsset)
            ?: return null
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val currentHost = runCatching {
            URI(activeBaseUrl).host.orEmpty().lowercase().removePrefix("www.")
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
                activeBaseUrl,
                historicalPosterHosts
            )
        } else {
            absolute.takeIf(::isSafeRemoteHttpUrl)
        }
    }

    fun playPageUrl(document: Document, detailUrl: String): String? {
        document.select("#mv-info > a[href]").firstNotNullOfOrNull { link ->
            ProviderHtmlParser.absoluteUrl(link.attr("href"), detailUrl)
                ?.takeIf(::isSafeRemoteHttpUrl)
                ?.takeIf { sameHost(it, detailUrl) }
        }?.let { return it }
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
        document.select("#mv-info > a[href]").firstNotNullOfOrNull { link ->
            ProviderHtmlParser.absoluteUrl(link.attr("href"), detailUrl)
                ?.takeIf(::isSafeRemoteHttpUrl)
                ?.takeIf { sameHost(it, detailUrl) }
        }?.let { return it }
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
        return document.select("#list-eps [data-iframe], a.btn-eps[data-iframe]")
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
            "#list-eps [data-iframe], a.btn-eps[data-iframe]"
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
                host == "abyss.to" || host.endsWith(".abyss.to") -> 1
            host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) -> 3
            else -> 2
        }
    }

    private fun isUsablePosterAsset(raw: String): Boolean {
        val value = raw.trim()
        if (value.isBlank()) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.isAbsolute && !isSafeRemoteHttpUrl(value)) return false
        if (value.startsWith("//") && !isSafeRemoteHttpUrl("https:$value")) return false
        val path = uri.path.orEmpty().ifBlank { value.substringBefore('?') }
            .lowercase()
        val fileName = path.substringAfterLast('/')
        return !(
            fileName.startsWith("cropped-") ||
                fileName.startsWith("logo-rebahin") ||
                fileName == "fb-capture.png" ||
                fileName.endsWith(".ico") ||
                "ikonatas" in fileName ||
                placeholderPosterName.containsMatchIn(fileName)
            )
    }

    private fun isTmdbPosterAsset(raw: String): Boolean {
        return runCatching {
            val value = if (raw.startsWith("//")) "https:$raw" else raw
            val uri = URI(value)
            uri.host.orEmpty().lowercase().removePrefix("www.") == "image.tmdb.org" &&
                uri.path.orEmpty().startsWith("/t/p/", ignoreCase = true)
        }.getOrDefault(false)
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
