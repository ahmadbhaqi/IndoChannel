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
        val sources = root.path("mp4").path("sources")
        if (!sources.isArray) return emptyList()

        return sources.take(MAX_SOURCE_COUNT).mapNotNull { source ->
            val baseUrl = source.path("url").asText().trim().takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val path = source.path("path").asText().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val size = source.path("size").asLong(0L)
            val partSize = source.path("partSize").asLong(0L)
            if (size > 0L && partSize > 0L && partSize < size) return@mapNotNull null
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
