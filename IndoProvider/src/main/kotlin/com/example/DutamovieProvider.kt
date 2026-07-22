package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

private const val DUTAMOVIE_DISCOVERY_TIMEOUT_MS = 30_000L
private const val DUTAMOVIE_TAB_TIMEOUT_MS = 8_000L
private const val DUTAMOVIE_TAB_HTTP_TIMEOUT_SECONDS = 8L
private const val DUTAMOVIE_CANDIDATE_TIMEOUT_MS = 20_000L
private const val DUTAMOVIE_SESSION_TIMEOUT_MS = 120_000L
private const val MAX_DUTAMOVIE_INITIAL_PROBES = 2
private const val MAX_DUTAMOVIE_DISCOVERY_TABS = 16
private const val DUTAMOVIE_DISCOVERY_CONCURRENCY = 4

class DutamovieProvider : MainAPI() {
    override var mainUrl = "https://restaurantesabadell.com"
    private val legacyHosts = setOf("austincomputerworks.org", "wavereview.com")
    override var name = "Dutamovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "box-office/page/%d/" to "Box Office",
        "serial-tv/page/%d/" to "TV Series",
        "action/page/%d/" to "Action",
        "comedy/page/%d/" to "Comedy",
        "drama/page/%d/" to "Drama",
        "horror/page/%d/" to "Horror"
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
        val href = normalizePageUrl(selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster; addQuality(quality) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        return app.get("$mainUrl?s=$encodedQuery&post_type[]=post&post_type[]=tv").document
            .select("article.item-infinite, article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val requestUrl = normalizePageUrl(url)
            ?: throw ErrorLoadingException("Invalid Dutamovie URL")
        val fetch = app.get(requestUrl)
        val document = fetch.document
        val canonicalUrl = normalizePageUrl(fetch.url)
            ?: throw ErrorLoadingException("Dutamovie redirected to a foreign host")
        val rawTitle = document.selectFirst("h1.entry-title, h1[itemprop=name]")?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
        val title = MovieMetadataParser.title(rawTitle)
            ?: MovieMetadataParser.title(document.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: throw ErrorLoadingException("Dutamovie returned a page without a movie title")
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val episodeElements = document.select("div.vid-episodes a, div.gmr-listseries a")
        val tvType = if (canonicalUrl.contains("/tv/") || episodeElements.isNotEmpty()) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        val description = MovieMetadataParser.synopsis(document)
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors] a")?.map { it.text() }

        return if (tvType == TvType.TvSeries) {
            val episodes = episodeElements.mapNotNull { eps ->
                val href = normalizePageUrl(eps.attr("href")) ?: return@mapNotNull null
                val label = eps.attr("title").ifBlank { eps.text() }.trim()
                if (
                    label.contains("Lihat Semua Episode", ignoreCase = true) ||
                    label.contains("View All Episodes", ignoreCase = true) ||
                    href.substringBefore('?').substringBefore('#').trimEnd('/') ==
                    canonicalUrl.substringBefore('?').substringBefore('#').trimEnd('/')
                ) return@mapNotNull null
                DutamoviePlayerParser.newEpisode(this, href, label, poster)
            }.distinctBy { it.data }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; addActors(actors); addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; addActors(actors); addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val requestUrl = normalizePageUrl(data) ?: return false
        val fetch = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val document = fetch.document
        val canonicalUrl = normalizePageUrl(fetch.url) ?: return false
        val baseUrl = getBaseUrl(canonicalUrl)
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            inlineSourceParser = { html, _ -> InlineDataParser.playableInlineUrls(html) },
            candidateTimeoutMs = DUTAMOVIE_CANDIDATE_TIMEOUT_MS,
            sessionTimeoutMs = DUTAMOVIE_SESSION_TIMEOUT_MS
        )
        val initialSchedule = DutamoviePlayerParser.initialMediaSchedule(
            DutamoviePlayerParser.pageMediaUrls(document, canonicalUrl)
        )

        val discoveredCandidates = Collections.synchronizedList(
            mutableListOf<Pair<String, String>>()
        )
        val discoverySemaphore = Semaphore(DUTAMOVIE_DISCOVERY_CONCURRENCY)
        // Discovery only performs network/HTML parsing, so it can safely overlap
        // the bounded serial resolver probe. The resolver itself remains serial.
        supervisorScope {
            val discoveryJob = launch {
                withTimeoutOrNull(DUTAMOVIE_DISCOVERY_TIMEOUT_MS) {
                    supervisorScope {
                if (id.isNullOrEmpty()) {
                    val playerPages = document.select("ul.muvipro-player-tabs li a")
                        .mapNotNull { normalizePageUrl(it.attr("href")) }
                        .let(DutamoviePlayerParser::orderPlayerPages)
                        .take(MAX_DUTAMOVIE_DISCOVERY_TABS)
                    playerPages.forEach { playerPage ->
                        launch {
                            discoverySemaphore.withPermit {
                                val response = try {
                                    withTimeoutOrNull(DUTAMOVIE_TAB_TIMEOUT_MS) {
                                        app.get(
                                            playerPage,
                                            referer = canonicalUrl,
                                            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                            timeout = DUTAMOVIE_TAB_HTTP_TIMEOUT_SECONDS
                                        )
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    null
                                } ?: return@withPermit
                                val responsePageUrl = normalizePageUrl(response.url)
                                    ?: return@withPermit
                                val candidates = DutamoviePlayerParser.pageMediaUrls(
                                    response.document,
                                    responsePageUrl
                                ).map { source -> source to responsePageUrl }
                                discoveredCandidates.addAll(candidates)
                            }
                        }
                    }
                } else {
                    document.select("div.tab-content-ajax")
                        .take(MAX_DUTAMOVIE_DISCOVERY_TABS)
                        .forEach { element ->
                            launch {
                                discoverySemaphore.withPermit {
                                    val response = try {
                                        withTimeoutOrNull(DUTAMOVIE_TAB_TIMEOUT_MS) {
                                            app.post(
                                                "$baseUrl/wp-admin/admin-ajax.php",
                                                data = mapOf(
                                                    "action" to "muvipro_player_content",
                                                    "tab" to element.attr("id"),
                                                    "post_id" to id.orEmpty()
                                                ),
                                                referer = canonicalUrl,
                                                headers = mapOf(
                                                    "X-Requested-With" to "XMLHttpRequest"
                                                ),
                                                timeout = DUTAMOVIE_TAB_HTTP_TIMEOUT_SECONDS
                                            )
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        null
                                    } ?: return@withPermit
                                    val responsePageUrl = normalizePageUrl(response.url)
                                        ?: return@withPermit
                                    val candidates = DutamoviePlayerParser.ajaxMediaCandidates(
                                        response.document,
                                        responsePageUrl,
                                        canonicalUrl
                                    )
                                    discoveredCandidates.addAll(candidates)
                                }
                            }
                        }
                }
                    }
                }
            }
            DutamoviePlayerParser.resolveEagerCandidates(
                initialSchedule.eager,
                canContinue = { resolver.canContinue }
            ) { server ->
                resolver.resolveInline(server, canonicalUrl)
            }
            if (resolver.loaded) discoveryJob.cancel()
        }
        if (resolver.loaded) return true
        val discoveredSnapshot = synchronized(discoveredCandidates) {
            discoveredCandidates.toList()
        }
        val postDiscoveryCandidates = DutamoviePlayerParser.postDiscoverySchedule(
            deferredInitial = initialSchedule.deferred.map { source -> source to canonicalUrl },
            discovered = discoveredSnapshot
        )
        for ((server, referer) in postDiscoveryCandidates) {
            if (!resolver.canContinue || resolver.resolveInline(server, referer)) break
        }
        if (!resolver.loaded) {
            for (download in ProviderHtmlParser.downloadCandidateUrls(document, canonicalUrl)) {
                if (!resolver.canContinue || resolver.resolve(download, canonicalUrl)) break
            }
        }
        return resolver.loaded
    }

    private fun Element.getImageAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src"); hasAttr("data-lazy-src") -> attr("abs:data-lazy-src"); else -> attr("abs:src")
    }
    private fun String?.fixImageQuality(): String? { if (this == null) return null; val r = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this; return replace(r, "") }
    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }
    private fun normalizePageUrl(raw: String?): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, legacyHosts)
}

