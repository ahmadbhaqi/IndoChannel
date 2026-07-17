package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeindoProvider : MainAPI() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "Animeindo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "page/%d/" to "Update Terbaru",
        "movie/page/%d/" to "Movie",
        "genres/action/page/%d/" to "Action",
        "genres/comedy/page/%d/" to "Comedy",
        "genres/fantasy/page/%d/" to "Fantasy"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        return newHomePageResponse(request.name, document.catalogItems())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        return app.get("$mainUrl/search.php?q=$encodedQuery").document.catalogItems()
    }

    private fun Document.catalogItems(): List<SearchResponse> {
        return select("div.ngiri div.menu > a:has(div.list-anime), table.otable")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val anchor = when {
            tagName() == "a" -> this
            else -> selectFirst("td.videsc > a, h2 a, a")
        } ?: return null
        val rawUrl = anchor.attr("href")
        val detailUrl = ProviderHtmlParser.absoluteUrl(properAnimePath(rawUrl), mainUrl) ?: return null
        val title = selectFirst("div.list-anime p, td.videsc > a, h2 a")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val labels = select("span.label").text()
        val type = when {
            labels.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            labels.contains("OVA", ignoreCase = true) || labels.contains("Special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }
        val poster = fixUrlNull(ProviderHtmlParser.firstImageSource(this))
        val episode = selectFirst("span.eps")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, detailUrl, type) {
            posterUrl = poster
            addSub(episode)
        }
    }

    private fun properAnimePath(rawUrl: String): String {
        val absolute = ProviderHtmlParser.absoluteUrl(rawUrl, mainUrl) ?: rawUrl
        if (absolute.contains("/anime/")) return absolute

        val slug = absolute
            .substringBefore("?")
            .trimEnd('/')
            .substringAfterLast('/')
            .removePrefix("nonton-")
            .replace(Regex("(?i)-episode-\\d+(?:-\\d+)?(?:-sub-indo)?$"), "")
        return "/anime/$slug/"
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title, h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val detail = document.selectFirst("div.detail")
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(detail?.selectFirst("img")))
        val tags = detail?.select("li a[href*=/genres/]")?.map { it.text().trim() }.orEmpty()
        val description = detail?.selectFirst("p")?.text()?.trim()
        val episodes = document.select("div.ep > a[href]").mapNotNull { link ->
            val href = ProviderHtmlParser.absoluteUrl(link.attr("href"), mainUrl) ?: return@mapNotNull null
            val label = link.text().trim()
            val number = Regex("\\d+").find(label)?.value?.toIntOrNull()
            newEpisode(href) {
                episode = number
                name = if (label.isNotBlank()) "Episode $label" else "Episode"
                posterUrl = poster
            }
        }
        val type = when {
            title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            title.contains("OVA", ignoreCase = true) || title.contains("Special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS).document
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val emittedDirect = mutableSetOf<String>()
        var directLoaded = false

        suspend fun emitDirectVideo(url: String, type: ExtractorLinkType, label: String) {
            if (!emittedDirect.add(url)) return
            callback(
                newExtractorLink(name, "$name $label", url, type) {
                    // Both Blogger and XtWap declare no-referrer. Their signed
                    // media URLs are bound to the extraction IP, not a page URL.
                    referer = ""
                    quality = Qualities.Unknown.value
                }.withSimpleServerName(name)
            )
            directLoaded = true
        }

        suspend fun resolvePlayerCandidate(raw: String, referer: String) {
            val candidate = ProviderHtmlParser.absoluteUrl(raw, referer) ?: return
            animeindoSourceType(candidate)?.let { type ->
                emitDirectVideo(candidate, type, if (type == ExtractorLinkType.M3U8) "HLS" else "MP4")
                return
            }
            if (resolver.resolve(candidate, referer)) return

            try {
                val playerResponse = app.get(
                    candidate,
                    referer = referer,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
                val playerDocument = playerResponse.document
                val inlineSources = InlineDataParser.inlinePlayerSources(playerResponse.text)
                val declaredHlsUrls = inlineSources
                    .filter { it.isHls }
                    .mapNotNull { ProviderHtmlParser.absoluteUrl(it.url, candidate) }
                    .toSet()
                val nested = buildList {
                    addAll(ProviderHtmlParser.mediaSources(playerDocument))
                    addAll(playerDocument.select("video[src], video source[src], source[src]").map { it.attr("src") })
                    addAll(inlineSources.map { it.url })
                }.mapNotNull { ProviderHtmlParser.absoluteUrl(it, candidate) }.distinct()

                nested.forEach { mediaUrl ->
                    val type = animeindoSourceType(mediaUrl, mediaUrl in declaredHlsUrls)
                    if (type != null) {
                        emitDirectVideo(
                            mediaUrl,
                            type,
                            if (type == ExtractorLinkType.M3U8) "HLS" else "MP4"
                        )
                    } else {
                        resolver.resolve(mediaUrl, candidate)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A broken mirror must not prevent the remaining servers from loading.
            }
        }

        val candidates = buildList {
            addAll(ProviderHtmlParser.iframeSources(document))
            addAll(document.select("a.server[data-video]").map { it.attr("data-video") })
        }.mapNotNull { ProviderHtmlParser.absoluteUrl(it, data) }.distinct()

        candidates.forEach { candidate ->
            resolvePlayerCandidate(candidate, data)
        }
        return directLoaded || resolver.loaded
    }
}

internal fun animeindoSourceType(
    url: String,
    sourceDeclaresHls: Boolean = false
): ExtractorLinkType? = when {
    InlineDataParser.isDirectHttpVideo(url) -> ExtractorLinkType.VIDEO
    directMediaType(url) == ExtractorLinkType.M3U8 -> ExtractorLinkType.M3U8
    sourceDeclaresHls -> ExtractorLinkType.M3U8
    else -> null
}
