package com.example

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal data class BloggerRpcRequest(
    val endpoint: String,
    val formBody: String,
    val referer: String,
    val headers: Map<String, String>
)

/** Network boundary for Blogger's player bootstrap and WcwnYd RPC requests. */
internal interface BloggerVideoNetwork {
    suspend fun getPlayer(url: String, referer: String): String
    suspend fun postRpc(request: BloggerRpcRequest): String
}

private object DefaultBloggerVideoNetwork : BloggerVideoNetwork {
    override suspend fun getPlayer(url: String, referer: String): String = app.get(
        url,
        referer = referer,
        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
    ).text

    override suspend fun postRpc(request: BloggerRpcRequest): String = app.post(
        request.endpoint,
        requestBody = request.formBody
            .toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType()),
        referer = request.referer,
        headers = request.headers,
        timeout = PROVIDER_HTTP_TIMEOUT_SECONDS
    ).text
}

/** Resolves Blogger /video.g players to direct video links for any provider. */
internal class BloggerVideoResolver(
    private val source: String,
    private val emitter: suspend (ExtractorLink) -> Boolean,
    private val network: BloggerVideoNetwork = DefaultBloggerVideoNetwork
) {
    private val emittedUrls = mutableSetOf<String>()

    val loaded: Boolean get() = emittedUrls.isNotEmpty()

    suspend fun resolve(playerUrl: String, pageReferer: String): Boolean {
        val token = InlineDataParser.bloggerToken(playerUrl) ?: return false
        val beforePlayer = emittedUrls.size
        return try {
            val bootstrap = network.getPlayer(playerUrl, pageReferer)
            for (url in InlineDataParser.bloggerVideoUrls(bootstrap)) {
                emitVideo(url)
            }
            if (emittedUrls.size > beforePlayer) return true

            val bootstrapData = InlineDataParser.bloggerBootstrap(bootstrap) ?: return false
            val request = BloggerRpcRequest(
                endpoint = rpcEndpoint(bootstrapData),
                formBody = InlineDataParser.bloggerRpcFormBody(token),
                referer = "$BLOGGER_ORIGIN/",
                headers = mapOf(
                    "Accept" to "application/json,text/plain,*/*",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "Origin" to BLOGGER_ORIGIN,
                    "X-Same-Domain" to "1"
                )
            )
            for (url in InlineDataParser.bloggerVideoUrls(network.postRpc(request))) {
                emitVideo(url)
            }
            emittedUrls.size > beforePlayer
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun emitVideo(url: String): Boolean {
        if (!isSafeRemoteHttpUrl(url) || url in emittedUrls) return false
        val accepted = emitter(
            newExtractorLink(source, "$source Blogger", url, ExtractorLinkType.VIDEO) {
                referer = "$BLOGGER_ORIGIN/"
                quality = Qualities.Unknown.value
                headers = mapOf("Referer" to "$BLOGGER_ORIGIN/")
            }.withSimpleServerName(source)
        )
        if (accepted) emittedUrls += url
        return accepted
    }

    private fun rpcEndpoint(bootstrap: BloggerBootstrap): String = buildString {
        append("$BLOGGER_ORIGIN/_/BloggerVideoPlayerUi/data/batchexecute")
        append("?rpcids=WcwnYd&source-path=%2Fvideo.g")
        append("&f.sid=")
        append(URLEncoder.encode(bootstrap.sid, Charsets.UTF_8.name()))
        append("&bl=")
        append(URLEncoder.encode(bootstrap.buildLabel, Charsets.UTF_8.name()))
        append("&hl=en-US")
        append("&_reqid=1&rt=c")
    }

    private companion object {
        const val BLOGGER_ORIGIN = "https://www.blogger.com"
    }
}
