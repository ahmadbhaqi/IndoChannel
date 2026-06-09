package com.example

internal fun fixIframeUrl(url: String, fixUrlFn: (String) -> String): String = when {
    url.startsWith("//") -> "https:$url"
    url.startsWith("http") -> url
    else -> fixUrlFn(url)
}

internal fun parseQualityFromString(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}

internal fun parseEpisodeNumber(text: String): Int? {
    return Regex("Episode\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

internal fun cleanTitleForEpisode(rawTitle: String): String {
    return rawTitle.replaceFirst(Regex("(?i)Permalink ke\\s*"), "").trim()
}
