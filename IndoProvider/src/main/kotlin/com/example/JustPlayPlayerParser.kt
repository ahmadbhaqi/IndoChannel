package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal enum class JustPlayHttpMethod {
    GET,
    POST
}

internal data class JustPlayHttpRequest(
    val method: JustPlayHttpMethod,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null
)

internal typealias JustPlayApiFetcher = suspend (request: JustPlayHttpRequest) -> String

internal data class JustPlayEmbedContext(
    val settingsUrl: String,
    val captchaUrl: String,
    val captchaVerifyUrl: String,
    val playbackUrl: String,
    val accessChallengeUrl: String,
    val accessAttestUrl: String,
    val headers: Map<String, String>
)

private data class JustPlayPowChallenge(
    val nonce: String,
    val difficulty: Int,
    val token: String
)

/** Resolves the current JustPlay embed API without executing its browser bundle. */
internal object JustPlayPlayerParser {
    private const val MAX_URL_SIZE = 8_192
    private const val MAX_CODE_SIZE = 128
    private const val MAX_NONCE_SIZE = 1_024
    private const val MAX_TOKEN_SIZE = 8_192
    private const val MAX_SETTINGS_RESPONSE_SIZE = 256_000
    private const val MAX_CAPTCHA_RESPONSE_SIZE = 64_000
    private const val MAX_PLAYBACK_RESPONSE_SIZE = 2_000_000

    // The browser implementation rejects work that runs beyond 20 seconds.
    // Keep the resolver below the outer per-candidate timeout as well.
    private const val DEFAULT_MAX_POW_MILLIS = 15_000L
    private const val DEFAULT_MAX_POW_ATTEMPTS = 1_000_000
    private const val MAX_POW_DIFFICULTY = 20
    private const val POW_YIELD_INTERVAL = 1_024

    private const val POW_MEMORY_WORDS = 512
    private const val POW_MEMORY_MASK = POW_MEMORY_WORDS - 1
    private const val POW_MIX_ROUNDS = 2
    private const val POW_GOLDEN_RATIO = -1_640_531_535 // 0x9e3779b1
    private const val POW_MURMUR_MIX = -2_048_144_777 // 0x85ebca77

    private val codePattern = Regex("[A-Za-z0-9_-]{1,$MAX_CODE_SIZE}")
    private val mapper = jacksonObjectMapper()

    fun supports(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        return normalized == "justplay.cam" || normalized.endsWith(".justplay.cam")
    }

    /** Builds same-origin endpoints and the embed context headers used by the site frontend. */
    fun context(playerUrl: String, referer: String?): JustPlayEmbedContext? = runCatching {
        if (playerUrl.isBlank() || playerUrl.length > MAX_URL_SIZE) return@runCatching null
        val playerUri = URI(playerUrl)
        val scheme = playerUri.scheme?.lowercase().takeIf { it == "https" || it == "http" }
            ?: return@runCatching null
        val host = playerUri.host?.takeIf { it.isNotBlank() } ?: return@runCatching null
        if (!supports(host) || playerUri.userInfo != null) return@runCatching null

        val pathSegments = playerUri.rawPath.orEmpty().split('/')
        val code = pathSegments.getOrNull(2)
            ?.takeIf { pathSegments.getOrNull(1) == "e" && codePattern.matches(it) }
            ?: return@runCatching null
        val origin = URI(scheme, null, host, playerUri.port, null, null, null).toString()
            .trimEnd('/')
        val base = "$origin/api/videos/$code/embed"

        val headers = linkedMapOf<String, String>()
        val parentUri = referer
            ?.trim()
            ?.takeIf { it.length <= MAX_URL_SIZE }
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?.takeIf {
                it.userInfo == null && !it.host.isNullOrBlank() &&
                    it.scheme?.lowercase() in setOf("http", "https")
            }
        parentUri?.host
            ?.lowercase()
            ?.trimEnd('.')
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["X-Embed-Origin"] = it }
        parentUri?.toString()?.let { headers["X-Embed-Referer"] = it }
        headers["X-Embed-Parent"] = playerUrl

