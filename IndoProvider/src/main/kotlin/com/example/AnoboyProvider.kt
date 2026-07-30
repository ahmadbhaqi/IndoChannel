package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnoboyProvider : MainAPI() {
    override var mainUrl = "https://anoboy.xyz"
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
        val href = normalizePageUrl(attr("href")) ?: return null
        if (
            SensitiveContentPolicy.isBlocked(
                title,
                href,
                categories = AnoboyContentPolicy.categories(this)
            )
        ) return null
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
        val pageUrl = normalizePageUrl(url) ?: return null
        val document = app.get(pageUrl).document
        val rawTitle = document.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim() ?: return null
        val title = rawTitle.replace("Subtitle Indonesia", "", ignoreCase = true).trim()
        val categories = AnoboyContentPolicy.categories(document)
        if (SensitiveContentPolicy.isBlocked(title, pageUrl, categories = categories)) return null
        val description = document.select("div.entry-content p, div.sisi.entry-content").text().trim().takeIf { it.isNotBlank() }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val episode = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val type = if (rawTitle.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        val episodes = listOf(
            newEpisode(
                AnimePlaybackDataCodec.encode(
                    url = pageUrl,
                    title = rawTitle,
                    categories = categories,
                    detailUrl = pageUrl
                ),
                initializer = {
                    this.name = rawTitle
                    this.episode = episode
                    this.posterUrl = poster
                },
                fix = false
            )
        )

        return newAnimeLoadResponse(title, pageUrl, type) {
            posterUrl = poster
            plot = description
            tags = categories
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playback = AnimePlaybackDataCodec.decode(data)
        if (AnimePlaybackDataCodec.isBlocked(data)) return false
        val pageUrl = normalizePageUrl(playback?.url ?: data) ?: return false
        val fetch = try {
            app.get(pageUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        if (AnoboyContentPolicy.isBlocked(fetch.document, fetch.url)) return false
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val bloggerResolver = BloggerVideoResolver(name, resolver::emitResolved)
        val router = AnoboyCandidateRouter(
            wrapperFetcher = wrapperFetcher@{ candidate, referer ->
                try {
                    val wrapper = resolver.withinBudget {
                        app.get(
                            candidate,
                            referer = referer,
                            timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                        )
                    } ?: return@wrapperFetcher null
                    AnoboyFetchedPage(wrapper.url, wrapper.document)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            },
            bloggerResolver = { candidate, referer ->
                resolver.withinBudget { bloggerResolver.resolve(candidate, referer) } == true
            },
            genericResolver = { candidate, referer -> resolver.resolve(candidate, referer) },
            canContinue = { resolver.canContinue }
        )
        return router.resolveAll(AnoboyPlaybackParser.episodeCandidates(fetch.document, fetch.url))
    }

    private fun normalizePageUrl(raw: String?): String? =
        ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl, ANOBOY_LEGACY_HOSTS)

    private companion object {
        val ANOBOY_LEGACY_HOSTS = setOf("ww1.anoboy.boo")
    }
}

internal object AnoboyContentPolicy {
    private const val TAXONOMY_SELECTOR =
        ".entry-meta a[rel='category tag'], " +
            ".entry-meta a[href*='/genre/'], .entry-meta a[href*='/category/'], " +
            ".tags-links a, .post-tags a"

    fun categories(document: Document): List<String> {
        val scope = document.selectFirst(
            "article:has(h1.entry-title), article:has(h2.entry-title)"
        ) ?: document.selectFirst(
            "main:has(h1.entry-title), main:has(h2.entry-title), " +
                "#content:has(h1.entry-title), #content:has(h2.entry-title)"
        ) ?: return emptyList()
        return categories(scope)
    }

    fun categories(scope: Element): List<String> =
        scope.select(TAXONOMY_SELECTOR)
            .map { link -> link.text().trim() }
            .filter(String::isNotBlank)
            .distinct()

    fun isBlocked(document: Document, pageUrl: String): Boolean =
        SensitiveContentPolicy.isBlocked(
            title = document.selectFirst("h1.entry-title, h2.entry-title")?.text(),
            url = pageUrl,
            categories = categories(document)
        )
}

internal data class AnoboyPlayerCandidate(
    val url: String,
    val referer: String
)

internal data class AnoboyFetchedPage(
    val url: String,
    val document: Document
)

internal object AnoboyPlaybackParser {
    fun episodeCandidates(document: Document, pageUrl: String): List<AnoboyPlayerCandidate> =
        (ProviderHtmlParser.mediaSources(document, "iframe#mediaplayer, iframe") +
            document.select("a[data-video]").mapNotNull { link ->
                link.attr("data-video").trim().takeIf { it.isNotBlank() }
            })
            .mapNotNull { raw -> ProviderHtmlParser.absoluteUrl(raw, pageUrl) }
            .distinct()
            .map { url -> AnoboyPlayerCandidate(url, pageUrl) }

    fun wrapperCandidates(document: Document, wrapperUrl: String): List<AnoboyPlayerCandidate> =
        ProviderHtmlParser.mediaSources(document)
            .mapNotNull { raw -> ProviderHtmlParser.absoluteUrl(raw, wrapperUrl) }
            .distinct()
            .map { url -> AnoboyPlayerCandidate(url, wrapperUrl) }
}

internal class AnoboyCandidateRouter(
    private val wrapperFetcher: suspend (url: String, referer: String) -> AnoboyFetchedPage?,
    private val bloggerResolver: suspend (url: String, referer: String) -> Boolean,
    private val genericResolver: suspend (url: String, referer: String) -> Boolean,
    private val canContinue: () -> Boolean = { true },
    private val maxDepth: Int = 3,
    private val maxCandidates: Int = 24
) {
    private val visited = mutableSetOf<String>()

    suspend fun resolve(raw: String, referer: String): Boolean = resolve(raw, referer, depth = 0)

    private suspend fun resolve(raw: String, referer: String, depth: Int): Boolean {
        if (
            !canContinue() ||
            depth > maxDepth.coerceAtLeast(0) ||
            visited.size >= maxCandidates.coerceAtLeast(1) ||
            !isSafeRemoteHttpUrl(raw)
        ) return false
        if (!visited.add(raw)) return false

        return if (raw.isUploadsWrapper()) {
            val wrapper = wrapperFetcher(raw, referer) ?: return false
            resolveAll(
                AnoboyPlaybackParser.wrapperCandidates(wrapper.document, wrapper.url),
                depth + 1
            )
        } else {
            resolveDirect(raw, referer)
        }
    }

    suspend fun resolveAll(candidates: List<AnoboyPlayerCandidate>): Boolean =
        resolveAll(candidates, depth = 0)

    private suspend fun resolveAll(candidates: List<AnoboyPlayerCandidate>, depth: Int): Boolean {
        var loaded = false
        candidates.forEach { candidate ->
            if (!canContinue()) return@forEach
            try {
                loaded = resolve(candidate.url, candidate.referer, depth) || loaded
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A failed mirror must not prevent subsequent candidates from resolving.
            }
        }
        return loaded
    }

    private fun String.isUploadsWrapper(): Boolean = runCatching {
        directMediaType(this) == null && URI(this).path.orEmpty().contains("/uploads/", ignoreCase = true)
    }.getOrDefault(false)

    private suspend fun resolveDirect(url: String, referer: String): Boolean {
        if (url.isBloggerVideoUrl() && bloggerResolver(url, referer)) return true
        return genericResolver(url, referer)
    }

    private fun String.isBloggerVideoUrl(): Boolean = runCatching {
        if (!isSafeRemoteHttpUrl(this)) return@runCatching false
        val uri = URI(this)
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return@runCatching false
        (host == "blogger.com" || host.endsWith(".blogger.com")) && uri.path == "/video.g"
    }.getOrDefault(false)
}
