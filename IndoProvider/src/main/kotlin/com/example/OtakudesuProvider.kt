package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class OtakudesuProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.blog/"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        const val acefile = "https://acefile.co"
        val mirrorBlackList = arrayOf("Mega", "MegaUp", "Otakufiles")
    }

    override val mainPage = mainPageOf("ongoing-anime/page/%d/" to "Anime Ongoing", "complete-anime/page/%d/" to "Anime Completed")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val home = document.select("div.venz > ul > li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.select("div.thumbz > img").attr("src").ifBlank { null }
        val epNum = this.selectFirst("div.epz")?.ownText()?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl; addSub(epNum) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded&post_type=anime").document
        return document.select("ul.chivsrc > li").mapNotNull {
            val title = it.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")?.trim()
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")?.ownText()?.replace(":", "")?.trim() ?: return null
        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")
        val tags = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val type = getAnimeType(document.selectFirst("div.infozingle > p:nth-child(5) > span")?.ownText()?.replace(":", "")?.trim() ?: "tv")
        val year = Regex("\\d, (\\d*)").find(document.select("div.infozingle > p:nth-child(9) > span").text())?.groupValues?.get(1)?.toIntOrNull()
        val status = getAnimeStatus(document.selectFirst("div.infozingle > p:nth-child(6) > span")?.ownText()?.replace(":", "")?.trim() ?: "Completed")
        val description = document.select("div.sinopc > p").text()
        val episodeListElements = document.select("div.episodelist")
        val episodes = (episodeListElements.getOrNull(1) ?: episodeListElements.firstOrNull())?.select("ul > li")?.mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val name = a.text()
            val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)
            val link = fixUrl(a.attr("href"))
            newEpisode(link) { this.episode = episode?.toIntOrNull() }
        }?.reversed() ?: emptyList()
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        return newAnimeLoadResponse(title, url, type) {
            engName = title; posterUrl = tracker?.image ?: poster; backgroundPosterUrl = tracker?.cover; this.year = year
            addEpisodes(DubStatus.Subbed, episodes); showStatus = status; plot = description; this.tags = tags
            addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    data class ResponseSources(@JsonProperty("id") val id: String, @JsonProperty("i") val i: String, @JsonProperty("q") val q: String)
    data class ResponseData(@JsonProperty("data") val data: String)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        var hasLinks = false
        try {
            val scriptData = document.select("script:containsData(action:)").lastOrNull()?.data()
                ?: throw ErrorLoadingException("No script data found for Otakudesu player")
            val token = scriptData.substringAfter("{action:\"").substringBefore("\"}")
            if (token.isBlank()) throw ErrorLoadingException("Failed to extract player token")
            val nonce = app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf("action" to token)).parsed<ResponseData>().data
            val action = scriptData.substringAfter(",action:\"").substringBefore("\"}")
            if (action.isBlank()) throw ErrorLoadingException("Failed to extract player action")
            val mirrorData = document.select("div.mirrorstream > ul > li").mapNotNull { base64Decode(it.select("a").attr("data-content")) }.toString()
            tryParseJson<List<ResponseSources>>(mirrorData)?.forEach { res ->
                try {
                    val sources = Jsoup.parse(base64Decode(app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf("id" to res.id, "i" to res.i, "q" to res.q, "nonce" to nonce, "action" to action)).parsed<ResponseData>().data)).select("iframe").attr("src")
                    if (sources.isNotBlank()) {
                        loadCustomExtractor(sources, data, subtitleCallback, callback, getQuality(res.q))
                        hasLinks = true
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        return hasLinks
    }

    private suspend fun loadCustomExtractor(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, quality: Int = Qualities.Unknown.value) {
        loadExtractor(url, referer, subtitleCallback) { link -> runBlocking { callback.invoke(newExtractorLink(link.name, link.name, link.url, link.type) { this.referer = link.referer; this.quality = quality; this.headers = link.headers; this.extractorData = link.extractorData }) } }
    }

    private fun getQuality(str: String?): Int = Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
}
