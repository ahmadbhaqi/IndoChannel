package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class AnoboyProvider : MainAPI() {
    override var mainUrl = "https://ww1.anoboy.boo"
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "page/%d/" to "Terbaru",
        "category/anime/page/%d/" to "Anime",
        "category/anime-movie/page/%d/" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("a[rel=bookmark]:has(div.amv)").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = attr("title").ifBlank { selectFirst("h3.ibox1")?.text() }?.trim() ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val posterUrl = fixUrlNull(ProviderHtmlParser.imageSource(selectFirst("img")))
        val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("a[rel=bookmark]:has(div.amv)").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim() ?: return null
        val title = rawTitle.replace("Subtitle Indonesia", "", ignoreCase = true).trim()
        val description = document.select("div.entry-content p, div.sisi.entry-content").text().trim().takeIf { it.isNotBlank() }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val episode = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val type = if (rawTitle.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        val episodes = listOf(
            newEpisode(url) {
                this.name = rawTitle
                this.episode = episode
                this.posterUrl = poster
            }
        )

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var loaded = false

        val candidates = (
            ProviderHtmlParser.mediaSources(document, "iframe#mediaplayer, iframe") +
                document.select("a[data-video]").mapNotNull { it.attr("data-video").takeIf { value -> value.isNotBlank() } }
            ).distinct()

        candidates.forEach { raw ->
            val url = toPlayableUrl(raw) ?: return@forEach
            val resolvedSources = if (url.contains("/uploads/", ignoreCase = true)) {
                try {
                    ProviderHtmlParser.mediaSources(app.get(url).document)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                listOf(url)
            }

            resolvedSources.forEach { source ->
                loaded = loadResolvedExtractorWithResult(source, mainUrl, subtitleCallback, callback) || loaded
            }
        }

        return loaded
    }
}
