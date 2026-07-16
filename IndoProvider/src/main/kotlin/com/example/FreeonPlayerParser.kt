package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI

internal data class FreeonMediaSource(
    val label: String,
    val url: String,
    val mimeType: String
)

/** Parser for the high-Unicode Dean Edwards variant used by Freeon players. */
internal object FreeonPlayerParser {
    private const val PACKER_MARKER = "eval(function(p,a,c,k,e,d)"
    private const val MAX_DICTIONARY_SIZE = 10_000
    private const val MAX_PACKED_INPUT_SIZE = 2_000_000
    private const val MAX_UNPACKED_OUTPUT_SIZE = 4_000_000
    private val mapper = jacksonObjectMapper()

    fun apiUrls(html: String, playerUrl: String): List<String> {
        return unpackedScripts(html).flatMap { script ->
            Regex(
                "(?i)url\\s*[:=]\\s*[\\\"']((?:https?:)?//(?:[^/\\\"']+\\.)?freeon\\.site/api/\\?[^\\\"']+)[\\\"']"
            ).findAll(script).mapNotNull { match ->
                absoluteHttpUrl(match.groupValues[1], playerUrl)
            }.toList()
        }.distinct()
    }

    fun sources(json: String): List<FreeonMediaSource> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!root.path("status").asText().equals("ok", ignoreCase = true)) return emptyList()
        return root.path("sources")
            .takeIf { it.isArray }
            ?.mapNotNull { source ->
                val url = source.path("file").asText().trim()
                    .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?: return@mapNotNull null
                FreeonMediaSource(
                    label = source.path("label").asText().trim().ifBlank { "Video" },
                    url = url,
                    mimeType = source.path("type").asText().trim()
                )
            }
            .orEmpty()
            .distinctBy { it.url }
    }

    fun unpackedScripts(html: String): List<String> {
        if (html.length > MAX_PACKED_INPUT_SIZE) return emptyList()
        val output = mutableListOf<String>()
        var cursor = 0
        while (cursor < html.length) {
            val start = html.indexOf(PACKER_MARKER, cursor)
            if (start < 0) break
            unpackUnicodePacker(html, start)?.let(output::add)
            cursor = start + PACKER_MARKER.length
        }
        return output
    }

    internal fun unpackUnicodePacker(script: String, start: Int = script.indexOf(PACKER_MARKER)): String? {
        if (start < 0 || script.length > MAX_PACKED_INPUT_SIZE) return null
        val payloadMarker = script.indexOf("}('", start)
        if (payloadMarker < 0 || payloadMarker - start > 2_000) return null
        val header = script.substring(start, payloadMarker)
        val offset = Regex("fromCharCode\\(c%a\\+(\\d+)\\)")
            .find(header)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 0..65_535 }
            ?: return null

        val payload = readSingleQuoted(script, payloadMarker + 3) ?: return null
        val argumentsStart = payload.endExclusive
        val arguments = Regex("^\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'")
            .find(script.substring(argumentsStart, (argumentsStart + 100).coerceAtMost(script.length)))
            ?: return null
        val radix = arguments.groupValues[1].toIntOrNull()
            ?.takeIf { it in 2..65_535 && offset + it <= 65_536 }
            ?: return null
        val count = arguments.groupValues[2].toIntOrNull()
            ?.takeIf { it in 0..MAX_DICTIONARY_SIZE }
            ?: return null
        val dictionary = readSingleQuoted(script, argumentsStart + arguments.range.last + 1) ?: return null
        val words = dictionary.value.split('|')
        return unpackTokens(payload.value, words, count, radix, offset)?.decodeJsString()
    }

    private data class QuotedValue(val value: String, val endExclusive: Int)

    private fun readSingleQuoted(input: String, contentStart: Int): QuotedValue? {
        if (contentStart !in 0..input.length) return null
        val value = StringBuilder()
        var escaped = false
        for (index in contentStart until input.length) {
            if (value.length > MAX_PACKED_INPUT_SIZE) return null
            val char = input[index]
            when {
                escaped -> {
                    value.append('\\').append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == '\'' -> return QuotedValue(value.toString(), index + 1)
                else -> value.append(char)
            }
        }
        return null
    }

    private fun unpackTokens(
        payload: String,
        words: List<String>,
        count: Int,
        radix: Int,
        offset: Int
    ): String? {
        if (payload.length > MAX_PACKED_INPUT_SIZE) return null
        val output = StringBuilder(payload.length.coerceAtMost(MAX_UNPACKED_OUTPUT_SIZE))
        val upperExclusive = offset + radix
        var cursor = 0
        while (cursor < payload.length) {
            val code = payload[cursor].code
            if (code !in offset until upperExclusive) {
                output.append(payload[cursor++])
            } else {
                val tokenStart = cursor
                var value = 0L
                while (cursor < payload.length) {
                    val digit = payload[cursor].code - offset
                    if (digit !in 0 until radix) break
                    value = (value * radix + digit).coerceAtMost(Int.MAX_VALUE.toLong() + 1)
                    cursor++
                }
                val replacement = value
                    .takeIf { it in 0 until count.toLong() }
                    ?.toInt()
                    ?.let { words.getOrNull(it) }
                    .orEmpty()
                if (replacement.isNotEmpty()) {
                    output.append(replacement)
                } else {
                    output.append(payload, tokenStart, cursor)
                }
            }
            if (output.length > MAX_UNPACKED_OUTPUT_SIZE) return null
        }
        return output.toString()
    }

    private fun String.decodeJsString(): String = replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\\\", "\\")

    private fun absoluteHttpUrl(raw: String, baseUrl: String): String? {
        return runCatching {
            val normalized = if (raw.startsWith("//")) "https:$raw" else raw
            val resolved = URI(baseUrl).resolve(normalized).toString()
            resolved.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }.getOrNull()
    }
}
