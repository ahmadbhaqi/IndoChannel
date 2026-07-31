package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NomatProvider : MainAPI() {
    override var mainUrl = "https://nomat.shop"
    override var name = "Nomat"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "slug/film-terbaru/%d/" to "Film Terbaru",
        "slug/film-box-office-terkini/%d/" to "Box Office",
        "slug/film-serial-baru-terpopuler/%d/" to "Serial TV",
        "category/genre/action/%d/" to "Action",
        "category/genre/animasi/%d/" to "Animasi",
        "category/genre/horror/%d/" to "Horor",
        "category/genre/romance/%d/" to "Romansa",
        "category/country/indonesia/%d/" to "Indonesia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page.coerceAtLeast(1))
        val fetch = getProviderPage("$mainUrl/$path")
            ?: return newHomePageResponse(request.name, emptyList())
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return newHomePageResponse(request.name, emptyList())
        val document = Jsoup.parse(fetch.body, fetch.url)
        return newHomePageResponse(
            request.name,
            document.select("a:has(.item-content)").mapNotNull { it.toSearchResult() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = NomatParser.searchPathSegment(query)
        val fetch = getProviderPage("$mainUrl/search/$encoded/") ?: return emptyList()
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return emptyList()
        return Jsoup.parse(fetch.body, fetch.url)
            .select("a:has(.item-content)")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = providerUrl(attr("href")) ?: return null
        val title = MovieMetadataParser.title(selectFirst(".title")?.text()) ?: return null
        if (SensitiveContentPolicy.isBlockedCatalogCard(this, title, href)) return null
        val posterStyle = selectFirst(".poster")?.attr("style").orEmpty()
        val poster = fixUrlNull(
            Regex("""(?i)url\((?:['"])?([^'")]+)""")
                .find(posterStyle)?.groupValues?.getOrNull(1)
        )
        val quality = selectFirst("div.qual")?.text()?.trim()
        val episode = Regex("""(?i)eps?\.?\s*(\d+)""")
            .find(quality.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val isSeries = episode != null || href.contains("/serial-tv/", ignoreCase = true)
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
        val requestUrl = providerUrl(url) ?: return null
        val fetch = getProviderPage(requestUrl) ?: return null
        val canonicalUrl = fetch.url
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return null
        val document = Jsoup.parse(fetch.body, canonicalUrl)
        val heading = document.selectFirst("div.video-title h1, h1.entry-title")?.text()
        val title = MovieMetadataParser.title(heading) ?: return null
        val poster = fixUrlNull(NomatParser.poster(document))
        val tags = document.select("div.video-genre a").map { it.text().trim() }
        val year = document.select("a[href*='/category/year/']")
            .firstNotNullOfOrNull { Regex("""(?:19|20)\d{2}""").find(it.text())?.value?.toIntOrNull() }
        val description = document.selectFirst("div.video-synopsis")?.text()?.trim()
        val trailer = document.selectFirst("div.video-trailer iframe")?.attr("src")
        val rating = document.selectFirst("div.rtg")?.text()?.trim()
        val actors = document.select("div.video-actor a").map { it.text().trim() }
        val recommendations = document.select("a:has(.item-content)").mapNotNull { it.toSearchResult() }
        val episodeLinks = document.select("div.video-episodes a[href]")
        val isSeries = canonicalUrl.contains("/serial-tv/", ignoreCase = true) ||
            episodeLinks.isNotEmpty()

        return if (isSeries) {
            val season = Regex("""(?i)season\s*(\d+)""")
                .find(heading.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episodes = episodeLinks.mapNotNull { link ->
                val href = providerUrl(link.attr("href")) ?: return@mapNotNull null
                val episode = Regex("""\d+""").find(link.text())?.value?.toIntOrNull()
                newEpisode(href) {
                    this.season = season
                    this.episode = episode
                    name = episode?.let { "Episode $it" } ?: link.text().trim()
                    posterUrl = poster
                }
            }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                addScore(rating)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                addScore(rating)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = NomatParser.playbackPageUrl(data, mainUrl) ?: return false
        val response = fetchPlaybackPage(pageUrl) ?: return false
        val responseUrl = NomatParser.playbackPageUrl(response.url, mainUrl) ?: return false
        if (
            response.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(response.body)
        ) return false
        val document = Jsoup.parse(response.body, responseUrl)
        val fallbackRequest = providerUrl(responseUrl)?.let {
            NomatParser.fallbackRequest(document)
        }
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            inlineSourceParser = { html, playerUrl ->
                NomatParser.playerUrls(Jsoup.parse(html, playerUrl), playerUrl)
            }
        )
        val candidates = (
            NomatParser.serverUrls(document) +
                NomatParser.playerUrls(document, responseUrl) +
                ProviderHtmlParser.mediaSources(document) +
                ProviderHtmlParser.downloadCandidateUrls(document, responseUrl)
            ).mapNotNull { ProviderHtmlParser.absoluteUrl(it, responseUrl) }.distinct().take(48)

        resolver.resolveFirstVerified(
            candidates.map { candidate ->
                PlayerResolutionCandidate(candidate, responseUrl)
            }
        )
        if (resolver.loaded || fallbackRequest == null) return resolver.loaded
        return loadFallback(
            request = fallbackRequest,
            isCasting = isCasting,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun loadFallback(
        request: NomatFallbackRequest,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (
            loadExactPublicCatalogFallback(
                safeHttp = safeHttp,
                requests = listOf(request),
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        ) return true

        val providers = listOf(
            FilmapikProvider(),
            PusatfilmProvider(),
            KitanontonProvider(),
            LayarKacaProvider()
        )
        for (provider in providers) {
            try {
                val exactResults = provider.search(request.title).orEmpty()
                    .asSequence()
                    .filter { NomatParser.isPotentialFallbackTitle(request, it.name) }
                    .distinctBy { it.url }
                    .take(3)
                    .toList()
                for (result in exactResults) {
                    val detail = provider.load(result.url) ?: continue
                    if (!NomatParser.isExactFallbackMatch(request, detail.name, detail.year)) {
                        continue
                    }
                    val playbackData = when (detail) {
                        is MovieLoadResponse ->
                            detail.dataUrl.takeIf {
                                request.season == null && request.episode == null
                            }

                        is TvSeriesLoadResponse -> {
                            val matchingEpisodes = detail.episodes.filter { episode ->
                                episode.episode == request.episode &&
                                    (request.season == null || episode.season == request.season)
                            }
                            matchingEpisodes.singleOrNull()?.data
                        }

                        else -> null
                    } ?: continue
                    if (
                        provider.loadLinks(
                            playbackData,
                            isCasting,
                            subtitleCallback,
                            callback
                        )
                    ) return true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A fallback failure must not prevent the next exact-match provider.
            }
        }
        return false
    }

    private suspend fun fetchPlaybackPage(initialUrl: String): ProviderHttpResult? = try {
        safeHttp.get(
            url = initialUrl,
            normalizer = ProviderUrlNormalizer {
                NomatParser.networkPlaybackPageUrl(it, mainUrl)
            },
            referer = mainUrl,
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun getProviderPage(url: String): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer {
                NomatParser.networkProviderPageUrl(it, mainUrl)
            },
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun providerUrl(raw: String?): String? = NomatParser.providerPageUrl(raw, mainUrl)
}

internal data class NomatFallbackRequest(
    val title: String,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null
)

internal object NomatParser {
    private const val MAX_SERVER_VALUE_SIZE = 16_384
    private val providerHosts = setOf("nomat.site", "nomat.store", "nomat.asia")
    private val playbackHosts = setOf("nontonhemat.link")
    private val yearRegex = Regex("""\b(?:19|20)\d{2}\b""")
    private val parenthesizedYearSuffixRegex =
        Regex("""\s*[\[(](?:19|20)\d{2}[\])]\s*$""")
    private val seasonRegex = Regex("""(?i)\bseason\s*[-:]?\s*(\d+)\b""")
    private val episodeRegex = Regex("""(?i)\b(?:episode|eps?\.?)\s*[-:]?\s*(\d+)\b""")

    fun searchPathSegment(query: String): String =
        URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")

    fun providerPageUrl(raw: String?, mainUrl: String): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, providerHosts)

    fun networkProviderPageUrl(raw: String?, mainUrl: String): String? =
        ProviderHtmlParser.preserveProviderPageUrl(raw, mainUrl, providerHosts)

    fun playbackPageUrl(raw: String?, mainUrl: String): String? {
        providerPageUrl(raw, mainUrl)?.let { return it }
        return externalPlaybackPageUrl(raw)
    }

    fun networkPlaybackPageUrl(raw: String?, mainUrl: String): String? {
        networkProviderPageUrl(raw, mainUrl)?.let { return it }
        return externalPlaybackPageUrl(raw)
    }

    private fun externalPlaybackPageUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf(::isSafeRemoteHttpUrl) ?: return null
        return runCatching {
            val host = URI(value).host.orEmpty().lowercase().removePrefix("www.")
            value.takeIf {
                playbackHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
            }
        }.getOrNull()
    }

    fun redirectTarget(location: String?, responseUrl: String, mainUrl: String): String? =
        ProviderHtmlParser.absoluteUrl(location, responseUrl)
            ?.let { playbackPageUrl(it, mainUrl) }

    fun serverUrls(document: Document): List<String> {
        return document.select("div.server-item[data-url], [data-url].server-item").mapNotNull {
            val encoded = it.attr("data-url").trim()
                .takeIf { value -> value.isNotBlank() && value.length <= MAX_SERVER_VALUE_SIZE }
                ?: return@mapNotNull null
            if (isSafeRemoteHttpUrl(encoded)) return@mapNotNull encoded
            decodeBase64Compat(encoded)?.toString(Charsets.UTF_8)
                ?.trim()
                ?.takeIf(::isSafeRemoteHttpUrl)
        }.distinct()
    }

    fun playerUrls(document: Document, pageUrl: String): List<String> =
        (
            serverUrls(document) +
                document.select(
                    "div.video-wrapper a[href], div.video-wrapper iframe[src], " +
                        "div.video-wrapper iframe[data-src]"
                ).flatMap { element ->
                    listOf(element.attr("href"), element.attr("data-src"), element.attr("src"))
                }
            ).mapNotNull { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
            .filter(::isSafeRemoteHttpUrl)
            .distinct()

    fun fallbackRequest(document: Document): NomatFallbackRequest? {
        val rawTitle = document.selectFirst("div.video-title h1, h1.entry-title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val parsedTitle = MovieMetadataParser.title(rawTitle) ?: return null
        val year = document.select("a[href*='/category/year/']")
            .firstNotNullOfOrNull { yearRegex.find(it.text())?.value?.toIntOrNull() }
            ?: parenthesizedYearSuffixRegex.find(parsedTitle)
                ?.let { match -> yearRegex.find(match.value) }
                ?.value
                ?.toIntOrNull()
        val season = seasonRegex.find(parsedTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = episodeRegex.find(parsedTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val title = fallbackTitle(parsedTitle, year)

        return title?.let {
            NomatFallbackRequest(
                title = it,
                year = year,
                season = season,
                episode = episode
            )
        }
    }

    fun isExactFallbackTitle(expected: String, candidate: String): Boolean {
        val expectedKey = titleKey(expected)
        return expectedKey.isNotEmpty() && expectedKey == titleKey(candidate)
    }

    fun isPotentialFallbackTitle(
        request: NomatFallbackRequest,
        candidate: String
    ): Boolean {
        if (isExactFallbackTitle(request.title, candidate)) return true
        val releaseYear = request.year ?: return false
        val withoutReleaseYear = candidate.replace(
            Regex("""(?:\s*[-–—|:]\s*|\s+)\Q$releaseYear\E\s*$"""),
            ""
        ).trim()
        return withoutReleaseYear.isNotBlank() &&
            isExactFallbackTitle(request.title, withoutReleaseYear)
    }

    fun isExactFallbackMatch(
        request: NomatFallbackRequest,
        candidateTitle: String,
        candidateYear: Int?
    ): Boolean {
        if (!isPotentialFallbackTitle(request, candidateTitle)) return false
        return request.year == null || candidateYear == request.year
    }

    fun poster(document: Document): String? {
        val style = document.selectFirst("div.video-poster")?.attr("style").orEmpty()
        return Regex("""(?i)url\((?:['"])?([^'")]+)""")
            .find(style)?.groupValues?.getOrNull(1)
            ?: ProviderHtmlParser.firstImageSource(document, "div.video-poster img")
    }

    private fun fallbackTitle(raw: String, releaseYear: Int? = null): String? {
        val cleaned = raw
        .replace(Regex("""(?i)^nonton(?:\s+film)?\s+"""), "")
        .replace(seasonRegex, " ")
        .replace(episodeRegex, " ")
        .replace(parenthesizedYearSuffixRegex, " ")
        .replace(Regex("""(?i)\b(?:subtitle\s+indonesia|sub\s*indo)\b.*$"""), "")
        .replace(Regex("""[()\[\]]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', ':', '|')
        val withoutReleaseYear = releaseYear?.let { year ->
            cleaned.replace(Regex("""\s+\Q$year\E\s*$"""), "")
                .trim()
                .takeIf { it.isNotBlank() }
        } ?: cleaned
        return withoutReleaseYear.takeIf { it.isNotBlank() }
    }

    private fun titleKey(raw: String): String {
        val normalized = fallbackTitle(MovieMetadataParser.title(raw) ?: raw).orEmpty()
        return Normalizer.normalize(normalized, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "")
    }
}
