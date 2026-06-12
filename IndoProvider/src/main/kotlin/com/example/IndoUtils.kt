package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

fun Element.getImageAttr(): String = when {
    hasAttr("data-src") -> attr("abs:data-src")
    hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
    hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
    else -> attr("abs:src")
}

fun String?.fixImageQuality(): String? {
    if (this == null) return null
    val suffix = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
    return replace(suffix, "")
}

fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

fun Element?.getIframeAttr(): String? =
    this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() } ?: this?.attr("src")

fun getAnimeType(t: String): TvType = when {
    t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
    t.contains("Movie", true) -> TvType.AnimeMovie
    else -> TvType.Anime
}

fun getAnimeStatus(t: String): ShowStatus = when (t) {
    "Completed" -> ShowStatus.Completed
    "Ongoing" -> ShowStatus.Ongoing
    else -> ShowStatus.Completed
}

/**
 * Selector covering the player iframe embedded directly on a gmr/"gomovies" theme page.
 */
internal const val GMR_EMBED_SELECTOR =
    "div.gmr-embed-responsive iframe, div.movieplay iframe, div.player-embed iframe"

/**
 * Selector covering direct download / mirror links commonly rendered by the gmr theme.
 */
internal const val GMR_DOWNLOAD_SELECTOR =
    "div.gmr-download-wrap a[href^=http], div#download a[href^=http], " +
        "ul.list-server-items li a[href^=http], div.gmr-download-list a[href^=http]"

/**
 * Pure extraction of every directly-reachable streaming/mirror URL from a gmr-theme page,
 * without performing any network request. Returns a de-duplicated, order-preserving list.
 *
 * This intentionally captures BOTH the default embedded player iframe and the download/mirror
 * links so that a single dead "active" server no longer hides the other valid sources, which
 * is the root cause of films showing only one server with a "link not found" status.
 */
internal fun collectGmrDirectLinks(
    document: org.jsoup.nodes.Document,
    fixUrlFn: (String) -> String
): List<String> {
    val out = LinkedHashSet<String>()
    document.select(GMR_EMBED_SELECTOR).forEach { ele ->
        ele.getIframeAttr()?.takeIf { it.isNotBlank() }?.let { out.add(fixIframeUrl(it, fixUrlFn)) }
    }
    document.select(GMR_DOWNLOAD_SELECTOR).forEach { ele ->
        ele.attr("href").takeIf { it.isNotBlank() }?.let { out.add(fixIframeUrl(it, fixUrlFn)) }
    }
    return out.toList()
}

/**
 * Robust link loader for gmr/"gomovies" + muvipro theme sites (Ngefilm, Gomov, Dutamovie,
 * Pusatfilm, ...).
 *
 * Unlike the previous per-provider logic that only looked at a single source, this:
 *  1. resolves the muvipro AJAX server tabs (`div.tab-content-ajax`),
 *  2. resolves the muvipro player tabs that link to standalone embed pages
 *     (`ul.muvipro-player-tabs li a`),
 *  3. captures the default player iframe embedded directly on the page, and
 *  4. captures direct download / mirror links.
 *
 * Every discovered URL is de-duplicated and dispatched to [loadExtractor]. The function
 * returns `true` only when at least one extractor actually produced a playable link, so the
 * UI status reflects reality instead of always reporting success.
 */
suspend fun loadGmrLinks(
    data: String,
    directUrl: String?,
    fixUrlFn: (String) -> String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val response = app.get(data)
    val document = response.document
    val base = (directUrl?.takeIf { it.isNotBlank() } ?: getBaseUrl(response.url)).trimEnd('/')
    val referer = "$base/"

    val found = java.util.concurrent.atomic.AtomicBoolean(false)
    val seen = java.util.Collections.synchronizedSet(HashSet<String>())

    suspend fun dispatch(rawUrl: String?) {
        val cleaned = rawUrl?.takeIf { it.isNotBlank() } ?: return
        val url = httpsify(fixIframeUrl(cleaned, fixUrlFn))
        if (!seen.add(url)) return
        try {
            if (loadExtractor(url, referer, subtitleCallback, callback)) found.set(true)
        } catch (e: Exception) {
            logError(e)
        }
    }

    // 1) muvipro AJAX server tabs
    val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
    if (!postId.isNullOrEmpty()) {
        document.select("div.tab-content-ajax").amap { ele ->
            try {
                val src = app.post(
                    "$base/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to ele.attr("id"),
                        "post_id" to postId
                    )
                ).document.selectFirst("iframe").getIframeAttr()
                dispatch(src)
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    // 2) muvipro player tabs that link to standalone embed pages
    document.select("ul.muvipro-player-tabs li a").amap { ele ->
        try {
            val href = ele.attr("href")
            if (href.isBlank()) return@amap
            val iframe = app.get(fixUrlFn(href)).document
                .selectFirst(GMR_EMBED_SELECTOR)
                .getIframeAttr()
            dispatch(iframe)
        } catch (e: Exception) {
            logError(e)
        }
    }

    // 3) default player iframe + direct download/mirror links already present on the page
    collectGmrDirectLinks(document, fixUrlFn).amap { dispatch(it) }

    return found.get()
}
