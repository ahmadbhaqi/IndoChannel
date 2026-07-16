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
        resolveCandidate(url, referer, genericDepth = 0)
        return emittedUrls.size > before
    }

    private suspend fun resolveCandidate(url: String, referer: String?, genericDepth: Int) {
        if (genericDepth > maxDepth || !visitedCandidates.add(url)) return

        try {
            var cachedHtml: String? = null
            directMediaType(url)?.let { type ->
                emitDirect(url, referer, type)
                return
            }

            val host = URI(url).host.orEmpty().lowercase()
            if (host == "playsobat.xyz" || host.endsWith(".playsobat.xyz")) {
                val html = pageFetcher(url, referer)
                cachedHtml = html
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedUrls.size
                InlineDataParser.playSobatUrls(html).forEach { nested ->
                    api.toPlayableUrl(nested)?.let { resolveCandidate(it, url, genericDepth) }
                }
                if (emittedUrls.size > beforeAdapter) return
            }

            if (host == "asiastream.cc" || host.endsWith(".asiastream.cc")) {
                val html = cachedHtml ?: pageFetcher(url, referer).also { cachedHtml = it }
                if (ProviderHtmlParser.isNonContentPage(html)) return
                val beforeAdapter = emittedUrls.size
                InlineDataParser.asiaStreamMasterUrl(html, url)?.let { master ->
                    emitDirect(master, url, ExtractorLinkType.M3U8)
                }
                if (emittedUrls.size > beforeAdapter) return
            }

            val beforeExtractor = emittedUrls.size
            extractorLoader(url, referer, subtitleCallback, ::emit)
            if (emittedUrls.size > beforeExtractor || genericDepth >= maxDepth) return

            val html = cachedHtml ?: pageFetcher(url, referer)
            if (ProviderHtmlParser.isNonContentPage(html)) return
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

    return when {
        value.startsWith("//") -> httpsify(value)
        explicitScheme != null -> value.replaceFirst(Regex("""^[A-Za-z][A-Za-z0-9+.-]*:"""), "https:")
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
