package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class AbyssMediaSource(
    val label: String,
    val url: String,
    val quality: Int,
    val headers: Map<String, String> = emptyMap()
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
            parseSources(
                mapper.readTree(String(decrypted, Charsets.UTF_8)),
                slug,
                md5Id
            )
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

    private fun parseSources(root: JsonNode, slug: String, md5Id: String): List<AbyssMediaSource> {
        val mp4 = root.path("mp4")
        val sources = mp4.path("sources")
        if (!sources.isArray) return emptyList()

        val domains = mp4.path("domains")
            .takeIf(JsonNode::isArray)
            ?.mapNotNull { domain -> soraHost(domain.asText()) }
            .orEmpty()
        val soraSources = sources.take(MAX_SOURCE_COUNT).mapNotNull { source ->
            val status = source.get("status")
            if (
                status != null &&
                (
                    (status.isBoolean && !status.asBoolean()) ||
                        (status.isTextual && status.asText().equals("false", ignoreCase = true))
                    )
            ) return@mapNotNull null
            val resId = source.path("res_id").asInt(-1).takeIf { it in 1..5 }
                ?: return@mapNotNull null
            val size = source.path("size").asLong(-1L)
                .takeIf { it in 1L..MAX_SORA_MEDIA_SIZE }
                ?: return@mapNotNull null
            val sub = source.path("sub").asText().trim().lowercase()
                .takeIf { it.matches(Regex("^[a-z0-9-]{2,63}$")) }
                ?: return@mapNotNull null
            val domain = domains.firstOrNull { host ->
                host == sub || host.startsWith("$sub.")
            } ?: return@mapNotNull null
            val token = soraToken("mp4", md5Id, resId, size, slug)
                ?: return@mapNotNull null
            val quality = source.path("label").asText()
                .let { Regex("\\d{3,4}").find(it)?.value?.toIntOrNull() }
                ?: SORA_QUALITY_BY_RES_ID[resId]
                ?: Qualities.Unknown.value
            AbyssMediaSource(
                label = if (quality > 0) "Abyss ${quality}p" else "Abyss",
                url = "https://$domain/sora/$size/$token",
                quality = quality,
                headers = mapOf("User-Agent" to Embed4mePlayerParser.USER_AGENT)
            )
        }.distinctBy { it.url }.sortedByDescending { it.quality }

        val legacySources = sources.take(MAX_SOURCE_COUNT).mapNotNull { source ->
            // url/path is encrypted backing storage when res_id/sub exists.
            if (
                source.path("sub").asText().isNotBlank() ||
                source.path("res_id").canConvertToInt()
            ) return@mapNotNull null
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
        return (soraSources + legacySources).distinctBy { it.url }
    }

    private fun soraToken(
        type: String,
        md5Id: String,
        resId: Int,
        size: Long,
        slug: String
    ): String? {
        if (
            type != "mp4" ||
            !md5Id.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) ||
            !slug.matches(Regex("^[A-Za-z0-9_-]{1,128}$"))
        ) return null
        return runCatching {
            val numericSize = size.toString().map { digit ->
                (digit - '0').toByte()
            }.toByteArray()
            val key = MessageDigest.getInstance("MD5")
                .digest(numericSize)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                .toByteArray(Charsets.US_ASCII)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(key.copyOfRange(0, 16))
            )
            val plaintext = "/$type/$md5Id/$resId/$size?v=$slug"
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val inner = encodeBase64NoPadding(encrypted)
            encodeBase64NoPadding(inner.toByteArray(Charsets.US_ASCII))
        }.getOrNull()
    }

    private fun soraHost(raw: String): String? {
        return runCatching {
            val value = raw.trim()
            val uri = URI(if (value.contains("://")) value else "https://$value")
            uri.host.orEmpty().lowercase().trimEnd('.')
                .takeIf { host ->
                    host.endsWith(".sssrr.org") &&
                        host.matches(Regex("^[a-z0-9.-]{4,253}$"))
                }
        }.getOrNull()
    }

    private const val MAX_SORA_MEDIA_SIZE = 20_000_000_000L
    private val SORA_QUALITY_BY_RES_ID = mapOf(
        1 to 240,
        2 to 360,
        3 to 480,
        4 to 720,
        5 to 1080
    )
}
