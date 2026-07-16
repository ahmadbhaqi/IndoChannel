package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://oploverz.org"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "ongoing/page/%d/" to "Ongoing",
        "complete/page/%d/" to "Completed",
        "movie/page/%d/" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        return newHomePageResponse(request.name, document.toAnimeResults())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.post(
            "$mainUrl/ajax/search_suggests.php",
            data = mapOf("kw" to query),
            referer = "$mainUrl/",
            headers = mapOf(
                "Accept" to "application/json,text/javascript,*/*;q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text
        return OploverzSearchParser.parse(json).map { item ->
            val type = if (item.title.contains("Movie", ignoreCase = true)) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }
            val poster = "$mainUrl/assets/covers/${item.image.replace(" ", "%20")}"
            newAnimeSearchResponse(item.title.cleanOploverzTitle(), "$mainUrl/${item.slug}/", type) {
                posterUrl = poster
            }
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

        return newAnimeSearchResponse(title.cleanOploverzTitle(), href, type) {
            posterUrl = poster
            addSub(episode)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
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
                newEpisode(href) {
                    name = label.ifBlank { episodeNumber?.let { "Episode $it" } ?: "Episode" }
                    episode = episodeNumber
                    posterUrl = poster
                }
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
            listOf(newEpisode(url) {
                name = title
                posterUrl = poster
            })
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fetch = try {
            app.get(data, timeout = PROVIDER_HTTP_TIMEOUT_SECONDS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        val document = fetch.document
        val resolver = LinkResolutionSession(this, subtitleCallback, callback)
        val emittedBloggerUrls = mutableSetOf<String>()

        suspend fun emitBloggerVideo(url: String) {
            if (!emittedBloggerUrls.add(url)) return
            callback(
                newExtractorLink(name, "$name Blogger", url, ExtractorLinkType.VIDEO) {
                    referer = "$BLOGGER_ORIGIN/"
                    quality = Qualities.Unknown.value
                    headers = mapOf("Referer" to "$BLOGGER_ORIGIN/")
                }
            )
        }

        suspend fun resolveBlogger(playerUrl: String, pageReferer: String): Boolean {
            val token = InlineDataParser.bloggerToken(playerUrl) ?: return false
            val beforePlayer = emittedBloggerUrls.size
            return try {
                val bootstrapResponse = app.get(
                    playerUrl,
                    referer = pageReferer,
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                )
                for (url in InlineDataParser.bloggerVideoUrls(bootstrapResponse.text)) {
                    emitBloggerVideo(url)
                }
                if (emittedBloggerUrls.size > beforePlayer) return true

                val bootstrap = InlineDataParser.bloggerBootstrap(bootstrapResponse.text) ?: return false
                val endpoint = buildString {
                    append("$BLOGGER_ORIGIN/_/BloggerVideoPlayerUi/data/batchexecute")
                    append("?rpcids=WcwnYd&source-path=%2Fvideo.g")
                    append("&f.sid=")
                    append(URLEncoder.encode(bootstrap.sid, Charsets.UTF_8.name()))
                    append("&bl=")
                    append(URLEncoder.encode(bootstrap.buildLabel, Charsets.UTF_8.name()))
                    append("&hl=en-US")
                    append("&_reqid=1&rt=c")
                }
                val rpcResponse = app.post(
                    endpoint,
                    requestBody = InlineDataParser.bloggerRpcFormBody(token)
                        .toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType()),
                    referer = "$BLOGGER_ORIGIN/",
                    headers = mapOf(
                        "Accept" to "application/json,text/plain,*/*",
                        "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                        "Origin" to BLOGGER_ORIGIN,
                        "X-Same-Domain" to "1"
                    ),
                    timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
                ).text
                for (url in InlineDataParser.bloggerVideoUrls(rpcResponse)) {
                    emitBloggerVideo(url)
                }
                emittedBloggerUrls.size > beforePlayer
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }

        suspend fun resolveCandidate(raw: String?, pageReferer: String) {
            val candidate = ProviderHtmlParser.absoluteUrl(raw, pageReferer) ?: return
            if (InlineDataParser.bloggerToken(candidate) != null) {
                if (resolveBlogger(candidate, pageReferer)) return
            }
            resolver.resolve(candidate, pageReferer)
        }

        ProviderHtmlParser.mediaSources(document, "iframe#istream, iframe").forEach { raw ->
            resolveCandidate(raw, fetch.url)
        }
        document.select("select.mirvid option[value], .mirvid option[value]").forEach { option ->
            val encoded = option.attr("value").trim().takeIf { it.isNotBlank() } ?: return@forEach
            val server = encoded.decodeOploverzMirror()
                ?: encoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            resolveCandidate(server, fetch.url)
        }
        return emittedBloggerUrls.isNotEmpty() || resolver.loaded
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

    private companion object {
        const val BLOGGER_ORIGIN = "https://www.blogger.com"
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