internal object DutamoviePlayerParser {
    data class InitialMediaSchedule(
        val eager: List<String>,
        val deferred: List<String>
    )

    data class EpisodeNumbers(
        val season: Int?,
        val episode: Int?
    )

    fun episodeNumbers(url: String, label: String): EpisodeNumbers {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val season = Regex(
            "(?i)(?:\\bseason|\\bs)[-\\s._]*(\\d+)\\b"
        ).find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)(?:^|[-/_.])(?:season|s)[-_.]*(\\d+)(?=$|[-/_.])")
                .find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = Regex(
            "(?i)(?:\\bepisode|\\beps?|\\bep)[-\\s._]*(\\d+)\\b"
        ).find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)\\be[-\\s._]*(\\d+)\\b")
                .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)(?:^|[-/_.])(?:episode|eps?|ep)[-_.]*(\\d+)(?=$|[-/_.])")
                .find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return EpisodeNumbers(season, episode)
    }

    fun newEpisode(api: MainAPI, href: String, label: String, poster: String?) =
        episodeNumbers(href, label).let { numbers ->
            val episode = numbers.episode ?: return@let null
            api.newEpisode(href) {
                name = "Episode $episode"
                season = numbers.season
                this.episode = episode
                posterUrl = poster
            }
        }

    fun detailMediaUrls(document: Document, detailUrl: String): List<String> {
        return pageMediaUrls(document, detailUrl)
    }

    fun pageMediaUrls(document: Document, pageUrl: String): List<String> {
        return (
            ProviderHtmlParser.mediaSources(document) +
                InlineDataParser.playableInlineUrls(document.outerHtml())
            ).mapNotNull { ProviderHtmlParser.absoluteUrl(it, pageUrl) }
            .distinct()
    }

    /**
     * AJAX response URLs are only resolution bases. Efek and other player
     * hosts validate the browser navigation chain against the movie page, so
     * retain the canonical detail page as the request Referer.
     */
    fun ajaxMediaCandidates(
        document: Document,
        responseUrl: String,
        canonicalDetailUrl: String
    ): List<Pair<String, String>> = pageMediaUrls(document, responseUrl)
        .map { source -> source to canonicalDetailUrl }

    fun orderPlayerPages(urls: List<String>): List<String> {
        // Player numbers are only tab identifiers. Current pages assign the
        // same host to different numbers per title, so preserve document order
        // until the iframe host is known.
        return urls.distinct()
    }

    fun orderMediaUrls(urls: List<String>): List<String> =
        urls.distinct().withIndex()
            .sortedWith(compareBy<IndexedValue<String>> { mediaPriority(it.value) }.thenBy { it.index })
            .map { it.value }

    fun initialMediaSchedule(urls: List<String>): InitialMediaSchedule {
        val ordered = orderMediaUrls(urls)
        return InitialMediaSchedule(
            eager = ordered.take(MAX_DUTAMOVIE_INITIAL_PROBES),
            deferred = ordered.drop(MAX_DUTAMOVIE_INITIAL_PROBES)
        )
    }

    /**
     * Each resolver call owns its candidate timeout. Do not wrap this loop in
     * a shared timeout: cancelling it midway leaves the resolver's visited key
     * consumed even though the candidate did not finish its bounded attempt.
     */
    suspend fun resolveEagerCandidates(
        eager: List<String>,
        canContinue: () -> Boolean,
        resolve: suspend (String) -> Boolean
    ): Boolean {
        for (candidate in eager) {
            if (!canContinue()) return false
            if (resolve(candidate)) return true
        }
        return false
    }

    /**
     * Discovery and detail-page candidates have independent value. Keep the
     * already-ranked initial lane stable, rank discovery by known reliability,
     * then alternate lanes so discovered tabs cannot consume the session before
     * initial candidate #3.
     */
    fun postDiscoverySchedule(
        deferredInitial: List<Pair<String, String>>,
        discovered: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        // initialMediaSchedule has already ranked this lane. Preserve that
        // exact order so candidate #3 cannot be moved behind a later initial
        // source merely because discovery completed in between.
        val initialLane = deferredInitial.distinct()
        val discoveredLane = discovered.distinct().withIndex()
            .sortedWith(
                compareBy<IndexedValue<Pair<String, String>>> {
                    mediaPriority(it.value.first)
                }.thenBy { it.index }
            )
            .map { it.value }

        val scheduled = mutableListOf<Pair<String, String>>()
        var initialIndex = 0
        var discoveredIndex = 0
        while (initialIndex < initialLane.size || discoveredIndex < discoveredLane.size) {
            val initial = initialLane.getOrNull(initialIndex)
            val found = discoveredLane.getOrNull(discoveredIndex)
            val initialFirst = when {
                initial == null -> false
                found == null -> true
                else -> mediaPriority(initial.first) <= mediaPriority(found.first)
            }
            if (initialFirst) {
                scheduled += initial!!
                initialIndex++
                discoveredLane.getOrNull(discoveredIndex++)?.let(scheduled::add)
            } else {
                scheduled += found!!
                discoveredIndex++
                initialLane.getOrNull(initialIndex++)?.let(scheduled::add)
            }
        }
        return scheduled.distinct()
    }

    fun mediaPriority(url: String): Int {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host == "morencius.com" || host.endsWith(".morencius.com") -> 0
            host == "abyssplayer.com" || host.endsWith(".abyssplayer.com") ||
                host == "abyss.to" || host.endsWith(".abyss.to") -> 1
            Embed4mePlayerParser.supportsHost(host) -> 2
            host == "embedpyrox.xyz" || host.endsWith(".embedpyrox.xyz") -> 3
            host.contains("voe") || host.contains("lulu") -> 4
            host.contains("hgcloud") || host.contains("streamwish") -> 5
            else -> 6
        }
    }
}

