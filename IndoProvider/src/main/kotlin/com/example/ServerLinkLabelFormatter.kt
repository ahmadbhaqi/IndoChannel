package com.example

import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI

internal object ServerLinkLabelFormatter {
    private val explicitResolution = Regex("""(?i)(?<!\d)(\d{3,4})\s*p\b""")
    private val serverNumber = Regex(
        """(?i)\b(?:server|mirror|source|player)\s*#?\s*\d+\b"""
    )
    private val technicalWord = Regex(
        """(?i)\b(?:server|mirror|source|video|player|direct|auto|hls|m3u8|mp4|hd|fhd|uhd|cdn)\b"""
    )
    private val separators = Regex("""[\[\](){}|•/:_\\-]+""")
    private val whitespace = Regex("""\s+""")
    private val ipv4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    private val genericHostLabels = setOf(
        "www", "embed", "player", "play", "watch", "video", "videos", "media", "cdn", "stream",
        "com", "net", "org", "co", "in", "id", "ac"
    )
    private val standardResolutions = setOf(
        144, 180, 240, 288, 360, 480, 540, 576, 720, 900, 1080, 1440, 2160, 4320
    )
    private val audioQualifiers = listOf(
        Regex("""(?i)\benglish\b""") to "English",
        Regex("""(?i)\bjapanese\b""") to "Japanese",
        Regex("""(?i)\b(?:indonesia|indo)\b""") to "Indo",
        Regex("""(?i)\bmulti\s+audio\b""") to "Multi Audio",
        Regex("""(?i)\bdual\s+audio\b""") to "Dual Audio",
        Regex("""(?i)\bdub(?:bed)?\b""") to "Dub",
        Regex("""(?i)\bsub(?:bed)?\b""") to "Sub"
    )

    fun format(
        providerName: String,
        source: String,
        currentName: String,
        url: String,
        referer: String?,
        quality: Int
    ): String {
        val resolution = resolution(quality, currentName, source)
        val cleanedName = cleanLabel(currentName, providerName)
        val cleanedSource = cleanLabel(source, providerName)
        val playerBrand = knownBrand(referer)
            ?: knownBrand(url)
            ?: genericBrand(referer, providerName)
            ?: genericBrand(url, providerName)
        val qualifier = audioQualifier(cleanedName)
        val explicitServerBrand = recognizedBrand(withoutAudioQualifier(cleanedName))
        val providerGenerated = normalized(source) == normalized(providerName) &&
            currentName.trim().startsWith(providerName, ignoreCase = true)
        val providerBrand = explicitServerBrand ?: playerBrand
        val base = if (providerGenerated && providerBrand != null) {
            listOfNotNull(providerBrand, qualifier).joinToString(" ")
        } else {
            listOf(cleanedName, cleanedSource)
                .firstOrNull(::isInformative)
                ?.let(::canonicalBrand)
                ?: playerBrand
                ?: "Server"
        }

        return if (resolution != null) "$base • ${resolution}p" else base
    }

