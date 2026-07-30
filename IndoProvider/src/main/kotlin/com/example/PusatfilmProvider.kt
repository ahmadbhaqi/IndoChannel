package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

internal suspend fun resolvePusatfilmStrategies(
    currentPlayers: List<String>,
    currentResolver: suspend (String) -> Boolean,
    genericResolver: suspend () -> Boolean
): Boolean {
    val strategies = mutableListOf<suspend () -> Boolean>()
    if (currentPlayers.isNotEmpty()) {
        strategies += {
            resolveByPriorityTiers(
                tiers = listOf(currentPlayers),
                maxConcurrency = 3,
                attempt = currentResolver
            )
        }
    }
    strategies += genericResolver
    return resolveByPriorityTiers(
        tiers = listOf(strategies),
        maxConcurrency = 2
    ) { strategy ->
        strategy()
    }
}

class PusatfilmProvider : MainAPI() {
    override var mainUrl = "https://v4.pusatfilm21info.com"
    private val legacyHosts = setOf("v3.pusatfilm21info.com")
    override var name = "Pusatfilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    private val playerHttp by lazy(LazyThreadSafetyMode.NONE) {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "film-terbaru/page/%d/" to "Terbaru",
        "trending/page/%d/" to "Trending",
        "series-terbaru/page/%d/" to "TV Series",
        "genre/action/page/%d/" to "Action",
        "genre/animation/page/%d/" to "Animation",
        "genre/comedy/page/%d/" to "Comedy",
        "genre/drama/page/%d/" to "Drama",
        "genre/horror/page/%d/" to "Horror",
        "genre/romance/page/%d/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article.item, article.item-infinite").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = MovieMetadataParser.title(
            selectFirst("h2.entry-title > a")?.text()
        ) ?: return null
        val href = normalizePageUrl(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")

        return if (quality.isEmpty()) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val document = app.get(
            "$mainUrl/?s=$encodedQuery&post_type[]=post&post_type[]=tv",
            timeout = 50L
        ).document
        return document.select("article.item, article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val requestUrl = normalizePageUrl(url)
            ?: throw ErrorLoadingException("Invalid Pusatfilm URL")
        val fetch = app.get(requestUrl)
        val document = fetch.document
        val canonicalUrl = normalizePageUrl(fetch.url) ?: requestUrl

        val rawTitle = document.selectFirst("h1.entry-title, h1[itemprop=name]")?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
        val title = MovieMetadataParser.title(rawTitle)
            ?: MovieMetadataParser.title(document.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: throw ErrorLoadingException("Pusatfilm returned a page without a movie title")
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr()?.fixImageQuality())
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val episodeElements = document.select("div.vid-episodes a, div.gmr-listseries a")
        val episodes = episodeElements
            .mapNotNull { eps ->
                val href = normalizePageUrl(eps.attr("href")) ?: return@mapNotNull null
                val rawTitle = eps.attr("title").takeIf { it.isNotBlank() } ?: eps.text()
                val label = rawTitle.replaceFirst(Regex("(?i)Permalink ke\\s*"), "").trim()
                DutamoviePlayerParser.newEpisode(this, href, label, poster)
            }
            .distinctBy { it.data }
        val tvType = RotatingMovieDetailClassifier.classify(canonicalUrl, episodes.size)
            ?: throw ErrorLoadingException("Tautan episode belum tersedia")
        val description = MovieMetadataParser.synopsis(document)
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull()

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                this.duration = duration ?: 0
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags

                this.duration = duration ?: 0
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
        val requestUrl = normalizePageUrl(data) ?: return false
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val document = fetch.document
        val canonicalUrl = normalizePageUrl(fetch.url) ?: requestUrl
        val iframes = document
            .select("div.gmr-embed-responsive iframe, div.movieplay iframe, iframe")
            .mapNotNull { ProviderHtmlParser.firstIframeSource(it) }

        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val currentPlayback = PusatfilmCurrentPlayback(
            pageFetcher = { url, referer ->
                playerHttp.get(
                    url = url,
                    normalizer = ProviderUrlNormalizer(PusatfilmPlayerPagePolicy::normalize),
                    referer = referer,
                    maxBodyBytes = PUSATFILM_PLAYER_PAGE_LIMIT_BYTES,
                    timeoutSeconds = PUSATFILM_PLAYER_TIMEOUT_SECONDS
                ).body
            },
            mediaResolver = resolver::resolve
        )
        val currentPlayers = iframes.filter { iframe ->
            PusatfilmPlayerPagePolicy.normalize(iframe)
                ?.let(PusatfilmPlayerPagePolicy::isKotakPage) == true
        }
        resolvePusatfilmStrategies(
            currentPlayers = currentPlayers,
            currentResolver = { iframe ->
                currentPlayback.resolve(iframe, canonicalUrl)
            },
            genericResolver = {
                resolver.resolveFirstVerified(
                    iframes.map { iframe ->
                        PlayerResolutionCandidate(iframe, canonicalUrl)
                    }
                )
            }
        )
        return resolver.loaded
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun normalizePageUrl(raw: String?): String? {
        return ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, legacyHosts)
    }
}

