package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal data class OtakudesuEpisodeEntry(
    val href: String,
    val number: Int
)

internal object OtakudesuEpisodeParser {
    private val episodePath = Regex("(?i)(?:^|/)episode(?:/|$)")
    private val labelNumber = Regex(
        "(?i)(?:episode|eps?|ep)[-\\s._:]*(\\d+)"
    )
    private val pathNumber = Regex(
        "(?i)(?:^|[-/_.])(?:episode|eps?|ep)[-_.]*(\\d+)(?=$|[-/_.])"
    )

    fun episodes(
        document: Document,
        providerBaseUri: String = document.baseUri()
    ): List<OtakudesuEpisodeEntry> =
        document.select("div.episodelist ul > li a")
            .mapNotNull { link ->
                val href = link.attr("href").trim().takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                if (!isProviderEpisodeLink(href, providerBaseUri)) {
                    return@mapNotNull null
                }
                val label = link.attr("title").ifBlank { link.text() }
                val number = labelNumber.find(label)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: pathNumber.find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: return@mapNotNull null
                OtakudesuEpisodeEntry(href, number)
            }
            .distinctBy(OtakudesuEpisodeEntry::href)

    private fun isProviderEpisodeLink(href: String, baseUri: String): Boolean =
        runCatching {
            val uri = URI(href)
            if (!episodePath.containsMatchIn(uri.path.orEmpty())) {
                return@runCatching false
            }
            if (uri.scheme != null && uri.scheme.lowercase() !in setOf("http", "https")) {
                return@runCatching false
            }

            val linkHost = uri.host ?: return@runCatching uri.rawAuthority == null
            val providerHost = URI(baseUri).host ?: return@runCatching false
            linkHost.equals(providerHost, ignoreCase = true)
        }.getOrDefault(false)
}

internal fun ExtractorLink.withOtakudesuQuality(explicitQuality: Int): ExtractorLink = apply {
    if (explicitQuality != Qualities.Unknown.value) {
        quality = explicitQuality
    }
}

