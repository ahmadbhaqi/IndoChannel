package com.example

internal object StreamTapePlayerParser {
    private val supportedHosts = setOf(
        "streamtape.com",
        "streamtape.net",
        "streamtape.to",
        "streamtape.cc",
        "streamtape.xyz",
        "streamta.pe",
        "strcloud.in",
        "strtape.cloud"
    )

    fun supports(host: String, path: String): Boolean {
        val normalizedHost = host.lowercase()
        val normalizedPath = path.lowercase()
        return supportedHosts.any { normalizedHost == it || normalizedHost.endsWith(".$it") } &&
            (normalizedPath.contains("/e/") || normalizedPath.contains("/embed/"))
    }

    fun directUrl(html: String, playerUrl: String): String? {
        return sequenceOf("botlink", "robotlink", "ideoolink")
            .flatMap { assignments(html, it).asSequence() }
            .mapNotNull { rightHandSide ->
                StringExpressionParser(rightHandSide).parse()
                    ?.replace("&amp;", "&")
                    ?.let { ProviderHtmlParser.absoluteUrl(it, playerUrl) }
                    ?.takeIf { it.contains("/get_video", ignoreCase = true) }
            }
            .firstOrNull()
    }

    private fun assignments(html: String, elementId: String): List<String> {
        val occurrences = mutableListOf<Pair<Int, String>>()
        listOf("getElementById('$elementId')", "getElementById(\"$elementId\")").forEach { marker ->
            var cursor = 0
            while (cursor < html.length) {
                val markerIndex = html.indexOf(marker, cursor)
                if (markerIndex < 0) break
                val propertyIndex = html.indexOf(".innerHTML", markerIndex)
                if (propertyIndex >= 0 && propertyIndex - markerIndex <= 100) {
                    val equalsIndex = html.indexOf('=', propertyIndex)
                    if (equalsIndex >= 0 && equalsIndex - propertyIndex <= 40) {
                        statementEnd(html, equalsIndex + 1)?.let { endIndex ->
                            occurrences += markerIndex to html.substring(equalsIndex + 1, endIndex)
                        }
                    }
                }
                cursor = markerIndex + marker.length
            }
        }
        return occurrences.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun statementEnd(html: String, start: Int): Int? {
        var quote: Char? = null
        var escaped = false
        var parentheses = 0
        val limit = (start + 4_096).coerceAtMost(html.length)
        for (index in start until limit) {
            val char = html[index]
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
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                ';' -> if (parentheses == 0) return index
            }
        }
        return null
    }

    private class StringExpressionParser(private val input: String) {
        private var cursor = 0
        private var operations = 0
        private var nesting = 0

        fun parse(): String? {
            val value = expression() ?: return null
            skipWhitespace()
            return value.takeIf { cursor == input.length && it.length <= 8_192 }
        }

        private fun expression(): String? {
            var value = term() ?: return null
            while (true) {
                skipWhitespace()
                if (!consume('+')) return value
                val next = term() ?: return null
                if (value.length + next.length > 8_192) return null
                value += next
            }
        }

        private fun term(): String? {
            var value = primary() ?: return null
            while (true) {
                skipWhitespace()
                val method = when {
                    consumeWord(".substring") -> "substring"
                    consumeWord(".slice") -> "slice"
                    else -> return value
                }
                if (++operations > 16 || !consume('(')) return null
                skipWhitespace()
                val start = number() ?: return null
                skipWhitespace()
                var end: Int? = null
                if (consume(',')) {
                    skipWhitespace()
                    end = number() ?: return null
                    skipWhitespace()
                }
                if (!consume(')')) return null
                if (start > value.length || (end != null && end > value.length)) return null
                value = if (method == "substring") {
                    val finish = end ?: value.length
                    value.substring(minOf(start, finish), maxOf(start, finish))
                } else {
                    val finish = end ?: value.length
                    if (finish < start) "" else value.substring(start, finish)
                }
            }
        }

        private fun primary(): String? {
            skipWhitespace()
            val quote = input.getOrNull(cursor)
            if (quote == '\'' || quote == '"') return quoted(quote)
            if (!consume('(')) return null
            if (++nesting > 16) return null
            val value = expression() ?: return null
            skipWhitespace()
            if (!consume(')')) return null
            nesting--
            return value
        }

        private fun quoted(quote: Char): String? {
            cursor++
            val value = StringBuilder()
            while (cursor < input.length) {
                val char = input[cursor++]
                if (char == quote) return value.toString()
                if (char != '\\') {
                    value.append(char)
                    continue
                }
                val escaped = input.getOrNull(cursor++) ?: return null
                when (escaped) {
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'x' -> value.append(hexChar(2) ?: return null)
                    'u' -> value.append(hexChar(4) ?: return null)
                    else -> value.append(escaped)
                }
                if (value.length > 8_192) return null
            }
            return null
        }

        private fun hexChar(length: Int): Char? {
            if (cursor + length > input.length) return null
            val value = input.substring(cursor, cursor + length).toIntOrNull(16) ?: return null
            cursor += length
            return value.toChar()
        }

        private fun number(): Int? {
            val start = cursor
            while (input.getOrNull(cursor)?.isDigit() == true && cursor - start < 6) cursor++
            return input.substring(start, cursor).toIntOrNull()
        }

        private fun consume(expected: Char): Boolean {
            if (input.getOrNull(cursor) != expected) return false
            cursor++
            return true
        }

        private fun consumeWord(expected: String): Boolean {
            if (!input.regionMatches(cursor, expected, 0, expected.length)) return false
            cursor += expected.length
            return true
        }

        private fun skipWhitespace() {
            while (input.getOrNull(cursor)?.isWhitespace() == true) cursor++
        }
    }
}
