package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import org.jsoup.Jsoup

/**
 * Parses VOE's current redirect shell and packed JSON configuration.
 *
 * The ordinary `var source = ...` assignment on the target page is a decoy.
 * Only the bounded `script[type=application/json]` payload is accepted.
 */
internal object VoePlayerParser {
    private const val MAX_HTML_SIZE = 1_000_000
    private const val MAX_PACKED_SIZE = 64_000
    private const val MAX_STAGE_SIZE = 512_000
    private const val MAX_CONFIG_SIZE = 256_000
    private const val MAX_URL_SIZE = 8_192
    private const val MAX_CAPTION_COUNT = 100
    private const val MAX_METADATA_SIZE = 1_024
    private val mapper = jacksonObjectMapper()
    private val junkTokens = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
    private val redirectPattern = Regex(
        """(?is)(?:window\s*\.\s*)?location(?:\s*\.\s*href)?\s*=\s*(['"])(.*?)\1"""
    )

    fun supports(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("www.")
        return normalized == "voe.sx" || normalized.endsWith(".voe.sx")
    }

    fun redirectTarget(html: String, pageUrl: String): String? {
        if (html.isBlank() || html.length > MAX_HTML_SIZE) return null
        val raw = redirectPattern.find(html)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.length in 1..MAX_URL_SIZE }
            ?: return null
        return ProviderHtmlParser.absoluteUrl(raw, pageUrl)
            ?.takeIf(::isSafeRemoteHttpUrl)
    }

    fun playback(html: String, playerUrl: String): BysePlayback? {
        if (html.isBlank() || html.length > MAX_HTML_SIZE) return null
        val document = Jsoup.parse(html, playerUrl)
        return document.select("script[type=application/json]")
            .asSequence()
            .mapNotNull { script ->
                val json = script.data().ifBlank { script.html() }.trim()
                    .takeIf { it.length in 1..MAX_PACKED_SIZE }
                    ?: return@mapNotNull null
                packedValue(json)?.let(::decodePacked)
            }
            .mapNotNull { config -> parsePlayback(config, playerUrl) }
            .firstOrNull()
    }

    private fun packedValue(scriptJson: String): String? = runCatching {
        val node = mapper.readTree(scriptJson)
        node.takeIf { it.isArray && it.size() in 1..8 }
            ?.firstOrNull()
            ?.takeIf(JsonNode::isTextual)
            ?.asText()
            ?.takeIf { it.length in 1..MAX_PACKED_SIZE }
    }.getOrNull()

    private fun decodePacked(raw: String): JsonNode? = runCatching {
        val substituted = junkTokens.fold(rot13(raw)) { value, token ->
            value.replace(token, "_")
        }
        val cleaned = substituted.replace("_", "")
            .takeIf { it.length in 1..MAX_STAGE_SIZE }
            ?: return@runCatching null
        val shiftedBytes = decodeBase64Compat(cleaned)
            ?.takeIf { it.size <= MAX_STAGE_SIZE }
            ?: return@runCatching null
        val shifted = String(shiftedBytes, Charsets.ISO_8859_1)
        if (shifted.any { it.code < 3 }) return@runCatching null

        val innerBase64 = buildString(shifted.length) {
            for (index in shifted.indices.reversed()) {
                append((shifted[index].code - 3).toChar())
            }
        }
        val configBytes = decodeBase64Compat(innerBase64)
            ?.takeIf { it.size <= MAX_CONFIG_SIZE }
            ?: return@runCatching null
        mapper.readTree(configBytes)?.takeIf(JsonNode::isObject)
    }.getOrNull()

    private fun parsePlayback(config: JsonNode, playerUrl: String): BysePlayback? {
        val sourceNodes = listOf(
            config.path("source").asText() to "VOE HLS",
            config.path("direct_access_url").asText() to "VOE Direct"
        )
        val sources = sourceNodes.mapNotNull { (raw, label) ->
            val url = absoluteHttpUrl(raw, playerUrl) ?: return@mapNotNull null
            val isHls = runCatching {
                URI(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true)
            }.getOrDefault(false)
            ByseMediaSource(
                url = url,
                mimeType = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4",
                label = label,
                quality = label,
                height = null
            )
        }.distinctBy(ByseMediaSource::url)
        if (sources.isEmpty()) return null

        val captions = config.path("captions")
        val tracks = if (captions.isArray && captions.size() <= MAX_CAPTION_COUNT) {
            captions.mapNotNull { caption ->
                val url = absoluteHttpUrl(caption.path("file").asText(), playerUrl)
                    ?: return@mapNotNull null
                val label = metadata(caption.path("label").asText()) ?: "Subtitle"
                ByseTrack(
                    url = url,
                    kind = metadata(caption.path("kind").asText()) ?: "captions",
                    label = label,
                    language = metadata(
                        caption.path("language").asText()
                            .ifBlank { caption.path("srclang").asText() }
                    ) ?: label
                )
            }.distinctBy(ByseTrack::url)
        } else {
            emptyList()
        }
        return BysePlayback(sources, tracks)
    }

    private fun absoluteHttpUrl(raw: String?, pageUrl: String): String? {
        val value = raw?.trim()
            ?.takeIf { it.length in 1..MAX_URL_SIZE }
            ?: return null
        return ProviderHtmlParser.absoluteUrl(value, pageUrl)
            ?.takeIf(::isSafeRemoteHttpUrl)
    }

    private fun metadata(value: String): String? =
        value.trim().takeIf { it.isNotEmpty() && it.length <= MAX_METADATA_SIZE }

    private fun rot13(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    in 'A'..'Z' -> 'A' + ((char - 'A' + 13) % 26)
                    in 'a'..'z' -> 'a' + ((char - 'a' + 13) % 26)
                    else -> char
                }
            )
        }
    }
}
