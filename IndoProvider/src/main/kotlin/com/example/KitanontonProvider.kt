package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KitanontonProvider : MainAPI() {
    override var mainUrl = "https://kitanonton.com"
    override var name = "KitaNonton"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "category/action/page/%d/" to "Action",
        "category/drama/page/%d/" to "Drama",
        "category/crime/page/%d/" to "Crime",
        "category/komedi/page/%d/" to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        return newHomePageResponse(request.name, document.toArticleResults())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/?s=$encoded").document.toArticleResults()
    }

    private fun Document.toArticleResults(): List<SearchResponse> {
        return select("article.post").mapNotNull { it.toArticleResult() }.distinctBy { it.url }
    }

    private fun Element.toArticleResult(): SearchResponse? {
        val link = selectFirst("h2.entry-title a, a:has(img)") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = link.text().trim().takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(selectFirst("img")))
        return newMovieSearchResponse(title.cleanKitanontonTitle(), href, TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, meta[property=og:title]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.cleanKitanontonTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: fixUrlNull(ProviderHtmlParser.imageSource(document.selectFirst("article img, .entry-content img")))
        val description = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".entry-content p, article p")?.text()?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val tags = document.select("a[rel=tag], .cat-links a").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
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
        document.select(".entry-content a[href], article a[href]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isBlank() || href.contains("kitanonton.com") || href.startsWith("#")) return@forEach
            val server = toPlayableUrl(href) ?: return@forEach
            loaded = loadExtractorWithResult(server, data, subtitleCallback, callback) || loaded
        }
        return loaded
    }

    private fun String.cleanKitanontonTitle(): String {
        return replace("Sinopsis Dan Link Nonton", "", ignoreCase = true)
            .replace("Sinopsis", "", ignoreCase = true)
            .replace("Full Movie", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .replace("Subtitle Indonesia", "", ignoreCase = true)
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|')
    }
}
