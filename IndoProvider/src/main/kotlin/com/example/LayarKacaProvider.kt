package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class LayarKacaProvider : MainAPI() {
    // INFO: Karena URL LayarKaca sering berubah (diblokir), Anda perlu memasukkan URL aktif yang paling baru di sini.
    override var mainUrl = "https://tv10.lk21official.cc" // Contoh URL, ganti dengan yang aktif
    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Populer",
        "$mainUrl/terbaru/page/" to "Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        
        // Struktur HTML ini perlu disesuaikan dengan struktur asli website LayarKaca saat ini
        val home = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.poster-title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val document = app.get(url).document

        return document.select("article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1[itemprop=name]")?.text() ?: ""
        val posterUrl = document.selectFirst("div.poster img, img.img-thumbnail")?.attr("src")
        val plot = document.selectFirst("div.entry-content p, blockquote")?.text()
        
        // Coba ekstrak tahun dari judul atau metadata
        val yearText = document.selectFirst("span.year, a[rel=tag]")?.text()
        val year = yearText?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var hasLinks = false
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http")) {
                try {
                    loadExtractor(src, data, subtitleCallback, callback)
                    hasLinks = true
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        return hasLinks
    }
}
