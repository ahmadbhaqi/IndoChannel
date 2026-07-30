package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Element

open class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    override var name = "Kuronime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "anime/?page=%d&status=ongoing&order=update" to "Ongoing",
        "anime/?page=%d&status=completed&order=update" to "Completed",
        "genres/action/page/%d/" to "Action",
        "genres/comedy/page/%d/" to "Comedy",
        "genres/fantasy/page/%d/" to "Fantasy"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("div.listupd article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = selectFirst("div.bsx > a, div.bsux > a") ?: return null
        val title = a.attr("title").ifBlank { a.selectFirst("h2")?.text() } ?: return null
        val href = ProviderHtmlParser.absoluteUrl(a.attr("href"), mainUrl) ?: return null
        if (SensitiveContentPolicy.isBlocked(title, href)) return null
        val posterUrl = fixUrlNull(ProviderHtmlParser.firstImageSource(a, "img[itemprop=image], div.limit > img, img[src*=uploads], img"))
        val epNum = selectFirst("span.epx")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title.trim(), href, TvType.Anime) { this.posterUrl = posterUrl; addSub(epNum) }
    }

    private fun Element.infoItem(label: String): Element? {
        return select("div.infodetail li").firstOrNull { item ->
            item.selectFirst("b")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }
    }

    private fun Element.infoValue(label: String): String? {
        return infoItem(label)
            ?.ownText()
            ?.removePrefix(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun emitKuroplayerLink(raw: String, resolver: LinkResolutionSession): Boolean {
        if (!KuronimeSourceScheduler.isKuroplayerHls(raw)) return false

        val quality = Regex("""/(\d{3,4})p/""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
        val before = resolver.linkCount
        resolver.emitResolved(
            newExtractorLink(name, "$name HLS", raw, ExtractorLinkType.M3U8) {
                referer = KUROPLAYER_REFERER
                this.quality = quality
                headers = mapOf(
                    "Origin" to KUROPLAYER_ORIGIN,
                    "Referer" to KUROPLAYER_REFERER
                )
            }.withSimpleServerName(name)
        )
        return resolver.linkCount > before
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("div.listupd article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (SensitiveContentPolicy.isBlocked(null, url)) return null
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            ProviderHtmlParser.imageSource(document.selectFirst("div.thumb > img, div.tb img"))
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val tags = document.select("div.genxed > a").map { it.text() }.ifEmpty {
            document.infoItem("Genre")?.select("a")?.map { it.text() }.orEmpty()
        }
        if (SensitiveContentPolicy.isBlocked(title, url, categories = tags)) return null
        val publishedAt = document.selectFirst("meta[property=article:published_time]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("div.infodetail meta[itemprop=datePublished]")?.attr("datetime")
        val year = publishedAt?.take(4)?.toIntOrNull()
            ?: document.infoValue("Tayang")
                ?.let { Regex("""\b(?:19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
            ?: document.selectFirst("div.info-content span:contains(Released)")?.ownText()?.trim()?.toIntOrNull()
        val statusText = document.infoValue("Status")
            ?: document.selectFirst("div.info-content span:contains(Status)")?.ownText()?.trim()
        val status = if (statusText?.contains("Ongoing", ignoreCase = true) == true) {
            ShowStatus.Ongoing
        } else {
            ShowStatus.Completed
        }
        val typeText = document.infoValue("Tipe")
            ?: document.selectFirst("div.info-content span:contains(Type)")?.ownText()?.trim()
        val type = when {
            typeText?.contains("Movie", ignoreCase = true) == true -> TvType.AnimeMovie
            typeText?.contains("OVA", ignoreCase = true) == true ||
                typeText?.contains("ONA", ignoreCase = true) == true ||
                typeText?.contains("Special", ignoreCase = true) == true -> TvType.OVA
            else -> TvType.Anime
        }
        val description = document.selectFirst("div[itemprop=description]")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val episodes = document
            .select("div.eplister ul li, div.bixbox.bxcl ul li:has(span.lchx)")
            .mapNotNull { el ->
                val a = el.selectFirst("span.lchx > a[href], a[href*=\"/nonton-\"][href], a[href]")
                    ?: return@mapNotNull null
                val episodeName = a.text().trim()
                val epNum = a.selectFirst("div.epl-num")?.text()?.toIntOrNull()
                    ?: Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(episodeName)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                val href = ProviderHtmlParser.absoluteUrl(a.attr("href"), mainUrl) ?: return@mapNotNull null
                newEpisode(
                    AnimePlaybackDataCodec.encode(
                        url = href,
                        title = episodeName.ifBlank { title },
                        categories = tags,
                        detailUrl = url
                    ),
                    initializer = {
                        this.episode = epNum
                        this.name = episodeName.ifBlank {
                            epNum?.let { "Episode $it" } ?: "Episode"
                        }
                    },
                    fix = false
                )
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        return newAnimeLoadResponse(title, url, type) {
            engName = title; posterUrl = tracker?.image ?: poster; backgroundPosterUrl = tracker?.cover; this.year = year
            addEpisodes(DubStatus.Subbed, episodes); showStatus = status; plot = description; this.tags = tags
            addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val playback = AnimePlaybackDataCodec.decode(data)
        if (AnimePlaybackDataCodec.isBlocked(data)) return false
        val pageUrl = playback?.url ?: data
        if (SensitiveContentPolicy.isBlocked(null, pageUrl)) return false
        val fetch = app.get(pageUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        val document = fetch.document
        val pageTitle = document.selectFirst("h1.entry-title")?.text()
        val pageTags = document.select("div.genxed > a").map { it.text() }.ifEmpty {
            document.infoItem("Genre")?.select("a")?.map { it.text() }.orEmpty()
        }
        if (SensitiveContentPolicy.isBlocked(pageTitle, fetch.url, categories = pageTags)) return false
        val html = fetch.text
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)

        InlineDataParser.kuronimeSourceId(html)?.let { sourceId ->
            try {
                val response = app.post(
                    "https://animeku.org/api/v9/sources",
                    json = mapOf("id" to sourceId),
                    referer = fetch.url,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Origin" to mainUrl
                    ),
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).text
                val apiUrls = InlineDataParser.kuronimeApiUrls(response)
                KuronimeSourceScheduler.resolve(
                    candidates = apiUrls,
                    resolveKuroplayer = { raw -> emitKuroplayerLink(raw, resolver) },
                    resolveGeneric = { raw -> resolver.resolve(raw, fetch.url) }
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }

        ProviderHtmlParser.mediaSources(document).forEach { src ->
            resolver.resolve(src, "$mainUrl/")
        }

        document.select("div.video-nav a[href], #linksDDLContainer a[href]").forEach { link ->
            val src = ProviderHtmlParser.absoluteUrl(link.attr("href"), fetch.url)
            resolver.resolve(src, fetch.url)
        }

        document.select("select.mirror > option[value]").forEach { option ->
            try {
                val decoded = base64Decode(option.attr("value"))
                val iframe = org.jsoup.Jsoup.parse(decoded).selectFirst("iframe")?.let {
                    ProviderHtmlParser.firstIframeSource(it)
                }
                resolver.resolve(iframe, "$mainUrl/")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
        return resolver.linkCount > 0
    }

    private companion object {
        const val KUROPLAYER_ORIGIN = "https://player.animeku.org"
        const val KUROPLAYER_REFERER = "$KUROPLAYER_ORIGIN/"
    }
}

internal object KuronimeSourceScheduler {
    internal suspend fun resolve(
        candidates: List<String>,
        resolveKuroplayer: suspend (String) -> Boolean,
        resolveGeneric: suspend (String) -> Boolean
    ): Boolean {
        var loaded = false
        candidates.forEach { candidate ->
            try {
                loaded = if (isKuroplayerHls(candidate)) {
                    resolveKuroplayer(candidate) || loaded
                } else {
                    resolveGeneric(candidate) || loaded
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        return loaded
    }

    internal fun isKuroplayerHls(raw: String): Boolean {
        val isKuroplayer = runCatching {
            java.net.URI(raw).host.orEmpty().endsWith(".kuroplayer.xyz", ignoreCase = true)
        }.getOrDefault(false)
        return isKuroplayer && directMediaType(raw) == ExtractorLinkType.M3U8
    }
}
