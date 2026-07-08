package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.parser.Parser
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object InlineDataParser {
    private const val PLAY_SOBAT_KEY = "96fb393f57087e9333cc067bf4aa378e"
    private const val KURONIME_PASSPHRASE = "3&!Z0M,;dZWrawa=="
    private val mapper = jacksonObjectMapper()
    private val urlRegex = Regex("""url:"(https?://[^"]+)"""")
    private val jsonLinkRegex = Regex(""""link":"(https?://[^"]+)"""")

    fun decodeEscapedInlineData(html: String): String {
        return Parser.unescapeEntities(html, true)
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "")
    }

    fun oploverzStreamUrls(html: String, episode: Int?): List<String> {
        val data = decodeEscapedInlineData(html)
        val blocks = if (episode != null) {
            val escapedEpisode = Regex.escape(episode.toString())
            Regex("""(?s)episodeNumber:"?$escapedEpisode"?.*?streamUrl:\[(.*?)],content:""")
                .findAll(data)
                .map { it.groupValues[1] }
                .toList()
        } else {
            Regex("""(?s)streamUrl:\[(.*?)],content:""")
                .findAll(data)
                .map { it.groupValues[1] }
                .take(1)
                .toList()
        }

        val sourceBlocks = blocks.ifEmpty {
            Regex("""(?s)streamUrl:\[(.*?)],content:""")
                .findAll(data)
                .map { it.groupValues[1] }
                .take(1)
                .toList()
        }

        return sourceBlocks
            .flatMap { block -> urlRegex.findAll(block).map { it.groupValues[1] } }
            .distinct()
    }

    fun miranimeSourceUrls(html: String): List<String> {
        val data = decodeEscapedInlineData(html)
        return Regex("""(?s)"sources":\[(.*?)]""")
            .findAll(data)
            .flatMap { match -> jsonLinkRegex.findAll(match.groupValues[1]).map { it.groupValues[1] } }
            .distinct()
            .toList()
    }

    fun playSobatUrls(html: String): List<String> {
        val payload = Regex("""window\.payload\s*=\s*"((?:\\.|[^"\\])*)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { decodeJsonString(it) }
            ?: return emptyList()

        return try {
            val node = mapper.readTree(payload)
            val iv = Base64.getDecoder().decode(node.path("iv").asText())
            val decrypted = decryptAesCbcBase64(
                data = node.path("data").asText(),
                key = PLAY_SOBAT_KEY.toByteArray(Charsets.UTF_8),
                iv = iv
            )
            mapper.readTree(decrypted)
                .fields()
                .asSequence()
                .mapNotNull { normalizePlaySobatUrl(it.key, it.value.asText()) }
                .distinct()
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun kuronimeSourceId(html: String): String? {
        return Regex("""var\s+_0xa100d42aa\s*=\s*"([^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    fun kuronimeMirrorUrls(encrypted: String?): List<String> {
        val value = encrypted?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            val decrypted = decryptCryptoJsPassphrase(value, KURONIME_PASSPHRASE)
            collectUrls(mapper.readTree(decrypted))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun kuronimeApiUrls(responseJson: String): List<String> {
        return try {
            val node = mapper.readTree(responseJson)
            val urls = mutableListOf<String>()
            listOf("mirror", "src", "src_sd").forEach { field ->
                urls += kuronimeMirrorUrls(node.path(field).asText(null))
            }
            node.path("blog").asText(null)?.takeIf { it.isNotBlank() }?.let { blog ->
                urls += "https://blog.animeku.org/player2.php?id=$blog"
            }
            node.path("src").asText(null)?.takeIf { it.isNotBlank() }?.let { src ->
                urls += "https://player.animeku.org/?data=$src"
            }
            urls.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun collectJsonUrls(json: String): List<String> {
        return try {
            collectUrls(mapper.readTree(json))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizePlaySobatUrl(name: String, url: String?): String? {
        val value = url?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
        val slug = value.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() && it != "-" }
        return when {
            name.equals("VIDHIDE", ignoreCase = true) || name.equals("TURBOVIP", ignoreCase = true) ->
                slug?.let { "https://dintezuvio.com/embed/$it" }
            name.equals("STREAMWISH", ignoreCase = true) ->
                slug?.let { "https://hglink.to/e/$it" }
            else -> value.replace(".ink", ".icu")
        }
    }

    private fun decodeJsonString(value: String): String? {
        return try {
            mapper.readValue("\"$value\"", String::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptCryptoJsPassphrase(encrypted: String, passphrase: String): String {
        val cipherJson = String(Base64.getDecoder().decode(encrypted), Charsets.UTF_8)
        val node = mapper.readTree(cipherJson)
        val salt = node.path("s").asText().takeIf { it.isNotBlank() }?.let { hexToBytes(it) } ?: ByteArray(0)
        val keyAndIv = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt, 48)
        return decryptAesCbcBase64(
            data = node.path("ct").asText(),
            key = keyAndIv.copyOfRange(0, 32),
            iv = keyAndIv.copyOfRange(32, 48)
        )
    }

    private fun decryptAesCbcBase64(data: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(Base64.getDecoder().decode(data)), Charsets.UTF_8)
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        val output = mutableListOf<Byte>()
        var previous = ByteArray(0)
        while (output.size < length) {
            digest.reset()
            digest.update(previous)
            digest.update(password)
            digest.update(salt)
            previous = digest.digest()
            previous.forEach { output.add(it) }
        }
        return output.take(length).toByteArray()
    }

    private fun hexToBytes(value: String): ByteArray {
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun collectUrls(node: JsonNode?): List<String> {
        val urls = mutableListOf<String>()

        fun visit(value: JsonNode?) {
            when {
                value == null || value.isNull -> Unit
                value.isTextual -> value.asText().trim()
                    .takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                    ?.let { urls.add(it) }
                value.isArray -> value.forEach { visit(it) }
                value.isObject -> value.fields().forEachRemaining { visit(it.value) }
            }
        }

        visit(node)
        return urls.distinct()
    }
}
