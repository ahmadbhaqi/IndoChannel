package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ByseMediaSource(
    val url: String,
    val mimeType: String,
    val label: String,
    val quality: String,
    val height: Int?
) {
    val isHls: Boolean
        get() = mimeType.contains("mpegurl", ignoreCase = true) ||
            URI.create(url).path?.endsWith(".m3u8", ignoreCase = true) == true
}

internal data class ByseTrack(
    val url: String,
    val kind: String,
    val label: String,
    val language: String
)

internal data class BysePlayback(
    val sources: List<ByseMediaSource>,
    val tracks: List<ByseTrack>
)

/** Decrypts the bounded AES-GCM playback envelope returned by Byse players. */
internal object BysePlayerParser {
    private const val MAX_API_RESPONSE_SIZE = 2_000_000
    private const val MAX_PAYLOAD_TEXT_SIZE = 1_800_000
    private const val MAX_CIPHERTEXT_SIZE = 1_350_000
    private const val MAX_PLAINTEXT_SIZE = 1_300_000
    private const val MAX_KEY_PART_COUNT = 64
    private const val MAX_KEY_PART_TEXT_SIZE = 128
    private const val MAX_SOURCE_COUNT = 100
    private const val MAX_TRACK_COUNT = 100
    private const val MAX_URL_SIZE = 8_192
    private const val MAX_METADATA_SIZE = 1_024
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val GCM_IV_BYTES = 12
    private const val AES_256_KEY_BYTES = 32
    private val videoCode = Regex("[A-Za-z0-9_-]{1,128}")
    private val mapper = jacksonObjectMapper()

    /** Builds the same-origin API URL from the first path segment following `/e/`. */
    fun apiUrl(playerUrl: String): String? = runCatching {
        if (playerUrl.length > MAX_URL_SIZE) return@runCatching null
        val uri = URI(playerUrl)
        val scheme = uri.scheme?.lowercase().takeIf { it == "https" || it == "http" }
            ?: return@runCatching null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return@runCatching null
        val segments = uri.rawPath.orEmpty().split('/')
        val embedIndex = segments.indexOfFirst { it == "e" }
        val code = segments.getOrNull(embedIndex + 1)
            ?.takeIf { embedIndex >= 0 && videoCode.matches(it) }
            ?: return@runCatching null
        URI(scheme, null, uri.host, uri.port, "/api/videos/$code", null, null).toString()
    }.getOrNull()

    /**
     * Byse rotates frontend domains. Identify the bounded page shape so a new
     * hostname can still use the same same-origin encrypted playback API.
     */
    fun isFrontendPage(html: String): Boolean {
        if (html.isBlank() || html.length > MAX_API_RESPONSE_SIZE) return false
        val normalized = html.lowercase()
        return normalized.contains("<title>byse frontend</title>") ||
            (
                normalized.contains("/api/videos/") &&
                    normalized.contains("playback") &&
                    normalized.contains("byse")
                )
    }