/**
 * Current TurboVIP pages expose their concrete HLS URL on the actual player
 * element. Keeping this selector exact avoids treating unrelated analytics or
 * advertising URLs as media.
 */
internal object PusatfilmCurrentPlayerParser {
    private const val MAX_HTML_SIZE = 2_000_000

    fun directMediaUrl(html: String): String? {
        if (html.length > MAX_HTML_SIZE) return null
        val candidate = runCatching {
            Jsoup.parse(html)
                .selectFirst("#video_player[data-hash]")
                ?.attr("data-hash")
                ?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val path = runCatching { URI(candidate).path.orEmpty().lowercase() }
            .getOrDefault("")
        return candidate.takeIf {
            path.endsWith(".m3u8") && isSafeRemoteHttpUrl(it)
        }
    }
}

internal object PusatfilmPlayerPagePolicy {
    private val allowedHosts = setOf(
        "kotakajaib.me",
        "emturbovid.com",
        "turbovidhls.com",
        "turboviplay.com"
    )

    fun normalize(candidate: String): String? {
        if (!isSafeRemoteHttpUrl(candidate)) return null
        return runCatching {
            val uri = URI(candidate)
            val host = uri.host?.lowercase() ?: return@runCatching null
            if (uri.scheme?.lowercase() != "https" ||
                uri.userInfo != null ||
                uri.port !in setOf(-1, 443) ||
                allowedHosts.none { allowed -> host == allowed || host.endsWith(".$allowed") }
            ) {
                return@runCatching null
            }
            uri.toASCIIString()
        }.getOrNull()
    }

    fun isKotakPage(candidate: String): Boolean = (
        normalizedHost(candidate)
            ?.let { it == "kotakajaib.me" || it.endsWith(".kotakajaib.me") }
        ) == true

    fun isTurboPage(candidate: String): Boolean =
        normalizedHost(candidate)?.let { host ->
            host == "emturbovid.com" ||
                host.endsWith(".emturbovid.com") ||
                host == "turbovidhls.com" ||
                host.endsWith(".turbovidhls.com") ||
                host == "turboviplay.com" ||
                host.endsWith(".turboviplay.com")
        } == true

    private fun normalizedHost(candidate: String): String? =
        normalize(candidate)?.let { URI(it).host?.lowercase() }
}

internal class PusatfilmCurrentPlayback(
    private val pageFetcher: suspend (url: String, referer: String) -> String,
    private val mediaResolver: suspend (url: String, referer: String) -> Boolean
) {
    suspend fun resolve(playerUrl: String, detailUrl: String): Boolean {
        val normalizedPlayer = PusatfilmPlayerPagePolicy.normalize(playerUrl)
            ?.takeIf(PusatfilmPlayerPagePolicy::isKotakPage)
            ?: return false
        val playerHtml = fetch(normalizedPlayer, detailUrl) ?: return false
        val mirrors = KotakDataFrameParser.urls(playerHtml).mapNotNull { mirror ->
            PusatfilmPlayerPagePolicy.normalize(mirror)
                ?.takeIf(PusatfilmPlayerPagePolicy::isTurboPage)
        }
        return resolveByPriorityTiers(
            tiers = listOf(mirrors),
            maxConcurrency = 3
        ) { normalizedMirror ->
            val mirrorHtml =
                fetch(normalizedMirror, normalizedPlayer) ?: return@resolveByPriorityTiers false
            val mediaUrl = PusatfilmCurrentPlayerParser.directMediaUrl(mirrorHtml)
                ?: return@resolveByPriorityTiers false
            mediaResolver(mediaUrl, normalizedMirror)
        }
    }

    private suspend fun fetch(url: String, referer: String): String? = try {
        pageFetcher(url, referer)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

private const val PUSATFILM_PLAYER_PAGE_LIMIT_BYTES = 1_000_000
private const val PUSATFILM_PLAYER_TIMEOUT_SECONDS = 20L
