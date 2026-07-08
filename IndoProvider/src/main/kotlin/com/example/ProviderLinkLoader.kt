package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI

internal fun MainAPI.toPlayableUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf {
        it.isNotBlank() && !it.startsWith("javascript:", ignoreCase = true)
    } ?: return null

    return when {
        value.startsWith("//") -> httpsify(value)
        value.startsWith("http://") || value.startsWith("https://") -> httpsify(value)
        else -> fixUrl(value)
    }
}

internal suspend fun MainAPI.loadResolvedExtractorWithResult(
    raw: String?,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val url = toPlayableUrl(raw)?.takeUnless { it.isTrailerUrl() } ?: return false
    return if (url.contains("playsobat.", ignoreCase = true)) {
        try {
            val nested = InlineDataParser.playSobatUrls(app.get(url, referer = referer).text)
            nested.fold(false) { loaded, source ->
                loadResolvedExtractorWithResult(source, url, subtitleCallback, callback) || loaded
            }
        } catch (_: Exception) {
            false
        }
    } else {
        loadExtractorWithResult(url, referer, subtitleCallback, callback)
    }
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
