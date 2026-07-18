package com.example

import org.jsoup.Jsoup

/** Extracts the real player URLs hidden in Kotakajaib's Base64 server buttons. */
internal object KotakDataFrameParser {
    private const val MAX_HTML_SIZE = 2_000_000
    private const val MAX_BUTTON_COUNT = 32
    private const val MAX_ENCODED_SIZE = 8_192
    private const val MAX_DECODED_SIZE = 4_096

    fun urls(html: String): List<String> {
        if (html.length > MAX_HTML_SIZE) return emptyList()

        return runCatching {
            Jsoup.parse(html)
                .select("button.server-item[data-frame]")
                .asSequence()
                .take(MAX_BUTTON_COUNT)
                .mapNotNull { button -> decodeUrl(button.attr("data-frame")) }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun decodeUrl(encoded: String): String? {
        val payload = encoded.trim()
        if (payload.isEmpty() || payload.length > MAX_ENCODED_SIZE) return null

        val decoded = decodeBase64Compat(payload)
            ?.takeIf { it.size <= MAX_DECODED_SIZE && it.all(::isPrintableAscii) }
            ?: return null
        val url = decoded.toString(Charsets.US_ASCII).trim()
        return url.takeIf(::isSafeRemoteHttpUrl)
    }

    private fun isPrintableAscii(byte: Byte): Boolean {
        return (byte.toInt() and 0xff) in 0x20..0x7e
    }
}

/** Extracts the concrete MP4 exposed by Emturbo/TurboVIP player pages. */
internal object TurboVipPlayerParser {
    private const val MAX_HTML_SIZE = 2_000_000
    private val urlPlay = Regex(
        """(?:var|let|const)\s+urlPlay\s*=\s*["'](https?://[^"'\\\s<>]+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val quotedMaster = Regex(
        """["'](https?://[^"'\\\s<>]+/[^"'\\\s<>]*master\.m3u8(?:\?[^"'\\\s<>]*)?)["']""",
        RegexOption.IGNORE_CASE
    )

    fun directUrl(html: String): String? {
        if (html.length > MAX_HTML_SIZE) return null
        val url = urlPlay.find(html)?.groupValues?.getOrNull(1)
            ?: quotedMaster.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val path = runCatching { java.net.URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return url.takeIf {
            (path.endsWith(".mp4") || path.endsWith(".m3u8")) && isSafeRemoteHttpUrl(it)
        }
    }
}
