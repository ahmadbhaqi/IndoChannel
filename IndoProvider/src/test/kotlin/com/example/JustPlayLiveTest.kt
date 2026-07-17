package com.example

import com.lagradost.cloudstream3.app
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JustPlayLiveTest {
    @Test
    fun `current JustPlay embed resolves directly`() = runBlocking {
        if (System.getenv("RUN_LIVE_PROVIDER_TESTS") != "1") {
            org.junit.Assume.assumeTrue(false)
            return@runBlocking
        }

        val playerUrl = "https://justplay.cam/e/fa49irj7tucw"
        val referer = "https://tv.nontonfilm.red/ip-man-kung-fu-legend-2026/"
        val playback = withTimeout(60_000) {
            JustPlayPlayerParser.resolve(playerUrl, referer) { request ->
                val uri = URI(request.url)
                val origin = URI(uri.scheme, null, uri.host, uri.port, null, null, null)
                    .toString()
                    .trimEnd('/')
                val headers = mapOf(
                    "Accept" to "application/json",
                    "Origin" to origin,
                    "User-Agent" to JUSTPLAY_USER_AGENT
                ) +
                    request.headers +
                    if (request.method == JustPlayHttpMethod.POST) {
                        mapOf("Content-Type" to "application/json;charset=UTF-8")
                    } else {
                        emptyMap()
                    }
                val response = when (request.method) {
                    JustPlayHttpMethod.GET -> app.get(
                        request.url,
                        referer = playerUrl,
                        headers = headers,
                        timeout = 25L
                    )
                    JustPlayHttpMethod.POST -> app.post(
                        request.url,
                        requestBody = request.body.orEmpty().toRequestBody(
                            "application/json;charset=UTF-8".toMediaType()
                        ),
                        referer = playerUrl,
                        headers = headers,
                        timeout = 25L
                    )
                }
                val body = response.text
                println(
                    "JustPlay ${request.method} ${uri.path} " +
                        "code=${response.code} bytes=${body.length}"
                )
                body
            }
        }

        assertNotNull(playback, "Current JustPlay embed returned no playback envelope")
        assertTrue(playback.sources.isNotEmpty(), "Current JustPlay playback had no media source")
    }
}
