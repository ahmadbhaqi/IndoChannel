package com.example

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal suspend fun resolveIndomaxPlayerPhases(
    primary: PlayerResolutionCandidate?,
    loadFallbacks: suspend () -> List<PlayerResolutionCandidate>,
    resolveBatch: suspend (List<PlayerResolutionCandidate>) -> Boolean
): Boolean {
    if (primary != null && resolveBatch(listOf(primary))) return true
    val fallbacks = loadFallbacks()
    return fallbacks.isNotEmpty() && resolveBatch(fallbacks)
}

class IndomaxProvider(
    private val fallbackProviderFactory: () -> List<MainAPI> = {
        listOf(
            FilmapikProvider(),
            IdlixProvider(),
            PusatfilmProvider(),
            KitanontonProvider()
        )
    }
) : MainAPI() {
    override var mainUrl = "https://idmxl.ink"
    override var name = "Indomax"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "category/serial-tv/page/%d/" to "TV Series",
        "category/action/page/%d/" to "Action",
        "category/adventure/page/%d/" to "Adventure",
        "category/animation/page/%d/" to "Animation",
        "category/anime/page/%d/" to "Anime",
        "category/comedy/page/%d/" to "Comedy",
        "category/donghua/page/%d/" to "Donghua",
        "category/thriller/page/%d/" to "Thriller",
        "country/china/page/%d/" to "China",
        "country/indonesia/page/%d/" to "Indonesia",
        "country/korea/page/%d/" to "Korea",
        "country/philippines/page/%d/" to "Philippines",
        "country/thailand/page/%d/" to "Thailand"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page.coerceAtLeast(1))
        val primaryItems = getProviderPage("$mainUrl/$path")
            ?.takeIf { fetch ->
                fetch.code in 200..299 &&
                    !ProviderHtmlParser.isNonContentPage(fetch.body)
            }
            ?.let { fetch ->
                IndomaxParser.catalogItems(
                    Jsoup.parse(fetch.body, fetch.url),
                    fetch.url
                ).map { item -> item.toSearchResult() }
            }
            .orEmpty()
        if (primaryItems.isNotEmpty()) {
            return newHomePageResponse(request.name, primaryItems)
        }
        return loadFallbackMainPage(page, request)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        val primary = async {
            getProviderPage(
                "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv"
            )?.takeIf { fetch ->
                fetch.code in 200..299 &&
                    !ProviderHtmlParser.isNonContentPage(fetch.body)
            }?.let { fetch ->
                IndomaxParser.catalogItems(
                    Jsoup.parse(fetch.body, fetch.url),
                    fetch.url
                ).map { item -> item.toSearchResult() }
            }.orEmpty()
        }
        val fallbackGroups = fallbackProviders().map { provider ->
            async {
                withTimeoutOrNull(INDOMAX_CATALOG_PROVIDER_TIMEOUT_MS) {
                    try {
                        provider.search(query).orEmpty()
                            .map { result -> result.withProviderOwner(name) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                }.orEmpty()
            }
        }

        interleaveIndomaxCatalogGroups(
            primary = primary.await(),
            fallbackGroups = fallbackGroups.awaitAll(),
            maxResults = INDOMAX_MAX_CATALOG_FALLBACK_RESULTS,
            keySelector = { result -> result.url }
        )
    }

    private fun IndomaxCatalogItem.toSearchResult(): SearchResponse {
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = fixUrlNull(this@toSearchResult.posterUrl)
                quality = getQualityFromString(this@toSearchResult.quality)
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = fixUrlNull(this@toSearchResult.posterUrl)
                quality = getQualityFromString(this@toSearchResult.quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = IndomaxParser.providerPageUrl(url, mainUrl)
            ?: return loadDelegatedDetail(url)
        val urlFallbackRequests = PencurimovieParser.fallbackRequests(
            Jsoup.parse(""),
            requestUrl
        )
        val fetch = getProviderPage(requestUrl)
            ?: return loadFallbackDetail(urlFallbackRequests)
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return loadFallbackDetail(urlFallbackRequests)

        val canonicalUrl = fetch.url
        val document = Jsoup.parse(fetch.body, canonicalUrl)
        val fallbackRequests = (
            PencurimovieParser.fallbackRequests(document, canonicalUrl) +
                urlFallbackRequests
            ).distinct()
        val title = MovieMetadataParser.title(
            document.selectFirst("h1.entry-title")?.text()
        ) ?: return loadFallbackDetail(fallbackRequests)
        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.let { ProviderHtmlParser.absoluteUrl(it, canonicalUrl) }
            ?.let(::fixUrlNull)
            ?: ProviderHtmlParser.firstImageSource(
                document,
                "figure.pull-left img, figure img"
            )?.let { ProviderHtmlParser.absoluteUrl(it, canonicalUrl) }
                ?.let(::fixUrlNull)
        val description = document.selectFirst("div[itemprop=description] p")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: MovieMetadataParser.synopsis(document)
        val tags = document.select(
            "div.gmr-moviedata a[href*='/genre/'], div.gmr-moviedata a[href*='/country/']"
        ).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = document.select("a[href*='/year/']")
            .firstNotNullOfOrNull {
                YEAR_REGEX.find(it.text())?.value?.toIntOrNull()
            }
        val trailer = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        val actors = document.select(
            "span[itemprop=actor] a, span[itemprop=actors] a"
        ).map { it.text().trim() }.filter { it.isNotBlank() }
        val rating = document.selectFirst("[itemprop=ratingValue]")?.text()?.trim()
            ?: document.selectFirst("div.gmr-rating-bar span")
                ?.attr("style")
                ?.let { RATING_WIDTH_REGEX.find(it)?.groupValues?.getOrNull(1) }
                ?.toDoubleOrNull()
                ?.div(10)
                ?.toString()
        val duration = DURATION_REGEX.find(
            document.select("div.gmr-moviedata").text()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        val recommendations = IndomaxParser.catalogItems(
            document.select("article.item.col-md-20, article.item").toDocument(canonicalUrl),
            canonicalUrl
        ).map { it.toSearchResult() }
        val episodeItems = IndomaxParser.episodeItems(document, canonicalUrl)
        val isSeries = canonicalUrl.contains("/tv/", ignoreCase = true) ||
            episodeItems.isNotEmpty()
        if (isSeries && episodeItems.isEmpty()) {
            return loadFallbackDetail(fallbackRequests)
        }

        return if (isSeries) {
            val episodes = episodeItems.map { item ->
                val (_, episodeNumber) = PopularProviderEpisodeParser.position(item.label)
                newEpisode(item.url) {
                    episode = episodeNumber
                    name = episodeNumber?.let { "Episode $it" } ?: item.label
                    posterUrl = poster
                }
            }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addScore(rating)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addScore(rating)
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
        val requestUrl = IndomaxParser.providerPageUrl(data, mainUrl)
            ?: return loadDelegatedLinks(
                data,
                isCasting,
                subtitleCallback,
                callback
            )
        val urlFallbackRequests = PencurimovieParser.fallbackRequests(
            Jsoup.parse(""),
            requestUrl
        )
        val fetch = getProviderPage(requestUrl) ?: return loadFallback(
            urlFallbackRequests,
            isCasting,
            subtitleCallback,
            callback
        )
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return loadFallback(
            urlFallbackRequests,
            isCasting,
            subtitleCallback,
            callback
        )

        val pageUrl = fetch.url
        val document = Jsoup.parse(fetch.body, pageUrl)
        val fallbackRequests = (
            PencurimovieParser.fallbackRequests(document, pageUrl) +
                urlFallbackRequests
            ).distinct()
        val resolver = LinkResolutionSession(
            api = this,
            subtitleCallback = subtitleCallback,
            callback = callback,
            pageFetcher = { playerUrl, referer ->
                getPublicPlayerPage(playerUrl, referer)?.body.orEmpty()
            },
            playerApiFetcher = { apiUrl, referer, headers ->
                getPublicPlayerPage(
                    apiUrl,
                    referer,
                    headers,
                    INDOMAX_PLAYER_API_LIMIT_BYTES
                )?.body.orEmpty()
            },
            inlineSourceParser = IndomaxParser::imaxMediaUrls,
            maxCandidates = 12
        )
        val primary = IndomaxParser.primaryPlayerUrl(document, pageUrl)?.let { playerUrl ->
            PlayerResolutionCandidate(playerUrl, pageUrl)
        }
        val resolved = resolveIndomaxPlayerPhases(
            primary = primary,
            loadFallbacks = {
                val remainingPlayers =
                    IndomaxParser.MAX_PLAYER_PAGES - if (primary == null) 0 else 1
                val tabUrls =
                    IndomaxParser.alternateTabUrls(document, pageUrl, remainingPlayers)
                coroutineScope {
                    tabUrls.map { tabUrl ->
                        async {
                            val tabFetch = resolver.withinBudget {
                                getProviderPage(tabUrl)
                            } ?: return@async null
                            if (
                                tabFetch.code !in 200..299 ||
                                ProviderHtmlParser.isNonContentPage(tabFetch.body)
                            ) return@async null
                            val tabDocument = Jsoup.parse(tabFetch.body, tabFetch.url)
                            IndomaxParser.primaryPlayerUrl(tabDocument, tabFetch.url)?.let {
                                playerUrl -> PlayerResolutionCandidate(playerUrl, tabFetch.url)
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            },
            resolveBatch = resolver::resolveFirstVerified
        )
        if (resolved && resolver.loaded) return true
        return loadFallback(
            fallbackRequests,
            isCasting,
            subtitleCallback,
            callback
        )
    }

    private suspend fun loadFallbackMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = withTimeoutOrNull(INDOMAX_CATALOG_FALLBACK_TIMEOUT_MS) {
            firstNonEmptyFallback(fallbackProviders()) { provider ->
                val categoryNames = fallbackCategoryNames(request.name)
                val fallbackPage = provider.mainPage.firstOrNull { candidate ->
                    categoryNames.any { name ->
                        name.equals(candidate.name, ignoreCase = true)
                    }
                } ?: provider.mainPage.firstOrNull()
                    ?: return@firstNonEmptyFallback emptyList()
                provider.getMainPage(
                    page,
                    MainPageRequest(
                        fallbackPage.name,
                        fallbackPage.data,
                        fallbackPage.horizontalImages
                    )
                )?.items?.flatMap { homeList -> homeList.list }
                    ?.let { results -> ownIndomaxFallbackCatalog(results, name) }
                    .orEmpty()
            }
        }.orEmpty()
        return newHomePageResponse(request.name, items)
    }

    private suspend fun loadDelegatedDetail(url: String): LoadResponse? =
        withTimeoutOrNull(INDOMAX_CATALOG_FALLBACK_TIMEOUT_MS) {
            firstNonEmptyFallback(fallbackProviders()) { provider ->
                listOfNotNull(provider.load(url)?.withProviderOwner(name))
                    .filter(::isUsableFallbackDetail)
            }.firstOrNull()
        }

    private fun isUsableFallbackDetail(detail: LoadResponse): Boolean = when (detail) {
        is com.lagradost.cloudstream3.TvSeriesLoadResponse ->
            detail.episodes.any { episode -> episode.data.isNotBlank() }

        else -> true
    }

    private suspend fun loadFallbackDetail(
        requests: List<NomatFallbackRequest>
    ): LoadResponse? = withTimeoutOrNull(INDOMAX_CATALOG_FALLBACK_TIMEOUT_MS) {
        for (request in requests) {
            for (provider in fallbackProviders()) {
                try {
                    val exactResults = provider.search(request.title).orEmpty()
                        .asSequence()
                        .filter {
                            NomatParser.isPotentialFallbackTitle(request, it.name)
                        }
                        .distinctBy { result -> result.url }
                        .take(INDOMAX_MAX_FALLBACK_RESULTS)
                        .toList()
                    for (result in exactResults) {
                        val detail = provider.load(result.url) ?: continue
                        val exactDetail = when (detail) {
                            is MovieLoadResponse ->
                                request.season == null &&
                                    request.episode == null &&
                                    NomatParser.isExactFallbackMatch(
                                        request,
                                        detail.name,
                                        detail.year
                                    )

                            is com.lagradost.cloudstream3.TvSeriesLoadResponse ->
                                LayarKacaPlayerParser.fallbackSeriesMatchesRequest(
                                    request,
                                    detail.name,
                                    detail.year,
                                    detail.episodes
                                )

                            else -> false
                        }
                        if (exactDetail) {
                            return@withTimeoutOrNull detail.withProviderOwner(name)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A failed fallback must not hide the next exact provider.
                }
            }
        }
        null
    }

    private suspend fun loadDelegatedLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withPlaybackFallbackBudget(INDOMAX_PLAYBACK_FALLBACK_TIMEOUT_MS) {
        loadFirstEmittingFallback(
            candidates = fallbackProviders(),
            callback = callback
        ) { provider, fallbackCallback ->
            provider.loadLinks(
                data,
                isCasting,
                subtitleCallback,
                fallbackCallback
            )
        }
    }

    private suspend fun loadFallback(
        requests: List<NomatFallbackRequest>,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withPlaybackFallbackBudget(INDOMAX_PLAYBACK_FALLBACK_TIMEOUT_MS) {
        for (request in requests) {
            val loaded = loadFirstEmittingFallback(
                candidates = fallbackProviders(),
                callback = callback
            ) { provider, fallbackCallback ->
                val exactResults = provider.search(request.title).orEmpty()
                    .asSequence()
                    .filter {
                        NomatParser.isPotentialFallbackTitle(request, it.name)
                    }
                    .distinctBy { result -> result.url }
                    .take(INDOMAX_MAX_FALLBACK_RESULTS)
                    .toList()
                for (result in exactResults) {
                    val detail = provider.load(result.url) ?: continue
                    val playbackData = when (detail) {
                        is MovieLoadResponse -> detail.dataUrl.takeIf {
                            request.season == null &&
                                request.episode == null &&
                                NomatParser.isExactFallbackMatch(
                                    request,
                                    detail.name,
                                    detail.year
                                )
                        }

                        is com.lagradost.cloudstream3.TvSeriesLoadResponse ->
                            PencurimovieParser.fallbackEpisodeData(
                                request,
                                detail.name,
                                detail.year,
                                detail.episodes
                            )

                        else -> null
                    } ?: continue
                    if (
                        provider.loadLinks(
                            playbackData,
                            isCasting,
                            subtitleCallback,
                            fallbackCallback
                        )
                    ) return@loadFirstEmittingFallback true
                }
                false
            }
            if (loaded) return@withPlaybackFallbackBudget true
        }
        false
    }

    private fun fallbackProviders(): List<MainAPI> = fallbackProviderFactory()

    private fun fallbackCategoryNames(requestedName: String): Set<String> = when {
        requestedName.equals("Box Office", ignoreCase = true) ->
            setOf(requestedName, "Terbaru", "Beranda")

        requestedName.equals("TV Series", ignoreCase = true) ->
            setOf(requestedName, "Serial TV")

        requestedName.equals("Animation", ignoreCase = true) ->
            setOf(requestedName, "Animasi")

        else -> setOf(requestedName)
    }

    private suspend fun getProviderPage(url: String): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer {
                IndomaxParser.providerPageUrl(it, mainUrl)
            },
            maxBodyBytes = INDOMAX_PROVIDER_PAGE_LIMIT_BYTES,
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun getPublicPlayerPage(
        url: String,
        referer: String?,
        headers: Map<String, String> = emptyMap(),
        maxBodyBytes: Int = IndomaxParser.MAX_PACKED_INPUT_CHARS
    ): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer(IndomaxParser::publicHttpsUrl),
            headers = headers,
            referer = referer,
            maxBodyBytes = maxBodyBytes,
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).takeIf {
            it.code in 200..299 && !ProviderHtmlParser.isNonContentPage(it.body)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val INDOMAX_CATALOG_FALLBACK_TIMEOUT_MS = 60_000L
        const val INDOMAX_CATALOG_PROVIDER_TIMEOUT_MS = 20_000L
        const val INDOMAX_PLAYBACK_FALLBACK_TIMEOUT_MS = 90_000L
        const val INDOMAX_MAX_FALLBACK_RESULTS = 8
        const val INDOMAX_MAX_CATALOG_FALLBACK_RESULTS = 24
        const val INDOMAX_PROVIDER_PAGE_LIMIT_BYTES = 2_000_000
        const val INDOMAX_PLAYER_API_LIMIT_BYTES = 4_000_000
        val YEAR_REGEX = Regex("""\b(?:19|20)\d{2}\b""")
        val RATING_WIDTH_REGEX = Regex("""(?i)width\s*:\s*([0-9]+(?:\.[0-9]+)?)%""")
        val DURATION_REGEX = Regex("""(?i)duration\s*:\s*(\d+)""")
    }
}

internal data class IndomaxCatalogItem(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val quality: String?,
    val episodeCount: Int?,
    val rating: Double?,
    val isSeries: Boolean
)

internal data class IndomaxEpisodeItem(
    val url: String,
    val label: String
)

internal fun <T> interleaveFallbackResults(
    groups: List<List<T>>,
    maxResults: Int
): List<T> = interleaveIndomaxCatalogGroups(
    primary = emptyList(),
    fallbackGroups = groups,
    maxResults = maxResults,
    keySelector = { value -> value }
)

internal fun <T, Key> interleaveIndomaxCatalogGroups(
    primary: List<T>,
    fallbackGroups: List<List<T>>,
    maxResults: Int,
    keySelector: (T) -> Key
): List<T> {
    val limit = maxResults.coerceAtLeast(0)
    if (limit == 0) return emptyList()
    val groups = listOf(primary) + fallbackGroups
    val seen = hashSetOf<Key>()
    return buildList {
        var index = 0
        while (size < limit && groups.any { index < it.size }) {
            groups.forEach { group ->
                val value = group.getOrNull(index) ?: return@forEach
                if (seen.add(keySelector(value))) add(value)
                if (size >= limit) return@buildList
            }
            index++
        }
    }
}

internal fun ownIndomaxFallbackCatalog(
    results: List<SearchResponse>,
    owner: String
): List<SearchResponse> = results.map { result -> result.withProviderOwner(owner) }

internal object IndomaxParser {
    const val MAX_PLAYER_PAGES = 4
    const val MAX_PACKED_INPUT_CHARS = 512_000
    private const val MAX_PACKED_BLOCKS = 4
    private const val MAX_PACKER_HEADER_CHARS = 4_096
    private const val MAX_DICTIONARY_ITEMS = 4_096
    private const val MAX_UNPACKED_OUTPUT_CHARS = 1_000_000
    private const val MAX_IMAX_MEDIA_CANDIDATES = 4
    private const val PACKER_MARKER = "eval(function(p,a,c,k,e,d)"
    private val providerHosts = setOf(
        "idmxl.ink",
        "akses7.indomax21.xyz",
        "akses8.indomax21.xyz",
        "akses6.indomax21.xyz",
        "akses10.indomax21.xyz"
    )
    private val rotatingProviderHostRegex =
        Regex("""^akses\d{1,3}\.indomax21\.xyz$""")
    private val imaxHosts = setOf("imaxstreams.net", "imaxstreams.com")
    private val hlsUrlRegex = Regex(
        """(?i)https://[^\s"'<>\\]+(?:\.m3u8|/master\.txt)(?:\?[^\s"'<>\\]*)?"""
    )
    private val packerArgumentsRegex = Regex(
        """^\s*,\s*(\d{1,3})\s*,\s*(\d{1,5})\s*,"""
    )
    private val dictionarySplitRegex = Regex(
        """^\s*\.split\(\s*['"]\|['"]\s*\)"""
    )

    fun catalogItems(document: Document, pageUrl: String): List<IndomaxCatalogItem> {
        return document.select("article.item-infinite, article.item").mapNotNull { article ->
            val anchor = article.selectFirst("h2.entry-title a[href]") ?: return@mapNotNull null
            val title = anchor.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = providerPageUrl(anchor.attr("href"), pageUrl) ?: return@mapNotNull null
            if (SensitiveContentPolicy.isBlockedCatalogCard(article, title, url)) {
                return@mapNotNull null
            }
            val poster = ProviderHtmlParser.firstImageSource(
                article,
                "img.wp-post-image, div.content-thumbnail img, img"
            )?.let { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
            val quality = article.selectFirst(".gmr-quality-item a, .gmr-quality-item")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val episodeCount = article.selectFirst(".gmr-numbeps span")
                ?.text()
                ?.filter(Char::isDigit)
                ?.toIntOrNull()
            val rating = article.selectFirst(".gmr-rating-item")
                ?.ownText()
                ?.trim()
                ?.toDoubleOrNull()
            IndomaxCatalogItem(
                title = title,
                url = url,
                posterUrl = poster,
                quality = quality,
                episodeCount = episodeCount,
                rating = rating,
                isSeries = episodeCount != null ||
                    url.contains("/tv/", ignoreCase = true)
            )
        }.distinctBy { it.url }
    }

    fun episodeItems(document: Document, canonicalUrl: String): List<IndomaxEpisodeItem> {
        val canonicalPage = providerPageUrl(canonicalUrl, canonicalUrl)
            ?.substringBefore('?')
            ?.trimEnd('/')
            ?: return emptyList()
        return document.select(
            "div.vid-episodes a[href], div.gmr-listseries a[href]"
        ).mapNotNull { link ->
            val url = providerPageUrl(link.attr("href"), canonicalUrl)
                ?: return@mapNotNull null
            val label = link.attr("title")
                .takeIf { it.isNotBlank() }
                ?.trim()
                ?: link.text().trim()
            val normalizedLabel = label
                .replace(Regex("""(?i)^permalink\s+ke\s+"""), "")
                .trim()
            if (
                normalizedLabel.contains("Lihat Semua Episode", ignoreCase = true) ||
                normalizedLabel.contains("View All Episodes", ignoreCase = true) ||
                url.substringBefore('?').trimEnd('/') == canonicalPage
            ) return@mapNotNull null
            IndomaxEpisodeItem(url, normalizedLabel)
        }.distinctBy { it.url }
    }

    fun providerPageUrl(raw: String?, baseUrl: String): String? {
        val value = raw?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 8_192
        } ?: return null
        return runCatching {
            val base = URI(baseUrl)
            val resolved = base.resolve(value)
            val host = resolved.host?.lowercase()?.trimEnd('.') ?: return@runCatching null
            if (
                resolved.scheme?.lowercase() != "https" ||
                resolved.userInfo != null ||
                resolved.rawFragment != null ||
                resolved.port !in setOf(-1, 443) ||
                host !in providerHosts && !rotatingProviderHostRegex.matches(host)
            ) return@runCatching null
            resolved.toASCIIString()
        }.getOrNull()
    }

    fun publicHttpsUrl(raw: String?): String? {
        return publicHttpsCandidate(raw, allowFragment = false)
    }

    fun publicPlayableUrl(raw: String?): String? {
        return publicHttpsCandidate(raw, allowFragment = true)
    }

    private fun publicHttpsCandidate(
        raw: String?,
        allowFragment: Boolean
    ): String? {
        val value = raw?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 8_192
        } ?: return null
        return runCatching {
            val uri = URI(value)
            if (
                uri.scheme?.lowercase() != "https" ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null ||
                (!allowFragment && uri.rawFragment != null) ||
                uri.port !in setOf(-1, 443) ||
                !isSafeRemoteHttpUrl(uri.toASCIIString())
            ) return@runCatching null
            uri.toASCIIString()
        }.getOrNull()
    }

    fun primaryPlayerUrl(document: Document, pageUrl: String): String? {
        val raw = ProviderHtmlParser.firstIframeSource(
            document.selectFirst("div.gmr-embed-responsive iframe")
        ) ?: return null
        return ProviderHtmlParser.absoluteUrl(raw, pageUrl)?.let(::publicPlayableUrl)
    }

    fun alternateTabUrls(
        document: Document,
        pageUrl: String,
        remainingPlayers: Int
    ): List<String> {
        if (remainingPlayers <= 0) return emptyList()
        val current = providerPageUrl(pageUrl, pageUrl)?.substringBefore('#')
        return document.select("ul.muvipro-player-tabs li a[href]").asSequence()
            .filterNot { link ->
                link.hasClass("active") || link.parent()?.hasClass("active") == true
            }
            .mapNotNull { link ->
                ProviderHtmlParser.absoluteUrl(link.attr("href"), pageUrl)
            }
            .mapNotNull { providerPageUrl(it, pageUrl) }
            .filter { it.substringBefore('#') != current }
            .distinct()
            .take(remainingPlayers.coerceAtMost(MAX_PLAYER_PAGES))
            .toList()
    }

    fun imaxMediaUrls(html: String, playerUrl: String): List<String> {
        if (
            html.length > MAX_PACKED_INPUT_CHARS ||
            !isImaxPlayerUrl(playerUrl)
        ) return emptyList()

        val scripts = Jsoup.parse(html, playerUrl).select("script")
            .asSequence()
            .map(Element::data)
            .filter { it.isNotBlank() }
            .filter {
                it.contains(PACKER_MARKER) ||
                    it.contains(".m3u8", ignoreCase = true)
            }
            .take(MAX_PACKED_BLOCKS)
            .toList()
        return scripts.asSequence()
            .flatMap { script ->
                sequenceOf(script) + unpackedScripts(script).asSequence()
            }
            .flatMap { script ->
                hlsUrlRegex.findAll(script.decodeJsUrl()).map { it.value }
            }
            .mapNotNull(::publicHttpsUrl)
            .distinct()
            .take(MAX_IMAX_MEDIA_CANDIDATES)
            .toList()
    }

    private fun isImaxPlayerUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme?.lowercase() == "https" &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            uri.host?.lowercase()?.trimEnd('.') in imaxHosts
    }.getOrDefault(false)

    private fun unpackedScripts(script: String): List<String> {
        val unpacked = mutableListOf<String>()
        var cursor = 0
        while (cursor < script.length && unpacked.size < MAX_PACKED_BLOCKS) {
            val start = script.indexOf(PACKER_MARKER, cursor)
            if (start < 0) break
            unpackClassicPacker(script, start)?.let(unpacked::add)
            cursor = start + PACKER_MARKER.length
        }
        return unpacked
    }

    private fun unpackClassicPacker(script: String, start: Int): String? {
        if (script.length > MAX_PACKED_INPUT_CHARS || start !in script.indices) return null
        val argumentsStart = script.indexOf("}(", start)
            .takeIf { it >= 0 && it - start <= MAX_PACKER_HEADER_CHARS }
            ?: return null
        var cursor = skipWhitespace(script, argumentsStart + 2)
        val payload = readJsQuoted(script, cursor) ?: return null
        cursor = payload.endExclusive

        val argumentPrefix = script.substring(
            cursor,
            (cursor + 100).coerceAtMost(script.length)
        )
        val arguments = packerArgumentsRegex.find(argumentPrefix) ?: return null
        val radix = arguments.groupValues[1].toIntOrNull()
            ?.takeIf { it in 2..62 }
            ?: return null
        val count = arguments.groupValues[2].toIntOrNull()
            ?.takeIf { it in 0..MAX_DICTIONARY_ITEMS }
            ?: return null
        cursor += arguments.range.last + 1
        cursor = skipWhitespace(script, cursor)
        val dictionary = readJsQuoted(script, cursor) ?: return null
        cursor = dictionary.endExclusive
        val suffix = script.substring(
            cursor,
            (cursor + 100).coerceAtMost(script.length)
        )
        if (!dictionarySplitRegex.containsMatchIn(suffix)) return null
        val words = dictionary.value.split('|')
        return unpackTokens(payload.value, words, count, radix)
    }

    private fun unpackTokens(
        payload: String,
        words: List<String>,
        count: Int,
        radix: Int
    ): String? {
        val output = StringBuilder(payload.length.coerceAtMost(MAX_UNPACKED_OUTPUT_CHARS))
        var cursor = 0
        while (cursor < payload.length) {
            if (!payload[cursor].isAsciiWord()) {
                output.append(payload[cursor++])
            } else {
                val start = cursor
                while (cursor < payload.length && payload[cursor].isAsciiWord()) cursor++
                val token = payload.substring(start, cursor)
                val index = decodePackerToken(token, radix)
                val replacement = index
                    ?.takeIf { it in 0 until count }
                    ?.let(words::getOrNull)
                    .orEmpty()
                output.append(replacement.ifEmpty { token })
            }
            if (output.length > MAX_UNPACKED_OUTPUT_CHARS) return null
        }
        return output.toString()
    }

    private fun decodePackerToken(token: String, radix: Int): Int? {
        var value = 0L
        for (character in token) {
            val digit = when (character) {
                in '0'..'9' -> character - '0'
                in 'a'..'z' -> character - 'a' + 10
                in 'A'..'Z' -> character - 'A' + 36
                else -> return null
            }
            if (digit >= radix) return null
            value = value * radix + digit
            if (value > Int.MAX_VALUE) return null
        }
        return value.toInt()
    }

    private data class JsQuotedValue(
        val value: String,
        val endExclusive: Int
    )

    private fun readJsQuoted(input: String, quoteIndex: Int): JsQuotedValue? {
        val quote = input.getOrNull(quoteIndex)?.takeIf { it == '\'' || it == '"' }
            ?: return null
        val value = StringBuilder()
        var cursor = quoteIndex + 1
        while (cursor < input.length) {
            val character = input[cursor++]
            when {
                character == quote -> return JsQuotedValue(value.toString(), cursor)
                character != '\\' -> value.append(character)
                cursor >= input.length -> return null
                else -> {
                    val escaped = input[cursor++]
                    when (escaped) {
                        '\\', '\'', '"', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            val decoded = input.decodeHex(cursor, 4) ?: return null
                            value.append(decoded.first)
                            cursor = decoded.second
                        }
                        'x' -> {
                            val decoded = input.decodeHex(cursor, 2) ?: return null
                            value.append(decoded.first)
                            cursor = decoded.second
                        }
                        '\r', '\n' -> Unit
                        else -> value.append(escaped)
                    }
                }
            }
            if (value.length > MAX_PACKED_INPUT_CHARS) return null
        }
        return null
    }

    private fun String.decodeHex(start: Int, length: Int): Pair<Char, Int>? {
        val end = start + length
        if (start < 0 || end > this.length) return null
        val value = substring(start, end).toIntOrNull(16) ?: return null
        return value.toChar() to end
    }

    private fun skipWhitespace(value: String, start: Int): Int {
        var cursor = start
        while (cursor < value.length && value[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun Char.isAsciiWord(): Boolean =
        this == '_' || this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'

    private fun String.decodeJsUrl(): String = replace("\\/", "/")
        .replace(Regex("""(?i)\\u0026"""), "&")
        .replace("&amp;", "&")
}

private fun List<Element>.toDocument(baseUrl: String): Document =
    Jsoup.parse(joinToString(separator = "") { it.outerHtml() }, baseUrl)
