package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.net.URLDecoder

internal data class JuicyCodesMedia(
    val url: String,
    val label: String,
    val quality: Int,
    val isHls: Boolean,
    val userAgent: String?
)

internal data class JuicyCodesTrack(
    val url: String,
    val label: String
)

internal data class JuicyCodesPlayback(
    val media: List<JuicyCodesMedia>,
    val tracks: List<JuicyCodesTrack>
)

/** Decodes the current JuicyCodes player used by KitaNonton's IP mirrors. */
internal object JuicyCodesPlayerParser {
    private const val MAX_HTML_SIZE = 2_000_000
    private const val MAX_PAYLOAD_SIZE = 750_000
    private const val MAX_DECODED_SIZE = 500_000
    private const val MAX_TOKEN_SIZE = 8_192
    private const val MAX_USER_AGENT_SIZE = 512
    private const val SYMBOLS = "`%-+*\$!_^="
    private val mapper = jacksonObjectMapper()
    private val callRegex = Regex("""(?s)_juicycodes\s*\((.*?)\)\s*;""")
    private val literalRegex = Regex("""["']([^"']*)["']""")
    private val configRegex = Regex(
        """(?s)\bvar\s+config\s*=\s*(\{.*?})\s*;\s*jwplayer\b"""
    )

    fun recognizes(html: String): Boolean {
        if (html.length > MAX_HTML_SIZE) return false
        val expression = callRegex.find(html)?.groupValues?.getOrNull(1) ?: return false
        return literalRegex.containsMatchIn(expression)
    }

    fun playback(html: String): JuicyCodesPlayback? {
        if (html.length > MAX_HTML_SIZE) return null
        val expression = callRegex.find(html)?.groupValues?.getOrNull(1) ?: return null
        val payload = literalRegex.findAll(expression).joinToString("") { it.groupValues[1] }
        if (payload.length !in 4..MAX_PAYLOAD_SIZE) return null
        val decoded = decode(payload) ?: return null
        val configJson = configRegex.find(decoded)?.groupValues?.getOrNull(1) ?: return null
        val config = runCatching { mapper.readTree(configJson) }.getOrNull() ?: return null

        val media = sourceNodes(config).mapNotNull(::media).distinctBy { it.url }
        val tracks = config.path("tracks")
            .takeIf(JsonNode::isArray)
            ?.mapNotNull { track ->
                val kind = track.path("kind").asText().lowercase()
                if (kind !in setOf("captions", "subtitle", "subtitles")) return@mapNotNull null
                val url = track.path("file").asText().trim()
                    .takeIf(::isSafeRemoteHttpUrl) ?: return@mapNotNull null
                JuicyCodesTrack(
                    url = url,
                    label = track.path("label").asText().trim().ifBlank { "Subtitle" }
                )
            }
            .orEmpty()
            .distinctBy { it.url }
        return JuicyCodesPlayback(media, tracks).takeIf { it.media.isNotEmpty() }
    }

    private fun decode(payload: String): String? = runCatching {
        val salt = payload.takeLast(3).map { it.code - 100 }.joinToString("").toInt()
        val symbols = decodeBase64Compat(payload.dropLast(3)) ?: return@runCatching null
        if (symbols.size > MAX_DECODED_SIZE * 4) return@runCatching null
        val digits = buildString(symbols.size) {
            symbols.forEach { byte ->
                val index = SYMBOLS.indexOf(byte.toInt().toChar())
                if (index < 0) return@runCatching null
                append(index)
            }
        }
        if (digits.length % 4 != 0 || digits.length / 4 > MAX_DECODED_SIZE) {
            return@runCatching null
        }
        buildString(digits.length / 4) {
            digits.chunked(4).forEach { chunk ->
                val code = chunk.toInt() % 1000 - salt
                if (code !in 0..0xffff) return@runCatching null
                append(code.toChar())
            }
        }
    }.getOrNull()

    private fun sourceNodes(config: JsonNode): List<JsonNode> {
        val sources = config.path("sources")
        return when {
            sources.isArray -> sources.toList()
            sources.isObject -> listOf(sources)
            config.path("file").isTextual -> listOf(config)
            else -> emptyList()
        }
    }

    private fun media(source: JsonNode): JuicyCodesMedia? {
        val url = source.path("file").asText().trim().takeIf(::isSafeRemoteHttpUrl) ?: return null
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (host == "groovy.monster" || host.endsWith(".groovy.monster")) return null
        val label = source.path("label").asText().trim().ifBlank { "JuicyCodes" }
        val type = source.path("type").asText().lowercase()
        return JuicyCodesMedia(
            url = url,
            label = label,
            quality = Regex("""\d{3,4}""").find(label)?.value?.toIntOrNull()
                ?: Qualities.Unknown.value,
            isHls = type.contains("mpegurl") || url.substringBefore('?').endsWith(".m3u8", true),
            userAgent = tokenUserAgent(url)
        )
    }

    private fun tokenUserAgent(url: String): String? = runCatching {
        val rawToken = URI(url).rawQuery
            ?.split('&')
            ?.firstNotNullOfOrNull { parameter ->
                val key = parameter.substringBefore('=', "")
                parameter.substringAfter('=', "").takeIf { key == "token" && it.isNotBlank() }
            }
            ?.takeIf { it.length <= MAX_TOKEN_SIZE }
            ?: return@runCatching null
        val tokenCandidates = listOf(
            rawToken,
            URLDecoder.decode(rawToken, Charsets.UTF_8.name())
        ).distinct()
        val decoded = tokenCandidates.firstNotNullOfOrNull(::decodeBase64Compat)
            ?.takeIf { it.size <= MAX_TOKEN_SIZE }
            ?: return@runCatching null
        val tokenValue = decoded.toString(Charsets.UTF_8)
        if ('\uFFFD' in tokenValue) return@runCatching null
        tokenValue.substringAfter("~~", "")
            .trim()
            .takeIf { userAgent ->
                userAgent.startsWith("Mozilla/5.0 ") &&
                    userAgent.length <= MAX_USER_AGENT_SIZE &&
                    userAgent.none(Char::isISOControl)
            }
    }.getOrNull()
}