internal object OtakudesuPlaybackScheduler {
    suspend fun resolve(
        mirrors: Iterable<OtakudesuProvider.ResponseSources>,
        canContinue: () -> Boolean,
        playerSources: suspend (OtakudesuProvider.ResponseSources) -> Iterable<String>,
        sourceResolver: suspend (String, Int) -> Boolean
    ): Boolean {
        var loaded = false
        for (mirror in mirrors) {
            if (!canContinue()) break
            val quality = ServerLinkLabelFormatter.resolution(
                Qualities.Unknown.value,
                mirror.q
            ) ?: Qualities.Unknown.value
            val sources = try {
                playerSources(mirror)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            for (source in sources.distinct()) {
                if (!canContinue()) break
                loaded = sourceResolver(source, quality) || loaded
            }
        }
        return loaded
    }
}

class OtakudesuProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.blog/"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    private val safeHttp by lazy {
        ProviderHttpSafetyClient(NiceHttpProviderFetcher(app))
    }

    companion object {
        const val acefile = "https://acefile.co"
        val mirrorBlackList = arrayOf("Mega", "MegaUp", "Otakufiles")
        private const val OTAKUDESU_PLAYBACK_TIMEOUT_MS = 60_000L
        private const val OTAKUDESU_PAGE_BODY_LIMIT_BYTES = 1_000_000
        private const val OTAKUDESU_AJAX_BODY_LIMIT_BYTES = 256_000
        fun getType(t: String): TvType = if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA else if (t.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
        fun getStatus(t: String): ShowStatus = when (t) { "Completed" -> ShowStatus.Completed; "Ongoing" -> ShowStatus.Ongoing; else -> ShowStatus.Completed }
    }

    override val mainPage = mainPageOf("ongoing-anime/page/%d/" to "Anime Ongoing", "complete-anime/page/%d/" to "Anime Completed")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val home = document.select("div.venz > ul > li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = this.select("div.thumbz > img").attr("src").toString()
        val epNum = this.selectFirst("div.epz")?.ownText()?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl; addSub(epNum) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=anime").document
        return document.select("ul.chivsrc > li").mapNotNull {
            val title = it.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")?.trim()
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")?.ownText()?.replace(":", "")?.trim().toString()
        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")
        val tags = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val type = getType(document.selectFirst("div.infozingle > p:nth-child(5) > span")?.ownText()?.replace(":", "")?.trim() ?: "tv")
        val year = Regex("\\d, (\\d*)").find(document.select("div.infozingle > p:nth-child(9) > span").text())?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(document.selectFirst("div.infozingle > p:nth-child(6) > span")!!.ownText().replace(":", "").trim())
        val description = document.select("div.sinopc > p").text()
        val episodes = OtakudesuEpisodeParser.episodes(document, mainUrl).map { entry ->
            newEpisode(fixUrl(entry.href)) {
                name = "Episode ${entry.number}"
                episode = entry.number
            }
        }.reversed()
        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        return newAnimeLoadResponse(title, url, type) {
            engName = title; posterUrl = tracker?.image ?: poster; backgroundPosterUrl = tracker?.cover; this.year = year
            addEpisodes(DubStatus.Subbed, episodes); showStatus = status; plot = description; this.tags = tags
            addMalId(tracker?.malId); addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    data class ResponseSources(@JsonProperty("id") val id: String, @JsonProperty("i") val i: String, @JsonProperty("q") val q: String)
    data class ResponseData(@JsonProperty("data") val data: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        return withTimeoutOrNull(OTAKUDESU_PLAYBACK_TIMEOUT_MS) {
            loadLinksWithinBudget(
                data,
                subtitleCallback,
                callback = { link ->
                    emitted = true
                    callback(link)
                }
            )
        } ?: emitted
    }

    private suspend fun loadLinksWithinBudget(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val requestUrl = networkProviderUrl(data) ?: return false
        val page = safeHttp.get(
            url = requestUrl,
            normalizer = ProviderUrlNormalizer(::networkProviderUrl),
            maxBodyBytes = OTAKUDESU_PAGE_BODY_LIMIT_BYTES,
            timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
        )
        if (
            page.code !in 200..299 ||
            ProviderHtmlParser.isNonContentPage(page.body)
        ) return false
        val document = Jsoup.parse(page.body, page.url)
        var activeQuality = Qualities.Unknown.value
        val resolver = LinkResolutionSession(
            this,
            subtitleCallback,
            callback = { link ->
                callback(link.withOtakudesuQuality(activeQuality))
            },
            candidateTimeoutMs = 18_000L,
            genericExtractorTimeoutMs = 8_000L,
            sessionTimeoutMs = 60_000L
        )
        try {
            val scriptData = document.select("script:containsData(action:)").lastOrNull()?.data()
            val token = scriptData?.substringAfter("{action:\"")?.substringBefore("\"}")
                ?.takeIf(String::isNotBlank) ?: return false
            val action = scriptData.substringAfter(",action:\"").substringBefore("\"}")
                .takeIf(String::isNotBlank) ?: return false
            val pageUri = URI(page.url)
            val ajaxUrl = "${pageUri.scheme}://${pageUri.rawAuthority}/wp-admin/admin-ajax.php"
            val ajaxNormalizer = ProviderUrlNormalizer(::networkProviderUrl)
            val nonceResponse = safeHttp.postForm(
                url = ajaxUrl,
                form = mapOf("action" to token),
                normalizer = ajaxNormalizer,
                referer = page.url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                maxBodyBytes = OTAKUDESU_AJAX_BODY_LIMIT_BYTES,
                timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
            )
            if (nonceResponse.code !in 200..299) return false
            val nonce = tryParseJson<ResponseData>(nonceResponse.body)
                ?.data?.takeIf(String::isNotBlank) ?: return false
            val mirrors = document.select("div.mirrorstream a[data-content]")
                .asSequence()
                .mapNotNull { anchor ->
                    runCatching { base64Decode(anchor.attr("data-content")) }
                        .getOrNull()
                        ?.let { tryParseJson<ResponseSources>(it) }
                }
                .distinctBy { Triple(it.id, it.i, it.q) }
                .take(48)
                .toList()
            OtakudesuPlaybackScheduler.resolve(
                mirrors = mirrors,
                canContinue = { resolver.canContinue },
                playerSources = { res ->
                    val response = safeHttp.postForm(
                        url = ajaxUrl,
                        form = mapOf(
                            "id" to res.id,
                            "i" to res.i,
                            "q" to res.q,
                            "nonce" to nonce,
                            "action" to action
                        ),
                        normalizer = ajaxNormalizer,
                        referer = page.url,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        maxBodyBytes = OTAKUDESU_AJAX_BODY_LIMIT_BYTES,
                        timeoutSeconds = PROVIDER_HTTP_TIMEOUT_SECONDS
                    )
                    if (response.code !in 200..299) {
                        emptyList()
                    } else {
                        tryParseJson<ResponseData>(response.body)?.data
                            ?.let { encoded ->
                                runCatching { base64Decode(encoded) }.getOrNull()
                            }
                            ?.let { fragment -> Jsoup.parse(fragment, page.url) }
                            ?.select("iframe[src]")
                            ?.map { it.attr("src") }
                            .orEmpty()
                    }
                },
                sourceResolver = { source, quality ->
                    activeQuality = quality
                    resolver.resolve(source, data)
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A rotating AJAX mirror can fail independently of the next episode.
        }
        return resolver.loaded
    }

    private fun networkProviderUrl(raw: String?): String? =
        ProviderHtmlParser.preserveProviderPageUrl(raw, mainUrl)
}
