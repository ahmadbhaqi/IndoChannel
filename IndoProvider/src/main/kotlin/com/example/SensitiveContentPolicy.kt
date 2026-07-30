package com.example

import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * Conservative, provider-independent explicit-content gate.
 *
 * Ambiguous words are intentionally not title signals. Strong taxonomy
 * metadata may block an item by itself, while title/slug filtering is limited
 * to explicit terms with narrow exceptions for known non-explicit titles.
 */
internal object SensitiveContentPolicy {
    private val explicitCategoryPhrases = listOf(
        Regex("""\bfilm\s+semi\b"""),
        Regex("""\bsemi\s+(?:filipina|jepang|korea|barat|thailand)\b"""),
        Regex("""\bjapanese\s+av\b""")
    )
    private val explicitCategoryToken = Regex(
        """\b(?:nsfw|hentai|smut|erotic|erotica|erotis|bokep|porn|porno|pornografi|pornography|pornographic|sex|jav)\b"""
    )
    private val adultCategoryToken = Regex("""\badult\b""")
    private val allowedAdultCategoryPhrases = listOf(
        Regex("""\byoung\s+adult\b"""),
        Regex("""\badult\s+cast\b"""),
        Regex("""\badult\s+life\b"""),
        Regex("""\badult\s+beginners?\b"""),
        Regex("""\badult\s+animation\b"""),
        Regex("""\badult\s+comedy\b"""),
        Regex("""\badult\s+swim\b""")
    )
    private val explicitTitleOrSlug = Regex(
        """\b(?:nsfw|hentai|smut|erotic|erotica|erotis|jav|bokep|porn|pornografi|porno|sange|sangean|sangenya|tobrut|ngewe|ngentot)\b"""
    )
    private val allowedExplicitWordTitlePhrases = listOf(
        Regex("""\bthe\s+hentai\s+prince(?:\s+and\s+the\s+stony\s+cat)?\b"""),
        Regex("""\bhentai\s+ouji\s+to\s+warawanai\s+neko\b""")
    )

    fun isBlocked(
        title: String?,
        url: String?,
        categories: Iterable<String> = emptyList()
    ): Boolean {
        if (categories.any { category ->
                val normalized = normalize(category)
                explicitCategoryToken.containsMatchIn(normalized) ||
                    containsBlockedAdultCategory(normalized) ||
                    explicitCategoryPhrases.any { it.containsMatchIn(normalized) }
            }
        ) {
            return true
        }
        return sequenceOf(title, decodeUrl(url))
            .filterNotNull()
            .map(::normalize)
            .map(::removeAllowedExplicitWordTitlePhrases)
            .any { normalized ->
                explicitTitleOrSlug.containsMatchIn(normalized) ||
                    explicitCategoryPhrases.any { it.containsMatchIn(normalized) }
            }
    }

    private fun containsBlockedAdultCategory(value: String): Boolean {
        val withoutAllowedPhrases = allowedAdultCategoryPhrases.fold(value) { filtered, allowedPhrase ->
            allowedPhrase.replace(filtered, " ")
        }
        return adultCategoryToken.containsMatchIn(withoutAllowedPhrases)
    }

    private fun removeAllowedExplicitWordTitlePhrases(value: String): String =
        allowedExplicitWordTitlePhrases.fold(value) { filtered, allowedPhrase ->
            allowedPhrase.replace(filtered, " ")
        }

    private fun decodeUrl(value: String?): String? = value?.let {
        runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it)
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9+]+"""), " ")
        .trim()
}

internal data class AnimePlaybackData(
    val url: String,
    val title: String?,
    val categories: List<String>,
    val detailUrl: String?
)

internal object AnimePlaybackDataCodec {
    private const val PREFIX = "indochannel-anime:v1?"
    private const val MAX_PAYLOAD_LENGTH = 16_384
    private const val MAX_URL_LENGTH = 4_096
    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_CATEGORY_LENGTH = 256
    private const val MAX_CATEGORIES = 32

    fun encode(
        url: String,
        title: String?,
        categories: Iterable<String>,
        detailUrl: String?
    ): String {
        val encodedUrl = encodeRequiredPart(
            value = url.take(MAX_URL_LENGTH),
            maxEncodedLength = MAX_PAYLOAD_LENGTH - PREFIX.length - "u=".length
        )
        val fields = mutableListOf("u=$encodedUrl")
        var payloadLength = PREFIX.length + fields.single().length

        fun appendIfFits(field: String) {
            val addedLength = 1 + field.length
            if (payloadLength + addedLength <= MAX_PAYLOAD_LENGTH) {
                fields += field
                payloadLength += addedLength
            }
        }

        title?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            appendIfFits("t=${encodePart(value.take(MAX_TITLE_LENGTH))}")
        }
        categories.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_CATEGORIES)
            .forEach { category ->
                appendIfFits("c=${encodePart(category.take(MAX_CATEGORY_LENGTH))}")
            }
        detailUrl?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            appendIfFits("d=${encodePart(value.take(MAX_URL_LENGTH))}")
        }
        return PREFIX + fields.joinToString("&")
    }

    fun decode(value: String): AnimePlaybackData? {
        if (!value.startsWith(PREFIX) || value.length > MAX_PAYLOAD_LENGTH) return null
        return runCatching {
            val fields = value.removePrefix(PREFIX)
                .split('&')
                .mapNotNull { field ->
                    val separator = field.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    field.substring(0, separator) to decodePart(field.substring(separator + 1))
                }
            val url = fields.firstOrNull { it.first == "u" }?.second
                ?.takeIf { it.isNotBlank() && it.length <= MAX_URL_LENGTH }
                ?: return@runCatching null
            val title = fields.firstOrNull { it.first == "t" }?.second
                ?.takeIf { it.isNotBlank() && it.length <= MAX_TITLE_LENGTH }
            val categories = fields.asSequence()
                .filter { it.first == "c" }
                .map { it.second }
                .filter { it.isNotBlank() && it.length <= MAX_CATEGORY_LENGTH }
                .distinct()
                .take(MAX_CATEGORIES)
                .toList()
            val detailUrl = fields.firstOrNull { it.first == "d" }?.second
                ?.takeIf { it.isNotBlank() && it.length <= MAX_URL_LENGTH }
            AnimePlaybackData(url, title, categories, detailUrl)
        }.getOrNull()
    }

    fun isBlocked(value: String): Boolean = decode(value)?.let { payload ->
        SensitiveContentPolicy.isBlocked(
            title = payload.title,
            url = payload.url,
            categories = payload.categories
        )
    } == true

    private fun encodePart(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun encodeRequiredPart(value: String, maxEncodedLength: Int): String {
        var low = 0
        var high = value.length
        var best = ""
        while (low <= high) {
            val midpoint = (low + high) ushr 1
            val candidate = encodePart(value.take(midpoint))
            if (candidate.length <= maxEncodedLength) {
                best = candidate
                low = midpoint + 1
            } else {
                high = midpoint - 1
            }
        }
        return best
    }

    private fun decodePart(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
