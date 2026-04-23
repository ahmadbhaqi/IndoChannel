package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnoboyProvider : MainAPI() {
    override var mainUrl = "https://anoboy.be"
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType = when { t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA; t.contains("Movie", true) -> TvType.AnimeMovie; else -> TvType.Anime }
        fun getStatus(t: String): ShowStatus = when (t) { "Completed" -> ShowStatus.Completed; "Ongoing" -> ShowStatus.Ongoing; else -> ShowStatus.Completed }
    }

    override val mainPage = mainPageOf(
        "anime/?page=%d&status=&type=&order=update" to "Latest Release",
        "genres/action/page/%d/" to "Action",
        "genres/adventure/page/%d/" to "Adventure",
        "genres/romance/page/%d/" to "Romance",
        "genres/school/page/%d/" to "School"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article[itemtype='http://schema.org/CreativeWork']").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.bsx > a") ?: return null
        val title = a.selectFirst("h2")?.text()?.trim() ?: a.attr("title") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("article[itemtype='http://schema.org/CreativeWork']").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.replace("Subtitle Indonesia", "")?.trim() ?: return null
        val poster = document.selectFirst("div.thumbook > div.thumb > img[itemprop=image]")?.attr("src")
        val tags = document.select("div.genxed > a").map { it.text() }
        val type = getType(document.selectFirst("div.info-content > div.spe > span:contains(Type:)")?.ownText()?.trim() ?: "TV")
        val year = document.selectFirst("div.info-content > div.spe > span:contains(Released:)")?.ownText()?.toIntOrNull()
        val status = getStatus(document.selectFirst("div.info-content > div.spe > span:contains(Status:)")?.ownText()?.trim() ?: "Completed")
        val description = document.select("div[itemprop=description] > p").text()
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")
        val episodes = document.select("div.eplister ul li").mapNotNull { element ->
            val headerAnchor = element.selectFirst("a") ?: return@mapNotNull null
            val link = fixUrl(headerAnchor.attr("href"))
            val episodeNumber = headerAnchor.text().trim().replace(Regex(".*Episode\\s*(\\d+).*"), "$1").toIntOrNull()
            newEpisode(link) { this.name = "Episode ${episodeNumber ?: 0}"; this.episode = episodeNumber }
        }.reversed()
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year)
        return newAnimeLoadResponse(title, url, type) {
            engName = title; posterUrl = tracker?.image ?: poster; backgroundPosterUrl = tracker?.cover; this.year = year
            addEpisodes(DubStatus.Subbed, episodes); showStatus = status; plot = description; this.tags = tags; addTrailer(trailer)
            addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val iframeUrl = document.selectFirst("div.player-embed > iframe")?.attr("src") ?: return false
        loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
        return true
    }
}
