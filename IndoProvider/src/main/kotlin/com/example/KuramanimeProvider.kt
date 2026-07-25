package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v11.kuramanime.tel"
    override var name = "Kuramanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Anime",
        "$mainUrl/anime?order_by=most_viewed&page=" to "Paling Banyak Ditonton"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data + page.coerceAtLeast(1),
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).document
        return newHomePageResponse(
            request.name,
            document.select("div#animeList div.product__item, div.col-lg-4.col-md-6.col-sm-6")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return app.get(
            "$mainUrl/anime?search=$encoded&order_by=latest",
            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
        ).document.select("div#animeList div.product__item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = selectFirst("h5 a[href], a[href*='/anime/'], a[href*='/episode/']")
            ?: return null
        val href = animeUrl(anchor.attr("href")) ?: return null
        val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(
            selectFirst("div.product__item__pic")?.attr("data-setbg")
                ?: ProviderHtmlParser.firstImageSource(this)
        )
        val episode = Regex("""(?i)ep\s*(\d+)""")
            .find(selectFirst("div.ep span, span.ep")?.text().orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestUrl = animeUrl(url) ?: return null
        val document = app.get(requestUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS).document
        val title = document.selectFirst(".anime__details__title h3, h1")?.text()
            ?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(
            document.selectFirst(".anime__details__pic")?.attr("data-setbg")
                ?: ProviderHtmlParser.firstImageSource(document, ".anime__details__pic img")
        )
        val description = document.selectFirst(".anime__details__text > p, .synopsis")?.text()?.trim()
        val tags = document.select(
            ".anime__details__widget li:contains(Genre:) a, a[href*='/properties/genre/']"
        ).map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val metadataText = document.select(".anime__details__widget").text()
        val year = Regex("""(?:19|20)\d{2}""").find(metadataText)?.value?.toIntOrNull()
        val status = when {
            metadataText.contains("Sedang Tayang", ignoreCase = true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
        val episodes = mutableListOf<Episode>()
        for (page in 1..10) {
            val pageDocument = if (page == 1) document else app.get(
                "${requestUrl.trimEnd('/')}?page=$page",
                timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
            ).document
            val pageEpisodes = KuramanimeParser.episodeLinks(pageDocument, requestUrl)
            if (pageEpisodes.isEmpty()) {
                if (page > 1) break
            } else {
                pageEpisodes.forEach { (episodeUrl, label) ->
                    val number = Regex("""\d+(?:[.,]\d+)?""").find(label)?.value
                        ?.replace(',', '.')?.toDoubleOrNull()?.toInt()
                    episodes += newEpisode(episodeUrl) {
                        episode = number
                        name = label
                        posterUrl = poster
                    }
                }
            }
        }
        val distinctEpisodes = episodes.distinctBy { it.data }
        val typeLabel = document.selectFirst("li:contains(Tipe:) a")?.text().orEmpty()
        val type = when {
            typeLabel.contains("movie", ignoreCase = true) && distinctEpisodes.size <= 1 ->
                TvType.AnimeMovie
            typeLabel.contains("ova", ignoreCase = true) ||
                typeLabel.contains("special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }
        val recommendations = document.select("div#randomList > a[href]").mapNotNull { anchor ->
            val href = animeUrl(anchor.attr("href")) ?: return@mapNotNull null
            val recommendationTitle = anchor.selectFirst("h5")?.text()?.trim()
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            newAnimeSearchResponse(recommendationTitle, href, TvType.Anime) {
                posterUrl = fixUrlNull(
                    anchor.selectFirst(".product__sidebar__view__item")?.attr("data-setbg")
                )
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        return newAnimeLoadResponse(title, requestUrl, type) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, distinctEpisodes)
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
            inlineSourceParser = KuramanimeParser::playerUrls
        )
        KuramanimeParser.playerUrls(response.text, response.url).forEach { candidate ->
            if (resolver.canContinue) resolver.resolve(candidate, response.url)
        }
        return resolver.loaded
    }

    private fun animeUrl(raw: String?): String? {
        val normalized = ProviderHtmlParser.normalizeProviderPageUrl(
            raw,
            mainUrl,
            setOf(
                "v8.kuramanime.tel",
                "v9.kuramanime.tel",
                "v10.kuramanime.tel"
            )
        ) ?: return null
        return if (normalized.contains("/episode/", ignoreCase = true)) {
            normalized.substringBefore("/episode/").trimEnd('/') + "/"
        } else {
            normalized
        }
    }

    private fun episodeUrl(raw: String?): String? = ProviderHtmlParser.normalizeProviderPageUrl(
        raw,
        mainUrl,
        setOf(
            "v8.kuramanime.tel",
            "v9.kuramanime.tel",
            "v10.kuramanime.tel"
        )
    )
}

internal object KuramanimeParser {
    private const val MAX_ENCODED_PLAYER_SIZE = 32_768
    private const val MAX_PLAYER_CANDIDATES = 48
    private const val PLAYER_MEDIA_SELECTOR =
        "#player iframe, #player video, #player source, " +
            "#player script:not([src]):not([data-src]):not([data-litespeed-src]), #player [data-iframe], " +
            ".server iframe, .server video, .server source, " +
            ".server script:not([src]):not([data-src]):not([data-litespeed-src]), .server [data-iframe], " +
            ".mirror iframe, .mirror video, .mirror source, " +
            ".mirror script:not([src]):not([data-src]):not([data-litespeed-src]), .mirror [data-iframe], " +
            ".streaming-server iframe, .streaming-server video, .streaming-server source, " +
            ".streaming-server script:not([src]):not([data-src]):not([data-litespeed-src]), " +
            ".streaming-server [data-iframe]"
    private const val FRAGMENT_MEDIA_SELECTOR =
        "iframe, video, source, script:not([src]):not([data-src]):not([data-litespeed-src]), " +
            "[data-iframe], " +
            "meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player]"
    private val legacyHosts = setOf(
        "v8.kuramanime.tel",
        "v9.kuramanime.tel",
        "v10.kuramanime.tel"
    )

    fun episodeLinks(document: Document, baseUrl: String): List<Pair<String, String>> {
        val encodedList = document.selectFirst("#episodeLists")?.attr("data-content")
            ?.takeIf { it.isNotBlank() }
            ?.let { Jsoup.parse(it, baseUrl) }
        val anchors = document.select(
            "#episodeLists a[href], .episode-list a[href], .eplister a[href]"
        ) + encodedList?.select(
            "a.btn.btn-sm.btn-danger[href], a[href*='/episode/']"
        ).orEmpty()
        return anchors.mapNotNull { anchor ->
            val url = ProviderHtmlParser.normalizeProviderPageUrl(
                anchor.attr("href"),
                baseUrl,
                legacyHosts
            ) ?: return@mapNotNull null
            val label = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            url to label
        }.distinctBy { it.first }
    }

    fun playerUrls(html: String, playerUrl: String): List<String> {
        val document = Jsoup.parse(html, playerUrl)
        val playerElements = PopularProviderLinkLimits.playerElements(
            document,
            "#player option[value], #player [data-video], #player [data-url], " +
                ".server option[value], .server [data-video], .server [data-url], " +
                ".mirror option[value], .streaming-server a[href]"
        )
        val direct = buildList {
            addAll(PopularProviderLinkLimits.scopedMediaUrls(document, PLAYER_MEDIA_SELECTOR))
            addAll(
                playerElements.flatMap { element ->
                    listOf(
                        element.attr("data-video"),
                        element.attr("data-url"),
                        element.attr("value"),
                        element.attr("href")
                    )
                }
            )
        }
        val decoded = playerElements.flatMap { element ->
            decodedPlayerValue(
                listOf(element.attr("value"), element.attr("data-video"), element.attr("data-url"))
                    .firstOrNull { it.isNotBlank() },
                playerUrl
            )
        }
        return (direct + decoded)
            .mapNotNull { candidate ->
                candidate.takeIf { value ->
                    value.startsWith("http://", true) ||
                        value.startsWith("https://", true) ||
                        value.startsWith("//") ||
                        value.startsWith("/")
                }?.let { ProviderHtmlParser.absoluteUrl(it, playerUrl) }
            }
            .filter(::isSafeRemoteHttpUrl)
            .distinct()
            .take(MAX_PLAYER_CANDIDATES)
    }

    private fun decodedPlayerValue(raw: String?, playerUrl: String): List<String> {
        val value = raw?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_ENCODED_PLAYER_SIZE }
            ?: return emptyList()
        val decoded = decodeBase64Compat(value)?.toString(Charsets.UTF_8)
            ?.takeIf { it.length <= MAX_ENCODED_PLAYER_SIZE }
            ?: return emptyList()
        if (isSafeRemoteHttpUrl(decoded.trim())) return listOf(decoded.trim())
        val document = Jsoup.parse(decoded, playerUrl)
        return PopularProviderLinkLimits.scopedMediaUrls(document, FRAGMENT_MEDIA_SELECTOR)
    }
}
