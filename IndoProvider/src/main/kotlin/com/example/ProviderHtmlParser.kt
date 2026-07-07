package com.example

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

    fun firstIframeSource(element: Element?): String? {
        return iframeAttrs
            .firstNotNullOfOrNull { attr ->
                element?.attr(attr)?.trim()?.takeIf { it.isPlayableCandidate() }
            }
    }

    fun iframeSources(document: Document, selector: String = "iframe"): List<String> {
        return document.select(selector).mapNotNull { firstIframeSource(it) }.distinct()
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
        return isNotBlank() && !startsWith("javascript:", ignoreCase = true)
    }
}
