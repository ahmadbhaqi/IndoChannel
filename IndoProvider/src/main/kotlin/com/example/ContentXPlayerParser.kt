package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Resolves the fragment-based encrypted player currently used by Ngefilm server 1. */
internal object ContentXPlayerParser {
    private const val MAX_URL_SIZE = 8_192
    private const val MAX_RESPONSE_SIZE = 2_000_000
    private const val MAX_CIPHERTEXT_SIZE = 1_000_000
    private const val MAX_PLAINTEXT_SIZE = 1_500_000
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{2,128}")
    private val hexPattern = Regex("[0-9A-Fa-f]+")
    private val mapper = jacksonObjectMapper()
    private val key = "kiemtienmua911ca".toByteArray(Charsets.US_ASCII)
    private val iv = "1234567890oiuytr".toByteArray(Charsets.US_ASCII)

    fun apiUrl(playerUrl: String, referer: String?): String? = runCatching {
        if (playerUrl.length > MAX_URL_SIZE) return@runCatching null
        val player = URI(playerUrl)
        val scheme = player.scheme?.lowercase().takeIf { it == "https" || it == "http" }
            ?: return@runCatching null
        val host = player.host?.lowercase()?.takeIf {
            it == "rpmlive.online" || it.endsWith(".rpmlive.online")
        } ?: return@runCatching null
        if (player.userInfo != null) return@runCatching null
        val videoId = player.rawFragment
            ?.substringBefore('&')
            ?.takeIf(videoIdPattern::matches)
            ?: return@runCatching null
        val refererHost = referer
            ?.takeIf { it.length <= MAX_URL_SIZE }
            ?.let(::URI)
            ?.host
            ?.lowercase()
            ?.removePrefix("www.")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9.-]{1,253}")) }
            .orEmpty()
        val origin = URI(scheme, null, host, player.port, null, null, null)
            .toString()
            .trimEnd('/')
        "$origin/api/v1/video?id=$videoId&w=1920&h=1080&r=$refererHost"
    }.getOrNull()

    fun playback(encryptedHex: String, playerUrl: String): BysePlayback? {
        if (encryptedHex.length > MAX_RESPONSE_SIZE) return null
        val payload = encryptedHex.trim()
        if (payload.length !in 2..MAX_RESPONSE_SIZE || payload.length % 2 != 0) return null
        if (!hexPattern.matches(payload)) return null
        return runCatching {
            val ciphertext = ByteArray(payload.length / 2) { index ->
                payload.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            if (ciphertext.size > MAX_CIPHERTEXT_SIZE) return@runCatching null
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            val plaintext = cipher.doFinal(ciphertext)
            if (plaintext.size > MAX_PLAINTEXT_SIZE) return@runCatching null
            parsePlayback(mapper.readTree(plaintext), playerUrl)
        }.getOrNull()
    }

    private fun parsePlayback(root: JsonNode, playerUrl: String): BysePlayback? {
        if (!root.isObject) return null
        val player = runCatching { URI(playerUrl) }.getOrNull() ?: return null
        val origin = URI(player.scheme, null, player.host, player.port, "/", null, null)
        val candidates = listOf(
            "hlsVideoTiktok" to "ContentX TikTok",
            "cfNative" to "ContentX Cloudflare",
            "source" to "ContentX In-House",
            "hlsVideoGoogle" to "ContentX Google",
            "hlsVideoCloudflare" to "ContentX Cloudflare"
        )
        val sources = candidates.mapNotNull { (field, label) ->
            val raw = root.path(field).takeIf(JsonNode::isTextual)?.asText()?.trim()
                ?.takeIf { it.isNotBlank() && it.length <= MAX_URL_SIZE }
                ?: return@mapNotNull null
            val resolved = runCatching { origin.resolve(raw).toString() }.getOrNull()
                ?.takeIf(::isSafeRemoteHttpUrl)
                ?: return@mapNotNull null
            ByseMediaSource(
                url = resolved,
                mimeType = "application/vnd.apple.mpegurl",
                label = label,
                quality = label,
                height = null
            )
        }.distinctBy { it.url }
        return sources.takeIf { it.isNotEmpty() }?.let { BysePlayback(it, emptyList()) }
    }
}
