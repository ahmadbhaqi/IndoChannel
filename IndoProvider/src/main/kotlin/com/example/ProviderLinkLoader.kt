package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.jsoup.Jsoup

internal typealias PlayerPageFetcher = suspend (url: String, referer: String?) -> String
internal typealias CloudstreamExtractorLoader = suspend (
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) -> Boolean
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
private const val CANDIDATE_TIMEOUT_MS = 25_000L
internal const val PROVIDER_HTTP_TIMEOUT_SECONDS = 25L
private const val SESSION_TIMEOUT_MS = 90_000L
private const val MAX_RESOLUTION_CANDIDATES = 48
private const val MAX_BYSE_API_RESPONSE_BYTES = 2_000_000
private const val MAX_JUSTPLAY_API_RESPONSE_BYTES = 2_000_000
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
    private val byseApiFetcher: PlayerPageFetcher = ::fetchBoundedByseApi,
    private val justPlayApiFetcher: JustPlayApiFetcher = ::fetchBoundedJustPlayApi,
    private val extractorLoader: CloudstreamExtractorLoader = ::loadExtractorWithResult,
    private val maxDepth: Int = 2,
    private val maxCandidates: Int = MAX_RESOLUTION_CANDIDATES,
    private val candidateTimeoutMs: Long = CANDIDATE_TIMEOUT_MS,
    sessionTimeoutMs: Long = SESSION_TIMEOUT_MS,
    private val playSobatUrlParser: PlaySobatUrlParser = InlineDataParser::playSobatUrls,
    private val playSobatMirrorTimeoutMs: Long = PLAY_SOBAT_MIRROR_TIMEOUT_MS,
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
    private val deadlineNanos = System.nanoTime() +
        sessionTimeoutMs.coerceIn(1L, 10 * 60_000L) * 1_000_000L

    val loaded: Boolean get() = emittedLinks.isNotEmpty()

    suspend fun resolve(raw: String?, referer: String?): Boolean {
        val before = emittedLinks.size
        val url = api.toPlayableUrl(raw)?.takeUnless { it.isTrailerUrl() } ?: return false
        val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        if (remainingMs == 0L) return false
        withTimeoutOrNull(minOf(candidateTimeoutMs.coerceAtLeast(1L), remainingMs)) {
            resolveCandidate(url, referer, genericDepth = 0)
        }
        return emittedLinks.size > before
    }

    private suspend fun resolveCandidate(url: String, referer: String?, genericDepth: Int) {
        if (genericDepth > maxDepth || System.nanoTime() >= deadlineNanos || !isSafeRemoteHttpUrl(url)) return
        val candidateKey = CandidateKey(url, referer.orEmpty())
        if (candidateKey !in visitedCandidates && visitedCandidates.size >= maxCandidates) return
        if (!visitedCandidates.add(candidateKey)) return

        try {
            var cachedHtml: String? = null
            directMediaType(url)?.let { type ->
                emitDirect(url, referer, type)
                return
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
                val beforeAdapter = emittedLinks.size
                FreeonPlayerParser.apiUrls(html, url).forEach { apiUrl ->
                    val response = try {
                        pageFetcher(apiUrl, url)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return@forEach
                    }
                    FreeonPlayerParser.sources(response).forEach { source ->
                        val type = if (source.mimeType.contains("mpegurl", ignoreCase = true) ||
                            source.url.contains(".m3u8", ignoreCase = true)
                        ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        emit(
                            directLinkFactory(
                                api.name,
                                "${api.name} ${source.label}",
                                source.url,
                                url,
                                Qualities.Unknown.value,
                                type,
                                mapOf("Referer" to url)
                            )
                        )
                    }
                }
                if (emittedLinks.size > beforeAdapter) return
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
                    resolveCandidate(nested, url, genericDepth)
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
                host == "abyss.to" || host.endsWith(".abyss.to")
            ) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                AbyssPlayerParser.sources(html).forEach { source ->
                    emit(
                        directLinkFactory(
                            api.name,
                            "${api.name} ${source.label}",
                            source.url,
                            url,
                            source.quality,
                            ExtractorLinkType.VIDEO,
                            mapOf("Referer" to url)
                        )
                    )
                }
                if (emittedLinks.size > beforeAdapter) return
                // Current Abyss pages without complete URL/path fields require a
                // browser-only WebSocket/service-worker pipeline. The generic
                // extractor cannot turn those pages into a playable media URL,
                // so let the parent mirror list advance immediately.
                return
            }

            if (host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedLinks.size
                JuicyCodesPlayerParser.playback(html)?.let { playback ->
                    playback.tracks.forEach { track ->
                        subtitleCallback(newSubtitleFile(track.label, track.url))
                    }
                    playback.media.forEach { media ->
                        emit(
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
                playback?.let { emitBysePlayback(it, url) }
                if (emittedLinks.size > beforeAdapter) return
            }

            if (host == "bysebuho.com" || host.endsWith(".bysebuho.com")) {
                val beforeAdapter = emittedLinks.size
                val apiUrl = BysePlayerParser.apiUrl(url)
                val apiJson = apiUrl?.let { endpoint ->
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
                        resolveCandidate(playable, url, genericDepth)
                    }
                }
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
            extractorLoader(url, referer, subtitleCallback, ::emit)
            if (emittedLinks.size > beforeExtractor || genericDepth >= maxDepth) return

            val html = cachedHtml ?: pageFetcher(url, referer)
            if (ProviderHtmlParser.isNonContentPage(html)) return
            val beforeJuicyCodes = emittedLinks.size
            JuicyCodesPlayerParser.playback(html)?.let { playback ->
                playback.tracks.forEach { track ->
                    subtitleCallback(newSubtitleFile(track.label, track.url))
                }
                playback.media.forEach { media ->
                    emit(
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
            val document = Jsoup.parse(html, url)
            ProviderHtmlParser.mediaSources(document).forEach { nested ->
                ProviderHtmlParser.absoluteUrl(nested, url)?.let {
                    resolveCandidate(it, url, genericDepth + 1)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Continue with sibling and later candidates.
        }
    }

    private suspend fun emitDirect(url: String, referer: String?, type: ExtractorLinkType) {
        if (!isSafeRemoteHttpUrl(url)) return
        emit(
            directLinkFactory(
                api.name,
                api.name,
                url,
                referer.orEmpty(),
                Qualities.Unknown.value,
                type,
                referer?.let { mapOf("Referer" to it) }.orEmpty()
            )
        )
    }

    private suspend fun emitBysePlayback(playback: BysePlayback, playerUrl: String) {
        val mediaHeaders = linkedMapOf("Referer" to playerUrl)
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
            emit(
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

    internal fun emitResolved(link: ExtractorLink) = emit(link)

    private fun emit(link: ExtractorLink) {
        if (link.url.isBlank() || !isSafeRemoteHttpUrl(link.url)) return
        val key = EmittedLinkKey(
            url = link.url,
            referer = link.referer.orEmpty(),
            type = link.type,
            headers = link.headers.toMap()
        )
        if (emittedLinks.add(key)) callback(link.withSimpleServerName(api.name))
    }
}

private suspend fun fetchBoundedByseApi(url: String, referer: String?): String {
    val response = app.get(url, referer = referer, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
    return readBoundedBody(response.body, MAX_BYSE_API_RESPONSE_BYTES)
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
    if (address is Inet6Address) return true
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
