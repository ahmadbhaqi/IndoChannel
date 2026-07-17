package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.Qualities
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class AbyssMediaSource(
    val label: String,
    val url: String,
    val quality: Int
)

internal object AbyssPlayerParser {
    private const val MAX_HTML_SIZE = 2_000_000
    private const val MAX_PAYLOAD_SIZE = 1_500_000
    private const val MAX_DECODED_SIZE = 1_000_000
    private const val MAX_MEDIA_SIZE = 750_000
    private const val MAX_SOURCE_COUNT = 100
    private val mapper = jacksonObjectMapper()
    private val payloadRegex = Regex(
        """const\s+datas\s*=\s*["']([A-Za-z0-9+/=_-]+)["']"""
    )
    private val hex = "0123456789abcdef".toCharArray()

    fun sources(html: String): List<AbyssMediaSource> {
        if (html.length > MAX_HTML_SIZE) return emptyList()
        val payload = payloadRegex.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        if (payload.length > MAX_PAYLOAD_SIZE) return emptyList()
        return runCatching {
            val outerBytes = decodeBase64Compat(payload) ?: return@runCatching emptyList()
            if (outerBytes.size > MAX_DECODED_SIZE) return@runCatching emptyList()
            val outerJson = String(outerBytes, Charsets.ISO_8859_1)
            val outer = mapper.readTree(outerJson)
            val slug = outer.path("slug").asText().takeIf { it.isNotBlank() }
                ?: return@runCatching emptyList()
            val userId = outer.path("user_id").asText().takeIf { it.isNotBlank() }
                ?: return@runCatching emptyList()
            val md5Id = outer.path("md5_id").asText().takeIf { it.isNotBlank() }
                ?: return@runCatching emptyList()
            val media = outer.path("media").asText()
            if (media.length > MAX_MEDIA_SIZE) return@runCatching emptyList()
            val encrypted = media.toByteArray(Charsets.ISO_8859_1)
            val decrypted = decrypt(encrypted, "$userId:$slug:$md5Id")
            if (decrypted.size > MAX_MEDIA_SIZE) return@runCatching emptyList()
            parseSources(mapper.readTree(String(decrypted, Charsets.UTF_8)))
        }.getOrDefault(emptyList())
    }

    private fun decrypt(encrypted: ByteArray, secret: String): ByteArray {
        val digest = MessageDigest.getInstance("MD5").digest(secret.toByteArray(Charsets.UTF_8))
        val key = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }.toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(key.copyOfRange(0, 16))
        )
        return cipher.doFinal(encrypted)
    }

    private fun parseSources(root: JsonNode): List<AbyssMediaSource> {
        val mp4 = root.path("mp4")
        val sources = mp4.path("sources")
        if (!sources.isArray) return emptyList()

        // Current Abyss payloads describe encrypted backing storage plus a
        // browser-only /sora chunk protocol. source.url + source.path is not a
        // playable MP4 even though it answers ranged requests. Do not pass
        // those ciphertext bytes to Cloudstream; advance to the next mirror.
        val domains = mp4.path("domains")
        val usesChunkProtocol =
            (domains.isArray && domains.size() > 0) ||
                sources.any { source ->
                    source.path("sub").asText().isNotBlank() &&
                        source.path("res_id").canConvertToInt()
                }
        if (usesChunkProtocol) return emptyList()

        return sources.take(MAX_SOURCE_COUNT).mapNotNull { source ->
            val baseUrl = source.path("url").asText().trim().takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val path = source.path("path").asText().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = source.path("label").asText().trim().ifBlank { "Abyss" }
            AbyssMediaSource(
                label = label,
                url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}",
                quality = Regex("\\d{3,4}").find(label)?.value?.toIntOrNull()
                    ?: Qualities.Unknown.value
            )
        }.distinctBy { it.url }
    }
}