    internal fun resolution(quality: Int, vararg labels: String): Int? {
        if (quality in standardResolutions) return quality
        labels.forEach { label ->
            explicitResolution.find(label)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in standardResolutions }
                ?.let { return it }
            if (Regex("""(?i)(?<!\w)4k(?!\w)""").containsMatchIn(label)) return 2160
            if (Regex("""(?i)(?<!\w)2k(?!\w)""").containsMatchIn(label)) return 1440
        }
        return null
    }

    private fun cleanLabel(raw: String, providerName: String): String {
        var value = raw
        if (providerName.isNotBlank()) {
            value = Regex(Regex.escape(providerName), RegexOption.IGNORE_CASE).replace(value, " ")
        }
        value = serverNumber.replace(value, " ")
        value = explicitResolution.replace(value, " ")
        value = Regex("""(?i)(?<!\w)[24]k(?!\w)""").replace(value, " ")
        value = technicalWord.replace(value, " ")
        value = separators.replace(value, " ")

        val seen = mutableSetOf<String>()
        return whitespace.split(value.trim())
            .filter { token -> token.isNotBlank() && seen.add(token.lowercase()) }
            .joinToString(" ")
            .takeIf(::isInformative)
            .orEmpty()
    }

    private fun isInformative(value: String): Boolean {
        return value.any(Char::isLetter) && value.lowercase() !in setOf(
            "server", "mirror", "source", "video", "player", "direct", "auto"
        )
    }

    private fun knownBrand(rawUrl: String?): String? {
        val host = host(rawUrl) ?: return null
        return when {
            "abyssplayer" in host || host == "abyss.to" || host.endsWith(".abyss.to") ||
                host == "sssrr.org" || host.endsWith(".sssrr.org") -> "Abyss"
            "freeon" in host -> "Freeon"
            "justplay" in host -> "JustPlay"
            "bysebuho" in host -> "Byse"
            "asiastream" in host -> "AsiaStream"
            "playsobat" in host -> "PlaySobat"
            "streamtape" in host -> "StreamTape"
            "emturbovid" in host || "turbovidhls" in host -> "TurboVid"
            "kotakajaib" in host -> "KotakAjaib"
            "opendrive" in host || host == "od.lk" || host.endsWith(".od.lk") -> "OpenDrive"
            "filemoon" in host -> "FileMoon"
            "streamwish" in host || "wishfast" in host -> "StreamWish"
            "vidhide" in host -> "VidHide"
            "filelions" in host -> "FileLions"
            "lulustream" in host || "luluvdo" in host -> "LuluStream"
            host == "voe.sx" || host.endsWith(".voe.sx") -> "VOE"
            "vidmoly" in host -> "VidMoly"
            "uqload" in host -> "Uqload"
            "mixdrop" in host -> "MixDrop"
            "dood" in host -> "DoodStream"
            "mp4upload" in host -> "Mp4Upload"
            "yourupload" in host -> "YourUpload"
            "googlevideo" in host || "blogger" in host -> "Blogger"
            "kuroplayer" in host -> "KuroPlayer"
            else -> null
        }
    }

    private fun genericBrand(rawUrl: String?, providerName: String): String? {
        val host = host(rawUrl) ?: return null
        if (ipv4.matches(host)) return "IP Server"
        val labels = host.split('.').filter(String::isNotBlank)
        if (labels.size < 2) return null
        val candidate = labels[labels.lastIndex - 1]
            .takeUnless { it in genericHostLabels || it.length !in 2..24 }
            ?: return null
        val normalizedCandidate = candidate.filter(Char::isLetterOrDigit).lowercase()
        val normalizedProvider = providerName.filter(Char::isLetterOrDigit).lowercase()
        if (normalizedProvider.isNotBlank() &&
            (normalizedCandidate.startsWith(normalizedProvider) || normalizedProvider.startsWith(normalizedCandidate))
        ) return null
        return candidate.split('-', '_')
            .filter(String::isNotBlank)
            .joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
            .takeIf(String::isNotBlank)
    }

    private fun canonicalBrand(value: String): String {
        return recognizedBrand(value) ?: value
    }

    private fun recognizedBrand(value: String): String? {
        val compact = value.filter(Char::isLetterOrDigit).lowercase()
        return when (compact) {
            "abyss", "abyssplayer" -> "Abyss"
            "freeon" -> "Freeon"
            "justplay" -> "JustPlay"
            "byse", "bysebuho" -> "Byse"
            "asiastream" -> "AsiaStream"
            "playsobat" -> "PlaySobat"
            "streamtape" -> "StreamTape"
            "turbovid", "turbovip" -> "TurboVid"
            "kotakajaib" -> "KotakAjaib"
            "opendrive" -> "OpenDrive"
            "filemoon" -> "FileMoon"
            "streamwish" -> "StreamWish"
            "vidhide" -> "VidHide"
            "filelions" -> "FileLions"
            "lulustream", "luluvdo" -> "LuluStream"
            "juicy", "juicycodes" -> "JuicyCodes"
            "kuroplayer" -> "KuroPlayer"
            "blogger" -> "Blogger"
            else -> null
        }
    }

    private fun audioQualifier(value: String): String? {
        return audioQualifiers
            .mapNotNull { (pattern, label) -> label.takeIf { pattern.containsMatchIn(value) } }
            .distinct()
            .joinToString(" ")
            .takeIf(String::isNotBlank)
    }

    private fun withoutAudioQualifier(value: String): String {
        return audioQualifiers.fold(value) { current, (pattern, _) ->
            pattern.replace(current, " ")
        }.let { whitespace.replace(it, " ").trim() }
    }

    private fun normalized(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()

    private fun host(rawUrl: String?): String? = runCatching {
        URI(rawUrl.orEmpty()).host?.lowercase()?.trimEnd('.')
    }.getOrNull()?.takeIf(String::isNotBlank)
}

@Suppress("DEPRECATION_ERROR")
internal fun ExtractorLink.withSimpleServerName(providerName: String): ExtractorLink {
    val formattedName = ServerLinkLabelFormatter.format(
        providerName = providerName,
        source = source,
        currentName = name,
        url = url,
        referer = referer,
        quality = quality
    )
    return if (formattedName == name) {
        this
    } else {
        // Extractor callbacks are synchronous while newExtractorLink is suspend.
        // Rebuild only to replace the immutable display name and preserve every
        // playback field exactly.
        ExtractorLink(
            source,
            formattedName,
            url,
            referer,
            quality,
            type,
            headers,
            extractorData
        )
    }
}
