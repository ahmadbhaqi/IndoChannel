package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.NiceResponse
import java.net.URI
import java.net.URLEncoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NomatProvider : MainAPI() {
    override var mainUrl = "https://nomat.site"
    override var name = "Nomat"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "slug/film-terbaru/%d/" to "Film Terbaru",
        "slug/film-box-office/%d/" to "Box Office",
        "slug/film-serial-baru-terpopuler/%d/" to "Serial TV",
        "category/genre/action/%d/" to "Action",
        "slug/film-movie-anime/%d/" to "Animasi",
        "category/genre/horror/%d/" to "Horor",
        "category/genre/romance/%d/" to "Romansa",
        "category/country/indonesia/%d/" to "Indonesia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page.coerceAtLeast(1))
        val document = app.get("$mainUrl/$path", timeout = PROVIDER_HTTP_TIMEOUT_SECONDS).document
        return newHomePageResponse(
            request.name,
            document.select("a:has(.item-content)").mapNotNull { it.toSearchResult() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = NomatParser.searchPathSegment(query)
        return app.get(
            "$mainUrl/search/$encoded/",
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).document.select("a:has(.item-content)").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = providerUrl(attr("href")) ?: return null
        val title = MovieMetadataParser.title(selectFirst(".title")?.text()) ?: return null
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
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val canonicalUrl = providerUrl(fetch.url) ?: return null
        val document = fetch.document
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
            val playUrl = document.selectFirst(
                "div.video-wrapper a[href*='nontonhemat.link'], div.video-wrapper a[href*='/play/']"
            )?.attr("href")?.let { ProviderHtmlParser.absoluteUrl(it, canonicalUrl) }
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, playUrl ?: canonicalUrl) {
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
        val document = response.document
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val candidates = (
            NomatParser.serverUrls(document) +
                ProviderHtmlParser.mediaSources(document) +
                ProviderHtmlParser.downloadCandidateUrls(document, responseUrl)
            ).mapNotNull { ProviderHtmlParser.absoluteUrl(it, responseUrl) }.distinct().take(48)

        candidates.forEach { candidate ->
            if (resolver.canContinue) resolver.resolve(candidate, responseUrl)
        }
        return resolver.loaded
    }

    private suspend fun fetchPlaybackPage(initialUrl: String): NiceResponse? {
        var currentUrl = initialUrl
        var redirectsRemaining = 5
        while (true) {
            val response = app.get(
                currentUrl,
                referer = mainUrl,
                allowRedirects = false,
                timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
            )
            val responseUrl = NomatParser.playbackPageUrl(response.url, mainUrl) ?: return null
            if (response.code !in 300..399) return response
            if (redirectsRemaining-- <= 0) return null
            currentUrl = NomatParser.redirectTarget(
                response.headers["Location"],
                responseUrl,
                mainUrl
            ) ?: return null
        }
    }

    private fun providerUrl(raw: String?): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl)
}

internal object NomatParser {
    private const val MAX_SERVER_VALUE_SIZE = 16_384
    private val playbackHosts = setOf("nontonhemat.link")

    fun searchPathSegment(query: String): String =
        URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")

    fun playbackPageUrl(raw: String?, mainUrl: String): String? {
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl)?.let { return it }
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

    fun poster(document: Document): String? {
        val style = document.selectFirst("div.video-poster")?.attr("style").orEmpty()
        return Regex("""(?i)url\((?:['"])?([^'")]+)""")
            .find(style)?.groupValues?.getOrNull(1)
            ?: ProviderHtmlParser.firstImageSource(document, "div.video-poster img")
    }
}