internal object MorenciusPlayerParser {
    private data class Candidate(
        val label: String,
        val url: String,
        val index: Int
    )

    fun supports(host: String): Boolean =
        host == "morencius.com" || host.endsWith(".morencius.com")

    fun mediaUrls(html: String, playerUrl: String): List<String> {
        val scripts = mutableListOf(html)
        Jsoup.parse(html, playerUrl).select("script").forEach { script ->
            val raw = script.data()
            if (raw.isBlank()) return@forEach
            scripts += raw
            if (raw.contains("eval(function(p,a,c,k,e")) {
                runCatching { getAndUnpack(raw) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(scripts::add)
            }
        }

        val candidates = mutableListOf<Candidate>()
        scripts.forEach { script ->
            val normalized = script
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
            Regex("""(?i)["']?(hls\d+)["']?\s*[:=]\s*["']([^"']+)["']""")
                .findAll(normalized)
                .forEach { match ->
                    candidates += Candidate(
                        label = match.groupValues[1],
                        url = match.groupValues[2],
                        index = match.range.first
                    )
                }
            Regex("""(?i)["']?file["']?\s*:\s*["']([^"']+)["']""")
                .findAll(normalized)
                .forEach { match ->
                    candidates += Candidate(
                        label = "file",
                        url = match.groupValues[1],
                        index = match.range.first
                    )
                }
        }

        return candidates
            .filter { candidate ->
                candidate.url.contains(".m3u8", ignoreCase = true) ||
                    candidate.url.contains("master.txt", ignoreCase = true)
            }
            .sortedWith(
                compareBy<Candidate> { candidate ->
                    when (candidate.label.lowercase()) {
                        "hls4" -> 0
                        "hls3" -> 1
                        "hls2" -> 2
                        else -> 3
                    }
                }.thenBy { it.index }
            )
            .mapNotNull { ProviderHtmlParser.absoluteUrl(it.url, playerUrl) }
            .distinct()
    }
}
