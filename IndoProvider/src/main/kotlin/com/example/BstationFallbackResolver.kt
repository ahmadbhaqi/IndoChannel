package com.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs
import org.jsoup.Jsoup

internal data class BstationSearchCandidate(
    val aid: String,
    val title: String,
    val pageUrl: String,
    val durationSeconds: Int
)

internal data class BstationPlaybackMedia(
    val videoUrl: String,
    val audioUrl: String,
    val height: Int
)

internal object BstationFallbackParser {
    private const val ORIGIN = "https://www.bilibili.tv"
    private const val MIN_FULL_LENGTH_SECONDS = 15 * 60
    private val aidPattern = Regex("""^/en/video/(\d{8,20})/?$""")
    private val terminalReleaseYearPattern =
        Regex("""\s*[\[(]?\s*((?:19|20)\d{2})\s*[\])]?\s*$""")

    val requestHeaders = mapOf(
        "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.8",
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 Chrome/136 Safari/537.36"
    )

    fun searchUrl(query: String): String? {
        val normalized = query.trim().takeIf {
            it.isNotBlank() &&
                it.length <= 256 &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
        } ?: return null
        return "$ORIGIN/en/search-result?q=" +
            URLEncoder.encode(normalized, Charsets.UTF_8.name()).replace("+", "%20")
    }

    fun searchUrls(request: NomatFallbackRequest): List<String> {
        if (request.season != null || request.episode != null) return emptyList()
        return listOfNotNull(
            request.year?.let { year -> "${request.title} $year" },
            request.title
        ).distinct().mapNotNull(::searchUrl)
    }

    fun playUrl(aid: String): String? {
        if (!aid.matches(Regex("""^\d{8,20}$"""))) return null
        return "https://api.bilibili.tv/intl/gateway/web/playurl" +
            "?platform=web&aid=$aid"
    }

