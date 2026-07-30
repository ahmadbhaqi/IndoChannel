package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI

/** Resolves the current DesuStream same-page JSON wrapper. */
internal object DesuStreamPlayerParser {
    private const val MAX_HTML_SIZE = 512_000
    private const val MAX_JSON_SIZE = 256_000
    private const val MAX_URL_SIZE = 8_192
    private val mapper = jacksonObjectMapper()
    private val fetchPattern = Regex(
        """fetch\s*\(\s*`\?mode=([A-Za-z0-9_-]+)&_=\${'$'}\{Date\.now\(\)\}`"""
    )

    fun supports(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("www.")
        return normalized == "desustream.info" ||
            normalized.endsWith(".desustream.info") ||
            normalized == "desustream.me" ||
            normalized.endsWith(".desustream.me")
    }

    fun apiUrl(
        html: String,
        playerUrl: String,
        epochMillis: Long = System.currentTimeMillis()
    ): String? {
        if (html.isBlank() || html.length > MAX_HTML_SIZE || epochMillis < 0L) return null
        val mode = fetchPattern.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.length <= 64 }
            ?: return null
        return runCatching {
            val page = URI(playerUrl)
            if (
                page.scheme?.lowercase() !in setOf("http", "https") ||
                page.host.isNullOrBlank() ||
                page.userInfo != null
            ) return@runCatching null
            URI(
                page.scheme,
                null,
                page.host,
                page.port,
                page.rawPath,
                "mode=$mode&_=$epochMillis",
                null
            ).toASCIIString().takeIf(::isSafeRemoteHttpUrl)
        }.getOrNull()
    }

    fun videoUrl(json: String, playerUrl: String): String? {
        if (json.isBlank() || json.length > MAX_JSON_SIZE) return null
        return runCatching {
            val raw = mapper.readTree(json)
                ?.path("video")
                ?.asText()
                ?.trim()
                ?.takeIf {
                    it.length in 1..MAX_URL_SIZE &&
                        !it.endsWith("token=", ignoreCase = true)
                }
                ?: return@runCatching null
            ProviderHtmlParser.absoluteUrl(raw, playerUrl)
                ?.takeIf(::isSafeRemoteHttpUrl)
        }.getOrNull()
    }
}