        JustPlayEmbedContext(
            settingsUrl = "$base/settings",
            captchaUrl = "$base/captcha",
            captchaVerifyUrl = "$base/captcha/verify",
            playbackUrl = "$base/playback",
            accessChallengeUrl = "$origin/api/videos/access/challenge",
            accessAttestUrl = "$origin/api/videos/access/attest",
            headers = headers
        )
    }.getOrNull()

    /** Executes the bounded settings -> PoW captcha -> playback flow. */
    suspend fun resolve(
        playerUrl: String,
        referer: String?,
        fetcher: JustPlayApiFetcher
    ): BysePlayback? {
        val context = context(playerUrl, referer) ?: return null
        val settingsJson = fetcher(
            JustPlayHttpRequest(
                method = JustPlayHttpMethod.GET,
                url = context.settingsUrl,
                headers = context.headers
            )
        )
        val captchaRequired = captchaRequired(settingsJson) ?: return null
        val fingerprint = JustPlayDeviceAttestor.attest(context, fetcher) ?: return null
        val fingerprintJson = fingerprint.jsonValue()

        val captchaToken = if (captchaRequired) {
            val challengeJson = fetcher(
                JustPlayHttpRequest(
                method = JustPlayHttpMethod.POST,
                url = context.captchaUrl,
                headers = context.headers,
                body = mapper.writeValueAsString(mapOf("fingerprint" to fingerprintJson))
                )
            )
            val challenge = challenge(challengeJson) ?: return null
            val solution = solvePow(challenge.nonce, challenge.difficulty) ?: return null
            val verifyBody = mapper.writeValueAsString(
                linkedMapOf(
                    "pow_token" to challenge.token,
                    "solution" to solution,
                    "fingerprint" to fingerprintJson
                )
            )
            val verifyJson = fetcher(
                JustPlayHttpRequest(
                    method = JustPlayHttpMethod.POST,
                    url = context.captchaVerifyUrl,
                    headers = context.headers,
                    body = verifyBody
                )
            )
            verifiedCaptchaToken(verifyJson) ?: return null
        } else {
            null
        }

        val playbackHeaders = context.headers.toMutableMap()
        captchaToken?.let { playbackHeaders["X-Captcha-Token"] = it }
        val playbackJson = fetcher(
            JustPlayHttpRequest(
                method = JustPlayHttpMethod.POST,
                url = context.playbackUrl,
                headers = playbackHeaders,
                body = mapper.writeValueAsString(mapOf("fingerprint" to fingerprintJson))
            )
        )
        if (playbackJson.length > MAX_PLAYBACK_RESPONSE_SIZE) return null
        return BysePlayerParser.playback(playbackJson)
    }

    /**
     * Solves JustPlay's current memory-hard leading-zero challenge. The attempt,
     * difficulty, input-size, wall-clock, and coroutine work are all bounded.
     */
    suspend fun solvePow(
        nonce: String,
        difficulty: Int,
        maxAttempts: Int = DEFAULT_MAX_POW_ATTEMPTS,
        maxMillis: Long = DEFAULT_MAX_POW_MILLIS
    ): String? {
        if (nonce.isBlank() || nonce.length > MAX_NONCE_SIZE) return null
        if (difficulty !in 0..MAX_POW_DIFFICULTY) return null
        if (difficulty == 0) return "0"
        val boundedAttempts = maxAttempts.coerceIn(0, DEFAULT_MAX_POW_ATTEMPTS)
        val boundedMillis = maxMillis.coerceIn(0L, DEFAULT_MAX_POW_MILLIS)
        if (boundedAttempts == 0 || boundedMillis == 0L) return null

        val startedAt = System.nanoTime()
        val state = IntArray(4)
        val memory = IntArray(POW_MEMORY_WORDS)
        val output = IntArray(8)
        var attempt = 0
        while (attempt < boundedAttempts) {
            val end = minOf(attempt + POW_YIELD_INTERVAL, boundedAttempts)
            while (attempt < end) {
                powHash("$nonce:$attempt", state, memory, output)
                if (leadingZeroBits(output) >= difficulty) return attempt.toString()
                attempt += 1
            }
            if ((System.nanoTime() - startedAt) / 1_000_000L >= boundedMillis) return null
            currentCoroutineContext().ensureActive()
            yield()
        }
        return null
    }

    fun leadingZeroBits(nonce: String, solution: String): Int {
        if (nonce.isBlank() || nonce.length > MAX_NONCE_SIZE) return 0
        if (!solution.matches(Regex("(?:0|[1-9][0-9]{0,9})"))) return 0
        val state = IntArray(4)
        val memory = IntArray(POW_MEMORY_WORDS)
        val output = IntArray(8)
        powHash("$nonce:$solution", state, memory, output)
        return leadingZeroBits(output)
    }

    private fun captchaRequired(json: String): Boolean? {
        val root = jsonObject(json, MAX_SETTINGS_RESPONSE_SIZE) ?: return null
        val node = root.get("captcha_required") ?: return null
        return node.takeIf(JsonNode::isBoolean)?.booleanValue()
    }

    private fun challenge(json: String): JustPlayPowChallenge? {
        val root = jsonObject(json, MAX_CAPTCHA_RESPONSE_SIZE) ?: return null
        val nonce = boundedText(root.get("pow_nonce"), MAX_NONCE_SIZE) ?: return null
        val token = boundedText(root.get("pow_token"), MAX_TOKEN_SIZE) ?: return null
        val difficultyNode = root.get("pow_difficulty") ?: return null
        val difficulty = when {
            difficultyNode.isIntegralNumber -> difficultyNode.asInt(Int.MIN_VALUE)
            difficultyNode.isTextual -> difficultyNode.asText().toIntOrNull()
            else -> null
        }?.takeIf { it in 0..MAX_POW_DIFFICULTY } ?: return null
        return JustPlayPowChallenge(nonce, difficulty, token)
    }

    private fun verifiedCaptchaToken(json: String): String? {
        val root = jsonObject(json, MAX_CAPTCHA_RESPONSE_SIZE) ?: return null
        if (root.path("status").asText() != "ok") return null
        return boundedText(root.get("token"), MAX_TOKEN_SIZE)
    }

    private fun jsonObject(json: String, maxSize: Int): JsonNode? {
        if (json.isBlank() || json.length > maxSize) return null
        return runCatching { mapper.readTree(json)?.takeIf(JsonNode::isObject) }.getOrNull()
    }

    private fun boundedText(node: JsonNode?, maxSize: Int): String? = node
        ?.takeIf(JsonNode::isTextual)
        ?.asText()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxSize }

    private fun powHash(input: String, state: IntArray, memory: IntArray, output: IntArray) {
        state[0] = 0x6a09e667
        state[1] = 0xbb67ae85L.toInt()
        state[2] = 0x3c6ef372
        state[3] = 0xa54ff53aL.toInt()

        input.forEach { character ->
            state[0] += character.code and 0xff
            state[0] = Integer.rotateLeft(state[0], 7)
            quarterRound(state)
        }
        repeat(8) { quarterRound(state) }

        for (index in memory.indices) {
            quarterRound(state)
            memory[index] = state[0] xor state[2]
        }
        repeat(POW_MIX_ROUNDS) {
            for (index in memory.indices) {
                val lookup = memory[index] and POW_MEMORY_MASK
                var mixed = memory[index] + memory[lookup]
                mixed = Integer.rotateLeft(mixed, 13)
                mixed = mixed xor (memory[(index + 1) and POW_MEMORY_MASK] * POW_GOLDEN_RATIO)
                memory[index] = mixed
                state[0] = state[0] xor mixed
                quarterRound(state)
            }
        }

        val outputStride = POW_MEMORY_WORDS / output.size
        for (outputIndex in output.indices) {
            quarterRound(state)
            var mixed = state[0]
            val offset = outputIndex * outputStride
            for (index in 0 until outputStride) {
                val word = memory[offset + index]
                mixed += word
                mixed = Integer.rotateLeft(mixed, 5)
                mixed = mixed xor (word * POW_MURMUR_MIX)
            }
            output[outputIndex] = mixed xor state[2]
        }
    }

    private fun quarterRound(state: IntArray) {
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 16)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 12)
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 8)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 7)
    }

    private fun leadingZeroBits(words: IntArray): Int {
        var count = 0
        words.forEach { word ->
            if (word == 0) {
                count += Int.SIZE_BITS
            } else {
                return count + Integer.numberOfLeadingZeros(word)
            }
        }
        return count
    }
}
