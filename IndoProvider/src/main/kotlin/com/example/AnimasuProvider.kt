package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimasuProvider : MainAPI() {
    override var mainUrl = "https://v1.animasu.app"
    override var name = "Animasu"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "urutan=update" to "Baru Diupdate",
        "status=&tipe=&urutan=publikasi" to "Baru Ditambahkan",
        "status=&tipe=&urutan=populer" to "Terpopuler",
        "status=&tipe=&urutan=rating" to "Rating Tertinggi",
        "status=&tipe=Movie&urutan=update" to "Film Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            "$mainUrl/pencarian/?${request.data}&halaman=${page.coerceAtLeast(1)}",
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).document
        return newHomePageResponse(
            request.name,
            document.select("div.listupd div.bs").mapNotNull { it.toSearchResult() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return app.get(
            "$mainUrl/?s=$encoded",
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).document.select("div.listupd div.bs").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = animeUrl(anchor.attr("href")) ?: return null
        val title = selectFirst("div.tt")?.text()?.trim()
            ?.takeIf(String::isNotBlank) ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.firstImageSource(this, "div.limit img, img"))
        val episode = selectFirst("span.epx")?.text()?.filter(Char::isDigit)?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = animeUrl(url) ?: return null
        val document = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS).document
        val title = document.selectFirst("div.infox h1, h1.entry-title")?.text()
            ?.replace("Sub Indo", "", ignoreCase = true)
            ?.trim()?.takeIf(String::isNotBlank) ?: return null
        val poster = fixUrlNull(
            ProviderHtmlParser.firstImageSource(document, "div.bigcontent img, .thumb img")
        )
        val table = document.selectFirst("div.infox div.spe, .spe")
        val typeText = table?.selectFirst("span:contains(Jenis:)")?.ownText().orEmpty()
        val type = when {
            typeText.contains("movie", ignoreCase = true) -> TvType.AnimeMovie
            typeText.contains("ova", ignoreCase = true) ||
                typeText.contains("special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }
        val year = table?.selectFirst("span:contains(Rilis:)")?.ownText()
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val statusText = table?.selectFirst("span:contains(Status:)")?.text().orEmpty()
        val status = if (statusText.contains("Sedang Tayang", ignoreCase = true)) {
            ShowStatus.Ongoing
        } else {
            ShowStatus.Completed
        }
        val trailer = document.selectFirst("div.trailer iframe")?.attr("src")
        val episodes = document.select("ul#daftarepisode > li, .eplister li").mapNotNull { row ->
            val anchor = row.selectFirst("a[href]") ?: return@mapNotNull null
            val href = episodeUrl(anchor.attr("href")) ?: return@mapNotNull null
            val label = anchor.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val number = Regex("""(?i)episode\s*(\d+)""")
                .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                episode = number
                name = label
                posterUrl = poster
            }
        }.reversed().distinctBy { it.data }

        return newAnimeLoadResponse(title, requestUrl, type) {
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = document.select("div.sinopsis p, .entry-content p").text().trim()
            tags = table?.select("span:contains(Genre:) a")?.map { it.text().trim() }
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = episodeUrl(data) ?: return false
        val response = app.get(pageUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            inlineSourceParser = AnimasuParser::playerUrls
        )
        AnimasuParser.playerUrls(response.text, response.url).forEach { candidate ->
            if (resolver.canContinue) resolver.resolve(candidate, response.url)
        }
        return resolver.loaded
    }

    private fun animeUrl(raw: String?): String? {
        val normalized = episodeUrl(raw) ?: return null
        if (normalized.contains("/anime/", ignoreCase = true)) return normalized
        val slug = normalized.substringAfter("$mainUrl/").trim('/')
            .substringBefore("-episode")
            .substringBefore("-movie")
            .takeIf { it.isNotBlank() } ?: return null
        return "$mainUrl/anime/$slug"
    }

    private fun episodeUrl(raw: String?): String? = ProviderHtmlParser.normalizeProviderPageUrl(
        raw,
        mainUrl,
        setOf("v1.animasu.top", "animasu.top", "animasu.cc")
    )
}

internal object AnimasuParser {
    private const val MAX_MIRROR_VALUE_SIZE = 65_536
    private const val MAX_PLAYER_CANDIDATES = 48
    private const val PLAYER_MEDIA_SELECTOR =
        ".mobius iframe, .mobius video, .mobius source, " +
            ".mobius script:not([src]):not([data-src]):not([data-litespeed-src]), .mobius [data-iframe], " +
            ".mirror iframe, .mirror video, .mirror source, " +
            ".mirror script:not([src]):not([data-src]):not([data-litespeed-src]), .mirror [data-iframe], " +
            "#server iframe, #server video, #server source, " +
            "#server script:not([src]):not([data-src]):not([data-litespeed-src]), #server [data-iframe], " +
            "#servers iframe, #servers video, #servers source, " +
            "#servers script:not([src]):not([data-src]):not([data-litespeed-src]), #servers [data-iframe]"
    private const val FRAGMENT_MEDIA_SELECTOR =
        "iframe, video, source, script:not([src]):not([data-src]):not([data-litespeed-src]), " +
            "[data-iframe], " +
            "meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player]"

    fun playerUrls(html: String, playerUrl: String): List<String> {
        val document = Jsoup.parse(html, playerUrl)
        val mirrorElements = PopularProviderLinkLimits.playerElements(
            document,
            ".mobius > .mirror > option[value], .mirror option[value], " +
                "#server option[value], #servers option[value]"
        )
        val decodedMirrors = mirrorElements.flatMap { option ->
            decodeMirror(option.attr("value"), playerUrl)
        }
        val regular = PopularProviderLinkLimits.scopedMediaUrls(document, PLAYER_MEDIA_SELECTOR)
        return (decodedMirrors + regular)
            .mapNotNull { fixBerkasDrive(it) }
            .mapNotNull { ProviderHtmlParser.absoluteUrl(it, playerUrl) }
            .filter(::isSafeRemoteHttpUrl)
            .distinct()
            .take(MAX_PLAYER_CANDIDATES)
    }

    private fun decodeMirror(raw: String, playerUrl: String): List<String> {
        val value = raw.trim().takeIf {
            it.isNotBlank() && it.length <= MAX_MIRROR_VALUE_SIZE
        } ?: return emptyList()
        if (isSafeRemoteHttpUrl(value)) return listOf(value)
        val decoded = decodeBase64Compat(value)?.toString(Charsets.UTF_8)
            ?.takeIf { it.length <= MAX_MIRROR_VALUE_SIZE }
            ?: return emptyList()
        if (isSafeRemoteHttpUrl(decoded.trim())) return listOf(decoded.trim())
        val document = Jsoup.parse(decoded, playerUrl)
        return PopularProviderLinkLimits.scopedMediaUrls(document, FRAGMENT_MEDIA_SELECTOR)
    }

    private fun fixBerkasDrive(raw: String): String? {
        val value = raw.trim()
        if (!value.startsWith("https://dl.berkasdrive.com", ignoreCase = true)) return value
        val encoded = value.substringAfter("id=", "").substringBefore('&')
            .takeIf { it.isNotBlank() && it.length <= MAX_MIRROR_VALUE_SIZE }
            ?: return value
        return decodeBase64Compat(encoded)?.toString(Charsets.UTF_8)
            ?.trim()?.takeIf(::isSafeRemoteHttpUrl) ?: value
    }
}
