package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val items = document.select("article.bs").mapNotNull { it.toSearchResult() }
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
        return app.get("$mainUrl/?s=$query").document.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")
        val tags = document.select("div.genxed > a").map { it.text() }
        val description = document.selectFirst("div[itemprop=description]")?.text()?.trim()
        val episodes = document.select("div.eplister ul li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val epNum = a.selectFirst("div.epl-num")?.text()?.toIntOrNull()
            newEpisode(fixUrl(a.attr("href"))) { this.episode = epNum; this.name = "Episode $epNum" }
        }.reversed()
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title; posterUrl = poster; addEpisodes(DubStatus.Subbed, episodes); plot = description; this.tags = tags
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
