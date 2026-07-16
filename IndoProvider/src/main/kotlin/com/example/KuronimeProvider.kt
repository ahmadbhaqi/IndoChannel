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

    private suspend fun emitKuroplayerLink(raw: String, callback: (ExtractorLink) -> Unit): Boolean {
        val isKuroplayer = runCatching {
            java.net.URI(raw).host.orEmpty().endsWith(".kuroplayer.xyz", ignoreCase = true)
        }.getOrDefault(false)
        if (!isKuroplayer || directMediaType(raw) != ExtractorLinkType.M3U8) return false

        val quality = Regex("""/(\d{3,4})p/""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
        callback(
            newExtractorLink(name, "$name HLS", raw, ExtractorLinkType.M3U8) {
                referer = KUROPLAYER_REFERER
                this.quality = quality
                headers = mapOf(
                    "Origin" to KUROPLAYER_ORIGIN,
                    "Referer" to KUROPLAYER_REFERER
                )
            }
        )
        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("div.listupd article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            ProviderHtmlParser.imageSource(document.selectFirst("div.thumb > img, div.tb img"))
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val tags = document.select("div.genxed > a").map { it.text() }.ifEmpty {
            document.infoItem("Genre")?.select("a")?.map { it.text() }.orEmpty()
        }
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
                newEpisode(href) {
                    this.episode = epNum
                    this.name = episodeName.ifBlank { epNum?.let { "Episode $it" } ?: "Episode" }
                }
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
        val fetch = app.get(data)
        val document = fetch.document
        val html = fetch.text
        var loaded = false

        InlineDataParser.kuronimeSourceId(html)?.let { sourceId ->
            try {
                val response = app.post(
                    "https://animeku.org/api/v9/sources",
                    json = mapOf("id" to sourceId),
                    referer = data,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Origin" to mainUrl
                    )
                ).text
                val apiUrls = InlineDataParser.kuronimeApiUrls(response)
                var emittedKuroplayer = false
                apiUrls.forEach { raw ->
                    emittedKuroplayer = emitKuroplayerLink(raw, callback) || emittedKuroplayer
                }
                if (emittedKuroplayer) {
                    loaded = true
                } else {
                    apiUrls.forEach { raw ->
                        loaded = loadResolvedExtractorWithResult(raw, data, subtitleCallback, callback) || loaded
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }

        ProviderHtmlParser.mediaSources(document).forEach { src ->
            loaded = loadResolvedExtractorWithResult(src, "$mainUrl/", subtitleCallback, callback) || loaded
        }

        document.select("div.video-nav a[href], #linksDDLContainer a[href]").forEach { link ->
            val src = ProviderHtmlParser.absoluteUrl(link.attr("href"), data)
            loaded = loadResolvedExtractorWithResult(src, data, subtitleCallback, callback) || loaded
        }

        document.select("select.mirror > option[value]").forEach { option ->
            try {
                val decoded = base64Decode(option.attr("value"))
                val iframe = org.jsoup.Jsoup.parse(decoded).selectFirst("iframe")?.let {
                    ProviderHtmlParser.firstIframeSource(it)
                }
                loaded = loadResolvedExtractorWithResult(iframe, "$mainUrl/", subtitleCallback, callback) || loaded
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
        return loaded
    }

    private companion object {
        const val KUROPLAYER_ORIGIN = "https://player.animeku.org"
        const val KUROPLAYER_REFERER = "$KUROPLAYER_ORIGIN/"
    }
}
