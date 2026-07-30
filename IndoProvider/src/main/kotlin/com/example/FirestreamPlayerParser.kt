package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import org.jsoup.Jsoup

internal data class FirestreamResolveRequest(
    val apiUrl: String,
    val playerUrl: String,
    val headers: Map<String, String>,
    val body: String
)

internal typealias FirestreamApiFetcher = suspend (request: FirestreamResolveRequest) -> String

/** Resolves Firestream's rotating token blob without executing its browser bundle. */
internal object FirestreamPlayerParser {
    private const val MAX_URL_SIZE = 8_192
    private const val MAX_HTML_SIZE = 2_000_000
    private const val MAX_TOKEN_SIZE = 8_192
    private const val MAX_TOKEN_BYTES = 4_096
    private const val MAX_JSON_SIZE = 256_000
    private val slugPattern = Regex("[A-Za-z0-9_-]{2,128}")
    private val tokenPattern = Regex("[A-Za-z0-9+/]+={0,2}")
    private val mapper = jacksonObjectMapper()

    fun supports(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        return normalized == "firestream.to" || normalized.endsWith(".firestream.to")
    }

    fun resolveRequest(html: String, playerUrl: String): FirestreamResolveRequest? = runCatching {
        if (html.length !in 1..MAX_HTML_SIZE || playerUrl.length !in 8..MAX_URL_SIZE) {
            return@runCatching null
        }
        val player = URI(playerUrl)
        val scheme = player.scheme?.lowercase().takeIf { it == "https" }
            ?: return@runCatching null
        val host = player.host?.lowercase()?.trimEnd('.')
            ?.takeIf(::supports)
            ?: return@runCatching null
        if (player.userInfo != null || player.port != -1) return@runCatching null
        val path = player.rawPath.orEmpty().trimEnd('/')
        val slug = Regex("^/e/([A-Za-z0-9_-]{2,128})$")
            .matchEntire(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(slugPattern::matches)
            ?: return@runCatching null
        val tokenElement = Jsoup.parse(html, playerUrl).selectFirst("script#token-blob")
            ?: return@runCatching null
        val token = tokenElement.data().trim()
            .takeIf { value ->
                value.length in 16..MAX_TOKEN_SIZE &&
                    value.length % 4 == 0 &&
                    tokenPattern.matches(value)
            }
            ?: return@runCatching null
        val decodedSize = decodeBase64Compat(token)?.size
            ?: return@runCatching null
        if (decodedSize !in 16..MAX_TOKEN_BYTES) return@runCatching null

        val origin = URI(scheme, null, host, -1, null, null, null)
            .toString()
            .trimEnd('/')
        FirestreamResolveRequest(
            apiUrl = "$origin/api/videos/$slug/resolve",
            playerUrl = playerUrl,
            headers = linkedMapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json;charset=UTF-8",
                "Origin" to origin,
                "Referer" to playerUrl
            ),
            body = mapper.writeValueAsString(mapOf("blob" to token))
        )
    }.getOrNull()

    fun signedVideoUrl(json: String): String? {
        if (json.length !in 2..MAX_JSON_SIZE || !json.trimStart().startsWith('{')) return null
        val root = runCatching { mapper.readTree(json) }.getOrNull()
            ?.takeIf(JsonNode::isObject)
            ?: return null
        val raw = root.path("signedVideoUrl")
            .takeIf(JsonNode::isTextual)
            ?.asText()
            ?.trim()
            ?.takeIf { it.length in 8..MAX_URL_SIZE }
            ?: return null
        return runCatching {
            if (!isSafeRemoteHttpUrl(raw)) return@runCatching null
            val uri = URI(raw)
            if (
                !uri.scheme.equals("https", ignoreCase = true) ||
                uri.userInfo != null ||
                uri.port != -1 ||
                !supports(uri.host.orEmpty()) ||
                (
                    !uri.path.orEmpty().endsWith(".mp4", ignoreCase = true) &&
                        !uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
                    )
            ) return@runCatching null
            val signedParameters = uri.rawQuery.orEmpty().split('&').associate { parameter ->
                parameter.substringBefore('=') to parameter.substringAfter('=', "")
            }
            raw.takeIf {
                signedParameters["md5"].orEmpty().isNotBlank() &&
                    signedParameters["expires"].orEmpty().isNotBlank()
            }
        }.getOrNull()
    }
}
