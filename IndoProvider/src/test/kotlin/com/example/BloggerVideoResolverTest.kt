package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BloggerVideoResolverTest {
    private val playerUrl = "https://www.blogger.com/video.g?token=current-token"
    private val pageReferer = "https://provider.example/episode-1"
    private val mediaUrl =
        "https://rr2---sn.example.googlevideo.com/videoplayback?expire=1&mime=video/mp4"

    @Test
    fun `emits direct media found in Blogger bootstrap`() = runBlocking {
        val requests = RecordingBloggerNetwork(
            playerResponse = """
                var VIDEO_CONFIG = {"streams":[{"play_url":"$mediaUrl"}]};
            """.trimIndent()
        )
        val links = mutableListOf<ExtractorLink>()
        val resolver = BloggerVideoResolver("Samehadaku", links::add, requests)

        assertTrue(resolver.resolve(playerUrl, pageReferer))
        assertEquals(listOf(playerUrl to pageReferer), requests.playerRequests)
        assertTrue(requests.rpcRequests.isEmpty())
        assertEquals(listOf(mediaUrl), links.map { it.url })
        assertEquals(ExtractorLinkType.VIDEO, links.single().type)
        assertEquals("https://www.blogger.com/", links.single().referer)
        assertEquals("https://www.blogger.com/", links.single().headers["Referer"])
    }

    @Test
    fun `uses WcwnYd RPC fallback when bootstrap has no direct media`() = runBlocking {
        val requests = RecordingBloggerNetwork(
            playerResponse = """
                window.WIZ_global_data={"FdrFJe":"-12345","cfb2h":"boq_build_20260709"};
            """.trimIndent(),
            rpcResponse = bloggerRpcResponse(mediaUrl)
        )
        val links = mutableListOf<ExtractorLink>()
        val resolver = BloggerVideoResolver("Oploverz", links::add, requests)

        assertTrue(resolver.resolve(playerUrl, pageReferer))
        assertEquals(listOf(mediaUrl), links.map { it.url })
        val request = requests.rpcRequests.single()
        assertTrue(request.endpoint.contains("rpcids=WcwnYd"))
        assertTrue(request.endpoint.contains("source-path=%2Fvideo.g"))
        assertTrue(request.endpoint.contains("f.sid=-12345"))
        assertTrue(request.endpoint.contains("bl=boq_build_20260709"))
        assertEquals("https://www.blogger.com/", request.referer)
        assertEquals("https://www.blogger.com", request.headers["Origin"])
        assertEquals("1", request.headers["X-Same-Domain"])
        assertEquals("application/x-www-form-urlencoded;charset=UTF-8", request.headers["Content-Type"])
        assertEquals(InlineDataParser.bloggerRpcFormBody("current-token"), request.formBody)
    }

    @Test
    fun `emits duplicate Blogger media URL only once`() = runBlocking {
        val requests = RecordingBloggerNetwork(
            playerResponse = """
                var VIDEO_CONFIG = {"streams":[{"play_url":"$mediaUrl"}]};
            """.trimIndent()
        )
        val links = mutableListOf<ExtractorLink>()
        val resolver = BloggerVideoResolver("Oploverz", links::add, requests)

        assertTrue(resolver.resolve(playerUrl, pageReferer))
        assertFalse(resolver.resolve(playerUrl, pageReferer))
        assertEquals(listOf(mediaUrl), links.map { it.url })
    }

    @Test
    fun `returns false when Blogger cannot yield media so caller can fall back`() = runBlocking {
        val requests = RecordingBloggerNetwork(playerResponse = "<html>unavailable</html>")
        val resolver = BloggerVideoResolver("Samehadaku", { false }, requests)

        assertFalse(resolver.resolve(playerUrl, pageReferer))
        assertTrue(requests.rpcRequests.isEmpty())
    }

    @Test
    fun `rejects private media urls from Blogger payloads`() = runBlocking {
        val requests = RecordingBloggerNetwork(
            playerResponse = """
                var VIDEO_CONFIG = {"streams":[{"play_url":"http://127.0.0.1/private.mp4"}]};
            """.trimIndent()
        )
        val links = mutableListOf<ExtractorLink>()
        val resolver = BloggerVideoResolver("Samehadaku", links::add, requests)

        assertFalse(resolver.resolve(playerUrl, pageReferer))
        assertTrue(links.isEmpty())
    }

    @Test
    fun `returns false when the media emitter rejects a stale link`() = runBlocking {
        val requests = RecordingBloggerNetwork(
            playerResponse = """
                var VIDEO_CONFIG = {"streams":[{"play_url":"$mediaUrl"}]};
            """.trimIndent()
        )
        val resolver = BloggerVideoResolver("Samehadaku", { false }, requests)

        assertFalse(resolver.resolve(playerUrl, pageReferer))
    }

    @Test
    fun `propagates cancellation from the Blogger network boundary`() = runBlocking {
        val requests = object : BloggerVideoNetwork {
            override suspend fun getPlayer(url: String, referer: String): String {
                throw CancellationException("cancelled")
            }

            override suspend fun postRpc(request: BloggerRpcRequest): String =
                error("RPC must not run after cancellation")
        }
        val resolver = BloggerVideoResolver("Samehadaku", { false }, requests)

        assertFailsWith<CancellationException> {
            resolver.resolve(playerUrl, pageReferer)
        }
        Unit
    }

    private class RecordingBloggerNetwork(
        private val playerResponse: String,
        private val rpcResponse: String = ""
    ) : BloggerVideoNetwork {
        val playerRequests = mutableListOf<Pair<String, String>>()
        val rpcRequests = mutableListOf<BloggerRpcRequest>()

        override suspend fun getPlayer(url: String, referer: String): String {
            playerRequests += url to referer
            return playerResponse
        }

        override suspend fun postRpc(request: BloggerRpcRequest): String {
            rpcRequests += request
            return rpcResponse
        }
    }

    private fun bloggerRpcResponse(url: String): String {
        val inner = """[1,null,[["$url",[18]]]]"""
        val escapedInner = jacksonObjectMapper().writeValueAsString(inner)
        return "[[\"wrb.fr\",\"WcwnYd\",$escapedInner,null,null,null,\"generic\"]]"
    }
}
