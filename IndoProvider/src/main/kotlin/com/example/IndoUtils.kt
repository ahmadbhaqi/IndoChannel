package com.example

import com.lagradost.cloudstream3.*
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

suspend fun loadMuviproLinks(
    document: org.jsoup.nodes.Document,
    directUrl: String?,
    fixUrlFn: (String) -> String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
    var hasLinks = false
    if (id.isNullOrEmpty()) {
        document.select("ul.muvipro-player-tabs li a").amap { ele ->
            try {
                val iframe = app.get(fixUrlFn(ele.attr("href")))
                    .document.selectFirst("div.gmr-embed-responsive iframe")
                    .getIframeAttr()
                    ?.let { httpsify(it) } ?: return@amap
                loadExtractor(iframe, "$directUrl/", subtitleCallback, callback)
                hasLinks = true
            } catch (e: Exception) {
                logError(e)
            }
        }
    } else {
        document.select("div.tab-content-ajax").amap { ele ->
            try {
                val src = app.post(
                    "$directUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "muvipro_player_content", "tab" to ele.attr("id"), "post_id" to "$id")
                ).document.select("iframe").attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(httpsify(src), "$directUrl/", subtitleCallback, callback)
                    hasLinks = true
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
    }
    return hasLinks
}
