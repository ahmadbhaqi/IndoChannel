package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.JsUnpacker
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.jsoup.Jsoup

internal data class VidhideResolveRequest(val apiUrl: String, val playerUrl: String, val body: String, val password: String)
internal typealias VidhideApiFetcher = suspend (request: VidhideResolveRequest) -> String

/** Decodes the public Vidhide bootstrap and its PBKDF2/AES source envelope without evaluating JavaScript. */
internal object VidhidePlayerParser {
    private val mapper = jacksonObjectMapper()
    private val assignments = Regex("""\b(apx|pd|ps|kaken)\s*=\s*["']([^"']+)["']""")
    private val aaDigits = listOf(
        "(c^_^o)", "(ﾟΘﾟ)", "((o^_^o)-(ﾟΘﾟ))", "(o^_^o)", "(ﾟｰﾟ)",
        "((ﾟｰﾟ)+(ﾟΘﾟ))", "((o^_^o)+(o^_^o))", "((ﾟｰﾟ)+(o^_^o))",
        "((ﾟｰﾟ)+(ﾟｰﾟ))", "((ﾟｰﾟ)+(ﾟｰﾟ)+(ﾟΘﾟ))", "(ﾟДﾟ).ﾟωﾟﾉ", "(ﾟДﾟ).ﾟΘﾟﾉ",
        "(ﾟДﾟ)['c']", "(ﾟДﾟ).ﾟｰﾟﾉ", "(ﾟДﾟ).ﾟДﾟﾉ", "(ﾟДﾟ)[ﾟΘﾟ]"
    ).mapIndexed { index, token -> token to index.toString(16) }.sortedByDescending { it.first.length }

    fun supports(host: String): Boolean = host.lowercase() in setOf("vidhide.org", "www.vidhide.org")

    fun resolveRequest(html: String, playerUrl: String): VidhideResolveRequest? = runCatching {
        if (html.length > 500_000) return@runCatching null
        val player = URI(playerUrl)
        if (player.scheme != "https" || !supports(player.host.orEmpty()) || player.rawUserInfo != null ||
            player.port !in setOf(-1, 443) || !Regex("""^/embed/[A-Za-z0-9_-]{2,512}/?$""").matches(player.rawPath.orEmpty())
        ) return@runCatching null
        val config = Jsoup.parse(html).select("script").take(16).firstNotNullOfOrNull { element ->
            val decoded = decodeAa(element.data()) ?: return@firstNotNullOfOrNull null
            val unpacker = JsUnpacker(decoded)
            if (unpacker.detect()) unpacker.unpack() else decoded
        }?.takeIf { it.length <= 64_000 } ?: return@runCatching null
        val values = assignments.findAll(config).associate { it.groupValues[1] to it.groupValues[2] }
        val password = values["pd"]?.takeIf { it.matches(Regex("""\d{8,12}""")) } ?: return@runCatching null
        fun token(name: String) = values[name]?.takeIf {
            it.length in 1..8_192 && it.matches(Regex("""[A-Za-z0-9+/,=-]+"""))
        }
        val signature = token("ps") ?: return@runCatching null
        val body = token("kaken") ?: return@runCatching null
        val encodedApi = values["apx"]?.takeIf { it.length <= 1_024 } ?: return@runCatching null
        val api = URI(String(decodeBase64Compat(encodedApi) ?: return@runCatching null, Charsets.UTF_8))
        val host = api.host?.lowercase() ?: return@runCatching null
        if (api.scheme != "https" || api.rawUserInfo != null || api.port !in setOf(-1, 443) ||
            api.rawQuery != null || api.rawFragment != null || api.rawPath != "/api-config/" ||
            !(host == "vidhide.org" || Regex("""s\d{1,2}\.vidhide\.org""").matches(host))
        ) return@runCatching null
        val endpoint = URI("https", null, host, api.port, "/api/", null, null).toString()
        VidhideResolveRequest("$endpoint?p=${URLEncoder.encode(signature, "UTF-8")}", playerUrl, body, password)
    }.getOrNull()

    private fun decodeAa(script: String): String? = runCatching {
        if (script.length > 500_000 || !script.contains("(ﾟДﾟ)[ﾟoﾟ]+")) return@runCatching null
        val compact = script.replace(Regex("""\s+"""), "")
        val start = compact.indexOf("(ﾟДﾟ)[ﾟoﾟ]+") + "(ﾟДﾟ)[ﾟoﾟ]+".length
        val end = compact.indexOf("(ﾟДﾟ)[ﾟoﾟ])", start).takeIf { it >= start } ?: return@runCatching null
        val blocks = compact.substring(start, end).split("(ﾟДﾟ)[ﾟεﾟ]+").filter { it.isNotEmpty() }
        if (blocks.size > 64_000) return@runCatching null
        buildString {
            for (block in blocks) {
                val radix = if (block.startsWith("(oﾟｰﾟo)+")) 16 else 8
                var digits = block.removePrefix("(oﾟｰﾟo)+")
                aaDigits.forEach { (token, value) -> digits = digits.replace(token, value) }
                digits = digits.replace("+", "")
                if (digits.length !in 1..6 || !digits.matches(Regex("[0-9a-f]+"))) return@runCatching null
                val char = digits.toInt(radix).takeIf { it in 0..65_535 } ?: return@runCatching null
                append(char.toChar())
            }
        }
    }.getOrNull()

    fun sources(response: String, password: String): List<FreeonMediaSource> = runCatching {
        if (response.length > 512_000 || password.length !in 1..128) return@runCatching emptyList()
        val content = response.trim()
        val json = if (content.startsWith("{")) content else {
            val envelope = decodeBase64Compat(content) ?: return@runCatching emptyList()
            if (envelope.size < 32 || (envelope.size - 16) % 16 != 0) return@runCatching emptyList()
            val key = deriveKey(password, envelope.copyOfRange(0, 16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 32), "AES"), IvParameterSpec(key.copyOfRange(32, 48)))
            String(cipher.doFinal(envelope.copyOfRange(16, envelope.size)), Charsets.UTF_8)
        }
        val root = mapper.readTree(json)
        if (root.path("status").asText() != "ok") return@runCatching emptyList()
        root.path("sources").takeIf { it.isArray }?.take(16).orEmpty().mapNotNull { source ->
            val url = source.path("file").asText().trim().takeIf {
                it.length <= 8_192 && isSafeRemoteHttpUrl(it)
            } ?: return@mapNotNull null
            FreeonMediaSource(source.path("label").asText().ifBlank { "Video" }, url, source.path("type").asText())
        }.distinctBy { it.url }
    }.getOrDefault(emptyList())

    // HMAC-SHA256 is available on older supported Android versions too.
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val key = ByteArray(48)
        for (block in 1..2) {
            var value = mac.doFinal(salt + byteArrayOf(0, 0, 0, block.toByte()))
            val combined = value.copyOf()
            repeat(9_999) {
                value = mac.doFinal(value)
                combined.indices.forEach { index -> combined[index] = (combined[index].toInt() xor value[index].toInt()).toByte() }
            }
            combined.copyInto(key, (block - 1) * 32, 0, minOf(32, key.size - (block - 1) * 32))
        }
        return key
    }
}
