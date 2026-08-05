package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

private const val PENCURIMOVIE_CATALOG_TIMEOUT_MS = 60_000L

class PencurimovieProvider(
    private val fallbackProviderFactory: () -> List<MainAPI> = {
        listOf(
            FilmapikProvider(),
            PusatfilmProvider(),
            KitanontonProvider(),
            MovieboxProvider()
        )
    }
) : MainAPI() {
    override var mainUrl = "https://ww21.pencurimovie.sbs"
    override var name = "Pencurimovie"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    private val ownedHosts = setOf(
        "ww73.pencurimovie.bond",
        "pencurimovie.bond",
        "pencurimovie.sbs"
    )
    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "movies" to "Film Terbaru",
        "series" to "Serial TV",
        "most-rating" to "Rating Tertinggi",
        "top-imdb" to "Top IMDb",
        "country/indonesia" to "Indonesia",
        "country/malaysia" to "Malaysia",
        "country/japan" to "Jepang",
        "country/thailand" to "Thailand"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val primaryItems = getProviderPage(
            "$mainUrl/${request.data}/page/${page.coerceAtLeast(1)}"
        )?.takeIf { fetch ->
            fetch.code in 200..299 &&
                !ProviderHtmlParser.isNonContentPage(fetch.body)
        }?.let { fetch ->
            Jsoup.parse(fetch.body, fetch.url).select("div.ml-item").mapNotNull {
                it.toSearchResult()
            }
        }.orEmpty()
        val items = resolvePencurimovieCatalog(
            primaryItems = primaryItems,
            fallbackProviders = fallbackProviders(),
            owner = name
        ) { provider ->
            val fallbackPage = provider.mainPage.firstOrNull { candidate ->
                candidate.name.equals(request.name, ignoreCase = true)
            } ?: provider.mainPage.firstOrNull()
                ?: return@resolvePencurimovieCatalog emptyList()
            provider.getMainPage(
                page,
                MainPageRequest(
                    fallbackPage.name,
                    fallbackPage.data,
                    fallbackPage.horizontalImages
                )
            )?.items?.flatMap { it.list }.orEmpty()
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return resolvePencurimovieSearch(
            query = query,
            primarySearch = { searchPrimary(query) },
            fallbackProviders = fallbackProviders(),
            owner = name
        )
    }

    private suspend fun searchPrimary(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val fetch = getProviderPage("$mainUrl/?s=$encoded") ?: return emptyList()
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return emptyList()
        return Jsoup.parse(fetch.body, fetch.url)
            .select("div.ml-item")
            .mapNotNull { it.toSearchResult() }
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = providerUrl(anchor.attr("href")) ?: return null
        val rawTitle = anchor.attr("oldtitle").ifBlank {
            anchor.attr("title").ifBlank { selectFirst("h2, h3")?.text().orEmpty() }
        }
        val title = MovieMetadataParser.title(rawTitle) ?: return null
        if (SensitiveContentPolicy.isBlockedCatalogCard(this, title, href)) return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(anchor.selectFirst("img")))
        val quality = selectFirst("span.mli-quality, div.jtip-quality")?.text()?.trim()
        val episode = selectFirst("span.mli-eps i")?.text()?.trim()?.toIntOrNull()
        val isSeries = episode != null || href.contains("/series/", ignoreCase = true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = providerUrl(url) ?: return loadDelegatedDetail(url)
        val fetch = getProviderPage(requestUrl) ?: return null
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return null
        val document = Jsoup.parse(fetch.body, fetch.url)
        val canonicalUrl = fetch.url
        val title = MovieMetadataParser.title(
            document.selectFirst("div.mvic-desc h3, h1.entry-title")?.text()
        ) ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)
            ?: fixUrlNull(ProviderHtmlParser.firstImageSource(document, "div.mvic-thumb img, img"))
        val description = document.selectFirst("div.desc p.f-desc, div[itemprop=description] p")
            ?.text()?.trim()
        val tags = document.select("div.mvic-info p:contains(Genre) a").map { it.text().trim() }
        val actors = document.select("div.mvic-info p:contains(Actors) a").map { it.text().trim() }
        val year = document.select("div.mvic-info p:contains(Release) a").text().toIntOrNull()
        val duration = document.selectFirst("span[itemprop=duration]")?.text()
            ?.filter(Char::isDigit)?.toIntOrNull()
        val score = document.selectFirst("span.imdb-r[itemprop=ratingValue]")?.text()
        val trailer = document.selectFirst("meta[itemprop=embedUrl]")?.attr("content")
        val recommendations = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        val seasonBlocks = document.select("div.tvseason")
        val isSeries = canonicalUrl.contains("/series/", ignoreCase = true) || seasonBlocks.isNotEmpty()

        return if (isSeries) {
            val episodes = seasonBlocks.flatMap { seasonBlock ->
                val seasonNumber = Regex("""(?i)season\s*(\d+)""")
                    .find(seasonBlock.selectFirst("strong")?.text().orEmpty())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                seasonBlock.select("div.les-content a[href]").mapNotNull { link ->
                    val href = providerUrl(link.attr("href")) ?: return@mapNotNull null
                    val label = link.text().trim()
                    val episodeNumber = Regex("""(?i)episode\s*(\d+)""")
                        .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    newEpisode(href) {
                        season = seasonNumber
                        episode = episodeNumber
                        name = label.substringAfter('-', label).trim()
                        posterUrl = poster
                    }
                }
            }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addScore(score)
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
                addActors(actors)
                addScore(score)
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
        val requestUrl = providerUrl(data)
            ?: return loadDelegatedLinks(data, isCasting, subtitleCallback, callback)
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
        val pageUrl = fetch.url
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) {
            return loadFallback(
                urlFallbackRequests,
                isCasting,
                subtitleCallback,
                callback
            )
        }
        val document = Jsoup.parse(fetch.body, pageUrl)
        val fallbackRequests = (
            PencurimovieParser.fallbackRequests(document, pageUrl) +
                urlFallbackRequests
            ).distinct()
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            candidateTimeoutMs = 20_000L,
            genericExtractorTimeoutMs = 8_000L,
            sessionTimeoutMs = 60_000L
        )
        val candidates = PencurimovieParser.orderedPlayerCandidates((
            document.select("div.movieplay iframe").flatMap { frame ->
                listOf(frame.attr("data-src"), frame.attr("src"))
            } +
                ProviderHtmlParser.mediaSources(document) +
                ProviderHtmlParser.downloadCandidateUrls(document, pageUrl)
            ).mapNotNull { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
                .distinct()
                .take(48)
        )

        resolver.resolveFirstVerified(
            candidates = candidates.map { candidate ->
                PlayerResolutionCandidate(candidate, pageUrl)
            },
            maxConcurrency = 4,
            tierTimeoutMs = PENCURIMOVIE_PRIMARY_TIMEOUT_MS
        )
        if (resolver.loaded) return true

        return loadFallback(
            fallbackRequests,
            isCasting,
            subtitleCallback,
            callback
        )
    }

    private suspend fun loadFallback(
        requests: List<NomatFallbackRequest>,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withPlaybackFallbackBudget(PENCURIMOVIE_FALLBACK_TIMEOUT_MS) {
        val orderedRequests = requests.distinct().sortedWith(
            compareByDescending<NomatFallbackRequest> { it.year != null }
                .thenByDescending { it.title.length }
        )
        val providerLoaded = withTimeoutOrNull(
            PENCURIMOVIE_PROVIDER_FALLBACK_TIMEOUT_MS
        ) {
            for (request in orderedRequests) {
                val loaded = loadFirstEmittingFallback(
                    candidates = fallbackProviders(),
                    callback = callback
                ) { provider, fallbackCallback ->
                    val exactResults = provider.search(request.title).orEmpty()
                        .asSequence()
                        .filter {
                            NomatParser.isPotentialFallbackTitle(request, it.name)
                        }
                        .distinctBy { it.url }
                        .take(PENCURIMOVIE_MAX_FALLBACK_RESULTS)
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

                            is TvSeriesLoadResponse ->
                                PencurimovieParser.fallbackEpisodeData(
                                    request = request,
                                    candidateTitle = detail.name,
                                    candidateYear = detail.year,
                                    episodes = detail.episodes
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
                if (loaded) return@withTimeoutOrNull true
            }
            false
        } == true
        if (providerLoaded) return@withPlaybackFallbackBudget true

        loadExactPublicCatalogFallback(
            safeHttp = safeHttp,
            requests = orderedRequests,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private fun fallbackProviders(): List<MainAPI> = fallbackProviderFactory()

    private suspend fun getProviderPage(url: String): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer(::networkProviderUrl),
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun providerUrl(raw: String?): String? = ProviderHtmlParser.normalizeProviderPageUrl(
        raw,
        mainUrl,
        ownedHosts
    )

    private fun networkProviderUrl(raw: String?): String? =
        ProviderHtmlParser.preserveProviderPageUrl(raw, mainUrl, ownedHosts)

    private suspend fun loadDelegatedDetail(url: String): LoadResponse? =
        withTimeoutOrNull(PENCURIMOVIE_CATALOG_TIMEOUT_MS) {
            firstNonEmptyFallback(fallbackProviders()) { provider ->
                listOfNotNull(provider.load(url)?.withProviderOwner(name))
            }.firstOrNull()
        }

    private suspend fun loadDelegatedLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withPlaybackFallbackBudget(PENCURIMOVIE_FALLBACK_TIMEOUT_MS) {
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

    private companion object {
        const val PENCURIMOVIE_PRIMARY_TIMEOUT_MS = 35_000L
        const val PENCURIMOVIE_FALLBACK_TIMEOUT_MS = 90_000L
        const val PENCURIMOVIE_PROVIDER_FALLBACK_TIMEOUT_MS = 55_000L
        const val PENCURIMOVIE_MAX_FALLBACK_RESULTS = 8
    }
}

internal suspend fun resolvePencurimovieCatalog(
    primaryItems: List<SearchResponse>,
    fallbackProviders: Iterable<MainAPI>,
    owner: String,
    fallbackItems: suspend (MainAPI) -> List<SearchResponse>
): List<SearchResponse> {
    if (primaryItems.isNotEmpty()) return primaryItems
    return withTimeoutOrNull(PENCURIMOVIE_CATALOG_TIMEOUT_MS) {
        firstNonEmptyFallback(fallbackProviders) { provider ->
            fallbackItems(provider).map { result -> result.withProviderOwner(owner) }
        }
    }.orEmpty()
}

internal suspend fun resolvePencurimovieSearch(
    query: String,
    primarySearch: suspend () -> List<SearchResponse>,
    fallbackProviders: Iterable<MainAPI>,
    owner: String
): List<SearchResponse> {
    repeat(2) {
        val primary = try {
            primarySearch()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        if (primary.isNotEmpty()) return primary
    }
    return withTimeoutOrNull(PENCURIMOVIE_CATALOG_TIMEOUT_MS) {
        firstNonEmptyFallback(fallbackProviders) { provider ->
            provider.search(query).orEmpty()
                .map { result -> result.withProviderOwner(owner) }
        }
    }.orEmpty()
}

internal object PencurimovieParser {
    private val playmogoAliases = setOf(
        "dsvplay.com",
        "ds2play.com",
        "doodstream.com"
    )
    private val yearRegex = Regex("""\b(?:19|20)\d{2}\b""")
    private val parenthesizedYearSuffixRegex =
        Regex("""\s*[\[(](?:19|20)\d{2}[\])]\s*$""")
    private val trailingYearRegex = Regex("""(?:^|\s)((?:19|20)\d{2})$""")
    private val seasonSuffixRegex =
        Regex("""(?i)\s+\bseason\s*[-:]?\s*\d+\b.*$""")
    private val episodeSuffixRegex =
        Regex("""(?i)\s+\b(?:episode|eps?\.?)\s*[-:]?\s*\d+\b.*$""")
    private val languageEditionTagRegex = Regex(
        """(?i)\s*[\[(]\s*(?:(?:malay|indonesian?|english|hindi|tamil|telugu|thai|mandarin|cantonese)\s*(?:dub(?:bed)?|audio|sub(?:title)?)|dual\s+audio)\s*[\])]\s*"""
    )

    fun extractorCompatibleUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf(::isSafeRemoteHttpUrl) ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        if (host !in playmogoAliases) return value

        return buildString {
            append("https://playmogo.com")
            append(uri.rawPath.orEmpty().ifBlank { "/" })
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }.takeIf(::isSafeRemoteHttpUrl)
    }

    fun orderedPlayerCandidates(rawCandidates: List<String>): List<String> {
        val groups = rawCandidates
            .mapNotNull(::extractorCompatibleUrl)
            .distinct()
            .groupBy(::mirrorFamily)
            .entries
            .sortedWith(compareBy({ mirrorRank(it.key) }, { it.key }))
            .map { it.value }

        return buildList {
            var mirrorIndex = 0
            while (groups.any { mirrorIndex < it.size }) {
                groups.forEach { mirrors ->
                    mirrors.getOrNull(mirrorIndex)?.let(::add)
                }
                mirrorIndex++
            }
        }
    }

    fun fallbackRequest(
        document: org.jsoup.nodes.Document,
        pageUrl: String? = null
    ): NomatFallbackRequest? = fallbackRequests(document, pageUrl).firstOrNull()

    fun fallbackRequests(
        document: org.jsoup.nodes.Document,
        pageUrl: String? = null
    ): List<NomatFallbackRequest> {
        val documentRequest = documentFallbackRequest(document, pageUrl)
        val urlRequests = pageUrl?.let(::urlFallbackRequests).orEmpty()
        return (listOfNotNull(documentRequest) + urlRequests).distinct()
    }

    private fun documentFallbackRequest(
        document: org.jsoup.nodes.Document,
        pageUrl: String?
    ): NomatFallbackRequest? {
        val rawTitle = document.selectFirst("div.mvic-desc h3, h1.entry-title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val parsedTitle = MovieMetadataParser.title(rawTitle) ?: return null
        val releaseYear = document.select("div.mvic-info p:contains(Release) a")
            .firstNotNullOfOrNull { element ->
                yearRegex.find(element.text())?.value?.toIntOrNull()
            }
        val coordinateText = buildString {
            append(parsedTitle)
            pageUrl?.let { url ->
                append(' ')
                append(
                    runCatching { URI(url).path.orEmpty() }
                        .getOrDefault("")
                        .replace('-', ' ')
                )
            }
        }
        val (season, episode) = PopularProviderEpisodeParser.position(coordinateText)
        val title = cleanTitle(parsedTitle, releaseYear)
            ?: return null
        val year = releaseYear
            ?: parenthesizedYearSuffixRegex.find(parsedTitle)
                ?.let { match -> yearRegex.find(match.value) }
                ?.value
                ?.toIntOrNull()
        return NomatFallbackRequest(title, year, season, episode)
    }

    private fun urlFallbackRequests(pageUrl: String): List<NomatFallbackRequest> {
        val slug = runCatching { URI(pageUrl).path.orEmpty() }
            .getOrNull()
            ?.trim('/')
            ?.substringAfterLast('/')
            ?.takeIf {
                it.isNotBlank() &&
                    it.lowercase() !in setOf(
                        "movies",
                        "series",
                        "most-rating",
                        "top-imdb"
                    )
            }
            ?: return emptyList()
        val rawTitle = slug.split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { character -> character.titlecase() }
            }
        val parsedTitle = MovieMetadataParser.title(rawTitle) ?: return emptyList()
        val (season, episode) = PopularProviderEpisodeParser.position(parsedTitle)
        val preservedTitle = cleanTitle(parsedTitle, null) ?: return emptyList()
        val preserved = NomatFallbackRequest(
            title = preservedTitle,
            year = null,
            season = season,
            episode = episode
        )
        val trailingYear = trailingYearRegex.find(preservedTitle)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val interpretedAsYear = trailingYear?.let { year ->
            cleanTitle(preservedTitle, year)?.let { title ->
                if (title == preservedTitle) {
                    null
                } else {
                    NomatFallbackRequest(title, year, season, episode)
                }
            }
        }
        return listOfNotNull(preserved, interpretedAsYear).distinct()
    }

    private fun cleanTitle(raw: String, releaseYear: Int?): String? {
        val cleaned = raw
            .replace(seasonSuffixRegex, "")
            .replace(episodeSuffixRegex, "")
            .replace(parenthesizedYearSuffixRegex, "")
            .replace(languageEditionTagRegex, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val withoutReleaseYear = releaseYear?.let { year ->
            cleaned.replace(Regex("""\s+\Q$year\E\s*$"""), "")
                .trim()
                .takeIf { it.isNotBlank() }
        } ?: cleaned
        return withoutReleaseYear.takeIf { it.isNotBlank() }
    }

    fun fallbackEpisodeData(
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?,
        episodes: List<Episode>
    ): String? {
        if (!NomatParser.isExactFallbackMatch(request, candidateTitle, candidateYear)) {
            return null
        }
        val expectedEpisode = request.episode ?: return null
        return episodes.filter { episode ->
            episode.episode == expectedEpisode &&
                (request.season == null || episode.season == request.season)
        }.singleOrNull()?.data?.takeIf { it.isNotBlank() }
    }

    private fun mirrorFamily(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")
        return when {
            host == "voe.sx" || host.endsWith(".voe.sx") -> "voe"
            host == "playmogo.com" || host.endsWith(".playmogo.com") -> "playmogo"
            host == "hgcloud.to" || host.endsWith(".hgcloud.to") -> "hgcloud"
            host == "streamtape.com" || host.endsWith(".streamtape.com") -> "streamtape"
            directMediaType(url) != null -> "direct"
            else -> "other:$host"
        }
    }

    private fun mirrorRank(family: String): Int = when (family) {
        "direct" -> 0
        "voe" -> 1
        "playmogo" -> 2
        "hgcloud" -> 3
        "streamtape" -> 5
        else -> 4
    }
}
