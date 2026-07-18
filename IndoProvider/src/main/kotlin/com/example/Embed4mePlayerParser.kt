package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class Embed4meApiRequest(
    val playerUrl: String,
    val origin: String,
    val videoApiUrl: String,
    val downloadApiUrl: String,
    val headers: Map<String, String>
)

internal data class Embed4meMediaSource(
    val label: String,
    val url: String,
    val type: ExtractorLinkType
)

/**
 * Resolves the hash-based SPA player shared by Embed4me, PlayerP2P and UPNS.
 * Its API returns lowercase hexadecimal AES-CBC ciphertext, never media bytes.
 */
internal object Embed4mePlayerParser {
    internal const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val AES_KEY = "kiemtienmua911ca"
    private const val AES_IV = "1234567890oiuytr"
    private const val MAX_CIPHERTEXT_HEX_SIZE = 4_000_000
    private const val MAX_PLAINTEXT_SIZE = 2_000_000
    private val fragmentId = Regex("^[A-Za-z0-9_-]{2,128}$")
    private val mapper = jacksonObjectMapper()
    private val supportedSuffixes = setOf(
        "embed4me.vip",
        "4meplayer.com",
        "playerp2p.online",
        "p2pplay.pro",
        "upns.live",
        "strp2p.site"
    )

    fun apiRequest(playerUrl: String, providerReferer: String?): Embed4meApiRequest? {
        return runCatching {
            val player = URI(playerUrl)
            val scheme = player.scheme.orEmpty().lowercase()
            val host = player.host.orEmpty().lowercase().removePrefix("www.")
            val id = player.rawFragment.orEmpty().takeIf(fragmentId::matches)
                ?: return@runCatching null
            if (scheme !in setOf("http", "https") || !supportsHost(host)) {
                return@runCatching null
            }
            val origin = URI(
                scheme,
                null,
                player.host,
                player.port,
                null,
                null,
                null
            ).toString().trimEnd('/')
            val providerHost = providerReferer
                ?.let { URI(it).host }
                ?.takeIf { it.matches(Regex("^[A-Za-z0-9.-]{1,253}$")) }
                .orEmpty()
            val encodedId = URLEncoder.encode(id, Charsets.UTF_8.name())
            val encodedReferer = URLEncoder.encode(providerHost, Charsets.UTF_8.name())
            val dimensions = "id=$encodedId&w=1920&h=1080&r=$encodedReferer"
            Embed4meApiRequest(
                playerUrl = playerUrl,
                origin = origin,
                videoApiUrl = "$origin/api/v1/video?$dimensions",
                downloadApiUrl = "$origin/api/v1/download?$dimensions",
                headers = mapOf(
                    "Origin" to origin,
                    "User-Agent" to USER_AGENT
                )
            )
        }.getOrNull()
    }

    fun videoSources(ciphertextHex: String, playerUrl: String): List<Embed4meMediaSource> {
        val root = decryptedJson(ciphertextHex) ?: return emptyList()
        return listOf(
            Triple("Embed4me HLS Proxy", "cfNative", ExtractorLinkType.M3U8),
            Triple("Embed4me HLS", "source", ExtractorLinkType.M3U8),
            Triple("Embed4me HLS Alternate", "hlsVideoTiktok", ExtractorLinkType.M3U8)
        ).mapNotNull { (label, field, type) ->
            root.findText(field)
                ?.let { absoluteMediaUrl(it, playerUrl) }
                ?.let { Embed4meMediaSource(label, it, type) }
        }.distinctBy { it.url }
    }

    fun downloadSources(ciphertextHex: String, playerUrl: String): List<Embed4meMediaSource> {
        val root = decryptedJson(ciphertextHex) ?: return emptyList()
        return listOf("mp4", "download", "url").mapNotNull { field ->
            root.findText(field)
                ?.let { absoluteMediaUrl(it, playerUrl) }
                ?.let { Embed4meMediaSource("Embed4me MP4", it, ExtractorLinkType.VIDEO) }
        }.distinctBy { it.url }
    }

    internal fun decryptHexPayload(ciphertextHex: String): String? {
        if (ciphertextHex.length > MAX_CIPHERTEXT_HEX_SIZE) return null
        val normalized = ciphertextHex.filterNot(Char::isWhitespace)
        if (
            normalized.isEmpty() ||
            normalized.length % 32 != 0 ||
            normalized.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }
        ) return null
        val encrypted = ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            )
            val plaintext = cipher.doFinal(encrypted)
            if (plaintext.size > MAX_PLAINTEXT_SIZE) return@runCatching null
            plaintext.toString(Charsets.UTF_8)
                .takeIf { '\uFFFD' !in it }
                ?.trim()
        }.getOrNull()
    }

    private fun decryptedJson(ciphertextHex: String): JsonNode? {
        val plaintext = decryptHexPayload(ciphertextHex) ?: return null
        return runCatching { mapper.readTree(plaintext) }.getOrNull()
    }

    private fun JsonNode.findText(field: String): String? {
        return findValue(field)
            ?.takeIf(JsonNode::isTextual)
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun absoluteMediaUrl(raw: String, playerUrl: String): String? {
        return runCatching {
            URI(playerUrl).resolve(raw.replace("\\/", "/")).toString()
                .takeIf(::isSafeRemoteHttpUrl)
        }.getOrNull()
    }

    internal fun supportsHost(host: String): Boolean {
        return supportedSuffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }
    }
}
