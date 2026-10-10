package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI
import org.jsoup.Jsoup

internal data class OkruMediaSource(val url: String, val quality: Int, val type: ExtractorLinkType)

/** Reads both current object metadata and older JSON-string metadata from OK.ru. */
internal object OkruPlayerParser {
    private val mapper = jacksonObjectMapper()
    private val playerPath = Regex("""^/video(?:embed)?/(\d{1,20})/?$""")
    private val qualities = mapOf(
        "mobile" to 144, "lowest" to 240, "low" to 360, "sd" to 480,
        "hd" to 720, "full" to 1080, "quad" to 1440, "ultra" to 2160
    )

    private fun movieId(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", true) || uri.host?.lowercase() !in setOf("ok.ru", "www.ok.ru") ||
            uri.rawUserInfo != null || uri.port !in setOf(-1, 443)
        ) return@runCatching null
        playerPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
    }.getOrNull()

    fun supports(url: String): Boolean = movieId(url) != null

    fun sources(html: String, playerUrl: String): List<OkruMediaSource> {
        val id = movieId(playerUrl) ?: return emptyList()
        if (html.length > 2_000_000) return emptyList()
        return Jsoup.parse(html).select("[data-options]").take(8).flatMap { player ->
            runCatching {
                val options = player.attr("data-options")
                if (options.length > 500_000) return@runCatching emptyList()
                val raw = mapper.readTree(options).path("flashvars").path("metadata")
                val metadata = if (raw.isTextual) mapper.readTree(raw.asText()) else raw
                if (metadata.path("movie").path("id").asText() != id) return@runCatching emptyList()
                fun safeUrl(value: String): String? = value.trim()
                    .takeIf { it.length <= 8_192 && isSafeRemoteHttpUrl(it) }
                val playlist = safeUrl(metadata.path("hlsManifestUrl").asText())
                    ?.let { listOf(OkruMediaSource(it, 0, ExtractorLinkType.M3U8)) }.orEmpty()
                val videos = metadata.path("videos").takeIf { it.isArray }?.take(16).orEmpty()
                    .filterNot { it.path("disallowed").asBoolean(false) }
                    .mapNotNull { video ->
                        safeUrl(video.path("url").asText())?.let { url ->
                            OkruMediaSource(url, qualities[video.path("name").asText().lowercase()] ?: 0, ExtractorLinkType.VIDEO)
                        }
                    }.sortedByDescending { it.quality }
                playlist + videos
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }
    }
}
