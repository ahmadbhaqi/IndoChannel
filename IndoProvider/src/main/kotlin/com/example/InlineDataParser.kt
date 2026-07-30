package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object InlineDataParser {
    private const val MAX_PLAYABLE_INLINE_PAGE_SIZE = 1_000_000
    private const val MAX_PLAYABLE_INLINE_SOURCES = 48
    private const val MAX_INERTIA_PAGE_SIZE = 1_000_000
    private const val MAX_INERTIA_DATA_SIZE = 512_000
    private const val PLAY_SOBAT_KEY = "96fb393f57087e9333cc067bf4aa378e"
    private const val KURONIME_PASSPHRASE = "3&!Z0M,VIZ;dZW=="
    private const val KURONIME_LEGACY_PASSPHRASE = "3&!Z0M,;dZWrawa=="
    private val mapper = jacksonObjectMapper()
    private val urlRegex = Regex("""url:"(https?://[^"]+)"""")
    private val asiaStreamSniffRegex = Regex(
        """(?s)sniff\(\s*"[^"]*"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(?:null|"[^"]*")\s*,\s*\[.*?]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*(?:true|false)\s*\)"""
    )

    fun decodeEscapedInlineData(html: String): String {
        return Parser.unescapeEntities(html, true)
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "")
    }

    fun inlinePlayerSources(html: String): List<InlinePlayerSource> =
        parseInlinePlayerSources(html, Int.MAX_VALUE, Int.MAX_VALUE)

    fun boundedInlinePlayerUrls(
        html: String,
        maxSources: Int,
        maxInputChars: Int
    ): List<String> {
        if (maxSources <= 0 || maxInputChars <= 0) return emptyList()
        return parseInlinePlayerSources(html, maxSources, maxInputChars).map { it.url }
    }

    private fun parseInlinePlayerSources(
        html: String,
        maxSources: Int,
        maxInputChars: Int
    ): List<InlinePlayerSource> {
        val boundedHtml = if (html.length > maxInputChars) html.take(maxInputChars) else html
        val data = decodeEscapedInlineData(boundedHtml)
        val sourceRegex = Regex(
            """(?i)(?:[\"']?file[\"']?|[\"']?src[\"']?)\s*:\s*[\"']([^\"']+)[\"']"""
        )
        val mimeRegex = Regex(
            """(?i)[\"']?(?:type|mimeType)[\"']?\s*:\s*[\"']([^\"']+)[\"']"""
        )
        return sourceRegex.findAll(data)
            .mapNotNull { match ->
                val url = match.groupValues[1].trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val objectStart = data.lastIndexOf('{', match.range.first)
                val objectEnd = data.indexOf('}', match.range.last)
                val context = if (objectStart >= 0 && objectEnd >= match.range.last && objectEnd - objectStart <= 2_000) {
                    data.substring(objectStart, objectEnd + 1)
                } else {
                    data.substring(
                        (match.range.first - 250).coerceAtLeast(0),
                        (match.range.last + 251).coerceAtMost(data.length)
                    )
                }
                InlinePlayerSource(
                    url = url,
                    mimeType = mimeRegex.find(context)?.groupValues?.getOrNull(1)?.trim()
                )
            }
            .distinctBy { it.url }
            .take(maxSources)
            .toList()
    }

    fun inlinePlayerUrls(html: String): List<String> = inlinePlayerSources(html).map { it.url }

    fun playableInlineUrls(
        html: String,
        playerUrl: String? = null
    ): List<String> {
        if (html.isBlank() || html.length > MAX_PLAYABLE_INLINE_PAGE_SIZE) {
            return emptyList()
        }
        val inlineFallbackHtml = if (isFileDonPlayerUrl(playerUrl)) {
            Jsoup.parse(html).apply {
                select("[data-page]").forEach { element ->
                    element.removeAttr("data-page")
                }
            }.outerHtml()
        } else {
            html
        }
        return (
            inertiaPlaybackUrls(html, playerUrl) +
                parseInlinePlayerSources(
                    inlineFallbackHtml,
                    MAX_PLAYABLE_INLINE_SOURCES,
                    MAX_PLAYABLE_INLINE_PAGE_SIZE
                )
                    .filter { source ->
                        val mimeType = source.mimeType.orEmpty().lowercase()
                        val path = runCatching { URI(source.url).path.orEmpty().lowercase() }
                            .getOrDefault(source.url.substringBefore('?').lowercase())
                        path.endsWith(".m3u8") ||
                            path.endsWith(".mp4") ||
                            mimeType.contains("mpegurl") ||
                            mimeType == "hls" ||
                            mimeType.startsWith("video/")
                    }
                    .map { it.url }
            )
            .distinct()
            .take(MAX_PLAYABLE_INLINE_SOURCES)
    }

    private fun inertiaPlaybackUrls(html: String, playerUrl: String?): List<String> {
        if (
            html.isBlank() ||
            html.length > MAX_INERTIA_PAGE_SIZE ||
            !isFileDonPlayerUrl(playerUrl)
        ) return emptyList()
        return Jsoup.parse(html).select("#app[data-page]")
            .asSequence()
            .take(1)
            .mapNotNull { element ->
            val data = element.attr("data-page")
                .trim()
                .takeIf { it.length in 1..MAX_INERTIA_DATA_SIZE }
                ?: return@mapNotNull null
            runCatching {
                val page = mapper.readTree(data)
                if (
                    !page.path("component").asText()
                        .equals("public/embed", ignoreCase = true)
                ) return@runCatching null
                val hls = sequenceOf(
                    page.at("/props/media/hls_url").asText()
                ).map(String::trim)
                    .firstOrNull { it.isHlsUrl() }
                if (hls != null) return@runCatching hls

                sequenceOf(
                    page.at("/props/url").asText()
                ).map(String::trim)
                    .firstOrNull { candidate ->
                        isSafeRemoteHttpUrl(candidate) &&
                            (isDirectHttpVideo(candidate) || candidate.isHlsUrl())
                    }
            }.getOrNull()
        }.distinct().toList()
    }

    private fun isFileDonPlayerUrl(playerUrl: String?): Boolean = runCatching {
        val uri = URI(playerUrl ?: return false)
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
        uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.userInfo == null &&
            (host == "filedon.co" || host.endsWith(".filedon.co"))
    }.getOrDefault(false)

    private fun String.isHlsUrl(): Boolean =
        isSafeRemoteHttpUrl(this) &&
            runCatching { URI(this).path.orEmpty().endsWith(".m3u8", ignoreCase = true) }
                .getOrDefault(false)

    fun isDirectHttpVideo(url: String): Boolean {
        return runCatching {
            val uri = URI(url)
            if (uri.scheme !in setOf("http", "https")) return false
            val path = uri.path.orEmpty().lowercase()
            if (path.endsWith(".mp4") || path.endsWith("/videoplayback")) return true
            val query = URLDecoder.decode(uri.rawQuery.orEmpty(), Charsets.UTF_8.name()).lowercase()
            query.split('&').any { parameter ->
                parameter == "mime=video/mp4" || parameter.startsWith("mime=video/")
            }
        }.getOrDefault(false)
    }

    fun bloggerToken(playerUrl: String): String? {
        return runCatching {
            val uri = URI(playerUrl)
            val host = uri.host.orEmpty()
            if (!(host.equals("blogger.com", ignoreCase = true) ||
                    host.endsWith(".blogger.com", ignoreCase = true)) ||
                uri.path != "/video.g"
            ) return null
            uri.rawQuery.orEmpty()
                .split('&')
                .mapNotNull { field ->
                    val pieces = field.split('=', limit = 2)
                    if (pieces.firstOrNull() != "token") null
                    else pieces.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
                }
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun bloggerBootstrap(html: String): BloggerBootstrap? {
        val sid = Regex("""[\"']FdrFJe[\"']\s*:\s*[\"']([^\"']+)[\"']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val buildLabel = Regex("""[\"']cfb2h[\"']\s*:\s*[\"']([^\"']+)[\"']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return BloggerBootstrap(sid, buildLabel)
    }

    fun bloggerRpcPayload(token: String): String {
        val inner = mapper.writeValueAsString(arrayOf<Any?>(token, null, 0))
        return mapper.writeValueAsString(
            arrayOf(
                arrayOf(
                    arrayOf<Any?>("WcwnYd", inner, null, "generic")
                )
            )
        )
    }

    fun bloggerRpcFormBody(token: String): String {
        val encoded = URLEncoder.encode(bloggerRpcPayload(token), Charsets.UTF_8.name())
        // The current batchexecute handler requires the same trailing form
        // separator emitted by Blogger's own player client.
        return "f.req=$encoded&"
    }

    fun bloggerVideoUrls(response: String): List<String> {
        val urls = mutableListOf<String>()

        fun visit(node: JsonNode?) {
            when {
                node == null || node.isNull -> Unit
                node.isArray -> {
                    if (node.size() >= 3 &&
                        node[0].asText() == "wrb.fr" &&
                        node[1].asText() == "WcwnYd" &&
                        node[2].isTextual
                    ) {
                        runCatching { mapper.readTree(node[2].asText()) }
                            .getOrNull()
                            ?.let { inner -> urls += collectUrls(inner).filter(::isDirectHttpVideo) }
                    }
                    node.forEach(::visit)
                }
                node.isObject -> {
                    val fields = node.fields()
                    while (fields.hasNext()) visit(fields.next().value)
                }
            }
        }

        response.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("[[") }
            .forEach { line -> runCatching { mapper.readTree(line) }.getOrNull()?.let(::visit) }

        // Blogger's legacy response used an inline VIDEO_CONFIG object. Keeping
        // this fallback lets old mirrors work while the current WcwnYd RPC is used.
        balancedObjectAfter(response, Regex("""var\s+VIDEO_CONFIG\s*="""))
            ?.let { json ->
                runCatching { mapper.readTree(json) }.getOrNull()
                    ?.let { urls += collectUrls(it).filter(::isDirectHttpVideo) }
            }

        return urls.distinct()
    }

    private fun balancedObjectAfter(input: String, marker: Regex): String? {
        val markerEnd = marker.find(input)?.range?.last?.plus(1) ?: return null
        val start = input.indexOf('{', markerEnd).takeIf { it >= 0 } ?: return null
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until input.length.coerceAtMost(start + 2_000_000)) {
            val char = input[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return input.substring(start, index + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
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

    fun asiaStreamMasterUrl(html: String, playerUrl: String): String? {
        val match = asiaStreamSniffRegex.find(html) ?: return null
        val uid = match.groupValues[1].takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
        val hash = match.groupValues[2].takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
        val cache = match.groupValues[3]
        return runCatching {
            val player = java.net.URI(playerUrl)
            val scheme = player.scheme?.lowercase()
            if (scheme !in setOf("http", "https") || player.host.isNullOrBlank()) return null
            java.net.URI(
                scheme,
                player.authority,
                "/m3u8/$uid/$hash/master.txt",
                "s=1&cache=$cache",
                null
            ).toString()
        }.getOrNull()
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
            val iv = decodeBase64Compat(node.path("iv").asText()) ?: return emptyList()
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
        return Regex("""var\s+_0xa100d42aa\s*=\s*["']([^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    fun kuronimeMirrorUrls(encrypted: String?): List<String> {
        val value = encrypted?.takeIf { it.isNotBlank() } ?: return emptyList()
        return sequenceOf(KURONIME_PASSPHRASE, KURONIME_LEGACY_PASSPHRASE)
            .mapNotNull { passphrase ->
                try {
                    val decrypted = decryptCryptoJsPassphrase(value, passphrase)
                    collectUrls(mapper.readTree(decrypted))
                } catch (_: Exception) {
                    null
                }
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    fun kuronimeApiUrls(responseJson: String): List<String> {
        return try {
            val node = mapper.readTree(responseJson)
            val urls = mutableListOf<String>()
            listOf("src", "src_sd", "mirror").forEach { field ->
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
        val cipherBytes = decodeBase64Compat(encrypted) ?: error("Invalid encrypted payload")
        val cipherJson = String(cipherBytes, Charsets.UTF_8)
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
        val encrypted = decodeBase64Compat(data) ?: error("Invalid encrypted data")
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
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
                value.isObject -> {
                    val fields = value.fields()
                    while (fields.hasNext()) visit(fields.next().value)
                }
            }
        }

        visit(node)
        return urls.distinct()
    }
}

internal data class BloggerBootstrap(
    val sid: String,
    val buildLabel: String
)

internal data class InlinePlayerSource(
    val url: String,
    val mimeType: String?
) {
    val isHls: Boolean
        get() = mimeType?.contains("mpegurl", ignoreCase = true) == true
}
