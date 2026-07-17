package com.example

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal object MovieMetadataParser {
    private val windows1252Bytes = mapOf(
        '\u20AC' to 0x80,
        '\u201A' to 0x82,
        '\u0192' to 0x83,
        '\u201E' to 0x84,
        '\u2026' to 0x85,
        '\u2020' to 0x86,
        '\u2021' to 0x87,
        '\u02C6' to 0x88,
        '\u2030' to 0x89,
        '\u0160' to 0x8A,
        '\u2039' to 0x8B,
        '\u0152' to 0x8C,
        '\u017D' to 0x8E,
        '\u2018' to 0x91,
        '\u2019' to 0x92,
        '\u201C' to 0x93,
        '\u201D' to 0x94,
        '\u2022' to 0x95,
        '\u2013' to 0x96,
        '\u2014' to 0x97,
        '\u02DC' to 0x98,
        '\u2122' to 0x99,
        '\u0161' to 0x9A,
        '\u203A' to 0x9B,
        '\u0153' to 0x9C,
        '\u017E' to 0x9E,
        '\u0178' to 0x9F
    )

    private val defaultDirectSynopsisSelectors = listOf(
        "[itemprop=reviewBody] p",
        ".synopsis p",
        ".synopsis",
        ".sinopsis p",
        ".sinopsis",
        ".sinopsis-indo p",
        ".sinopsis-indo",
        ".entry-synopsis p",
        ".entry-synopsis",
        ".detail-synopsis p",
        ".detail-synopsis",
        "[itemprop=description] > p"
    )

    private val defaultMetaDescriptionSelectors = listOf(
        "meta[property=og:description]",
        "meta[name=description]"
    )

    private val defaultTaglineSelectors = listOf(
        "[itemprop=alternativeHeadline]",
        ".tagline",
        ".mvic-tagline",
        ".gmr-tagline"
    )

    private val boilerplateMarkers = listOf(
        "website streaming film terlengkap",
        "nonton film sub indo dan download streaming",
        "nonton streamin gratis sub indo",
        "situs penyedia jasa streaming",
        "kami menyediakan berbagai macam film",
        "tanpa harus registrasi",
        "bioskop online cinema xxi",
        "tips nonton film",
        "info film ini di ambil dari imdb",
        "streaming film terbaik hanya di",
        "lk21 layarkaca21",
        "juraganfilm21",
        "youtube downloader untuk download video"
    )

    fun title(raw: String?): String? {
        var value = normalize(raw) ?: return null
        value = value
            .replace(
                Regex(
                    "^(?:(?:LK21|LAYARKACA(?:21)?|REBAHIN(?:XXI)?|INDOXXI|INDOFILM|FILMAPIK)\\s*[-:|\\u2013\\u2014]?\\s*)+",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    "^(?:Nonton\\s+(?:Film|Streaming(?:\\s+Film)?)|Streaming\\s+Film)\\s*[-:|\\u2013\\u2014]?\\s*",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex("\\s+(?:Subtitle\\s+Indonesia|Sub\\s*Indo)\\b.*$", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(
                Regex(
                    "\\s*(?:[|\\u2013\\u2014-]\\s*)?(?:LK21|LAYARKACA(?:21)?|KITA\\s*NONTON|FILMAPIK|INDOXXI|INDOFILM|REBAHIN(?:XXI)?|JURAGANFILM|CGVINDO)\\b.*$",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':', '\u2013', '\u2014')

        return value.takeIf { it.isNotBlank() }
    }

    fun synopsis(
        document: Document,
        directSelectors: List<String> = defaultDirectSynopsisSelectors,
        includeMetaDescription: Boolean = true,
        taglineSelectors: List<String> = defaultTaglineSelectors
    ): String? {
        findMeaningful(document, directSelectors)?.let { return it }
        if (includeMetaDescription) {
            findMeaningful(document, defaultMetaDescriptionSelectors)?.let { return it }
        }
        return findMeaningful(document, taglineSelectors)
    }

    fun meaningfulDescription(raw: String?): String? {
        val value = normalize(raw)
            ?.replace(
                Regex("^Sinopsis(?:\\s+Indonesia)?\\s*[:\\-\\u2013\\u2014]?\\s*", RegexOption.IGNORE_CASE),
                ""
            )
            ?.trim()
            ?.takeIf { it.length >= 5 }
            ?: return null
        val lower = value.lowercase()
        if (lower in setOf("n/a", "tidak ada sinopsis", "no overview found", "coming soon")) return null
        if (boilerplateMarkers.any(lower::contains)) return null
        return value
    }

    fun repairMojibake(raw: String): String {
        var value = raw
        repeat(2) {
            val score = mojibakeScore(value)
            if (score == 0) return value
            val decoded = repairMojibakePass(value)
            if (decoded == value || mojibakeScore(decoded) >= score) return value
            value = decoded
        }
        return value
    }

    private fun repairMojibakePass(value: String): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val byteCount = when (value[index].code) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> 0
            }
            if (byteCount > 0 && index + byteCount <= value.length) {
                val segment = value.substring(index, index + byteCount)
                val bytes = segment.mapNotNull(::mojibakeByte)
                if (bytes.size == byteCount) {
                    val decoded = bytes.toByteArray().toString(Charsets.UTF_8)
                    if ('\uFFFD' !in decoded && mojibakeScore(decoded) < mojibakeScore(segment)) {
                        output.append(decoded)
                        index += byteCount
                        continue
                    }
                }
            }
            output.append(value[index])
            index += 1
        }
        return output.toString()
    }

    private fun mojibakeByte(character: Char): Byte? {
        val value = windows1252Bytes[character] ?: character.code.takeIf { it in 0..0xFF }
        return value?.toByte()
    }

    private fun findMeaningful(document: Document, selectors: List<String>): String? {
        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                meaningfulDescription(element.metadataValue())?.let { return it }
            }
        }
        return null
    }

    private fun Element.metadataValue(): String? {
        val value = when {
            tagName().equals("meta", ignoreCase = true) -> attr("content")
            hasAttr("content") && text().isBlank() -> attr("content")
            else -> text()
        }.takeIf { it.isNotBlank() } ?: return null

        if (!tagName().equals("p", ignoreCase = true)) return value
        val emphasizedPrefix = children().firstOrNull()
            ?.takeIf { child ->
                child.tagName().equals("strong", ignoreCase = true) ||
                    child.tagName().equals("b", ignoreCase = true)
            }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return value
        return value.removePrefix(emphasizedPrefix).trimStart(' ', ':', '-', '\u2013', '\u2014')
    }

    private fun normalize(raw: String?): String? {
        val decoded = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { Parser.unescapeEntities(it, false) }
            ?.let(::repairMojibake)
            ?: return null
        return decoded.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
    }

    private fun mojibakeScore(value: String): Int {
        return value.count { character ->
            character.code in setOf(0xC2, 0xC3, 0xC4, 0xC5, 0xE2, 0xF0) ||
                character.code in 0x80..0x9F
        }
    }
}
