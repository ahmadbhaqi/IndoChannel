package com.example

internal object PopularProviderEpisodeParser {
    private val compactPair = Regex("""(?i)\bS(?:eason)?\s*(\d+)\s*[/x._ -]\s*E(?:pisode|p)?\s*(\d+)\b""")
    private val seasonPattern = Regex("""(?i)\b(?:season|s)\s*[-.:]?\s*(\d+)\b""")
    private val episodePattern = Regex("""(?i)\b(?:episode|eps?|e)\s*[-.:]?\s*(\d+)\b""")

    fun position(label: String): Pair<Int?, Int?> {
        compactPair.find(label)?.let { match ->
            return match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
        }
        val season = seasonPattern.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = episodePattern.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: label.trim().takeIf { it.matches(Regex("""\d+""")) }?.toIntOrNull()
        return season to episode
    }
}
