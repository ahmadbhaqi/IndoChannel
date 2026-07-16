package com.example

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal data class MuviproAjaxRequest(
    val postId: String,
    val tab: String
) {
    fun toPostData(): Map<String, String> = mapOf(
        "action" to "muvipro_player_content",
        "tab" to tab,
        "post_id" to postId
    )
}

internal object ProviderHtmlParser {
    private val iframeAttrs = listOf("data-litespeed-src", "data-src", "src")
    private val imageAttrs = listOf("data-litespeed-src", "data-src", "data-lazy-src", "data-original", "src")
    private val mediaMetaSelectors = listOf(
        "meta[property=og:video:url]",
        "meta[property=og:video:secure_url]",
        "meta[name=twitter:player]"
    )

    fun firstIframeSource(element: Element?): String? {
        return iframeAttrs
            .firstNotNullOfOrNull { attr ->
                element?.attr(attr)?.trim()?.takeIf { it.isPlayableCandidate() }
            }
    }

    fun iframeSources(document: Document, selector: String = "iframe"): List<String> {
        return document.select(selector).mapNotNull { firstIframeSource(it) }.distinct()
    }

    fun imageSource(element: Element?): String? {
        if (element == null) return null

        element.attr("srcset")
            .srcsetFirstUrl()
            ?.takeIf { it.isImageCandidate() }
            ?.let { raw ->
                element.attr("abs:srcset").srcsetFirstUrl()?.takeIf { it.isImageCandidate() } ?: raw
            }
            ?.let { return it }

        return imageAttrs.firstNotNullOfOrNull { attr ->
            element.attr(attr)
                .trim()
                .takeIf { it.isImageCandidate() }
                ?.let { raw ->
                    element.attr("abs:$attr").trim().takeIf { it.isImageCandidate() } ?: raw
                }
        }
    }

    fun firstImageSource(element: Element?, selector: String = "img"): String? {
        return element
            ?.select(selector)
            ?.firstNotNullOfOrNull { imageSource(it) }
    }

    fun absoluteUrl(raw: String?, baseUrl: String): String? {
        val value = raw?.trim()?.takeIf { it.isPlayableCandidate() } ?: return null
        return try {
            val uri = URI(value)
            if (uri.isAbsolute) value else URI(baseUrl.trimEnd('/') + "/").resolve(value).toString()
        } catch (_: Exception) {
            null
        }
    }

    fun normalizeUrlHost(raw: String?, baseUrl: String, hostNeedle: String): String? {
        val value = absoluteUrl(raw, baseUrl) ?: return null
        return try {
            val target = URI(value)
            val base = URI(baseUrl)
            val targetHost = target.host.orEmpty()
            if (!targetHost.contains(hostNeedle, ignoreCase = true) || targetHost.equals(base.host, ignoreCase = true)) {
                return value
            }

            URI(
                base.scheme,
                base.authority,
                target.rawPath,
                target.rawQuery,
                target.rawFragment
            ).toString()
        } catch (_: Exception) {
            value
        }
    }

    fun mediaSources(document: Document, iframeSelector: String = "iframe"): List<String> {
        val iframeSources = iframeSources(document, iframeSelector)
        val metaSources = mediaMetaSelectors.flatMap { selector ->
            document.select(selector).mapNotNull { meta ->
                meta.attr("content").trim().takeIf { it.isPlayableCandidate() }
            }
        }
        return (iframeSources + metaSources).distinct()
    }

    fun isNonContentPage(html: String): Boolean {
        val normalized = html.trim().lowercase()
        if (normalized.isEmpty()) return true
        return listOf(
            "<title>internet positif</title>",
            "<title>just a moment...</title>",
            "challenges.cloudflare.com",
            "enable javascript and cookies to continue",
            "mysql server has gone away",
            "sqlstate[hy000] [2006]"
        ).any(normalized::contains)
    }

    fun muviproAjaxRequests(document: Document): List<MuviproAjaxRequest> {
        val postId = document
            .selectFirst("div#muvipro_player_content_id")
            ?.attr("data-id")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        return document
            .select("div.tab-content-ajax[id]")
            .mapNotNull { element ->
                element.attr("id").trim().takeIf { it.isNotBlank() }?.let { tab ->
                    MuviproAjaxRequest(postId = postId, tab = tab)
                }
            }
            .distinct()
    }

    private fun String.isPlayableCandidate(): Boolean {
        return isNotBlank() &&
            !startsWith("javascript:", ignoreCase = true) &&
            !equals("about:blank", ignoreCase = true)
    }

    private fun String.isImageCandidate(): Boolean {
        return isPlayableCandidate() &&
            !startsWith("data:", ignoreCase = true) &&
            !contains("/assets/images/controls-play.svg", ignoreCase = true) &&
            !contains("/assets/images/search.svg", ignoreCase = true) &&
            !contains("histats.com/0.gif", ignoreCase = true)
    }

    private fun String.srcsetFirstUrl(): String? {
        return split(",")
            .asSequence()
            .map { it.trim().substringBefore(" ").trim() }
            .firstOrNull { it.isNotBlank() }
    }
}
