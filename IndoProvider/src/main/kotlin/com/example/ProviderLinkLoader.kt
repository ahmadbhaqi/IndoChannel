package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

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

internal class LinkResolutionSession(
    private val api: MainAPI,
    private val subtitleCallback: (SubtitleFile) -> Unit,
    private val callback: (ExtractorLink) -> Unit,
    private val pageFetcher: PlayerPageFetcher = { url, referer -> app.get(url, referer = referer).text },
    private val extractorLoader: CloudstreamExtractorLoader = ::loadExtractorWithResult,
    private val maxDepth: Int = 1,
    private val directLinkFactory: DirectLinkFactory = { source, name, url, referer, quality, type, headers ->
        newExtractorLink(source, name, url, type) {
            this.referer = referer
            this.quality = quality
            this.headers = headers
        }
    }
) {
    private val visitedCandidates = mutableSetOf<String>()
    private val emittedUrls = mutableSetOf<String>()

    val loaded: Boolean get() = emittedUrls.isNotEmpty()

    suspend fun resolve(raw: String?, referer: String?): Boolean {
        val before = emittedUrls.size
        val url = api.toPlayableUrl(raw)?.takeUnless { it.isTrailerUrl() } ?: return false
        if (!visitedCandidates.add(url)) return false

        try {
            val mediaType = directMediaType(url)
            if (mediaType != null) {
                emitDirect(url, referer, mediaType)
            } else {
                extractorLoader(url, referer, subtitleCallback, ::emit)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Candidate failure is isolated; later candidates must still run.
        }
        return emittedUrls.size > before
    }

    private suspend fun emitDirect(url: String, referer: String?, type: ExtractorLinkType) {
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

    private fun emit(link: ExtractorLink) {
        if (link.url.isNotBlank() && emittedUrls.add(link.url)) callback(link)
    }
}

internal fun directMediaType(url: String): ExtractorLinkType? {
    val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrNull() ?: return null
    return when {
        path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
        path.endsWith(".mp4") -> ExtractorLinkType.VIDEO
        else -> null
    }
}

internal fun MainAPI.toPlayableUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf {
        it.isNotBlank() && !it.startsWith("javascript:", ignoreCase = true)
    } ?: return null

    return when {
        value.startsWith("//") -> httpsify(value)
        value.startsWith("http://") -> httpsify(value)
        value.startsWith("https://") -> value
        else -> fixUrl(value)
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
