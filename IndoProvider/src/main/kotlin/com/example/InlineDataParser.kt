package com.example

import org.jsoup.parser.Parser

internal object InlineDataParser {
    private val urlRegex = Regex("""url:"(https?://[^"]+)"""")
    private val jsonLinkRegex = Regex(""""link":"(https?://[^"]+)"""")

    fun decodeEscapedInlineData(html: String): String {
        return Parser.unescapeEntities(html, true)
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "")
    }

    fun oploverzStreamUrls(html: String, episode: Int?): List<String> {
        val data = decodeEscapedInlineData(html)
        val blocks = if (episode != null) {
            val escapedEpisode = Regex.escape(episode.toString())
            Regex("""(?s)episodeNumber:"?$escapedEpisode"?.*?streamUrl:\[(.*?)],content:""")
                .findAll(data)
                .map { it.groupValues[1] }
                .toList()
        } else {
            Regex("""(?s)streamUrl:\[(.*?)],content:""")
                .findAll(data)
                .map { it.groupValues[1] }
                .take(1)
                .toList()
        }

        val sourceBlocks = blocks.ifEmpty {
            Regex("""(?s)streamUrl:\[(.*?)],content:""")
                .findAll(data)
                .map { it.groupValues[1] }
                .take(1)
                .toList()
        }

        return sourceBlocks
            .flatMap { block -> urlRegex.findAll(block).map { it.groupValues[1] } }
            .distinct()
    }

    fun miranimeSourceUrls(html: String): List<String> {
        val data = decodeEscapedInlineData(html)
        return Regex("""(?s)"sources":\[(.*?)]""")
            .findAll(data)
            .flatMap { match -> jsonLinkRegex.findAll(match.groupValues[1]).map { it.groupValues[1] } }
            .distinct()
            .toList()
    }
}
