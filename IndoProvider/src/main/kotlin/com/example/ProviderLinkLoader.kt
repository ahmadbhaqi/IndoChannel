package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.jsoup.Jsoup

internal typealias PlayerPageFetcher = suspend (url: String, referer: String?) -> String
internal typealias PlayerApiFetcher = suspend (
    url: String,
    referer: String?,
    headers: Map<String, String>
) -> String
internal typealias HowNetworkApiFetcher = suspend (request: HowNetworkApiRequest) -> String
internal typealias InlineSourceParser = (html: String, playerUrl: String) -> List<String>
internal typealias CloudstreamExtractorLoader = suspend (
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) -> Boolean
internal typealias MediaLinkProbe = suspend (ExtractorLink) -> ExtractorLink?
internal typealias DirectLinkFactory = suspend (
    source: String,
    name: String,
    url: String,
    referer: String,
    quality: Int,
    type: ExtractorLinkType,
    headers: Map<String, String>
) -> ExtractorLink
internal typealias PlaySobatUrlParser = (html: String) -> List<String>

private const val PLAY_SOBAT_MIRROR_TIMEOUT_MS = 12_000L
private const val CANDIDATE_TIMEOUT_MS = 40_000L
private const val GENERIC_EXTRACTOR_TIMEOUT_MS = 12_000L
internal const val PROVIDER_HTTP_TIMEOUT_SECONDS = 25L
private const val SESSION_TIMEOUT_MS = 90_000L
private const val MAX_RESOLUTION_CANDIDATES = 48
private const val MAX_BYSE_API_RESPONSE_BYTES = 2_000_000
private const val MAX_JUSTPLAY_API_RESPONSE_BYTES = 2_000_000
private const val MAX_EMBED4ME_API_RESPONSE_BYTES = 4_000_000
private const val MAX_HOWNETWORK_API_RESPONSE_BYTES = 2_000_000
private const val MAX_MEDIA_PROBE_BYTES = 65_536
private const val MEDIA_PROBE_TIMEOUT_SECONDS = 10L
private const val MAX_ABYSS_QUALITY_PROBES = 8
private const val MAX_PLAYLIST_PROBE_ITEMS = 16
private const val MAX_CONCURRENT_PLAYLIST_PROBES = 4
private const val MAX_AUDIO_TRACK_PROBE_ITEMS = 8
private const val JSON_MEDIA_TYPE = "application/json;charset=UTF-8"

private data class CandidateKey(
    val url: String,
    val referer: String
)

private data class EmittedLinkKey(
    val url: String,
    val referer: String,
    val type: ExtractorLinkType,
    val headers: Map<String, String>
)

