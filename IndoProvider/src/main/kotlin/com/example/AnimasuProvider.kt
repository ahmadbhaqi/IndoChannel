package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object AnimasuCatalogRouting {
    const val FAST_TIMEOUT_SECONDS = 20L
    const val DEFAULT_TIMEOUT_SECONDS = 35L

    fun pageUrl(mainUrl: String, page: Int, requestData: String): String {
        val baseUrl = mainUrl.trimEnd('/')
        val safePage = page.coerceAtLeast(1)
        return if (safePage == 1 && requestData == "urutan=update") {
            "$baseUrl/"
        } else {
            "$baseUrl/pencarian/?$requestData&halaman=$safePage"
        }
    }

    fun pageUrls(mainUrl: String, page: Int, requestData: String): List<String> {
        val primary = pageUrl(mainUrl, page, requestData)
        if (page.coerceAtLeast(1) != 1 || requestData != "urutan=update") {
            return listOf(primary)
        }
        val fallback = "${mainUrl.trimEnd('/')}/pencarian/?" +
            "$requestData&halaman=1"
        return listOf(primary, fallback).distinct()
    }

    fun timeoutSeconds(index: Int, candidateCount: Int): Long {
        require(candidateCount > 0 && index in 0 until candidateCount)
        return if (index == 0 && candidateCount > 1) {
            FAST_TIMEOUT_SECONDS
        } else {
            DEFAULT_TIMEOUT_SECONDS
        }
    }
}

class AnimasuProvider : MainAPI() {
    override var mainUrl = "https://v2.animasu.work"
    override var name = "Animasu"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    private val ownedHosts = setOf(
        "v1.animasu.app",
        "v1.animasu.work",
        "animasu.com",
        "v1.animasu.top",
        "animasu.top",
        "animasu.cc"
    )
    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    override val mainPage = mainPageOf(
        "urutan=update" to "Baru Diupdate",
        "status=&tipe=&urutan=publikasi" to "Baru Ditambahkan",
        "status=&tipe=&urutan=populer" to "Terpopuler",
        "status=&tipe=&urutan=rating" to "Rating Tertinggi",
        "status=&tipe=Movie&urutan=update" to "Film Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = AnimasuCatalogRouting.pageUrls(mainUrl, page, request.data)
        for ((index, url) in urls.withIndex()) {
            val timeoutSeconds = AnimasuCatalogRouting.timeoutSeconds(index, urls.size)
            val fetch = getProviderPage(url, timeoutSeconds) ?: continue
            if (
                fetch.code !in 200..299 ||
                ProviderHtmlParser.isNonContentPage(fetch.body)
            ) continue
            val items = Jsoup.parse(fetch.body, fetch.url)
                .select("div.listupd div.bs")
                .mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                return newHomePageResponse(request.name, items)
            }
        }
        return newHomePageResponse(request.name, emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val fetch = getProviderPage("$mainUrl/?s=$encoded") ?: return emptyList()
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return emptyList()
        return Jsoup.parse(fetch.body, fetch.url)
            .select("div.listupd div.bs")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = animeUrl(anchor.attr("href")) ?: return null
        val title = selectFirst("div.tt")?.text()?.trim()
            ?.takeIf(String::isNotBlank) ?: return null
        if (SensitiveContentPolicy.isBlocked(title, href)) return null
        val poster = fixUrlNull(ProviderHtmlParser.firstImageSource(this, "div.limit img, img"))
        val episode = selectFirst("span.epx")?.text()?.filter(Char::isDigit)?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = animeUrl(url) ?: return null
        if (SensitiveContentPolicy.isBlocked(null, requestUrl)) return null
        val fetch = getProviderPage(requestUrl) ?: return null
        if (
            fetch.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(fetch.body)
        ) return null
        val document = Jsoup.parse(fetch.body, fetch.url)
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
        val tags = table?.select("span:contains(Genre:) a")?.map { it.text().trim() }.orEmpty()
        if (SensitiveContentPolicy.isBlocked(title, requestUrl, categories = tags)) return null
        val episodes = document.select("ul#daftarepisode > li, .eplister li").mapNotNull { row ->
            val anchor = row.selectFirst("a[href]") ?: return@mapNotNull null
            val href = episodeUrl(anchor.attr("href")) ?: return@mapNotNull null
            val label = anchor.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val number = Regex("""(?i)episode\s*(\d+)""")
                .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(
                AnimePlaybackDataCodec.encode(
                    url = href,
                    title = label,
                    categories = tags,
                    detailUrl = requestUrl
                ),
                initializer = {
                    episode = number
                    name = label
                    posterUrl = poster
                },
                fix = false
            )
        }.reversed().distinctBy { it.data }

        return newAnimeLoadResponse(title, requestUrl, type) {
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = document.select("div.sinopsis p, .entry-content p").text().trim()
            this.tags = tags
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playback = AnimePlaybackDataCodec.decode(data)
        if (AnimePlaybackDataCodec.isBlocked(data)) return false
        val pageUrl = episodeUrl(playback?.url ?: data) ?: return false
        if (SensitiveContentPolicy.isBlocked(null, pageUrl)) return false
        val response = getProviderPage(pageUrl) ?: return false
        if (
            response.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(response.body)
        ) return false
        val episodeDocument = Jsoup.parse(response.body, response.url)
        val episodeTitle = episodeDocument.selectFirst("h1.entry-title, div.infox h1")
            ?.text()
        val episodeTags = episodeDocument
            .select("div.infox div.spe span:contains(Genre:) a, .spe span:contains(Genre:) a")
            .map { it.text().trim() }
        if (
            SensitiveContentPolicy.isBlocked(
                episodeTitle,
                response.url,
                categories = episodeTags
            )
        ) return false
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            inlineSourceParser = AnimasuParser::playerUrls
        )
        AnimasuParser.playerUrls(response.body, response.url).forEach { candidate ->
            if (resolver.canContinue) resolver.resolve(candidate, response.url)
        }
        return resolver.loaded
    }

    private suspend fun getProviderPage(
        url: String,
        timeoutSeconds: Long = AnimasuCatalogRouting.DEFAULT_TIMEOUT_SECONDS
    ): ProviderHttpResult? = try {
        safeHttp.get(
            url = url,
            normalizer = ProviderUrlNormalizer(::networkProviderUrl),
            timeoutSeconds = timeoutSeconds
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
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
        ownedHosts
    )

    private fun networkProviderUrl(raw: String?): String? =
        ProviderHtmlParser.preserveProviderPageUrl(raw, mainUrl, ownedHosts)
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