    fun networkUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf {
            it.isNotBlank() &&
                it.length <= 8_192 &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
        } ?: return null
        return runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return@runCatching null
            if (
                uri.scheme?.lowercase() != "https" ||
                uri.userInfo != null ||
                uri.port !in setOf(-1, 443) ||
                uri.rawFragment != null
            ) return@runCatching null
            val validPath = when (host) {
                "www.bilibili.tv" ->
                    uri.path == "/en/search-result" ||
                        aidPattern.matches(uri.path.orEmpty())

                "api.bilibili.tv" ->
                    uri.path == "/intl/gateway/web/playurl"

                else -> false
            }
            uri.toASCIIString().takeIf { validPath }
        }.getOrNull()
    }

    fun searchCandidates(html: String): List<BstationSearchCandidate> {
        if (html.isBlank() || html.length > 2_000_000) return emptyList()
        val document = Jsoup.parse(html, "$ORIGIN/en/search-result")
        return document.select("div.bstar-video-card")
            .asSequence()
            .mapNotNull { card ->
                val anchor = card.selectFirst("a[href*='/video/']") ?: return@mapNotNull null
                val pageUrl = ProviderHtmlParser.absoluteUrl(
                    anchor.attr("href"),
                    "$ORIGIN/en/search-result"
                )?.let(::networkUrl) ?: return@mapNotNull null
                val aid = aidPattern.matchEntire(URI(pageUrl).path.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@mapNotNull null
                val title = MovieMetadataParser.title(
                    card.selectFirst("img[alt]")?.attr("alt")
                        ?.ifBlank { null }
                        ?: anchor.attr("title")
                ) ?: return@mapNotNull null
                val duration = card.selectFirst(
                    ".bstar-video-card__cover-mask-text--bold, " +
                        ".bstar-video-card__cover-mask-text"
                )?.text()?.let(::durationSeconds) ?: return@mapNotNull null
                BstationSearchCandidate(aid, title, pageUrl, duration)
            }
            .distinctBy { candidate -> candidate.aid }
            .take(30)
            .toList()
    }

    fun isExactCandidate(
        request: NomatFallbackRequest,
        candidate: BstationSearchCandidate
    ): Boolean {
        if (candidate.durationSeconds < MIN_FULL_LENGTH_SECONDS) return false
        if (request.season != null || request.episode != null) return false
        val normalizedTitle = MovieMetadataParser.title(candidate.title) ?: return false
        val yearSuffix = terminalReleaseYearPattern.find(normalizedTitle)
        val titleWithoutYear = yearSuffix
            ?.takeIf { match -> match.range.last == normalizedTitle.lastIndex }
            ?.let { match ->
                normalizedTitle.removeRange(match.range).trim()
                    .takeIf { value ->
                        value.isNotBlank() &&
                            NomatParser.isExactFallbackTitle(request.title, value)
                    }
            }
        val exactTitle =
            titleWithoutYear != null ||
                NomatParser.isExactFallbackTitle(request.title, normalizedTitle)
        if (!exactTitle) return false

        val expectedYear = request.year ?: return true
        val candidateYear = titleWithoutYear
            ?.let { yearSuffix?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?: return false
        // User uploads often label a film with its production/festival year
        // instead of the theatrical catalog year. Permit only that adjacent
        // year, and only after the normalized title is an exact match.
        return abs(expectedYear - candidateYear) <= 1
    }

    fun playbackMedia(response: BstationPlayResponse): BstationPlaybackMedia? {
        if (response.code != 0) return null
        val playUrl = response.data?.playUrl ?: return null
        val video = playUrl.videos.orEmpty()
            .asSequence()
            .mapNotNull { stream ->
                val resource = stream.resource ?: return@mapNotNull null
                resource.takeIf {
                    it.codecId == 7 &&
                        (it.height ?: 0) in 1..4_320 &&
                        isTrustedMediaUrl(it.url)
                }?.let { stream to it }
            }
            .sortedWith(
                compareByDescending<Pair<BstationPlayResponse.Video, BstationPlayResponse.Resource>> {
                    it.second.height ?: 0
                }.thenByDescending { it.second.quality ?: 0 }
            )
            .firstOrNull()
            ?: return null
        val audio = playUrl.audio.orEmpty()
            .asSequence()
            .filter { resource ->
                isTrustedMediaUrl(resource.url)
            }
            .sortedWith(
                compareByDescending<BstationPlayResponse.Resource> {
                    it.quality == video.first.audioQuality
                }.thenByDescending { it.quality ?: 0 }
            )
            .firstOrNull()
            ?: return null
        return BstationPlaybackMedia(
            videoUrl = video.second.url ?: return null,
            audioUrl = audio.url ?: return null,
            height = video.second.height ?: return null
        )
    }

    fun mediaHeaders(pageUrl: String): Map<String, String> =
        requestHeaders + mapOf(
            "Referer" to pageUrl,
            "Origin" to ORIGIN
        )

    private fun durationSeconds(raw: String): Int? {
        val parts = raw.trim().split(':').mapNotNull(String::toIntOrNull)
        if (parts.size !in 2..3) return null
        if (parts.any { it !in 0..59 }) {
            if (parts.size == 3 && parts.first() > 59) return null
            if (parts.drop(1).any { it !in 0..59 }) return null
        }
        return if (parts.size == 3) {
            parts[0] * 3_600 + parts[1] * 60 + parts[2]
        } else {
            parts[0] * 60 + parts[1]
        }
    }

    private fun isTrustedMediaUrl(raw: String?): Boolean {
        val value = raw?.trim()?.takeIf {
            it.isNotBlank() &&
                it.length <= 16_384 &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
        } ?: return false
        return runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return@runCatching false
            val trustedHost =
                (
                    host.endsWith(".akamaized.net") &&
                        host.substringBefore('.').startsWith("upos-bstar")
                    ) ||
                    (
                        host.endsWith(".bilivideo.com") &&
                            host.substringBefore('.').startsWith("upos-")
                        )
            uri.scheme?.lowercase() == "https" &&
                trustedHost &&
                uri.userInfo == null &&
                uri.port in setOf(-1, 443) &&
                uri.path.orEmpty().lowercase().endsWith(".m4s")
        }.getOrDefault(false)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class BstationPlayResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: Data? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Data(
        @JsonProperty("playurl") val playUrl: PlayUrl? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PlayUrl(
        @JsonProperty("video") val videos: List<Video>? = emptyList(),
        @JsonProperty("audio_resource") val audio: List<Resource>? = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Video(
        @JsonProperty("video_resource") val resource: Resource? = null,
        @JsonProperty("audio_quality") val audioQuality: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Resource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("codec_id") val codecId: Int? = null,
        @JsonProperty("width") val width: Int? = null,
        @JsonProperty("height") val height: Int? = null
    )
}
