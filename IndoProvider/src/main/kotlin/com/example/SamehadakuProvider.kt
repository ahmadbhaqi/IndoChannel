package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Element

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v2.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType = when { t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA; t.contains("Movie", true) -> TvType.AnimeMovie; else -> TvType.Anime }
        fun getStatus(t: String): ShowStatus = when (t) { "Completed" -> ShowStatus.Completed; "Ongoing" -> ShowStatus.Ongoing; else -> ShowStatus.Completed }
    }

    override val mainPage = mainPageOf(
        "anime-terbaru/page/%d" to "Terbaru",
        "genre/action/page/%d/" to "Action",
        "genre/fantasy/page/%d/" to "Fantasy",
        "genre/adventure/page/%d/" to "Adventure",
        "genre/school/page/%d/" to "School"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = when (request.name) {
            "Terbaru" -> document.select("li[itemtype='http://schema.org/CreativeWork']")
            else -> document.select("article.animpost")
        }
        val homeList = items.mapNotNull { if (request.name == "Terbaru") it.toLatestAnimeResult() else it.toSearchResult() }
        return newHomePageResponse(request.name, homeList)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.animepost a") ?: return null
        val title = a.selectFirst("div.title h2")?.text()?.trim() ?: a.attr("title") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.content-thumb img")?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a = this.selectFirst("div.thumb a") ?: return null
        val title = this.selectFirst("h2.entry-title a")?.text()?.trim() ?: a.attr("title") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("div.dtla author")?.text()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl; addSub(epNum) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("main#main div.animepost").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) url else app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href")
        val document = app.get(fixUrl ?: return null).document
        val title = document.selectFirst("h1.entry-title")?.text()?.replace(Regex("(Nonton)|(Anime)|(Subtitle\\sIndonesia)"), "")?.trim() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")
        val tags = document.select("div.genre-info > a").map { it.text() }
        val year = document.selectFirst("div.spe > span:contains(Rilis)")?.ownText()?.let { Regex("\\d,\\s(\\d*)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val status = getStatus(document.selectFirst("div.spe > span:contains(Status)")?.ownText() ?: return null)
        val type = getType(document.selectFirst("div.spe > span:contains(Type)")?.ownText()?.trim()?.lowercase() ?: "tv")
        val description = document.select("div.desc p").text().trim()
        val trailer = document.selectFirst("div.trailer-anime iframe")?.attr("src")
        val episodes = document.select("div.lstepsiode.listeps ul li").mapNotNull {
            val header = it.selectFirst("span.lchx > a") ?: return@mapNotNull null
            val episode = Regex("Episode\\s?(\\d+)").find(header.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(header.attr("href"))) { this.episode = episode }
        }.reversed()
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        return newAnimeLoadResponse(title, url, type) {
            engName = title; posterUrl = tracker?.image ?: poster; backgroundPosterUrl = tracker?.cover; this.year = year
            addEpisodes(DubStatus.Subbed, episodes); showStatus = status; plot = description; addTrailer(trailer); this.tags = tags
            addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS).document
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val bloggerResolver = BloggerVideoResolver(name, resolver::emitResolved)
        val streamingCandidates = document
            .select("div#server ul li div, div.east_player_option")
            .mapNotNull { element ->
                val post = element.attr("data-post").trim()
                val nume = element.attr("data-nume").trim()
                if (post.isBlank() || nume.isBlank()) return@mapNotNull null
                SamehadakuStreamRequest(
                    post = post,
                    number = nume,
                    type = element.attr("data-type").trim()
                )
            }
        val downloadCandidates = document
            .select("div#downloadb li")
            .flatMap { container ->
                val quality = container.select("strong").text().fixQuality()
                container.select("a[href]").mapNotNull { element ->
                    element.attr("href").trim().takeIf { it.isNotBlank() }?.let { raw ->
                        SamehadakuDownloadRequest(fixUrl(raw), quality)
                    }
                }
            }

        return SamehadakuPlaybackScheduler.resolve(
            streamingCandidates = streamingCandidates,
            downloadCandidates = downloadCandidates,
            canContinue = { resolver.canContinue },
            streamResolver = streamResolver@{ request ->
                val embed = resolver.withinBudget {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "player_ajax",
                            "post" to request.post,
                            "nume" to request.number,
                            "type" to request.type
                        ),
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                    ).document.selectFirst("iframe")
                        ?.attr("src")
                        ?.let(::fixIframeUrl)
                } ?: return@streamResolver false

                val bloggerLoaded = if (InlineDataParser.bloggerToken(embed) != null) {
                    resolver.withinBudget { bloggerResolver.resolve(embed, data) } == true
                } else {
                    false
                }
                bloggerLoaded || resolver.resolve(embed, data)
            },
            downloadResolver = { candidate ->
                val before = resolver.linkCount
                val extractedLinks = mutableListOf<ExtractorLink>()
                resolver.withinBudget {
                    loadExtractorWithResult(
                        candidate.url,
                        "$mainUrl/",
                        subtitleCallback
                    ) { link -> extractedLinks += link }
                }
                extractedLinks.forEach { link ->
                    resolver.emitResolved(
                        newExtractorLink(link.source, link.name, link.url, link.type) {
                            referer = link.referer
                            quality = candidate.quality
                            headers = link.headers
                            extractorData = link.extractorData
                        }.withSimpleServerName(name)
                    )
                }
                resolver.linkCount > before
            }
        )
    }

    private fun fixIframeUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> fixUrl(url)
    }

    private fun String.fixQuality(): Int = when (uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }
}

internal data class SamehadakuStreamRequest(
    val post: String,
    val number: String,
    val type: String
)

internal data class SamehadakuDownloadRequest(
    val url: String,
    val quality: Int
)

internal object SamehadakuPlaybackScheduler {
    suspend fun <S, D> resolve(
        streamingCandidates: Iterable<S>,
        downloadCandidates: Iterable<D>,
        streamResolver: suspend (S) -> Boolean,
        downloadResolver: suspend (D) -> Boolean,
        canContinue: () -> Boolean = { true }
    ): Boolean {
        var streamingLoaded = false
        for (candidate in streamingCandidates.distinct()) {
            if (!canContinue()) break
            streamingLoaded = attempt(candidate, streamResolver) || streamingLoaded
        }
        if (streamingLoaded) return true

        for (candidate in downloadCandidates.distinct()) {
            if (!canContinue()) break
            if (attempt(candidate, downloadResolver)) return true
        }
        return false
    }

    private suspend fun <T> attempt(
        candidate: T,
        resolver: suspend (T) -> Boolean
    ): Boolean = try {
        resolver(candidate)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}
