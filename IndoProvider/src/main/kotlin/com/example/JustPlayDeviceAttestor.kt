package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

internal const val JUSTPLAY_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

internal data class JustPlayFingerprint(
    val token: String,
    val viewerId: String,
    val deviceId: String,
    val confidence: Double
) {
    fun jsonValue(): Map<String, Any> = linkedMapOf(
        "token" to token,
        "viewer_id" to viewerId,
        "device_id" to deviceId,
        "confidence" to confidence
    )
}

/** Reproduces JustPlay's signed, ephemeral P-256 device attestation. */
internal object JustPlayDeviceAttestor {
    private const val MAX_ACCESS_RESPONSE_SIZE = 128_000
    private const val MAX_ACCESS_VALUE_SIZE = 8_192
    private val mapper = jacksonObjectMapper()

    suspend fun attest(
        context: JustPlayEmbedContext,
        fetcher: JustPlayApiFetcher
    ): JustPlayFingerprint? = runCatching {
        val challengeJson = fetcher(
            JustPlayHttpRequest(
                method = JustPlayHttpMethod.POST,
                url = context.accessChallengeUrl,
                headers = context.headers,
                body = "{}"
            )
        )
        val challenge = objectNode(challengeJson) ?: return@runCatching null
        val challengeId = boundedText(challenge.get("challenge_id")) ?: return@runCatching null
        val nonce = boundedText(challenge.get("nonce")) ?: return@runCatching null
        val signed = signedNonce(nonce) ?: return@runCatching null

        val client = linkedMapOf<String, Any>(
            "user_agent" to JUSTPLAY_USER_AGENT,
            "languages" to listOf("id-ID", "id", "en-US", "en"),
            "timezone" to "Asia/Jakarta",
            "hardware_concurrency" to 8,
            "device_memory" to 4,
            "touch_points" to 5,
            "pointer_type" to "coarse,touch",
            "extra" to mapOf(
                "vendor" to "Google Inc.",
                "appVersion" to JUSTPLAY_USER_AGENT
            )
        )
        val requestBody = mapper.writeValueAsString(
            linkedMapOf(
                "viewer_id" to "",
                "device_id" to "",
                "challenge_id" to challengeId,
                "nonce" to nonce,
                "signature" to signed.signature,
                "public_key" to signed.publicJwk,
                "client" to client,
                "storage" to emptyMap<String, String>(),
                "attributes" to mapOf("entropy" to "low")
            )
        )
        val responseJson = fetcher(
            JustPlayHttpRequest(
                method = JustPlayHttpMethod.POST,
                url = context.accessAttestUrl,
                headers = context.headers,
                body = requestBody
            )
        )
        val response = objectNode(responseJson) ?: return@runCatching null
        JustPlayFingerprint(
            token = boundedText(response.get("token")) ?: return@runCatching null,
            viewerId = boundedText(response.get("viewer_id")) ?: return@runCatching null,
            deviceId = boundedText(response.get("device_id")) ?: return@runCatching null,
            confidence = response.get("confidence")
                ?.takeIf(JsonNode::isNumber)
                ?.asDouble(Double.NaN)
                ?.takeIf { it.isFinite() && it in 0.0..1.0 }
                ?: return@runCatching null
        )
    }.getOrNull()

    private data class SignedNonce(
        val signature: String,
        val publicJwk: Map<String, String>
    )

    private fun signedNonce(nonce: String): SignedNonce? = runCatching {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(nonce.toByteArray(Charsets.UTF_8))
        val compactSignature = derSignatureToCompact(signer.sign()) ?: return@runCatching null
        SignedNonce(
            signature = encodeBase64UrlNoPadding(compactSignature),
            publicJwk = linkedMapOf(
                "kty" to "EC",
                "crv" to "P-256",
                "x" to encodeBase64UrlNoPadding(publicKey.w.affineX.toFixedUnsigned(32)),
                "y" to encodeBase64UrlNoPadding(publicKey.w.affineY.toFixedUnsigned(32))
            )
        )
    }.getOrNull()

    private fun objectNode(json: String): JsonNode? {
        if (json.isBlank() || json.length > MAX_ACCESS_RESPONSE_SIZE) return null
        return runCatching { mapper.readTree(json)?.takeIf(JsonNode::isObject) }.getOrNull()
    }

    private fun boundedText(node: JsonNode?): String? = node
        ?.takeIf(JsonNode::isTextual)
        ?.asText()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ACCESS_VALUE_SIZE }

    private fun BigInteger.toFixedUnsigned(size: Int): ByteArray {
        val raw = toByteArray()
        val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= size)
        return ByteArray(size).also { output ->
            unsigned.copyInto(output, destinationOffset = size - unsigned.size)
        }
    }

    private fun derSignatureToCompact(der: ByteArray): ByteArray? = runCatching {
        var index = 0
        if (der.getOrNull(index++)?.toInt() != 0x30) return@runCatching null
        val sequenceLength = readDerLength(der, index) ?: return@runCatching null
        index = sequenceLength.second
        if (sequenceLength.first != der.size - index) return@runCatching null
        if (der.getOrNull(index++)?.toInt() != 0x02) return@runCatching null
        val rLength = readDerLength(der, index) ?: return@runCatching null
        index = rLength.second
        val r = der.copyOfRange(index, index + rLength.first)
        index += rLength.first
        if (der.getOrNull(index++)?.toInt() != 0x02) return@runCatching null
        val sLength = readDerLength(der, index) ?: return@runCatching null
        index = sLength.second
        val s = der.copyOfRange(index, index + sLength.first)
        index += sLength.first
        if (index != der.size) return@runCatching null
        r.toUnsignedScalar().plus(s.toUnsignedScalar())
    }.getOrNull()

    private fun ByteArray.toUnsignedScalar(): ByteArray {
        val unsigned = dropWhile { it == 0.toByte() }.toByteArray()
        require(unsigned.size <= 32)
        return ByteArray(32).also { output ->
            unsigned.copyInto(output, destinationOffset = 32 - unsigned.size)
        }
    }

    private fun readDerLength(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        val first = bytes.getOrNull(offset)?.toInt()?.and(0xff) ?: return null
        if (first < 0x80) return first to (offset + 1)
        val byteCount = first and 0x7f
        if (byteCount !in 1..2 || offset + 1 + byteCount > bytes.size) return null
        var length = 0
        repeat(byteCount) { index ->
            length = (length shl 8) or (bytes[offset + 1 + index].toInt() and 0xff)
        }
        return length to (offset + 1 + byteCount)
    }
}