    /** Validates, decrypts, and parses an API response. Invalid envelopes fail closed. */
    fun playback(apiJson: String): BysePlayback? {
        if (apiJson.length > MAX_API_RESPONSE_SIZE) return null
        return runCatching {
            val root = mapper.readTree(apiJson) ?: return@runCatching null
            val envelope = root.path("playback").takeIf { it.isObject }
                ?: root.path("data").path("playback").takeIf { it.isObject }
                ?: return@runCatching null
            if (envelope.path("algorithm").asText() != "AES-256-GCM") {
                return@runCatching null
            }

            val version = envelope.path("version").asText().toIntOrNull()
                ?.takeIf { it in 1..20 }
                ?: return@runCatching null
            val keyParts = envelope.path("key_parts")
            if (!keyParts.isArray || keyParts.size() !in 1..MAX_KEY_PART_COUNT) {
                return@runCatching null
            }
            val firstPartIndex = version - 1
            val secondPartIndex = 31 - version - 1
            val firstPart = decodeKeyPart(keyParts.get(firstPartIndex))
                ?: return@runCatching null
            val secondPart = decodeKeyPart(keyParts.get(secondPartIndex))
                ?: return@runCatching null
            val key = firstPart + secondPart
            if (key.size != AES_256_KEY_BYTES) return@runCatching null

            val ivText = envelope.path("iv").asText()
            if (ivText.length > MAX_KEY_PART_TEXT_SIZE) return@runCatching null
            val iv = decodeBase64Compat(ivText)
                ?.takeIf { it.size == GCM_IV_BYTES }
                ?: return@runCatching null
            val payloadText = envelope.path("payload").asText()
            if (payloadText.isBlank() || payloadText.length > MAX_PAYLOAD_TEXT_SIZE) {
                return@runCatching null
            }
            val ciphertext = decodeBase64Compat(payloadText)
                ?.takeIf { it.size in (GCM_TAG_BYTES + 1)..MAX_CIPHERTEXT_SIZE }
                ?: return@runCatching null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            val plaintext = cipher.doFinal(ciphertext)
            if (plaintext.size > MAX_PLAINTEXT_SIZE) return@runCatching null
            parsePlaintext(mapper.readTree(plaintext))
        }.getOrNull()
    }

    fun sources(apiJson: String): List<ByseMediaSource> =
        playback(apiJson)?.sources.orEmpty()

    private fun decodeKeyPart(node: JsonNode?): ByteArray? {
        if (node == null || !node.isTextual) return null
        val encoded = node.asText()
        if (encoded.isBlank() || encoded.length > MAX_KEY_PART_TEXT_SIZE) return null
        return decodeBase64Compat(encoded)
    }

    private fun parsePlaintext(root: JsonNode): BysePlayback? {
        if (!root.isObject) return null
        val sourceNodes = root.path("sources")
        if (!sourceNodes.isArray || sourceNodes.size() > MAX_SOURCE_COUNT) return null
        val trackNodes = root.path("tracks")
        if (!trackNodes.isMissingNode && (!trackNodes.isArray || trackNodes.size() > MAX_TRACK_COUNT)) {
            return null
        }

        val sources = sourceNodes.mapNotNull { source ->
            val url = httpUrl(source.path("url").asText()) ?: return@mapNotNull null
            val mimeType = metadata(source.path("mime_type").asText()) ?: ""
            val label = metadata(source.path("label").asText()) ?: "Byse"
            val quality = metadata(source.path("quality").asText()) ?: label
            val height = source.path("height").asText().toIntOrNull()
                ?.takeIf { it in 1..4_320 }
            ByseMediaSource(url, mimeType, label, quality, height)
        }.distinctBy { it.url }

        val trackItems = if (trackNodes.isArray) trackNodes.toList() else emptyList()
        val tracks = trackItems.mapNotNull { track ->
            val url = sequenceOf("url", "file", "src")
                .map { track.path(it).asText() }
                .mapNotNull(::httpUrl)
                .firstOrNull()
                ?: return@mapNotNull null
            ByseTrack(
                url = url,
                kind = metadata(track.path("kind").asText()) ?: "captions",
                label = metadata(track.path("label").asText()) ?: "",
                language = metadata(
                    track.path("language").asText().ifBlank { track.path("srclang").asText() }
                ) ?: ""
            )
        }.distinctBy { it.url }
        return BysePlayback(sources, tracks)
    }

    private fun metadata(value: String): String? =
        value.trim().takeIf { it.isNotEmpty() && it.length <= MAX_METADATA_SIZE }

    private fun httpUrl(value: String): String? {
        if (value.isBlank() || value.length > MAX_URL_SIZE) return null
        return runCatching {
            val uri = URI(value.trim())
            value.trim().takeIf {
                (uri.scheme.equals("https", ignoreCase = true) ||
                    uri.scheme.equals("http", ignoreCase = true)) &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null
            }
        }.getOrNull()
    }
}
