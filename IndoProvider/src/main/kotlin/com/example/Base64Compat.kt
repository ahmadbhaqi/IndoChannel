package com.example

/** Small Base64 decoder that works on Android API 21 and accepts URL-safe input. */
internal fun decodeBase64Compat(raw: String): ByteArray? {
    val output = ByteArray((raw.length * 3) / 4 + 3)
    var outputSize = 0
    var buffer = 0
    var bufferedBits = 0
    var symbols = 0
    var padding = 0
    var sawPadding = false

    for (char in raw) {
        if (char.isWhitespace()) continue
        if (char == '=') {
            sawPadding = true
            padding++
            if (padding > 2) return null
            continue
        }
        if (sawPadding) return null

        val value = when (char) {
            in 'A'..'Z' -> char.code - 'A'.code
            in 'a'..'z' -> char.code - 'a'.code + 26
            in '0'..'9' -> char.code - '0'.code + 52
            '+', '-' -> 62
            '/', '_' -> 63
            else -> return null
        }
        symbols++
        buffer = (buffer shl 6) or value
        bufferedBits += 6
        if (bufferedBits >= 8) {
            bufferedBits -= 8
            output[outputSize++] = ((buffer ushr bufferedBits) and 0xff).toByte()
            buffer = if (bufferedBits == 0) 0 else buffer and ((1 shl bufferedBits) - 1)
        }
    }

    if (symbols % 4 == 1) return null
    if (padding > 0 && (symbols + padding) % 4 != 0) return null
    if (padding == 1 && symbols % 4 != 3) return null
    if (padding == 2 && symbols % 4 != 2) return null
    return output.copyOf(outputSize)
}
