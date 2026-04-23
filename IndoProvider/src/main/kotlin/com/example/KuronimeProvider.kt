package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KuronimeProvider : MainAPI() {
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
        val a = selectFirst("div.bsx > a") ?: return null
        val title = a.attr("title").ifBlank { a.selectFirst("h2")?.text() } ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src") ?: a.selectFirst("img")?.attr("data-src"))
        val epNum = selectFirst("span.epx")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title.trim(), href, TvType.Anime) { this.posterUrl = posterUrl; addSub(epNum) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("div.listupd article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")
        val tags = document.select("div.genxed > a").map { it.text() }
        val year = document.selectFirst("div.info-content span:contains(Released)")?.ownText()?.trim()?.toIntOrNull()
        val status = when (document.selectFirst("div.info-content span:contains(Status)")?.ownText()?.trim()) { "Ongoing" -> ShowStatus.Ongoing; else -> ShowStatus.Completed }
        val type = when { document.selectFirst("div.info-content span:contains(Type)")?.ownText()?.contains("Movie", true) == true -> TvType.AnimeMovie; else -> TvType.Anime }
        val description = document.selectFirst("div[itemprop=description]")?.text()?.trim()
        val episodes = document.select("div.eplister ul li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val epNum = a.selectFirst("div.epl-num")?.text()?.toIntOrNull() ?: Regex("Episode\\s*(\\d+)").find(a.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(a.attr("href"))) { this.episode = epNum; this.name = "Episode $epNum" }
        }.reversed()
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        return newAnimeLoadResponse(title, url, type) {
            engName = title; posterUrl = tracker?.image ?: poster; backgroundPosterUrl = tracker?.cover; this.year = year
            addEpisodes(DubStatus.Subbed, episodes); showStatus = status; plot = description; this.tags = tags
            addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("select.mirror > option[value]").forEach { option ->
            val decoded = base64Decode(option.attr("value"))
            val iframe = org.jsoup.Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: return@forEach
            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
}
