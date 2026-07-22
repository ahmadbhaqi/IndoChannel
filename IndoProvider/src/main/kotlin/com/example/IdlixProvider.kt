package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

private const val IDLIX_API_TIMEOUT_SECONDS = 25L
private const val IDLIX_MAX_JSON_BYTES = 2_000_000
private const val IDLIX_MAX_HTML_BYTES = 2_000_000
private const val IDLIX_MAX_MANIFEST_BYTES = 500_000
private const val IDLIX_MAX_GATE_WAIT_MS = 20_000L
private const val IDLIX_SEASON_CONCURRENCY = 4
private const val IDLIX_MAX_STREAMS = 6
private const val IDLIX_SEARCH_PAGE_SIZE = 30
private const val IDLIX_MAX_SEARCH_PAGES = 20
private const val IDLIX_SEARCH_BUDGET_MS = 30_000L
private const val IDLIX_JSON_MEDIA_TYPE = "application/json;charset=UTF-8"
private const val IDLIX_PLAYBACK_PATH = "/__idlix_playback__/"
private const val IDLIX_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

internal object IdlixSearchPager {
    data class Page<T>(
        val items: List<T>,
        val total: Int,
        val cookies: Map<String, String>,
        val receivedCount: Int = items.size
    )

    suspend fun <T, K> collect(
        pageSize: Int,
        maxPages: Int,
        budgetMs: Long,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        key: (T) -> K,
        fetch: suspend (page: Int, cookies: Map<String, String>, timeoutMs: Long) -> Page<T>?
    ): List<T> {
        if (pageSize <= 0 || maxPages <= 0 || budgetMs <= 0L) return emptyList()
        val startedAt = nowMs()
        val results = mutableListOf<T>()
        val seenKeys = LinkedHashSet<K>()
        var cookies = emptyMap<String, String>()
        var page = 1
        var loadedCount = 0

        while (page <= maxPages) {
            val elapsedMs = (nowMs() - startedAt).coerceAtLeast(0L)
            val remainingMs = budgetMs - elapsedMs
            if (remainingMs <= 0L) break
            val response = withTimeoutOrNull(remainingMs) {
                fetch(page, cookies, remainingMs)
            } ?: break
            cookies = response.cookies
            val previousUniqueCount = seenKeys.size
            response.items.forEach { item ->
                if (seenKeys.add(key(item))) results += item
            }
            loadedCount += response.receivedCount
            if (seenKeys.size == previousUniqueCount ||
                !IdlixParser.shouldLoadNextSearchPage(
                    total = response.total,
                    loadedCount = loadedCount,
                    currentPageCount = response.receivedCount,
                    currentPage = page,
                    pageSize = pageSize,
                    maxPages = maxPages
                )
            ) break
            page++
        }
        return results
    }
}

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "movie" to "Film Terbaru",
        "series" to "Series Terbaru"
    )

    private val mapper = jacksonObjectMapper()
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val apiUrl: String get() = "$mainUrl/api"

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor =
        IdlixPlaybackInterceptor(extractorLink.url, cloudflareKiller)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = request.data.takeIf { it == "movie" || it == "series" } ?: "movie"
        val endpoint = if (type == "movie") "movies" else "series"
        val referer = "$mainUrl/$type"
        val root = apiGetJson("/$endpoint?page=${page.coerceAtLeast(1)}&limit=24", referer)?.node
        val results = root?.path("data")
            ?.takeIf(JsonNode::isArray)
            ?.mapNotNull { IdlixParser.searchResult(this@IdlixProvider, it, type) }
            .orEmpty()
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val encoded = URLEncoder.encode(clean, Charsets.UTF_8.name())
        val referer = "$mainUrl/search?q=$encoded"
        return IdlixSearchPager.collect<SearchResponse, String>(
            pageSize = IDLIX_SEARCH_PAGE_SIZE,
            maxPages = IDLIX_MAX_SEARCH_PAGES,
            budgetMs = IDLIX_SEARCH_BUDGET_MS,
            key = SearchResponse::url
        ) { page, cookies, timeoutMs ->
            val response = apiGetJson(
                "/search?q=$encoded&page=$page&limit=$IDLIX_SEARCH_PAGE_SIZE",
                referer,
                cookies,
                timeoutMs = timeoutMs
            ) ?: return@collect null
            val pageNodes = response.node.path("results")
                .takeIf(JsonNode::isArray)
                ?.toList()
                .orEmpty()
            IdlixSearchPager.Page(
                items = pageNodes.mapNotNull {
                    IdlixParser.searchResult(this@IdlixProvider, it)
                },
                total = response.node.path("total").asInt(-1),
                cookies = response.cookies,
                receivedCount = pageNodes.size
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val page = IdlixParser.contentPage(url, mainUrl) ?: return null
        val endpoint = if (page.type == "movie") "movies" else "series"
        val detail = apiGetJson("/$endpoint/${page.slug}", page.url) ?: return null
        val root = detail.node
        val title = root.textOrNull("title") ?: return null
        val contentId = root.textOrNull("id")?.takeIf(IdlixParser::isUuid) ?: return null
        val year = (root.textOrNull("releaseDate") ?: root.textOrNull("firstAirDate"))
            ?.take(4)
            ?.toIntOrNull()
        val poster = IdlixParser.tmdbImage(root.textOrNull("posterPath"), "w500")
        val plot = root.textOrNull("overview")
        val tags = root.path("genres")
            .takeIf(JsonNode::isArray)
            ?.mapNotNull { it.textOrNull("name") }
            .orEmpty()
        val actors = root.path("cast")
            .takeIf(JsonNode::isArray)
            ?.mapNotNull { cast ->
                cast.textOrNull("name")?.let { actorName ->
                    Actor(
                        actorName,
                        IdlixParser.tmdbImage(cast.textOrNull("profilePath"), "w342")
                    )
                }
            }
            .orEmpty()
        val trailer = root.textOrNull("trailerUrl")

        if (page.type == "movie") {
            val playback = IdlixParser.encodePlayback("movie", contentId, page.url)
            return newMovieLoadResponse(title, page.url, TvType.Movie, playback) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                duration = root.path("runtime").asInt(0)
                addActors(actors)
                addTrailer(trailer)
            }
        }

        val episodes = loadSeriesEpisodes(root, page, detail.cookies)
        return newTvSeriesLoadResponse(title, page.url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private suspend fun loadSeriesEpisodes(
        root: JsonNode,
        page: IdlixParser.ContentPage,
        cookies: Map<String, String>
    ) =
        coroutineScope {
            val seasons = root.path("seasons")
                .takeIf(JsonNode::isArray)
                ?.filter { season -> season.path("isPublished").asBoolean(true) }
                .orEmpty()
            val defaultSeason = root.path("defaultSeason")
                .takeUnless(JsonNode::isMissingNode)
                ?.takeUnless(JsonNode::isNull)
            val semaphore = Semaphore(IDLIX_SEASON_CONCURRENCY)
            seasons.map { season ->
                async {
                    semaphore.withPermit {
                        val number = season.path("seasonNumber").asInt(-1).takeIf { it >= 0 }
                            ?: return@withPermit emptyList()
                        // The embedded defaultSeason may be only a preview. Prefer
                        // the dedicated endpoint for every season and keep the
                        // embedded payload solely as a network-failure fallback.
                        val endpointSeason = apiGetJson(
                                "/series/${page.slug}/season/$number",
                                page.url,
                                cookies
                            )?.node
                        val seasonNode = IdlixParser.selectSeasonPayload(
                            endpointSeason,
                            defaultSeason,
                            number
                        )
                            ?: return@withPermit emptyList()
                        IdlixParser.episodes(this@IdlixProvider, seasonNode, number, page.url)
                    }
                }
            }.awaitAll().flatten().distinctBy { it.data }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = IdlixParser.decodePlayback(data, mainUrl) ?: return false
        val preflightCookies = preflightPage(request.pageUrl) ?: return false
        val playInfo = apiGetJson(
            "/watch/play-info/${request.contentType}/${request.contentId}",
            request.pageUrl,
            preflightCookies,
            retryWithPreflight = false
        ) ?: return false
        val direct = IdlixParser.directPlayback(playInfo.node)
        val session = if (direct == null) {
            claimPlayback(playInfo, request.pageUrl) ?: return false
        } else {
            null
        }
        val redeemed = direct
            ?: redeemPlayback(session ?: return false, request.pageUrl)
            ?: return false
        val maxHeight = (session?.node ?: playInfo.node)
            .path("maxHeight")
            .asInt(Qualities.Unknown.value)
        val masterUrl = redeemed.textOrNull("url")
            ?.takeIf(IdlixParser::isTrustedMasterUrl)
            ?: return false
        val manifestResponse = try {
            app.get(
                masterUrl,
                referer = request.pageUrl,
                headers = browserHeaders(request.pageUrl),
                timeout = IDLIX_API_TIMEOUT_SECONDS,
                interceptor = cloudflareKiller
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        if (manifestResponse.code !in 200..299 || manifestResponse.text.length > IDLIX_MAX_MANIFEST_BYTES) {
            return false
        }
        val verifiedMasterUrl = manifestResponse.url
            .takeIf(IdlixParser::isTrustedMasterUrl)
            ?: return false
        val manifest = IdlixParser.masterManifest(
            manifestResponse.text,
            verifiedMasterUrl,
            maxHeight
        ) ?: return false

        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            candidateTimeoutMs = 25_000L,
            sessionTimeoutMs = 75_000L,
            // The same master was just fetched, bounded and parsed above. Do not
            // spend another signed request before handing it to the player.
            mediaLinkProbe = { it }
        )
        val mediaHeaders = mapOf(
            "User-Agent" to IDLIX_USER_AGENT,
            "Referer" to request.pageUrl,
            "Origin" to mainUrl
        )
        (manifest.subtitles + IdlixParser.redeemedSubtitles(redeemed, verifiedMasterUrl))
            .distinctBy(IdlixParser.SubtitleTrack::url)
            .take(32)
            .forEach { track ->
                subtitleCallback(newIdlixSubtitleFile(track, mediaHeaders))
            }
        resolver.emitResolved(
            newIdlixMasterLink(
                source = name,
                masterUrl = verifiedMasterUrl,
                pageUrl = request.pageUrl,
                quality = manifest.streams.maxOfOrNull { it.height } ?: maxHeight,
                headers = mediaHeaders
            )
        )
        return resolver.loaded
    }

    private suspend fun claimPlayback(
        playInfo: ApiResult,
        referer: String
    ): ApiResult? {
        if (playInfo.node.textOrNull("kind") == "pentos") return playInfo
        if (playInfo.node.textOrNull("kind") != "gate") return null
        val token = playInfo.node.textOrNull("gateToken")
            ?.takeIf { it.length in 32..4096 }
            ?: return null
        val serverNow = playInfo.node.path("serverNow").asLong(0L)
        val unlockAt = playInfo.node.path("unlockAt").asLong(0L)
        val waitMs = IdlixParser.gateWaitMs(serverNow, unlockAt) ?: return null
        return IdlixParser.pollGateClaim<ApiResult>(
            initialWaitMs = waitMs,
            pendingBudgetMs = IDLIX_MAX_GATE_WAIT_MS,
            sleep = { wait -> if (wait > 0L) delay(wait) },
            request = { previous, requestBudgetMs ->
                withTimeoutOrNull(requestBudgetMs) {
                    apiPostJson(
                        "/watch/session/claim",
                        mapper.createObjectNode().put("gateToken", token),
                        referer,
                        previous?.cookies ?: playInfo.cookies,
                        timeoutSeconds = ((requestBudgetMs + 999L) / 1_000L)
                            .coerceIn(1L, IDLIX_API_TIMEOUT_SECONDS)
                    )
                }
            },
            kind = { result -> result.node.textOrNull("kind") },
            remainingMs = { result -> result.node.path("remainingMs").asLong(0L) }
        )
    }

    private suspend fun redeemPlayback(session: ApiResult, referer: String): JsonNode? {
        val claim = session.node.textOrNull("claim")
            ?.takeIf { it.length in 8..4096 }
            ?: return null
        val redeemUrl = session.node.textOrNull("redeemUrl")
            ?.takeIf(IdlixParser::isTrustedRedeemUrl)
            ?: return null
        val body = mapper.createObjectNode()
            .put("claim", claim)
            .put("mode", "browser")
            .toString()
        val response = try {
            app.post(
                redeemUrl,
                requestBody = body.toRequestBody(IDLIX_JSON_MEDIA_TYPE.toMediaType()),
                referer = referer,
                headers = browserHeaders(referer) +
                    mapOf("Content-Type" to IDLIX_JSON_MEDIA_TYPE),
                timeout = IDLIX_API_TIMEOUT_SECONDS,
                interceptor = cloudflareKiller
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val text = response.text
        if (
            response.code !in 200..299 ||
            text.length !in 2..IDLIX_MAX_JSON_BYTES ||
            !text.trimStart().startsWith("{")
        ) return null
        val node = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        return node.takeIf {
            it.textOrNull("code") == "ok" &&
                it.textOrNull("mode") == "browser" &&
                it.textOrNull("url")?.let(IdlixParser::isTrustedMasterUrl) == true
        }
    }

    private suspend fun apiGetJson(
        path: String,
        referer: String,
        cookies: Map<String, String> = emptyMap(),
        retryWithPreflight: Boolean = true,
        timeoutMs: Long = IDLIX_API_TIMEOUT_SECONDS * 1_000L
    ): ApiResult? {
        if (timeoutMs <= 0L) return null
        return withTimeoutOrNull(timeoutMs) {
            val requestTimeoutSeconds = ((timeoutMs + 999L) / 1_000L)
                .coerceIn(1L, IDLIX_API_TIMEOUT_SECONDS)

            suspend fun request(requestCookies: Map<String, String>): ApiResult? {
                val response = try {
                    app.get(
                        "$apiUrl$path",
                        referer = referer,
                        headers = browserHeaders(referer) + cookieHeaders(requestCookies),
                        timeout = requestTimeoutSeconds,
                        interceptor = cloudflareKiller
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return null
                }
                val text = response.text
                if (
                    response.code !in 200..299 ||
                    text.length !in 2..IDLIX_MAX_JSON_BYTES ||
                    text.trimStart().firstOrNull() !in setOf('{', '[')
                ) return null
                val node = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
                return ApiResult(node, requestCookies + response.cookies)
            }

            request(cookies)?.let { return@withTimeoutOrNull it }
            if (!retryWithPreflight) return@withTimeoutOrNull null
            val pageCookies = preflightPage(referer) ?: return@withTimeoutOrNull null
            request(cookies + pageCookies)
        }
    }

    private suspend fun apiPostJson(
        path: String,
        body: JsonNode,
        referer: String,
        cookies: Map<String, String>,
        timeoutSeconds: Long = IDLIX_API_TIMEOUT_SECONDS
    ): ApiResult? {
        val response = try {
            app.post(
                "$apiUrl$path",
                requestBody = body.toString().toRequestBody(IDLIX_JSON_MEDIA_TYPE.toMediaType()),
                referer = referer,
                headers = browserHeaders(referer) +
                    cookieHeaders(cookies) +
                    mapOf("Content-Type" to IDLIX_JSON_MEDIA_TYPE),
                timeout = timeoutSeconds,
                interceptor = cloudflareKiller
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val text = response.text
        if (
            response.code !in 200..299 ||
            text.length !in 2..IDLIX_MAX_JSON_BYTES ||
            !text.trimStart().startsWith("{")
        ) return null
        val node = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        return ApiResult(node, cookies + response.cookies)
    }

    private suspend fun preflightPage(url: String): Map<String, String>? {
        val page = IdlixParser.contentPage(url, mainUrl)
        if (page == null && url != "$mainUrl/movie" && url != "$mainUrl/series" &&
            !url.startsWith("$mainUrl/search?")) return null
        val response = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to IDLIX_USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8"
                ),
                timeout = IDLIX_API_TIMEOUT_SECONDS,
                interceptor = cloudflareKiller
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val text = response.text
        if (
            response.code !in 200..299 ||
            text.length !in 32..IDLIX_MAX_HTML_BYTES ||
            ProviderHtmlParser.isNonContentPage(text)
        ) return null
        return response.cookies
    }

    private fun browserHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to IDLIX_USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Origin" to mainUrl,
        "Referer" to referer
    )

    private fun cookieHeaders(cookies: Map<String, String>): Map<String, String> {
        val value = cookies.entries
            .filter { (key, value) ->
                key.matches(Regex("^[A-Za-z0-9_.-]{1,64}$")) &&
                    value.length <= 4096 &&
                    !value.any { it == '\r' || it == '\n' || it == ';' }
            }
            .joinToString("; ") { (key, value) -> "$key=$value" }
        return if (value.isBlank()) emptyMap() else mapOf("Cookie" to value)
    }

    private data class ApiResult(
        val node: JsonNode,
        val cookies: Map<String, String>
    )
}

internal suspend fun newIdlixMasterLink(
    source: String,
    masterUrl: String,
    pageUrl: String,
    quality: Int,
    headers: Map<String, String>
): ExtractorLink = newExtractorLink(source, "$source Auto", masterUrl, ExtractorLinkType.M3U8) {
    referer = pageUrl
    this.quality = quality
    this.headers = headers
}

internal suspend fun newIdlixSubtitleFile(
    track: IdlixParser.SubtitleTrack,
    headers: Map<String, String>
): SubtitleFile = newSubtitleFile("id", track.url) {
    this.headers = headers
}

/**
 * Keeps IDLIX's parent master intact so Media3 can select its external audio
 * group, while carrying the short-lived master token to trusted child requests.
 */
internal class IdlixPlaybackInterceptor(
    private val masterUrl: String,
    private val cloudflareKiller: Interceptor
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewrittenUrl = IdlixParser.playbackRequestUrl(masterUrl, original.url.toString())
        val rewritten = if (rewrittenUrl == original.url.toString()) {
            original
        } else {
            original.newBuilder().url(rewrittenUrl).build()
        }
        val forwardingChain = if (rewritten === original) {
            chain
        } else {
            object : Interceptor.Chain by chain {
                override fun request(): Request = rewritten
            }
        }
        return cloudflareKiller.intercept(forwardingChain)
    }
}

internal object IdlixParser {
    data class ContentPage(val type: String, val slug: String, val url: String)
    data class PlaybackRequest(val contentType: String, val contentId: String, val pageUrl: String)
    data class Stream(val url: String, val height: Int)
    data class SubtitleTrack(val label: String, val url: String)
    data class MasterManifest(
        val streams: List<Stream>,
        val audioUrls: List<String>,
        val subtitles: List<SubtitleTrack>
    )

    fun shouldLoadNextSearchPage(
        total: Int,
        loadedCount: Int,
        currentPageCount: Int,
        currentPage: Int,
        pageSize: Int = IDLIX_SEARCH_PAGE_SIZE,
        maxPages: Int = IDLIX_MAX_SEARCH_PAGES
    ): Boolean =
        pageSize > 0 &&
            maxPages > 0 &&
            currentPage in 1 until maxPages &&
            currentPageCount >= pageSize &&
            (total < 0 || loadedCount < total)

    fun playerStreams(manifest: MasterManifest): List<Stream> = manifest.streams
        .filter { stream ->
            isTrustedPlaybackAsset(stream.url) &&
                stream.url.containsCurrentPlaybackToken()
        }
        .distinctBy(Stream::url)
        .take(IDLIX_MAX_STREAMS)

    fun directPlayback(node: JsonNode): JsonNode? = node.takeIf { candidate ->
        candidate.textOrNull("kind")?.lowercase() !in setOf("gate", "pending", "pentos") &&
            candidate.textOrNull("url")?.let(::isTrustedMasterUrl) == true
    }

    fun gateWaitMs(
        serverNow: Long,
        unlockAt: Long,
        graceMs: Long = 900L,
        capMs: Long = IDLIX_MAX_GATE_WAIT_MS
    ): Long? {
        if (serverNow <= 0L || unlockAt <= 0L || graceMs < 0L || capMs < 0L) return null
        val secondsThreshold = 10_000_000_000L
        val serverUsesSeconds = serverNow < secondsThreshold
        val unlockUsesSeconds = unlockAt < secondsThreshold
        if (serverUsesSeconds != unlockUsesSeconds) return null
        if (unlockAt <= serverNow) return 0L
        // Both values are positive and unlockAt is larger, so this subtraction
        // cannot overflow. Avoid java.lang.Math.*Exact (Android API 24+).
        val delta = unlockAt - serverNow
        val deltaMs = if (serverUsesSeconds) {
            // Second-based timestamps are below secondsThreshold, keeping this
            // multiplication well inside Long on every supported Android API.
            delta * 1_000L
        } else {
            delta
        }
        if (deltaMs >= capMs) return capMs
        return if (graceMs >= capMs - deltaMs) capMs else deltaMs + graceMs
    }

    suspend fun <T> pollGateClaim(
        initialWaitMs: Long,
        pendingBudgetMs: Long,
        sleep: suspend (Long) -> Unit,
        request: suspend (previous: T?, timeoutMs: Long) -> T?,
        kind: (T) -> String?,
        remainingMs: (T) -> Long,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        maxRequests: Int = 8
    ): T? {
        if (initialWaitMs < 0L || pendingBudgetMs < 0L || maxRequests <= 0) return null
        if (initialWaitMs > 0L) sleep(initialWaitMs)
        var previous: T? = null
        var budgetLeft = pendingBudgetMs
        repeat(maxRequests) {
            if (budgetLeft <= 0L) return null
            val requestStartedAt = nowMs()
            val result = request(previous, budgetLeft) ?: return null
            val requestFinishedAt = nowMs()
            val requestElapsed = (requestFinishedAt - requestStartedAt).coerceAtLeast(0L)
            budgetLeft = (budgetLeft - requestElapsed.coerceAtMost(budgetLeft)).coerceAtLeast(0L)
            when (kind(result)?.lowercase()) {
                "pentos" -> return result
                "pending" -> {
                    if (budgetLeft <= 0L) return null
                    val reported = remainingMs(result).coerceAtLeast(0L)
                    val desired = if (reported > Long.MAX_VALUE - 400L) {
                        Long.MAX_VALUE
                    } else {
                        reported + 400L
                    }
                    val wait = desired.coerceAtMost(budgetLeft)
                    if (wait <= 0L) return null
                    sleep(wait)
                    budgetLeft -= wait
                    previous = result
                }
                else -> return null
            }
        }
        return null
    }

    fun contentPage(raw: String, mainUrl: String): ContentPage? = runCatching {
        val current = URI(mainUrl)
        val uri = URI(raw)
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(current.host, ignoreCase = true) ||
            uri.port != -1 ||
            uri.userInfo != null
        ) return@runCatching null
        val parts = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        if (parts.size != 2 || parts[0] !in setOf("movie", "series")) return@runCatching null
        val slug = parts[1].takeIf { it.matches(Regex("^[a-z0-9][a-z0-9-]{0,159}$")) }
            ?: return@runCatching null
        ContentPage(parts[0], slug, "${mainUrl.trimEnd('/')}/${parts[0]}/$slug")
    }.getOrNull()

    fun encodePlayback(contentType: String, contentId: String, pageUrl: String): String {
        require(contentType == "movie" || contentType == "episode")
        require(isUuid(contentId))
        val payload = listOf(contentType, contentId, pageUrl).joinToString("\t")
        val page = URI(pageUrl)
        require(page.scheme.equals("https", ignoreCase = true))
        require(!page.host.isNullOrBlank() && page.port == -1 && page.userInfo == null)
        val encoded = encodeBase64UrlNoPadding(payload.toByteArray(Charsets.UTF_8))
        return "https://${page.host.lowercase()}$IDLIX_PLAYBACK_PATH$encoded"
    }

    fun decodePlayback(raw: String, mainUrl: String): PlaybackRequest? = runCatching {
        val encoded = when {
            raw.startsWith("idlix:") -> raw.removePrefix("idlix:")
            else -> {
                val uri = URI(raw)
                val current = URI(mainUrl)
                if (
                    !uri.scheme.equals("https", ignoreCase = true) ||
                    !uri.host.equals(current.host, ignoreCase = true) ||
                    uri.port != -1 ||
                    uri.userInfo != null ||
                    uri.rawQuery != null ||
                    uri.rawFragment != null
                ) return@runCatching null
                when {
                    uri.rawPath.startsWith(IDLIX_PLAYBACK_PATH) ->
                        uri.rawPath.removePrefix(IDLIX_PLAYBACK_PATH)
                    // Compatibility with episode data normalized by older Cloudstream helpers.
                    uri.rawPath.startsWith("/idlix:") -> uri.rawPath.removePrefix("/idlix:")
                    else -> return@runCatching null
                }
            }
        }.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,2048}$")) }
            ?: return@runCatching null
        val payload = decodeBase64Compat(encoded)
            ?.takeIf { it.size <= 1024 }
            ?.toString(Charsets.UTF_8)
            ?: return@runCatching null
        val parts = payload.split('\t')
        if (parts.size != 3 || parts[0] !in setOf("movie", "episode") || !isUuid(parts[1])) {
            return@runCatching null
        }
        val page = contentPage(parts[2], mainUrl) ?: return@runCatching null
        if (parts[0] == "movie" && page.type != "movie") return@runCatching null
        if (parts[0] == "episode" && page.type != "series") return@runCatching null
        PlaybackRequest(parts[0], parts[1], page.url)
    }.getOrNull()

    fun isUuid(value: String): Boolean =
        value.matches(Regex("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"))

    fun searchResult(
        api: MainAPI,
        node: JsonNode,
        contentTypeHint: String? = null
    ): SearchResponse? {
        if (!node.path("isPublished").asBoolean(true)) return null
        val title = node.textOrNull("title") ?: return null
        val slug = node.textOrNull("slug")
            ?.takeIf { it.matches(Regex("^[a-z0-9][a-z0-9-]{0,159}$")) }
            ?: return null
        // The dedicated /movies and /series listings currently omit
        // contentType, while /search includes it. Keep the endpoint context so
        // series cards are not silently rewritten to /movie URLs.
        val contentType = node.textOrNull("contentType")
            ?.lowercase()
            ?: contentTypeHint.orEmpty().lowercase()
        val isSeries = contentType in setOf("series", "tv_series", "tv")
        // Series records intentionally have no video of their own; playback is
        // attached to their episodes. `hasVideo=false` only makes a movie card
        // unplayable at this level.
        if (!isSeries && !node.path("hasVideo").asBoolean(true)) return null
        val url = "https://z2.idlixku.com/${if (isSeries) "series" else "movie"}/$slug"
        val poster = tmdbImage(node.textOrNull("posterPath"), "w500")
        val quality = getQualityFromString(node.textOrNull("quality"))
        return if (isSeries) {
            api.newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                this.quality = quality
            }
        } else {
            api.newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.quality = quality
            }
        }
    }

    fun episodes(api: MainAPI, seasonNode: JsonNode, seasonNumber: Int, pageUrl: String) =
        episodeArray(seasonNode)
            ?.mapNotNull { episode ->
                if (!episode.path("isPublished").asBoolean(true) ||
                    !episode.path("hasVideo").asBoolean(false)) return@mapNotNull null
                val id = episode.textOrNull("id")?.takeIf(::isUuid) ?: return@mapNotNull null
                val number = episode.path("episodeNumber").asInt(-1).takeIf { it > 0 }
                    ?: return@mapNotNull null
                api.newEpisode(encodePlayback("episode", id, pageUrl)) {
                    season = seasonNumber
                    this.episode = number
                    name = episode.textOrNull("name") ?: "Episode $number"
                    description = episode.textOrNull("overview")
                    posterUrl = tmdbImage(episode.textOrNull("stillPath"), "w780")
                }
            }
            .orEmpty()

    fun selectSeasonPayload(
        endpoint: JsonNode?,
        fallback: JsonNode?,
        expectedSeasonNumber: Int
    ): JsonNode? = endpoint
        ?.takeIf { isValidSeasonPayload(it, expectedSeasonNumber) }
        ?: fallback?.takeIf { isValidSeasonPayload(it, expectedSeasonNumber) }

    private fun isValidSeasonPayload(candidate: JsonNode, expectedSeasonNumber: Int): Boolean {
        val payload = candidate.path("season").takeIf(JsonNode::isObject) ?: candidate
        val episodes = payload.path("episodes")
        if (!episodes.isArray) return false
        val suppliedSeasonNumber = payload.path("seasonNumber")
            .takeUnless(JsonNode::isMissingNode)
            ?.takeUnless(JsonNode::isNull)
            ?.asInt(Int.MIN_VALUE)
        return suppliedSeasonNumber == null || suppliedSeasonNumber == expectedSeasonNumber
    }

    private fun episodeArray(candidate: JsonNode): JsonNode? {
        val payload = candidate.path("season").takeIf(JsonNode::isObject) ?: candidate
        return payload.path("episodes").takeIf(JsonNode::isArray)
    }

    fun tmdbImage(path: String?, size: String): String? = path
        ?.trim()
        ?.takeIf { it.matches(Regex("^/[A-Za-z0-9._/-]{1,300}$")) }
        ?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun masterManifest(raw: String, masterUrl: String, maxHeight: Int): MasterManifest? {
        if (raw.length !in 8..IDLIX_MAX_MANIFEST_BYTES) return null
        val lines = raw.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.firstOrNull() != "#EXTM3U") return null
        val audio = lines.mapNotNull { line ->
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) ||
                !line.contains("TYPE=AUDIO", ignoreCase = true)) return@mapNotNull null
            attribute(line, "URI")?.let { tokenizedAsset(it, masterUrl) }
        }.distinct().take(8)
        val subtitles = lines.mapNotNull { line ->
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) ||
                !line.contains("TYPE=SUBTITLES", ignoreCase = true)) return@mapNotNull null
            val language = attribute(line, "LANGUAGE")
            val label = attribute(line, "NAME") ?: language
            if (!isIndonesianSubtitle(language, label)) return@mapNotNull null
            val url = attribute(line, "URI")
                ?.let { tokenizedAsset(it, masterUrl) }
                ?: return@mapNotNull null
            SubtitleTrack(label ?: "Bahasa Indonesia", url)
        }.distinctBy(SubtitleTrack::url).take(32)

        val hasVariantDeclarations = lines.any {
            it.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)
        }
        val allStreams = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) return@forEachIndexed
                val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") }
                    ?: return@forEachIndexed
                val url = tokenizedAsset(uri, masterUrl) ?: return@forEachIndexed
                val height = Regex("(?i)RESOLUTION=\\d+x(\\d{2,4})")
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Qualities.Unknown.value
                add(Stream(url, height))
            }
        }.distinctBy(Stream::url)

        if (allStreams.isEmpty()) {
            if (hasVariantDeclarations) return null
            return MasterManifest(
                streams = listOf(Stream(masterUrl, Qualities.Unknown.value)),
                audioUrls = audio,
                subtitles = subtitles
            ).takeIf { isTrustedMasterUrl(masterUrl) }
        }
        val eligible = if (maxHeight > 0) {
            allStreams.filter { it.height <= 0 || it.height <= maxHeight }
        } else {
            allStreams
        }
        val selected = eligible.ifEmpty {
            listOfNotNull(allStreams.filter { it.height > 0 }.minByOrNull(Stream::height))
        }
        return MasterManifest(
            streams = selected.sortedByDescending(Stream::height).take(IDLIX_MAX_STREAMS),
            audioUrls = audio,
            subtitles = subtitles
        ).takeIf { it.streams.isNotEmpty() }
    }

    private fun attribute(line: String, key: String): String? =
        Regex("(?i)(?:^|,)$key=(?:\"([^\"]+)\"|([^,]+))")
            .find(line.substringAfter(':', line))
            ?.let { match ->
                match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
            }

    private fun tokenizedAsset(raw: String, masterUrl: String): String? =
        signedPlaybackAsset(masterUrl, raw)

    fun signedPlaybackAsset(masterUrl: String, raw: String): String? = runCatching {
        if (!isTrustedMasterUrl(masterUrl)) return@runCatching null
        val master = URI(masterUrl)
        val resolved = master.resolve(raw)
        if (!isTrustedPlaybackAsset(resolved.toString())) return@runCatching null
        val masterParts = master.rawPath.orEmpty().trim('/').split('/')
        if (
            masterParts.size !in 3..4 ||
            masterParts.firstOrNull() != "v" ||
            !masterParts.last().matches(Regex("^config-[0-9]+\\.json$"))
        ) {
            return@runCatching null
        }
        val videoPathPrefix = "/${masterParts.dropLast(1).joinToString("/")}/"
        if (!resolved.rawPath.orEmpty().startsWith(videoPathPrefix)) {
            return@runCatching null
        }
        val masterParams = master.rawQuery.orEmpty().split('&')
            .mapNotNull { item ->
                val key = item.substringBefore('=', "")
                val value = item.substringAfter('=', "")
                if (key in setOf("t", "pm") && value.isNotBlank()) key to value else null
            }
        val authoritativeKeys = masterParams.map(Pair<String, String>::first).toSet()
        val resolvedParams = resolved.rawQuery.orEmpty().split('&')
            .filter(String::isNotBlank)
            .filterNot { parameter -> parameter.substringBefore('=', "") in authoritativeKeys }
        val existingKeys = resolvedParams.map { it.substringBefore('=') }.toSet()
        val additions = masterParams.filterNot { it.first in existingKeys }
        val query = (resolvedParams + additions.map { (key, value) -> "$key=$value" })
            .joinToString("&")
            .takeIf(String::isNotBlank)
        URI(
            resolved.scheme,
            resolved.rawAuthority,
            resolved.rawPath,
            query,
            null
        ).toASCIIString()
    }.getOrNull()

    fun redeemedSubtitles(redeemed: JsonNode, masterUrl: String): List<SubtitleTrack> =
        redeemed.path("subtitles")
            .takeIf(JsonNode::isArray)
            ?.take(32)
            ?.mapNotNull { subtitle ->
                val raw = subtitle.textOrNull("path")
                    ?: subtitle.textOrNull("url")
                    ?: subtitle.textOrNull("src")
                    ?: subtitle.textOrNull("file")
                    ?: return@mapNotNull null
                val url = signedPlaybackAsset(masterUrl, raw) ?: return@mapNotNull null
                val language = subtitle.textOrNull("lang")
                    ?: subtitle.textOrNull("language")
                val label = subtitle.textOrNull("label") ?: language
                if (!isIndonesianSubtitle(language, label)) return@mapNotNull null
                SubtitleTrack(label ?: "Bahasa Indonesia", url)
            }
            ?.distinctBy(SubtitleTrack::url)
            .orEmpty()

    private fun isIndonesianSubtitle(language: String?, label: String?): Boolean {
        fun normalized(raw: String?): String? = raw
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf(String::isNotBlank)

        fun matches(value: String): Boolean =
            value == "id" ||
                value.startsWith("id-") ||
                value in setOf("in", "ind", "indonesian", "indonesia", "bahasa indonesia") ||
                value.contains("bahasa indonesia") ||
                Regex("(?:^|[^a-z])indonesian(?:[^a-z]|$)").containsMatchIn(value)

        val explicitLanguage = normalized(language)
        return if (explicitLanguage != null) {
            matches(explicitLanguage)
        } else {
            normalized(label)?.let(::matches) == true
        }
    }

    fun isTrustedRedeemUrl(raw: String): Boolean = trustedUri(raw) { uri ->
        val host = uri.host.orEmpty().lowercase()
        (host == "majorplay.net" || host.endsWith(".majorplay.net")) &&
            uri.path.orEmpty().matches(Regex("^/api/play/?$"))
    }

    fun isTrustedMasterUrl(raw: String): Boolean = trustedUri(raw) { uri ->
        isTrustedPlaybackHost(uri.host.orEmpty()) &&
            uri.rawPath.orEmpty().matches(
                Regex("^/v/(?:[A-Za-z0-9_-]+/){1,2}config-[0-9]+\\.json$")
            ) &&
            uri.rawQuery.orEmpty().split('&').any { parameter ->
                parameter.substringBefore('=', "") == "t" &&
                    parameter.substringAfter('=', "").isNotBlank()
            }
    }

    fun isTrustedPlaybackAsset(raw: String): Boolean = trustedUri(raw) { uri ->
        isTrustedPlaybackHost(uri.host.orEmpty()) && uri.rawPath.orEmpty().startsWith("/v/")
    }

    fun playbackRequestUrl(masterUrl: String, requestUrl: String): String =
        signedPlaybackAsset(masterUrl, requestUrl) ?: requestUrl

    private fun trustedUri(raw: String, predicate: (URI) -> Boolean): Boolean = runCatching {
        if (raw.length !in 8..8192 || !isSafeRemoteHttpUrl(raw)) return@runCatching false
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            predicate(uri)
    }.getOrDefault(false)

    private fun isTrustedPlaybackHost(raw: String): Boolean {
        val host = raw.lowercase().trimEnd('.')
        return host == "majorplay.net" || host.endsWith(".majorplay.net") ||
            host == "asia9sports.com" || host.endsWith(".asia9sports.com") ||
            host.endsWith(".akamaized.net") ||
            (host.substringBefore('.').matches(Regex("^g\\d+$")) &&
                PLAYBACK_CDN_SUFFIXES.any { host == it || host.endsWith(".$it") })
    }

    private fun String.containsCurrentPlaybackToken(): Boolean = runCatching {
        URI(this).rawQuery.orEmpty().split('&').any { parameter ->
            parameter.substringBefore('=') == "t" &&
                parameter.substringAfter('=', "").isNotBlank()
        }
    }.getOrDefault(false)

    private val PLAYBACK_CDN_SUFFIXES = setOf(
        "akademivo.website",
        "aspireheightsacademy.digital",
        "belajarsmart.site",
        "edubambu.store",
        "edusparkonline.quest",
        "eldrinlorekeeper.store",
        "studihits.space",
        "horizonacademy.site",
        "knowledgequest.store",
        "learnleapinstitute.site",
        "mindbloomcourses.website",
        "akademix.store",
        "kelasnext.store",
        "skillsprouthub.space",
        "ruangskill.space"
    )
}

private fun JsonNode.textOrNull(field: String): String? = path(field)
    .takeUnless(JsonNode::isMissingNode)
    ?.takeUnless(JsonNode::isNull)
    ?.asText()
    ?.trim()
    ?.takeIf(String::isNotBlank)
