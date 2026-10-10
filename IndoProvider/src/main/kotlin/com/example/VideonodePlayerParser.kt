package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI

internal data class VideonodePlayerRequest(
    val apiUrl: String,
    val host: String,
    val id: String
)

internal object VideonodePlayerParser {
    private val mapper = jacksonObjectMapper()
    private val playerPath = Regex("""^/iframe3/(p2p|cast|hydrax)/([A-Za-z0-9_-]{1,128})/?$""")

    fun request(playerUrl: String): VideonodePlayerRequest? = runCatching {
        val uri = URI(playerUrl)
        if (!uri.scheme.equals("https", true) || !uri.host.equals("videonode.de", true) ||
            uri.rawUserInfo != null || uri.port !in setOf(-1, 443)
        ) return@runCatching null
        val match = playerPath.matchEntire(uri.rawPath.orEmpty()) ?: return@runCatching null
        VideonodePlayerRequest("https://videonode.de/api.php", match.groupValues[1], match.groupValues[2])
    }.getOrNull()

    fun embedUrl(json: String): String? {
        if (json.length > 65_536) return null
        return runCatching {
            mapper.readTree(json).path("embedUrl").asText()
                .trim().takeIf(::isSafeRemoteHttpUrl)
        }.getOrNull()
    }
}
