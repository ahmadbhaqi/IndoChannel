package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

internal data class HowNetworkApiRequest(
    val playerUrl: String,
    val apiUrl: String,
    val form: Map<String, String>,
    val headers: Map<String, String>
)

internal data class HowNetworkMediaSource(
    val label: String,
    val url: String,
    val type: ExtractorLinkType
)

/** Bounded adapter for the legacy cloud.hownetwork.xyz AJAX player. */
internal object HowNetworkPlayerParser {
    private const val HOST = "cloud.hownetwork.xyz"
    private const val MAX_RESPONSE_SIZE = 2_000_000
    private const val MAX_SOURCES = 32
    private val idPattern = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val mapper = jacksonObjectMapper()

    fun apiRequest(playerUrl: String): HowNetworkApiRequest? = runCatching {
        val player = URI(playerUrl)
        if (
            player.scheme?.lowercase() !in setOf("http", "https") ||
            !player.host.orEmpty().equals(HOST, ignoreCase = true) ||
            player.userInfo != null
        ) return@runCatching null
        val id = player.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
            if (!parameter.substringBefore('=').equals("id", ignoreCase = true)) {
                return@firstNotNullOfOrNull null
            }
            URLDecoder.decode(
                parameter.substringAfter('=', ""),
                Charsets.UTF_8.name()
            ).takeIf(idPattern::matches)
        } ?: return@runCatching null
        val origin = "${player.scheme.lowercase()}://$HOST"
        HowNetworkApiRequest(
            playerUrl = playerUrl,
            apiUrl = "$origin/api.php?id=${URLEncoder.encode(id, Charsets.UTF_8.name())}",
            form = mapOf(
                "r" to "https://playeriframe.sbs/",
                "d" to "stream.hownetwork.xyz"
            ),
            headers = mapOf(
                "Origin" to origin,
                "X-Requested-With" to "XMLHttpRequest"
            )
        )
    }.getOrNull()

    fun sources(json: String, playerUrl: String): List<HowNetworkMediaSource> {
        if (json.length > MAX_RESPONSE_SIZE) return emptyList()
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        val data = root.path("data").takeIf(JsonNode::isArray) ?: return emptyList()
        return data.take(MAX_SOURCES).mapNotNull { item ->
            val raw = item.path("file").takeIf(JsonNode::isTextual)?.asText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = runCatching { URI(playerUrl).resolve(raw.replace("\\/", "/")).toString() }
                .getOrNull()
                ?.takeIf(::isSafeRemoteHttpUrl)
                ?: return@mapNotNull null
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            HowNetworkMediaSource(
                label = item.path("label").asText().trim().ifBlank { "HowNetwork" },
                url = url,
                type = if (path.endsWith(".m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            )
        }.distinctBy { it.url }
    }
}
