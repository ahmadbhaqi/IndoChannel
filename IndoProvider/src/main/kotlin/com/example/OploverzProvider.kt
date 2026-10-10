package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://oploverz.site"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "home" to "Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val document = app.get("$mainUrl/").document
        val items = OploverzCurrentSiteParser.catalogItems(document, mainUrl, true)
            .mapNotNull { it.toCurrentAnimeResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val document = app.get("$mainUrl/series").document
        return OploverzCurrentSiteParser.catalogItems(document, mainUrl, false)
            .filter { it.title.contains(query.trim(), ignoreCase = true) }
            .mapNotNull { it.toCurrentAnimeResult() }
    }

    private fun OploverzCurrentCatalogItem.toCurrentAnimeResult(): AnimeSearchResponse? {
        if (SensitiveContentPolicy.isBlocked(title, url)) return null
        val type = if (url.contains("/movie/")) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, url, type) {
            posterUrl = poster
        }
    }

    private fun Document.toAnimeResults(): List<AnimeSearchResponse> {
        return select(".xrelated > a:has(img)")
            .mapNotNull { it.toAnimeResult() }
            .distinctBy { it.url }
    }

    private fun Element.toAnimeResult(): AnimeSearchResponse? {
        val href = ProviderHtmlParser.absoluteUrl(attr("href"), mainUrl) ?: return null
        val title = selectFirst(".titlelist")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val cleanTitle = title.cleanOploverzTitle()
        if (SensitiveContentPolicy.isBlocked(cleanTitle, href)) return null
        val poster = fixUrlNull(ProviderHtmlParser.imageSource(selectFirst("img")))
        val episode = Regex("""(?:Episode|Ep\.?)[\s-]*(\d+)""", RegexOption.IGNORE_CASE)
            .find(selectFirst(".eplist")?.text().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val type = if (title.contains("Movie", ignoreCase = true) || href.contains("/movie/")) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newAnimeSearchResponse(cleanTitle, href, type) {
            posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (SensitiveContentPolicy.isBlocked(null, url)) return null
        if (url.startsWith("$mainUrl/series/") || url.startsWith("$mainUrl/movie/")) {
            return loadCurrentSeries(url)
        }
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1.entry-title, meta[property=og:title]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.substringBefore("|")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val title = rawTitle.cleanOploverzTitle()
        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: fixUrlNull(ProviderHtmlParser.imageSource(document.selectFirst("img.cover")))
        val description = document.select(".sinops p, .sinops")
            .text()
            .trim()
            .takeIf { it.isNotBlank() }
        val infoText = document.select(".infopost").text()
        val year = Regex("""(?:Rilis|Released?)\s*:?\s*.*?\b((?:19|20)\d{2})\b""", RegexOption.IGNORE_CASE)
            .find(infoText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val tags = document.select(".infopost a[href*='/genres/'], .infopost a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (SensitiveContentPolicy.isBlocked(title, url, categories = tags)) return null
        val status = when {
            infoText.contains("Completed", ignoreCase = true) ||
                infoText.contains("Tamat", ignoreCase = true) -> ShowStatus.Completed
            infoText.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }
        val episodes = document.select("a.othereps[href]")
            .mapNotNull { link ->
                val href = ProviderHtmlParser.absoluteUrl(link.attr("href"), mainUrl) ?: return@mapNotNull null
                val label = link.text().trim().ifBlank { link.attr("title").trim() }
                val episodeNumber = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find("$label $href")
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                newEpisode(
                    AnimePlaybackDataCodec.encode(
                        url = href,
                        title = label.ifBlank { title },
                        categories = tags,
                        detailUrl = url
                    ),
                    initializer = {
                        name = label.ifBlank {
                            episodeNumber?.let { "Episode $it" } ?: "Episode"
                        }
                        episode = episodeNumber
                        posterUrl = poster
                    },
                    fix = false
                )
            }
            .distinctBy { it.data }
        val type = if (title.contains("Movie", ignoreCase = true) ||
            infoText.contains("Movie", ignoreCase = true)
        ) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        val playableEpisodes = episodes.ifEmpty {
            listOf(
                newEpisode(
                    AnimePlaybackDataCodec.encode(
                        url = url,
                        title = title,
                        categories = tags,
                        detailUrl = url
                    ),
                    initializer = {
                        name = title
                        posterUrl = poster
                    },
                    fix = false
                )
            )
        }
        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            showStatus = status
            addEpisodes(DubStatus.Subbed, playableEpisodes)
        }
    }

    private suspend fun loadCurrentSeries(url: String): LoadResponse? {
        val fetch = app.get(url)
        val document = fetch.document
        val title = document.select("meta[property=og:title]").lastOrNull()
            ?.attr("content")?.substringBefore('|')?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val tags = OploverzCurrentSiteParser.genres(fetch.text)
        if (SensitiveContentPolicy.isBlocked(title, url, categories = tags)) return null
        val poster = document.select("meta[property=og:image]").lastOrNull()
            ?.attr("content")?.takeIf { it.isNotBlank() }
        val description = document.select("meta[property=og:description]").lastOrNull()
            ?.attr("content")?.takeIf { it.isNotBlank() }
        val isMovie = url.contains("/movie/")
        val episodeEntries = if (isMovie) listOf(url to 1) else OploverzCurrentSiteParser.episodeLinks(document, url)
        val episodes = episodeEntries.map { (episodeUrl, number) ->
            newEpisode(
                AnimePlaybackDataCodec.encode(
                    url = episodeUrl,
                    title = title,
                    categories = tags,
                    detailUrl = url
                ),
                initializer = {
                    name = if (isMovie) title else number?.let { "Episode $it" } ?: title
                    episode = number
                    posterUrl = poster
                },
                fix = false
            )
        }
        if (episodes.isEmpty()) return null
        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
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
        val playback = AnimePlaybackDataCodec.decode(data)
        if (AnimePlaybackDataCodec.isBlocked(data)) return false
        val pageUrl = playback?.url ?: data
        if (SensitiveContentPolicy.isBlocked(null, pageUrl)) return false
        val fetch = try {
            app.get(pageUrl, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        val document = fetch.document
        val pageTitle = document.select("meta[property=og:title]").lastOrNull()
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
        val pageTags = document
            .select(".infopost a[href*='/genres/'], .infopost a[href*='/genre/']")
            .map { it.text().trim() } + OploverzCurrentSiteParser.genres(fetch.text)
        if (SensitiveContentPolicy.isBlocked(pageTitle, fetch.url, categories = pageTags)) return false
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback,
            mediaProbeTimeoutSeconds = 30L
        )
        val bloggerResolver = BloggerVideoResolver(name, resolver::emitResolved)

        suspend fun resolveCandidate(raw: String?, pageReferer: String) {
            val candidate = ProviderHtmlParser.absoluteUrl(raw, pageReferer) ?: return
            if (InlineDataParser.bloggerToken(candidate) != null) {
                if (resolver.withinBudget { bloggerResolver.resolve(candidate, pageReferer) } == true) return
            }
            resolver.resolve(candidate, pageReferer)
        }

        val currentStreams = if (pageUrl.contains("/movie/")) {
            OploverzCurrentSiteParser.movieStreams(fetch.text)
        } else {
            OploverzCurrentSiteParser.episodeStreams(fetch.text)
        }
        currentStreams.forEach { raw ->
            if (!resolver.loaded && resolver.canContinue) {
                resolveCandidate(raw, fetch.url)
            }
        }
        if (resolver.loaded) return true

        ProviderHtmlParser.mediaSources(document, "iframe#istream, iframe").forEach { raw ->
            resolveCandidate(raw, fetch.url)
        }
        document.select("select.mirvid option[value], .mirvid option[value]").forEach { option ->
            val encoded = option.attr("value").trim().takeIf { it.isNotBlank() } ?: return@forEach
            val server = encoded.decodeOploverzMirror()
                ?: encoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            resolveCandidate(server, fetch.url)
        }
        return resolver.loaded
    }

    private fun String.decodeOploverzMirror(): String? {
        return runCatching {
            val first = URLDecoder.decode(this, "UTF-8").rotateRight(13).decodeBase64()
            URLDecoder.decode(first, "UTF-8").rotateRight(13).decodeBase64()
                .trim()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.getOrNull()
    }

    private fun String.rotateRight(amount: Int): String {
        if (isEmpty()) return this
        val offset = ((amount % length) + length) % length
        return takeLast(offset) + dropLast(offset)
    }

    private fun String.decodeBase64(): String {
        return String(decodeBase64Compat(this) ?: error("Invalid Base64"), Charsets.UTF_8)
    }

    private fun String.cleanOploverzTitle(): String {
        return replace(Regex("""\s*(?:Subtitle\s+Indonesia|Sub\s+Indo).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|')
    }
}

internal data class OploverzSearchItem(
    val slug: String,
    val image: String,
    val title: String
)

internal object OploverzSearchParser {
    private val mapper = jacksonObjectMapper()

    fun parse(json: String): List<OploverzSearchItem> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (root.path("status").asText() != "1") return emptyList()
        return root.path("data")
            .takeIf { it.isArray }
            ?.mapNotNull { node ->
                val slug = node.path("slug").asText().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val image = node.path("img").asText().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = node.path("title").asText().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OploverzSearchItem(slug, image, title)
            }
            .orEmpty()
    }
}

internal data class OploverzCurrentCatalogItem(
    val title: String,
    val url: String,
    val poster: String?
)

internal object OploverzCurrentSiteParser {
    fun movieStreams(html: String): List<String> {
        val streams = Regex(
            """type:\s*"data"\s*,\s*data:\s*\{\s*series:\s*\{.*?episodes:\s*\[.*?streamUrl:\s*\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        return streamUrls(streams)
    }

    fun genres(html: String): List<String> {
        val values = Regex(
            """type:\s*"data"\s*,\s*data:\s*\{\s*(?:series|episode):\s*\{.*?genres:\s*\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        return Regex("""name:\s*"([^\"]+)"""").findAll(values)
            .map { it.groupValues[1] }.distinct().toList()
    }

    fun catalogItems(
        document: Document,
        mainUrl: String,
        requirePoster: Boolean
    ): List<OploverzCurrentCatalogItem> {
        return document.select("a[href*=/series/], a[href*=/movie/]").mapNotNull { link ->
            val raw = link.attr("href").substringBefore("/episode/")
            val url = ProviderHtmlParser.normalizeProviderPageUrl(raw, mainUrl)
                ?: return@mapNotNull null
            val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
            if (!Regex("""^/(?:series|movie)/[^/]+/?$""").matches(path)) return@mapNotNull null
            val poster = ProviderHtmlParser.imageSource(link.selectFirst("img"))
            if (requirePoster && poster.isNullOrBlank()) return@mapNotNull null
            val title = link.selectFirst("img[alt]")?.attr("alt")
                ?.takeIf { it.isNotBlank() }
                ?: link.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            OploverzCurrentCatalogItem(title, url, poster)
        }.distinctBy { it.url }
    }

    fun episodeLinks(document: Document, seriesUrl: String): List<Pair<String, Int?>> {
        val prefix = seriesUrl.trimEnd('/') + "/episode/"
        return document.select("a[href*=episode]").mapNotNull { link ->
            val url = ProviderHtmlParser.absoluteUrl(link.attr("href"), seriesUrl)
                ?: return@mapNotNull null
            if (!url.startsWith(prefix)) return@mapNotNull null
            val number = url.removePrefix(prefix).substringBefore('/').toIntOrNull()
            url to number
        }.distinctBy { it.first }
    }

    fun episodeStreams(html: String): List<String> {
        val streams = Regex(
            """type:\s*"data"\s*,\s*data:\s*\{\s*episode:\s*\{.*?streamUrl:\s*\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        return streamUrls(streams)
    }

    private fun streamUrls(streams: String): List<String> {
        return Regex("""source:\s*"([^\"]*)"\s*,\s*url:\s*"([^\"]+)"""")
            .findAll(streams)
            .mapNotNull { match ->
                val source = match.groupValues[1]
                val value = match.groupValues[2].replace("\\u0026", "&").replace("\\/", "/")
                when {
                    source.equals("okru", true) && value.matches(Regex("""\d{1,20}""")) ->
                        "https://ok.ru/videoembed/$value"
                    isSafeRemoteHttpUrl(value) -> value
                    else -> null
                }
            }
            .distinct()
            .toList()
    }
}
