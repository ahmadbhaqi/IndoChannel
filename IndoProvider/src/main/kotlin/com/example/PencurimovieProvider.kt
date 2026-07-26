package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup

class PencurimovieProvider : MainAPI() {
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
        val fetch = getProviderPage(
            "$mainUrl/${request.data}/page/${page.coerceAtLeast(1)}"
        ) ?: return newHomePageResponse(request.name, emptyList())
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return newHomePageResponse(request.name, emptyList())
        val document = Jsoup.parse(fetch.body, fetch.url)
        return newHomePageResponse(request.name, document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        })
    }

    override suspend fun search(query: String): List<SearchResponse> {
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
        val requestUrl = providerUrl(url) ?: return null
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
        val requestUrl = providerUrl(data) ?: return false
        val fetch = getProviderPage(requestUrl) ?: return false
        val pageUrl = fetch.url
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return false
        val document = Jsoup.parse(fetch.body, pageUrl)
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            candidateTimeoutMs = 30_000L,
            genericExtractorTimeoutMs = 25_000L,
            sessionTimeoutMs = 100_000L
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

        for (candidate in candidates) {
            if (resolver.loaded || !resolver.canContinue) break
            resolver.resolve(candidate, pageUrl)
        }
        return resolver.loaded
    }

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
}

internal object PencurimovieParser {
    fun extractorCompatibleUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf(::isSafeRemoteHttpUrl) ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        if (host != "dsvplay.com") return value

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
