package com.example

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor
import org.jsoup.select.QueryParser

internal object PopularProviderLinkLimits {
    private const val MAX_MUVIPRO_AJAX_REQUESTS = 16
    private const val MAX_PLAYER_ELEMENTS = 48
    private const val MAX_SCOPED_SCAN_NODES = 16_384
    private const val MAX_SCOPED_SCRIPT_CHARS = 262_144
    private const val MAX_DATA_IFRAME_CHARS = 65_536
    private val scopedMediaAttributes = listOf(
        "data-litespeed-src",
        "data-src",
        "src",
        "content",
        "data-video",
        "data-url"
    )
    private val externalScriptAttributes = listOf(
        "src",
        "data-src",
        "data-litespeed-src"
    )

    fun muviproAjaxRequests(document: Document): List<MuviproAjaxRequest> =
        ProviderHtmlParser.muviproAjaxRequests(document).take(MAX_MUVIPRO_AJAX_REQUESTS)

    fun playerElements(document: Document, selector: String): List<Element> {
        val evaluator = QueryParser.parse(selector)
        val matches = ArrayList<Element>(MAX_PLAYER_ELEMENTS)
        var scannedNodes = 0
        NodeTraversor.filter(
            object : NodeFilter {
                override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                    scannedNodes += 1
                    if (scannedNodes > MAX_SCOPED_SCAN_NODES) {
                        return NodeFilter.FilterResult.STOP
                    }
                    if (node is Element && evaluator.matches(document, node)) {
                        matches += node
                        if (matches.size >= MAX_PLAYER_ELEMENTS) {
                            return NodeFilter.FilterResult.STOP
                        }
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }

                override fun tail(node: Node, depth: Int): NodeFilter.FilterResult =
                    NodeFilter.FilterResult.CONTINUE
            },
            document
        )
        return matches
    }

    fun scopedMediaUrls(document: Document, selector: String): List<String> {
        val evaluator = QueryParser.parse(selector)
        val candidates = LinkedHashSet<String>()
        var scannedNodes = 0
        NodeTraversor.filter(
            object : NodeFilter {
                override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                    scannedNodes += 1
                    if (scannedNodes > MAX_SCOPED_SCAN_NODES) {
                        return NodeFilter.FilterResult.STOP
                    }
                    val element = node as? Element ?: return NodeFilter.FilterResult.CONTINUE
                    if (!evaluator.matches(document, element)) {
                        return NodeFilter.FilterResult.CONTINUE
                    }
                    appendScopedMedia(element, candidates)
                    return if (candidates.size >= MAX_PLAYER_ELEMENTS) {
                        NodeFilter.FilterResult.STOP
                    } else {
                        NodeFilter.FilterResult.CONTINUE
                    }
                }

                override fun tail(node: Node, depth: Int): NodeFilter.FilterResult =
                    NodeFilter.FilterResult.CONTINUE
            },
            document
        )
        return candidates.toList()
    }

    private fun appendScopedMedia(element: Element, candidates: MutableSet<String>) {
        val isScript = element.tagName().equals("script", ignoreCase = true)
        val externalScript = isScript && externalScriptAttributes.any(element::hasAttr)
        if (externalScript) return

        if (!isScript) {
            for (attribute in scopedMediaAttributes) {
                element.attr(attribute).trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(candidates::add)
                if (candidates.size >= MAX_PLAYER_ELEMENTS) return
            }
        }

        if (!isScript) {
            element.attr("data-iframe").trim()
                .takeIf { it.isNotBlank() && it.length <= MAX_DATA_IFRAME_CHARS }
                ?.let { encoded ->
                    val decoded = if (isSafeRemoteHttpUrl(encoded)) {
                        encoded
                    } else {
                        decodeBase64Compat(encoded)?.toString(Charsets.UTF_8)?.trim()
                            ?.takeIf { it.length <= MAX_DATA_IFRAME_CHARS }
                            ?.takeIf(::isSafeRemoteHttpUrl)
                    }
                    decoded?.let(candidates::add)
                }
        }
        if (candidates.size >= MAX_PLAYER_ELEMENTS) return

        if (isScript) {
            candidates += InlineDataParser.boundedInlinePlayerUrls(
                element.data(),
                MAX_PLAYER_ELEMENTS - candidates.size,
                MAX_SCOPED_SCRIPT_CHARS
            )
        }
    }
}