internal class LinkResolutionSession(
    private val api: MainAPI,
    private val subtitleCallback: (SubtitleFile) -> Unit,
    private val callback: (ExtractorLink) -> Unit,
    private val pageFetcher: PlayerPageFetcher = { url, referer ->
        app.get(url, referer = referer, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS).text
    },
    private val playerApiFetcher: PlayerApiFetcher = { url, referer, headers ->
        val response = app.get(
            url,
            referer = referer,
            headers = headers,
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
        readBoundedBody(response.body, MAX_EMBED4ME_API_RESPONSE_BYTES)
    },
    private val byseApiFetcher: PlayerPageFetcher = ::fetchBoundedByseApi,
    private val justPlayApiFetcher: JustPlayApiFetcher = ::fetchBoundedJustPlayApi,
    private val howNetworkApiFetcher: HowNetworkApiFetcher = ::fetchBoundedHowNetworkApi,
    private val extractorLoader: CloudstreamExtractorLoader = ::loadExtractorWithResult,
    private val inlineSourceParser: InlineSourceParser? = null,
    private val maxDepth: Int = 2,
    private val maxCandidates: Int = MAX_RESOLUTION_CANDIDATES,
    private val candidateTimeoutMs: Long = CANDIDATE_TIMEOUT_MS,
    private val genericExtractorTimeoutMs: Long = GENERIC_EXTRACTOR_TIMEOUT_MS,
    sessionTimeoutMs: Long = SESSION_TIMEOUT_MS,
    private val playSobatUrlParser: PlaySobatUrlParser = InlineDataParser::playSobatUrls,
    private val playSobatMirrorTimeoutMs: Long = PLAY_SOBAT_MIRROR_TIMEOUT_MS,
    private val mediaProbeTimeoutSeconds: Long = MEDIA_PROBE_TIMEOUT_SECONDS,
    private val mediaLinkProbe: MediaLinkProbe = { link ->
        probeExtractorLink(link, mediaProbeTimeoutSeconds)
    },
    private val directLinkFactory: DirectLinkFactory = { source, name, url, referer, quality, type, headers ->
        newExtractorLink(source, name, url, type) {
            this.referer = referer
            this.quality = quality
            this.headers = headers
        }
    }
) {
    private val visitedCandidates = mutableSetOf<CandidateKey>()
    private val emittedLinks = mutableSetOf<EmittedLinkKey>()
    private val emissionMutex = Mutex()
    private val deadlineNanos = System.nanoTime() +
        sessionTimeoutMs.coerceIn(1L, 10 * 60_000L) * 1_000_000L

    val loaded: Boolean get() = emittedLinks.isNotEmpty()
    internal val linkCount: Int get() = emittedLinks.size
    internal val canContinue: Boolean
        get() = System.nanoTime() < deadlineNanos && visitedCandidates.size < maxCandidates

    internal suspend fun <T> withinBudget(block: suspend () -> T): T? {
        val remainingMs = remainingBudgetMs()
        if (remainingMs == 0L || visitedCandidates.size >= maxCandidates) return null
        return withTimeoutOrNull(minOf(candidateTimeoutMs.coerceAtLeast(1L), remainingMs)) {
            block()
        }
    }

    suspend fun resolve(raw: String?, referer: String?): Boolean {
        val before = emittedLinks.size
        val url = api.toPlayableUrl(raw)?.takeUnless { it.isTrailerUrl() } ?: return false
        val remainingMs = remainingBudgetMs()
        if (remainingMs == 0L) return false
        val timeoutMs = minOf(candidateTimeoutMs.coerceAtLeast(1L), remainingMs)
        val candidateDeadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        withTimeoutOrNull(timeoutMs) {
            resolveCandidate(
                url,
                referer,
                genericDepth = 0,
                candidateDeadlineNanos = candidateDeadlineNanos
            )
        }
        return emittedLinks.size > before
    }

    /** Emits a direct media declaration with browser-style Origin/Referer. */
    suspend fun resolveInline(raw: String?, referer: String?): Boolean {
        val url = api.toPlayableUrl(raw)?.takeUnless { it.isTrailerUrl() } ?: return false
        val type = directMediaType(url) ?: return resolve(url, referer)
        val before = emittedLinks.size
        val remainingMs = remainingBudgetMs()
        if (remainingMs == 0L) return false
        val timeoutMs = minOf(candidateTimeoutMs.coerceAtLeast(1L), remainingMs)
        val candidateDeadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        withTimeoutOrNull(timeoutMs) {
            emitDirect(url, referer, type, includeOrigin = true)
            if (emittedLinks.size == before) {
                // A URL ending in .mp4/.m3u8 can still be an HTML wrapper. Keep
                // the direct probe and wrapper fallback inside one candidate
                // timeout, and do not repeat the failed media request.
                resolveCandidate(
                    url,
                    referer,
                    genericDepth = 0,
                    candidateDeadlineNanos = candidateDeadlineNanos,
                    skipInitialDirectProbe = true
                )
            }
        }
        return emittedLinks.size > before
    }

    private suspend fun resolveCandidate(
        url: String,
        referer: String?,
        genericDepth: Int,
        candidateDeadlineNanos: Long,
        skipInitialDirectProbe: Boolean = false
    ) {
        if (genericDepth > maxDepth || System.nanoTime() >= deadlineNanos || !isSafeRemoteHttpUrl(url)) return
        val candidateKey = CandidateKey(url, referer.orEmpty())
        if (candidateKey !in visitedCandidates && visitedCandidates.size >= maxCandidates) return
        if (!visitedCandidates.add(candidateKey)) return

        try {
            var cachedHtml: String? = null
            if (!skipInitialDirectProbe) {
                directMediaType(url)?.let { type ->
                    val beforeDirect = emittedLinks.size
                    emitDirect(url, referer, type)
                    if (emittedLinks.size > beforeDirect) return
                }
            }

            val host = URI(url).host.orEmpty().lowercase()
            ContentXPlayerParser.apiUrl(url, referer)?.let { apiUrl ->
                val encrypted = pageFetcher(apiUrl, url)
                ContentXPlayerParser.playback(encrypted, url)?.let { playback ->
                    emitBysePlayback(playback, url)
                }
                // These fragment embeds require the encrypted API. Their HTML is
                // only a JavaScript loading shell, so a generic extractor cannot
                // recover a media URL when the API itself has no usable source.
                return
            }

            if (host == "freeon.site" || host.endsWith(".freeon.site")) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                if (emitFreeonPlayback(html, url)) return
            }

            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            if (StreamTapePlayerParser.supports(host, path)) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                StreamTapePlayerParser.directUrl(html, url)?.let { direct ->
                    emitDirect(direct, url, ExtractorLinkType.VIDEO)
                }
                if (emittedLinks.size > beforeAdapter) return
            }

            if (host == "kotakajaib.me" || host.endsWith(".kotakajaib.me")) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                for (nested in KotakDataFrameParser.urls(html)) {
                    if (emittedLinks.size > beforeAdapter) break
                    resolveCandidate(nested, url, genericDepth, candidateDeadlineNanos)
                }
                if (emittedLinks.size > beforeAdapter) return
            }

            if (host == "emturbovid.com" || host.endsWith(".emturbovid.com") ||
                host == "turbovidhls.com" || host.endsWith(".turbovidhls.com")
            ) {
                val html = cachedHtml ?: pageFetcher(url, referer).also { cachedHtml = it }
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                TurboVipPlayerParser.directUrl(html)?.let { direct ->
                    emitDirect(direct, url, ExtractorLinkType.VIDEO)
                }
                if (emittedLinks.size > beforeAdapter) return
            }

            if (host == "abyssplayer.com" || host.endsWith(".abyssplayer.com") ||
                host == "abyss.to" || host.endsWith(".abyss.to") ||
                host == "abysscdn.com"
            ) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                val qualityLinks = AbyssPlayerParser.sources(html)
                    .take(MAX_ABYSS_QUALITY_PROBES)
                    .map { source ->
                        directLinkFactory(
                            api.name,
                            "${api.name} ${source.label}",
                            source.url,
                            url,
                            source.quality,
                            ExtractorLinkType.VIDEO,
                            source.headers + ("Referer" to url)
                        )
                    }
                // Probe qualities together so a stalled 1080p URL cannot
                // starve a healthy 720p/480p fallback. Each successful quality
                // is emitted as soon as its own probe finishes.
                emitVerifiedBatch(qualityLinks)
                if (emittedLinks.size > beforeAdapter) return
                // A recognized Abyss page is terminal even when none of its
                // bounded Sora sources verifies. Generic extraction would only
                // rediscover encrypted backing storage and can surface it as an
                // unsupported media stream.
                return
            }

            if (MorenciusPlayerParser.supports(host)) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                for (mediaUrl in MorenciusPlayerParser.mediaUrls(html, url)) {
                    if (emittedLinks.size > beforeAdapter) break
                    emitDirect(mediaUrl, url, ExtractorLinkType.M3U8)
                }
                if (emittedLinks.size > beforeAdapter) return
            }

            Embed4mePlayerParser.apiRequest(url, referer)?.let { request ->
                val beforeAdapter = emittedLinks.size
                val videoPayload = try {
                    playerApiFetcher(request.videoApiUrl, request.playerUrl, request.headers)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                for (source in videoPayload
                    ?.let { Embed4mePlayerParser.videoSources(it, request.playerUrl) }
                    .orEmpty()
                ) {
                    emitVerified(
                        directLinkFactory(
                            api.name,
                            "${api.name} ${source.label}",
                            source.url,
                            request.playerUrl,
                            Qualities.Unknown.value,
                            source.type,
                            request.headers + ("Referer" to request.playerUrl)
                        )
                    )
                    if (emittedLinks.size > beforeAdapter) break
                }
                if (emittedLinks.size == beforeAdapter) {
                    val downloadPayload = try {
                        playerApiFetcher(request.downloadApiUrl, request.playerUrl, request.headers)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    for (source in downloadPayload
                        ?.let { Embed4mePlayerParser.downloadSources(it, request.playerUrl) }
                        .orEmpty()
                    ) {
                        emitVerified(
                            directLinkFactory(
                                api.name,
                                "${api.name} ${source.label}",
                                source.url,
                                request.playerUrl,
                                Qualities.Unknown.value,
                                source.type,
                                request.headers + ("Referer" to request.playerUrl)
                            )
                        )
                        if (emittedLinks.size > beforeAdapter) break
                    }
                }
                // A recognized hash player is an API shell. Generic extraction
                // can only rediscover the same shell or its ciphertext.
                return
            }

            HowNetworkPlayerParser.apiRequest(url)?.let { request ->
                val beforeAdapter = emittedLinks.size
                val payload = try {
                    howNetworkApiFetcher(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                val mediaHeaders = linkedMapOf(
                    "Referer" to request.playerUrl,
                    "Origin" to request.headers.getValue("Origin")
                )
                for (source in payload
                    ?.let { HowNetworkPlayerParser.sources(it, request.playerUrl) }
                    .orEmpty()
                ) {
                    emitVerified(
                        directLinkFactory(
                            api.name,
                            "${api.name} ${source.label}",
                            source.url,
                            request.playerUrl,
                            Qualities.Unknown.value,
                            source.type,
                            mediaHeaders
                        )
                    )
                    if (emittedLinks.size > beforeAdapter) break
                }
                // The recognized player is only an AJAX shell. Falling through
                // can expose its JSON or HTML as media and trigger parsing 3003.
                return
            }

            if (host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) {
                var playerPageUrl = url
                val html = try {
                    pageFetcher(url, referer)
                } catch (error: CancellationException) {
                    throw error
                } catch (httpsError: Exception) {
                    val httpFallback = publicIpHttpFallback(url)
                        ?: throw httpsError
                    playerPageUrl = httpFallback
                    pageFetcher(httpFallback, referer)
                }
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                JuicyCodesPlayerParser.playback(html)?.let { playback ->
                    playback.tracks.forEach { track ->
                        subtitleCallback(newSubtitleFile(track.label, track.url))
                    }
                    playback.media.forEach { media ->
                        emitVerified(
                            directLinkFactory(
                                api.name,
                                "${api.name} JuicyCodes ${media.label}",
                                media.url,
                                playerPageUrl,
                                media.quality,
                                if (media.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                linkedMapOf("Referer" to playerPageUrl).apply {
                                    media.userAgent?.let { put("User-Agent", it) }
                                }
                            )
                        )
                    }
                }
                if (emittedLinks.size > beforeAdapter) return
                if (JuicyCodesPlayerParser.recognizes(html)) return
            }

            if (JustPlayPlayerParser.supports(host)) {
                val beforeAdapter = emittedLinks.size
                val playback = try {
                    JustPlayPlayerParser.resolve(url, referer, justPlayApiFetcher)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                playback?.let {
                    emitBysePlayback(
                        it,
                        url,
                        mapOf("User-Agent" to JUSTPLAY_USER_AGENT)
                    )
                }
                if (emittedLinks.size > beforeAdapter) return
            }

            val byseApiUrl = BysePlayerParser.apiUrl(url)
            val looksLikeByseHost = host == "bysebuho.com" ||
                host.endsWith(".bysebuho.com") ||
                host.startsWith("byse")
            val looksLikeBysePage = if (byseApiUrl != null && !looksLikeByseHost) {
                val html = cachedHtml ?: pageFetcher(url, referer).also { cachedHtml = it }
                !ProviderHtmlParser.isNonContentPage(html) &&
                    BysePlayerParser.isFrontendPage(html)
            } else {
                false
            }
            if (byseApiUrl != null && (looksLikeByseHost || looksLikeBysePage)) {
                val beforeAdapter = emittedLinks.size
                val apiJson = byseApiUrl.let { endpoint ->
                    try {
                        byseApiFetcher(endpoint, url)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
                apiJson?.let(BysePlayerParser::playback)?.let { emitBysePlayback(it, url) }
                if (emittedLinks.size > beforeAdapter) return
            }

            if (host == "playsobat.xyz" || host.endsWith(".playsobat.xyz")) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                val mirrors = orderPlaySobatMirrorUrls(playSobatUrlParser(html))
                mirrors.forEach { nested ->
                    if (emittedLinks.size > beforeAdapter) return
                    val playable = api.toPlayableUrl(nested) ?: return@forEach
                    withTimeoutOrNull(playSobatMirrorTimeoutMs) {
                        resolveCandidate(playable, url, genericDepth, candidateDeadlineNanos)
                    }
                }
                if (emittedLinks.size > beforeAdapter) return
                // Keep the cached generic fallback: some PlaySobat pages also
                // expose a direct iframe/source outside the encrypted mirrors.
            }

            if (host == "asiastream.cc" || host.endsWith(".asiastream.cc")) {
                val html = cachedHtml ?: pageFetcher(url, referer).also { cachedHtml = it }
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                InlineDataParser.asiaStreamMasterUrl(html, url)?.let { master ->
                    emitDirect(master, url, ExtractorLinkType.M3U8)
                }
                if (emittedLinks.size > beforeAdapter) return
            }

            val beforeExtractor = emittedLinks.size
            val extractedLinks = mutableListOf<ExtractorLink>()
            try {
                // Generic Cloudstream extractors can perform their own network
                // retries. Bound that work separately and reserve at least half
                // of the remaining candidate budget for cached/fetched HTML.
                // Keep callbacks outside the timed block so links emitted before
                // a stalled extractor is cancelled can still be verified below.
                withTimeoutOrNull(genericExtractorBudgetMs(candidateDeadlineNanos)) {
                    extractorLoader(url, referer, subtitleCallback, extractedLinks::add)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: LinkageError) {
                // An optional extractor dependency missing at runtime must not
                // prevent the provider from trying a healthy sibling mirror.
            } catch (_: Exception) {
                // Some extractors emit one or more callbacks before a later
                // request fails. Keep and verify those callbacks, then continue
                // through the cached HTML fallback instead of discarding them.
            }
            for (link in extractedLinks) {
                if (!canContinue) break
                emitVerified(link)
            }
            if (emittedLinks.size > beforeExtractor || genericDepth >= maxDepth) return

            val html = cachedHtml ?: pageFetcher(url, referer)
            if (ProviderHtmlParser.isNonContentPage(html)) return
            // Freeon rotates its frontend onto unrelated domains such as
            // strplay.drama21.top. Detect the bounded Unicode packer and only
            // call a same-origin /api endpoint instead of trusting host names.
            if (emitFreeonPlayback(html, url)) return
            val beforeJuicyCodes = emittedLinks.size
            JuicyCodesPlayerParser.playback(html)?.let { playback ->
                playback.tracks.forEach { track ->
                    subtitleCallback(newSubtitleFile(track.label, track.url))
                }
                playback.media.forEach { media ->
                    emitVerified(
                        directLinkFactory(
                            api.name,
                            "${api.name} JuicyCodes ${media.label}",
                            media.url,
                            url,
                            media.quality,
                            if (media.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            linkedMapOf("Referer" to url).apply {
                                media.userAgent?.let { put("User-Agent", it) }
                            }
                        )
                    )
                }
            }
            if (emittedLinks.size > beforeJuicyCodes) return
            if (JuicyCodesPlayerParser.recognizes(html)) return
            inlineSourceParser
                ?.let { parser -> runCatching { parser(html, url) }.getOrDefault(emptyList()) }
                .orEmpty()
                .forEach { nested ->
                    val playable = ProviderHtmlParser.absoluteUrl(nested, url)
                        ?: return@forEach
                    val directType = directMediaType(playable)
                    if (directType != null) {
                        emitDirect(playable, url, directType, includeOrigin = true)
                    } else {
                        resolveCandidate(playable, url, genericDepth + 1, candidateDeadlineNanos)
                    }
                }
            if (emittedLinks.size > beforeJuicyCodes) return
            val document = Jsoup.parse(html, url)
            ProviderHtmlParser.mediaSources(document).forEach { nested ->
                val playable = ProviderHtmlParser.absoluteUrl(nested, url)
                    ?: return@forEach
                val directType = directMediaType(playable)
                if (directType != null) {
                    val beforeInline = emittedLinks.size
                    emitDirect(playable, url, directType, includeOrigin = true)
                    if (emittedLinks.size == beforeInline) {
                        resolveCandidate(playable, url, genericDepth + 1, candidateDeadlineNanos)
                    }
                } else {
                    resolveCandidate(playable, url, genericDepth + 1, candidateDeadlineNanos)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Continue with sibling and later candidates.
        }
    }

    private suspend fun emitDirect(
        url: String,
        referer: String?,
        type: ExtractorLinkType,
        includeOrigin: Boolean = false
    ) {
        if (!isSafeRemoteHttpUrl(url)) return
        val headers = linkedMapOf<String, String>()
        referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        if (includeOrigin) requestOrigin(referer)?.let { headers["Origin"] = it }
        emitVerified(
            directLinkFactory(
                api.name,
                api.name,
                url,
                referer.orEmpty(),
                Qualities.Unknown.value,
                type,
                headers
            )
        )
    }

    private fun requestOrigin(url: String?): String? = runCatching {
        val uri = URI(url ?: return@runCatching null)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return@runCatching null
        }
        URI(uri.scheme.lowercase(), null, uri.host, uri.port, null, null, null)
            .toString()
            .trimEnd('/')
    }.getOrNull()

    private suspend fun emitFreeonPlayback(html: String, playerUrl: String): Boolean {
        val beforeAdapter = emittedLinks.size
        for (apiUrl in FreeonPlayerParser.apiUrls(html, playerUrl)) {
            val response = try {
                pageFetcher(apiUrl, playerUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            for (source in FreeonPlayerParser.sources(response)) {
                val type = if (
                    source.mimeType.contains("mpegurl", ignoreCase = true) ||
                    source.url.contains(".m3u8", ignoreCase = true)
                ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                emitVerified(
                    directLinkFactory(
                        api.name,
                        "${api.name} ${source.label}",
                        source.url,
                        playerUrl,
                        Qualities.Unknown.value,
                        type,
                        mapOf("Referer" to playerUrl)
                    )
                )
            }
        }
        return emittedLinks.size > beforeAdapter
    }

    private suspend fun emitBysePlayback(
        playback: BysePlayback,
        playerUrl: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val mediaHeaders = linkedMapOf("Referer" to playerUrl).apply {
            putAll(extraHeaders)
        }
        runCatching {
            val uri = URI(playerUrl)
            if (uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                mediaHeaders["Origin"] = URI(
                    uri.scheme,
                    null,
                    uri.host,
                    uri.port,
                    null,
                    null,
                    null
                ).toString().trimEnd('/')
            }
        }
        playback.tracks
            .filter { track ->
                track.kind.lowercase() in setOf("captions", "subtitle", "subtitles")
            }
            .filter { track -> isSafeRemoteHttpUrl(track.url) }
            .forEach { track ->
                subtitleCallback(
                    newSubtitleFile(
                        track.label.ifBlank { track.language.ifBlank { "Subtitle" } },
                        track.url
                    )
                )
        }
        playback.sources.forEach { source ->
            val resolution = ServerLinkLabelFormatter.resolution(
                source.height ?: Qualities.Unknown.value,
                source.quality,
                source.label
            ) ?: Qualities.Unknown.value
            emitVerified(
                directLinkFactory(
                    api.name,
                    "${api.name} ${source.label}",
                    source.url,
                    playerUrl,
                    resolution,
                    if (source.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    mediaHeaders
                )
            )
        }
    }

    internal suspend fun emitResolved(link: ExtractorLink): Boolean {
        val before = emittedLinks.size
        emitVerified(link)
        return emittedLinks.size > before
    }

    private suspend fun emitVerified(link: ExtractorLink) {
        val verified = verifyMediaLink(link) ?: return
        emissionMutex.withLock {
            emitUnchecked(verified)
        }
    }

    private suspend fun emitVerifiedBatch(links: List<ExtractorLink>) = supervisorScope {
        if (links.isEmpty()) return@supervisorScope
        val semaphore = Semaphore(MAX_ABYSS_QUALITY_PROBES)
        links.forEach { link ->
            launch {
                semaphore.withPermit {
                    val verified = verifyMediaLink(link) ?: return@withPermit
                    emissionMutex.withLock {
                        emitUnchecked(verified)
                    }
                }
            }
        }
    }

    private suspend fun verifyMediaLink(link: ExtractorLink): ExtractorLink? {
        if (System.nanoTime() >= deadlineNanos) return null
        if (!link.hasSafeMediaUrls()) return null
        val remainingMs = remainingBudgetMs()
        if (remainingMs == 0L) return null
        return withTimeoutOrNull(
            minOf(mediaProbeTimeoutSeconds.coerceIn(1L, 60L) * 1_000L, remainingMs)
        ) {
            mediaLinkProbe(link)
        }
    }

    private suspend fun emitUnchecked(link: ExtractorLink) {
        if (System.nanoTime() >= deadlineNanos) return
        if (!link.hasSafeMediaUrls()) return
        val key = EmittedLinkKey(
            url = link.mediaIdentity(),
            referer = link.referer.orEmpty(),
            type = link.type,
            headers = link.headers.toMap()
        )
        if (emittedLinks.add(key)) callback(link.withSimpleServerName(api.name))
    }

    private fun remainingBudgetMs(): Long =
        ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)

    private fun genericExtractorBudgetMs(candidateDeadlineNanos: Long): Long {
        val remainingCandidateMs =
            ((candidateDeadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
        val extractorShareMs = (remainingCandidateMs / 2L).coerceAtLeast(1L)
        return minOf(
            genericExtractorTimeoutMs.coerceAtLeast(1L),
            extractorShareMs,
            remainingBudgetMs().coerceAtLeast(1L)
        )
    }
}

private suspend fun fetchBoundedByseApi(url: String, referer: String?): String {
    val response = app.get(url, referer = referer, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
    return readBoundedBody(response.body, MAX_BYSE_API_RESPONSE_BYTES)
}

private suspend fun fetchBoundedHowNetworkApi(request: HowNetworkApiRequest): String {
    val response = app.post(
        request.apiUrl,
        data = request.form,
        referer = request.playerUrl,
        headers = request.headers,
        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
    )
    return readBoundedBody(response.body, MAX_HOWNETWORK_API_RESPONSE_BYTES)
}

/**
 * Verifies that an extractor callback points to media bytes instead of an HTML
 * error page. Some rotating hosts return 200/404 HTML from URLs that look like
 * MP4/HLS, which otherwise surfaces in Cloudstream as parsing code 3003.
 */
@Suppress("DEPRECATION_ERROR")
private suspend fun probeExtractorLink(
    link: ExtractorLink,
    timeoutSeconds: Long
): ExtractorLink? {
    if (!link.hasSafeMediaUrls()) return null
    if (link is ExtractorLinkPlayList) {
        return validateExtractorPlaylist(link) { entry ->
            probeExtractorLink(entry, timeoutSeconds)
        }
    }

    val requestedUri = runCatching { URI(link.url) }.getOrNull() ?: return null
    if (requestedUri.host.isReservedTestHost()) return link
    if (link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET) {
        return null
    }

    val explicitReferer = link.headers.entries
        .lastOrNull { it.key.equals("Referer", ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }
    val requestHeaders = linkedMapOf<String, String>().apply {
        link.headers.forEach { (key, value) ->
            if (
                !key.equals("Referer", ignoreCase = true) &&
                !key.equals("Range", ignoreCase = true)
            ) {
                put(key, value)
            }
        }
        if (link.type == ExtractorLinkType.VIDEO) {
            put("Range", "bytes=0-${MAX_MEDIA_PROBE_BYTES - 1}")
        }
    }
    return try {
        val response = app.get(
            link.url,
            referer = explicitReferer ?: link.referer.takeIf { it.isNotBlank() },
            headers = requestHeaders,
            timeout = timeoutSeconds.coerceIn(1L, 60L)
        )
        if (response.code !in 200..299 || !isSafeRemoteHttpUrl(response.url)) {
            response.body.close()
            return null
        }
        if (
            expectedSoraRangeIsValid(
                link.url,
                response.code,
                response.headers["Content-Range"]
            ) == false
        ) {
            response.body.close()
            return null
        }
        val contentType = response.body.contentType()?.toString()
        val prefix = readBoundedPrefix(response.body, MAX_MEDIA_PROBE_BYTES)
        val detectedType = sniffMediaType(prefix, contentType) ?: return null

        // Keep the original URL and concrete subtype. The player can follow the
        // same redirect, while DRM, playlist and alternate-audio metadata stay
        // intact and origin-bound headers are not moved onto a foreign CDN URL.
        link.type = detectedType
        if (
            validateAuxiliaryAudioTracks(link) { track ->
                probeExtractorLink(track, timeoutSeconds)
            } == null
        ) return null
        link
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

@Suppress("DEPRECATION_ERROR")
internal suspend fun validateAuxiliaryAudioTracks(
    link: ExtractorLink,
    entryProbe: suspend (ExtractorLink) -> ExtractorLink?
): ExtractorLink? {
    val tracks = link.audioTracksCompat()
    if (tracks.size > MAX_AUDIO_TRACK_PROBE_ITEMS) return null
    if (tracks.isEmpty()) return link
    val verified = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_PLAYLIST_PROBES)
        tracks.map { track ->
            async {
                semaphore.withPermit {
                    val trackHeaders = track.headers
                    entryProbe(
                        ExtractorLink(
                            link.source,
                            link.name,
                            track.url,
                            trackHeaders.entries
                                .lastOrNull { it.key.equals("Referer", ignoreCase = true) }
                                ?.value
                                ?: link.referer,
                            Qualities.Unknown.value,
                            ExtractorLinkType.M3U8,
                            trackHeaders,
                            link.extractorData
                        )
                    )
                }
            }
        }.awaitAll()
    }
    return link.takeIf { verified.all { it != null } }
}

/**
 * A multipart extractor result is only safe to expose when every part is real
 * media. Checking just the first part lets a later HTML/expired part reach the
 * player and surface as unsupported parsing code 3003.
 */
@Suppress("DEPRECATION_ERROR")
internal suspend fun validateExtractorPlaylist(
    link: ExtractorLinkPlayList,
    entryProbe: suspend (ExtractorLink) -> ExtractorLink?
): ExtractorLinkPlayList? {
    val entries = link.playlist
    if (entries.isEmpty() || entries.size > MAX_PLAYLIST_PROBE_ITEMS) return null

    val verified = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_PLAYLIST_PROBES)
        entries.map { entry ->
            async {
                semaphore.withPermit {
                    entryProbe(
                        ExtractorLink(
                            link.source,
                            link.name,
                            entry.url,
                            link.referer,
                            link.quality,
                            link.type,
                            link.headers,
                            link.extractorData
                        )
                    )
                }
            }
        }.awaitAll()
    }
    if (verified.any { it == null }) return null
    val mediaTypes = verified.filterNotNull().map { it.type }.distinct()
    if (mediaTypes.size != 1) return null
    link.type = mediaTypes.single()
    return link
}

/**
 * Full-file Sora URLs encode the expected media size in their path. Requiring
 * the same total in Content-Range prevents the legacy 2 MiB chunk token from
 * passing the MP4 prefix sniff and failing later in the player.
 */
internal fun expectedSoraRangeIsValid(
    url: String,
    responseCode: Int,
    contentRange: String?
): Boolean? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host.orEmpty().lowercase().trimEnd('.')
    if (!host.endsWith(".sssrr.org")) return null
    if (!uri.rawPath.orEmpty().startsWith("/sora/", ignoreCase = true)) return null
    val expectedSize = Regex("^/sora/([1-9]\\d{0,19})/")
        .find(uri.rawPath.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it in 1L..20_000_000_000L }
        ?: return false
    if (responseCode != 206) return false
    val range = contentRange
        ?.trim()
        ?.let {
            Regex("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+)$").matchEntire(it)
        }
        ?: return false
    val start = range.groupValues[1].toLongOrNull() ?: return false
    val end = range.groupValues[2].toLongOrNull() ?: return false
    val total = range.groupValues[3].toLongOrNull() ?: return false
    return start == 0L && end in start until total && total == expectedSize
}

internal fun sniffMediaType(
    bytes: ByteArray,
    @Suppress("UNUSED_PARAMETER") contentType: String? = null
): ExtractorLinkType? {
    if (bytes.isEmpty()) return null
    val sample = bytes.copyOfRange(0, minOf(bytes.size, MAX_MEDIA_PROBE_BYTES))
    val textPrefix = sample.toString(Charsets.UTF_8)
        .removePrefix("\uFEFF")
        .trimStart()
    val normalizedText = textPrefix.take(512).lowercase()
    if (
        normalizedText.startsWith("<!doctype") ||
        normalizedText.startsWith("<html") ||
        normalizedText.startsWith("<head") ||
        normalizedText.startsWith("<body") ||
        normalizedText.startsWith("<script") ||
        normalizedText.startsWith("{") ||
        normalizedText.startsWith("[") ||
        normalizedText.contains("silence is golden") ||
        normalizedText.contains("just a moment...")
    ) {
        return null
    }
    val manifestLines = textPrefix.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
    if (isPlayableHlsManifest(manifestLines)) {
        return ExtractorLinkType.M3U8
    }
    if (
        (
            textPrefix.startsWith("<MPD", ignoreCase = true) ||
                (
                    textPrefix.startsWith("<?xml", ignoreCase = true) &&
                        textPrefix.take(2_048).contains("<MPD", ignoreCase = true)
                    )
            ) &&
        (
            textPrefix.contains("<Period", ignoreCase = true) ||
                textPrefix.contains("<Representation", ignoreCase = true)
            )
    ) {
        return ExtractorLinkType.DASH
    }

    val hasIsoBaseMediaSignature = sample.hasValidIsoBaseMediaPrefix()
    val hasMatroskaSignature = sample.size >= 8 &&
        sample.startsWithBytes(0x1A, 0x45, 0xDF, 0xA3)
    val hasFlvSignature = sample.size >= 13 &&
        sample.startsWithBytes(0x46, 0x4C, 0x56)
    val hasOggSignature = sample.size >= 27 &&
        sample.startsWithBytes(0x4F, 0x67, 0x67, 0x53)
    val hasMpegProgramSignature = sample.size >= 14 &&
        sample.startsWithBytes(0x00, 0x00, 0x01, 0xBA)
    val hasAviSignature = sample.size >= 16 &&
        sample.startsWithBytes(0x52, 0x49, 0x46, 0x46) &&
        sample.copyOfRange(8, 12).startsWithBytes(0x41, 0x56, 0x49)
    val hasTransportStreamSignature = sample.hasTransportStreamSync()
    if (
        hasIsoBaseMediaSignature ||
        hasMatroskaSignature ||
        hasFlvSignature ||
        hasOggSignature ||
        hasMpegProgramSignature ||
        hasAviSignature ||
        hasTransportStreamSignature
    ) {
        return ExtractorLinkType.VIDEO
    }

    // Do not trust Content-Type alone. Several dead/rotating hosts label HTML
    // or encrypted storage bytes as video/mp4, which Cloudstream then reports
    // as an unsupported parsing code. A real media signature is required.
    return null
}

private fun isPlayableHlsManifest(lines: List<String>): Boolean {
    if (lines.firstOrNull()?.equals("#EXTM3U", ignoreCase = true) != true) return false
    val body = lines.drop(1)
    val hasHlsDirective = body.any { line ->
        Regex("(?i)^#EXT(?:INF|-[A-Z0-9-]+)(?::|$)").containsMatchIn(line)
    }
    if (!hasHlsDirective) return false

    val directUris = body
        .filterNot { it.startsWith("#") }
        .filter(::isPlausibleHlsUri)
    if (directUris.isNotEmpty()) return true

    val attributeUri = Regex("(?i)\\bURI\\s*=\\s*[\"']([^\"']+)[\"']")
    return body.any { line ->
        line.startsWith("#") && attributeUri.findAll(line).any { match ->
            isPlausibleHlsUri(match.groupValues[1])
        }
    }
}

private fun isPlausibleHlsUri(raw: String): Boolean {
    val value = raw.trim()
    if (
        value.isBlank() ||
        value.length > 4_096 ||
        value.any { it.isWhitespace() || it.isISOControl() }
    ) return false
    val normalized = value.lowercase()
    if (
        normalized.startsWith("<") ||
        normalized.startsWith("{") ||
        normalized.startsWith("[") ||
        normalized in setOf("forbidden", "unauthorized", "denied", "error", "not-found") ||
        normalized.contains("access denied")
    ) return false

    return runCatching {
        val uri = URI(value.replace(Regex("\\{\\$[A-Za-z0-9_-]+}"), "variable"))
        if (uri.userInfo != null) return@runCatching false
        if (uri.isAbsolute) {
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        } else if (value.startsWith("//")) {
            !uri.host.isNullOrBlank()
        } else {
            uri.path.orEmpty().isNotBlank() && uri.path !in setOf(".", "..")
        }
    }.getOrDefault(false)
}

private suspend fun fetchBoundedJustPlayApi(request: JustPlayHttpRequest): String {
    val playerUrl = request.headers["X-Embed-Parent"]
    val uri = URI(request.url)
    val origin = URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString().trimEnd('/')
    val headers = linkedMapOf(
        "Accept" to "application/json",
        "Origin" to origin,
        "User-Agent" to JUSTPLAY_USER_AGENT
    ).apply {
        putAll(request.headers)
        if (request.method == JustPlayHttpMethod.POST) put("Content-Type", JSON_MEDIA_TYPE)
    }
    val response = when (request.method) {
        JustPlayHttpMethod.GET -> app.get(
            request.url,
            referer = playerUrl,
            headers = headers,
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
        JustPlayHttpMethod.POST -> app.post(
            request.url,
            requestBody = request.body.orEmpty().toRequestBody(JSON_MEDIA_TYPE.toMediaType()),
            referer = playerUrl,
            headers = headers,
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
    }
    return readBoundedBody(response.body, MAX_JUSTPLAY_API_RESPONSE_BYTES)
}

private fun readBoundedBody(body: ResponseBody, maxBytes: Int): String {
    val declaredSize = body.contentLength()
    require(declaredSize < 0L || declaredSize <= maxBytes)

    return body.byteStream().use { input ->
        val output = ByteArrayOutputStream(
            declaredSize.coerceIn(0L, maxBytes.toLong()).toInt()
        )
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes)
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
}

private fun readBoundedPrefix(body: ResponseBody, maxBytes: Int): ByteArray {
    return body.byteStream().use { input ->
        val output = ByteArrayOutputStream(maxBytes.coerceAtMost(DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (total < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
        }
        output.toByteArray()
    }
}

private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
    if (size < values.size) return false
    return values.indices.all { index -> this[index] == values[index].toByte() }
}

private fun ByteArray.hasValidIsoBaseMediaPrefix(): Boolean {
    if (size < 32 || indexOfAscii("ftyp", limit = 16) != 4) return false
    val ftypSize = readUInt32BigEndian(0)
    if (ftypSize !in 16L..size.toLong()) return false
    var offset = ftypSize.toInt()
    while (offset + 8 <= size) {
        val boxSize = readUInt32BigEndian(offset)
        val boxType = copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
        if (boxType in setOf("moov", "mdat", "moof", "sidx")) return boxSize == 0L || boxSize >= 8L
        if (boxSize < 8L || boxSize > Int.MAX_VALUE || offset + boxSize.toInt() > size) {
            return false
        }
        offset += boxSize.toInt()
    }
    return false
}

private fun ByteArray.readUInt32BigEndian(offset: Int): Long {
    if (offset < 0 || offset + 4 > size) return -1L
    return (0 until 4).fold(0L) { value, index ->
        (value shl 8) or (this[offset + index].toLong() and 0xffL)
    }
}

private fun ByteArray.hasTransportStreamSync(): Boolean {
    for (stride in intArrayOf(188, 192, 204)) {
        val maxOffset = minOf(512, size - (stride * 2 + 1))
        if (maxOffset < 0) continue
        for (offset in 0..maxOffset) {
            if (
                this[offset] == 0x47.toByte() &&
                this[offset + stride] == 0x47.toByte() &&
                this[offset + stride * 2] == 0x47.toByte()
            ) return true
        }
    }
    return false
}

private fun ByteArray.indexOfAscii(value: String, limit: Int): Int {
    val needle = value.toByteArray(Charsets.US_ASCII)
    val lastStart = minOf(size, limit) - needle.size
    if (lastStart < 0) return -1
    for (index in 0..lastStart) {
        if (needle.indices.all { offset -> this[index + offset] == needle[offset] }) {
            return index
        }
    }
    return -1
}

private fun ExtractorLink.mediaUrls(): List<String> = when (this) {
    is ExtractorLinkPlayList -> playlist.map { it.url }
    else -> listOf(url)
}

private fun ExtractorLink.hasSafeMediaUrls(): Boolean {
    val urls = mediaUrls() + audioTracksCompat().map { it.url }
    return urls.isNotEmpty() && urls.all { it.isNotBlank() && isSafeRemoteHttpUrl(it) }
}

private fun ExtractorLink.mediaIdentity(): String = mediaUrls().joinToString("\n")

private fun String?.isReservedTestHost(): Boolean {
    val host = this?.lowercase()?.trimEnd('.') ?: return false
    return host in setOf("example", "test", "invalid") ||
        host.endsWith(".example") ||
        host.endsWith(".test") ||
        host.endsWith(".invalid")
}

internal fun publicIpHttpFallback(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host.orEmpty()
    if (
        !host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) ||
        !isPublicIpv4(host)
    ) return null
    return "http:${url.substringAfter(':')}".takeIf(::isSafeRemoteHttpUrl)
}

internal fun orderPlaySobatMirrorUrls(urls: List<String>): List<String> {
    return urls
        .distinct()
        .filterNot { url ->
            runCatching { URI(url).host.orEmpty().lowercase() }
                .getOrDefault("") in setOf(
                    "dintezuvio.com",
                    "www.dintezuvio.com",
                    "omg10.com",
                    "www.omg10.com"
                )
        }
        .sortedBy { url ->
            val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            when {
                host == "abyssplayer.com" || host.endsWith(".abyssplayer.com") -> 0
                host == "hglink.to" || host.endsWith(".hglink.to") ||
                    host == "streamwish.to" || host.endsWith(".streamwish.to") -> 1
                host == "mdfx9dc8n.net" || host.endsWith(".mdfx9dc8n.net") ||
                    host.contains("mixdrop") -> 2
                host == "dood.la" || host.endsWith(".dood.la") -> 3
                host == "cloudplay.p2pstream.vip" || host.endsWith(".p2pstream.vip") -> 4
                else -> 2
            }
        }
}

internal fun directMediaType(url: String): ExtractorLinkType? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host.orEmpty().lowercase()
    val path = uri.path.orEmpty().lowercase()
    if (StreamTapePlayerParser.supports(host, path)) return null
    return when {
        path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
        path.endsWith(".mp4") -> ExtractorLinkType.VIDEO
        else -> null
    }
}

internal fun MainAPI.toPlayableUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf {
        it.isNotBlank()
    } ?: return null
    val explicitScheme = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")
        .find(value)
        ?.groupValues
        ?.get(1)
    if (explicitScheme != null &&
        !explicitScheme.equals("http", ignoreCase = true) &&
        !explicitScheme.equals("https", ignoreCase = true)
    ) return null

    val resolved = when {
        value.startsWith("//") -> httpsify(value)
        explicitScheme != null -> value.replaceFirst(Regex("""^[A-Za-z][A-Za-z0-9+.-]*:"""), "https:")
        else -> fixUrl(value)
    }
    return resolved.takeIf(::isSafeRemoteHttpUrl)
}

internal fun isSafeRemoteHttpUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    val host = uri.host
        ?.lowercase()
        ?.trimEnd('.')
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?: return false
    if (host == "localhost" || host.endsWith(".localhost")) return false

    if (host.contains(':')) {
        val address = runCatching { InetAddress.getByName(host.substringBefore('%')) }.getOrNull()
            ?: return false
        return isPublicAddress(address)
    }

    if (host.all { it.isDigit() || it == '.' }) {
        if (!host.matches(Regex("(?:0|[1-9]\\d{0,2})(?:\\.(?:0|[1-9]\\d{0,2})){3}"))) return false
        return isPublicIpv4(host)
    }
    if (host.startsWith("0x", ignoreCase = true) && host.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return false
    }
    return true
}

private fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    if (address is Inet6Address) {
        val first = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
        if ((first and 0xfe) == 0xfc) return false
        return true
    }
    return isPublicIpv4(address.hostAddress ?: return false)
}

private fun isPublicIpv4(host: String): Boolean {
    val octets = host.split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (a, b, c, _) = octets
    return when {
        a == 0 || a == 10 || a == 127 || a >= 224 -> false
        a == 100 && b in 64..127 -> false
        a == 169 && b == 254 -> false
        a == 172 && b in 16..31 -> false
        a == 192 && (b == 168 || (b == 0 && c in setOf(0, 2))) -> false
        a == 198 && (b in 18..19 || (b == 51 && c == 100)) -> false
        a == 203 && b == 0 && c == 113 -> false
        else -> true
    }
}

internal suspend fun MainAPI.loadResolvedExtractorWithResult(
    raw: String?,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return LinkResolutionSession(this, subtitleCallback, callback).resolve(raw, referer)
}

internal suspend fun loadExtractorWithResult(
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var emitted = false
    loadExtractor(url, referer, subtitleCallback) { link ->
        emitted = true
        callback(link)
    }
    return emitted
}

private fun String.isTrailerUrl(): Boolean {
    return try {
        val host = URI(this).host.orEmpty()
        host.contains("youtube.com", ignoreCase = true) ||
            host.contains("youtu.be", ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}
