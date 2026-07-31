package com.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.net.URLEncoder

internal data class InternetArchiveSearchCandidate(
    val identifier: String,
    val title: String,
    val year: Int?
)

internal data class InternetArchivePlaybackMedia(
    val mediaUrl: String,
    val itemUrl: String,
    val durationSeconds: Int
)

internal object InternetArchiveFallbackParser {
    private const val ORIGIN = "https://archive.org"
    private const val MIN_FULL_LENGTH_SECONDS = 15 * 60
    private const val MAX_FULL_LENGTH_SECONDS = 8 * 60 * 60
    private const val MIN_MEDIA_SIZE_BYTES = 10_000_000L
    private const val MAX_MEDIA_SIZE_BYTES = 50_000_000_000L
    private const val MAX_SEARCH_RESULTS = 20
    private val identifierPattern = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$""")
    private val metadataPathPattern =
        Regex("""^/metadata/([A-Za-z0-9][A-Za-z0-9._-]{0,127})$""")
    private val releaseYearPattern = Regex("""\b(?:19|20)\d{2}\b""")
    private val queryWhitespacePattern = Regex("""\s+""")
    private val queryPunctuationPattern = Regex("""[^\p{L}\p{N}]+""")

    val requestHeaders = mapOf(
        "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.8",
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 Chrome/136 Safari/537.36"
    )

    fun searchUrl(request: NomatFallbackRequest): String? {
        if (request.season != null || request.episode != null) return null
        val title = request.title
            .replace(queryPunctuationPattern, " ")
            .replace(queryWhitespacePattern, " ")
            .trim()
            .takeIf { it.isNotBlank() && it.length <= 256 }
            ?: return null
        val escapedTitle = title
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val query = """title:("$escapedTitle") AND mediatype:movies"""
        val fields = listOf("identifier", "title", "year", "date", "mediatype")
            .joinToString("&") { field ->
                "fl%5B%5D=${encodeQueryValue(field)}"
            }
        return "$ORIGIN/advancedsearch.php" +
            "?q=${encodeQueryValue(query)}" +
            "&$fields&rows=$MAX_SEARCH_RESULTS&page=1&output=json"
    }

    fun metadataUrl(identifier: String): String? =
        identifier.takeIf(identifierPattern::matches)?.let { "$ORIGIN/metadata/$it" }

    fun networkUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf {
            it.isNotBlank() &&
                it.length <= 8_192 &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
        } ?: return null
        return runCatching {
            val uri = URI(value)
            if (
                uri.scheme?.lowercase() != "https" ||
                uri.host?.lowercase()?.trimEnd('.') != "archive.org" ||
                uri.userInfo != null ||
                uri.port !in setOf(-1, 443) ||
                uri.rawFragment != null
            ) return@runCatching null
            val validPath =
                uri.path == "/advancedsearch.php" ||
                    metadataPathPattern.matches(uri.path.orEmpty())
            uri.toASCIIString().takeIf { validPath }
        }.getOrNull()
    }

    fun searchCandidates(
        response: InternetArchiveSearchResponse
    ): List<InternetArchiveSearchCandidate> =
        response.response?.docs.orEmpty()
            .asSequence()
            .filter { document ->
                document.mediatype.equals("movies", ignoreCase = true)
            }
            .mapNotNull { document ->
                val identifier = document.identifier
                    ?.trim()
                    ?.takeIf(identifierPattern::matches)
                    ?: return@mapNotNull null
                val title = scalarText(document.title)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it.length <= 512 }
                    ?: return@mapNotNull null
                InternetArchiveSearchCandidate(
                    identifier = identifier,
                    title = title,
                    year = releaseYear(document.year, document.date, title)
                )
            }
            .distinctBy { candidate -> candidate.identifier }
            .take(MAX_SEARCH_RESULTS)
            .toList()

    fun isExactCandidate(
        request: NomatFallbackRequest,
        candidate: InternetArchiveSearchCandidate
    ): Boolean =
        request.season == null &&
            request.episode == null &&
            NomatParser.isExactFallbackMatch(
                request = request,
                candidateTitle = candidate.title,
                candidateYear = candidate.year
            )

    fun playbackMedia(
        request: NomatFallbackRequest,
        candidate: InternetArchiveSearchCandidate,
        response: InternetArchiveMetadataResponse
    ): InternetArchivePlaybackMedia? {
        if (!isExactCandidate(request, candidate) || truthy(response.isDark)) return null
        val metadata = response.metadata ?: return null
        val identifier = metadata.identifier
            ?.trim()
            ?.takeIf(identifierPattern::matches)
            ?: return null
        if (identifier != candidate.identifier) return null
        if (!metadata.mediatype.equals("movies", ignoreCase = true)) return null
        val title = scalarText(metadata.title)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: return null
        val year = releaseYear(metadata.year, metadata.date, title)
        if (
            !NomatParser.isExactFallbackMatch(
                request = request,
                candidateTitle = title,
                candidateYear = year
            )
        ) return null

        val file = response.files.orEmpty()
            .asSequence()
            .filterNot { archiveFile -> truthy(archiveFile.isPrivate) }
            .mapNotNull { archiveFile ->
                val name = archiveFile.name
                    ?.trim()
                    ?.takeIf(::isSafeMp4Name)
                    ?: return@mapNotNull null
                val format = archiveFile.format.orEmpty().lowercase()
                if ("mpeg4" !in format && "h.264" !in format && "h264" !in format) {
                    return@mapNotNull null
                }
                val size = scalarLong(archiveFile.size)
                    ?.takeIf { it in MIN_MEDIA_SIZE_BYTES..MAX_MEDIA_SIZE_BYTES }
                    ?: return@mapNotNull null
                val duration = scalarDouble(archiveFile.length)
                    ?.takeIf {
                        it.isFinite() &&
                            it >= MIN_FULL_LENGTH_SECONDS &&
                            it <= MAX_FULL_LENGTH_SECONDS
                    }
                    ?: return@mapNotNull null
                ArchiveFileSelection(
                    name = name,
                    durationSeconds = duration.toInt(),
                    sizeBytes = size,
                    isH264Derivative =
                        archiveFile.source.equals("derivative", ignoreCase = true) &&
                            ("h.264" in format || "h264" in format)
                )
            }
            .sortedWith(
                compareByDescending<ArchiveFileSelection> { it.isH264Derivative }
                    .thenBy { it.sizeBytes }
            )
            .firstOrNull()
            ?: return null

        val encodedName = encodePathSegment(file.name)
        return InternetArchivePlaybackMedia(
            mediaUrl = "$ORIGIN/download/$identifier/$encodedName",
            itemUrl = "$ORIGIN/details/$identifier",
            durationSeconds = file.durationSeconds
        )
    }

    private fun isSafeMp4Name(value: String): Boolean =
        value.length in 1..512 &&
            value.endsWith(".mp4", ignoreCase = true) &&
            '/' !in value &&
            '\\' !in value &&
            ".." !in value &&
            value.none { character -> character.code < 0x20 || character.code == 0x7f }

    private fun releaseYear(year: Any?, date: Any?, title: String): Int? =
        sequenceOf(year, date)
            .mapNotNull(::scalarText)
            .mapNotNull { value ->
                releaseYearPattern.find(value)?.value?.toIntOrNull()
            }
            .firstOrNull()
            ?: releaseYearPattern.findAll(title).lastOrNull()?.value?.toIntOrNull()

    private fun scalarText(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        is Iterable<*> -> value.firstNotNullOfOrNull(::scalarText)
        is Array<*> -> value.firstNotNullOfOrNull(::scalarText)
        else -> null
    }

    private fun scalarLong(value: Any?): Long? =
        scalarText(value)?.trim()?.toLongOrNull()

    private fun scalarDouble(value: Any?): Double? =
        scalarText(value)?.trim()?.toDoubleOrNull()

    private fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> false
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private data class ArchiveFileSelection(
        val name: String,
        val durationSeconds: Int,
        val sizeBytes: Long,
        val isH264Derivative: Boolean
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class InternetArchiveSearchResponse(
    @JsonProperty("response") val response: Response? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Response(
        @JsonProperty("numFound") val numFound: Int? = null,
        @JsonProperty("docs") val docs: List<Document>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Document(
        @JsonProperty("identifier") val identifier: String? = null,
        @JsonProperty("title") val title: Any? = null,
        @JsonProperty("mediatype") val mediatype: String? = null,
        @JsonProperty("year") val year: Any? = null,
        @JsonProperty("date") val date: Any? = null
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class InternetArchiveMetadataResponse(
    @JsonProperty("is_dark") val isDark: Any? = null,
    @JsonProperty("metadata") val metadata: Metadata? = null,
    @JsonProperty("files") val files: List<File>? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Metadata(
        @JsonProperty("identifier") val identifier: String? = null,
        @JsonProperty("title") val title: Any? = null,
        @JsonProperty("mediatype") val mediatype: String? = null,
        @JsonProperty("year") val year: Any? = null,
        @JsonProperty("date") val date: Any? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class File(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("size") val size: Any? = null,
        @JsonProperty("length") val length: Any? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("private") val isPrivate: Any? = null
    )
}
