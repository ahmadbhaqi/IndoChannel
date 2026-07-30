package com.example

import com.lagradost.nicehttp.Requests
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Inet6Address
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody

class ProviderHttpSafetyTest {
    @Test
    fun `nicehttp adapter sends one explicit browser user agent`() = runBlocking {
        val browserUserAgent = "Mozilla/5.0 ProviderHttpSafetyTest"
        val captured = mutableListOf<okhttp3.Request>()
        val requests = Requests().apply {
            defaultHeaders = mapOf(
                "user-agent" to "NiceHttp",
                "accept" to "*/*"
            )
            baseClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    captured += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("ready".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                .build()
        }
        val response = NiceHttpProviderFetcher(requests).fetch(
            request = ProviderHttpRequest(
                method = ProviderHttpMethod.GET,
                url = "https://movie.example/headers",
                headers = mapOf(
                    "User-Agent" to browserUserAgent,
                    "Accept" to "text/html"
                )
            ),
            resolvedAddresses = listOf(publicAddress())
        )
        response.close()

        val sent = captured.single()
        assertEquals(listOf(browserUserAgent), sent.headers.values("User-Agent"))
        assertEquals(listOf("text/html"), sent.headers.values("Accept"))
        assertFalse(sent.headers.values("User-Agent").any { it.contains("NiceHttp") })
    }

    @Test
    fun `explicit headers replace inherited headers case insensitively`() {
        val merged = mergeProviderRequestHeaders(
            inherited = mapOf(
                "user-agent" to "NiceHttp",
                "accept" to "*/*",
                "X-Internal" to "must-not-leak"
            ),
            explicit = mapOf(
                "User-Agent" to "Mozilla/5.0 Browser",
                "Accept" to "text/html",
                "X-CSRF-TOKEN" to "current"
            )
        )

        assertEquals("Mozilla/5.0 Browser", merged["User-Agent"])
        assertEquals("text/html", merged["Accept"])
        assertEquals("current", merged["X-CSRF-TOKEN"])
        assertFalse(merged.containsKey("user-agent"))
        assertFalse(merged.containsKey("accept"))
        assertFalse(merged.containsKey("X-Internal"))
        assertEquals(
            1,
            merged.keys.count { it.equals("User-Agent", ignoreCase = true) }
        )
    }

    @Test
    fun `redirects are normalized resolved and fetched one hop at a time`() = runBlocking {
        val events = mutableListOf<String>()
        val first = FakeRawResponse(
            code = 302,
            url = "https://movie.example/start",
            headers = mapOf("Location" to listOf("https://cdn.example/final"))
        )
        val final = FakeRawResponse(
            code = 200,
            url = "https://cdn.example/final",
            body = "ready"
        )
        val fetcher = ScriptedFetcher(listOf(first, final), events)
        val resolver = RecordingResolver(events)
        val normalized = mutableListOf<String>()
        val client = ProviderHttpSafetyClient(fetcher, resolver, maxRedirectHops = 3)

        val result = client.get(
            url = "https://movie.example/start",
            normalizer = allowHosts("movie.example", "cdn.example", seen = normalized)
        )

        assertEquals("ready", result.body)
        assertEquals("https://cdn.example/final", result.url)
        assertEquals(
            listOf(
                "resolve:movie.example",
                "fetch:https://movie.example/start",
                "resolve:cdn.example",
                "fetch:https://cdn.example/final"
            ),
            events
        )
        assertEquals(
            listOf(
                "https://movie.example/start",
                "https://cdn.example/final",
                "https://cdn.example/final"
            ),
            normalized
        )
        assertTrue(fetcher.requests.all { !it.allowRedirects })
        assertTrue(first.closed)
        assertTrue(final.closed)
    }

    @Test
    fun `owned redirect aliases stay on the redirected host`() = runBlocking {
        val fetcher = ScriptedFetcher(
            listOf(
                FakeRawResponse(
                    code = 302,
                    url = "https://current.example/start",
                    headers = mapOf("Location" to listOf("https://old.example/final"))
                ),
                FakeRawResponse(
                    code = 200,
                    url = "https://old.example/final",
                    body = "ready"
                )
            )
        )
        val client = ProviderHttpSafetyClient(
            fetcher,
            ProviderDnsResolver { listOf(publicAddress()) }
        )

        val result = client.get(
            "https://current.example/start",
            normalizer = ProviderUrlNormalizer {
                ProviderHtmlParser.preserveProviderPageUrl(
                    it,
                    "https://current.example",
                    setOf("old.example")
                )
            }
        )

        assertEquals("ready", result.body)
        assertEquals("https://old.example/final", result.url)
        assertEquals(
            listOf(
                "https://current.example/start",
                "https://old.example/final"
            ),
            fetcher.requests.map { it.url }
        )
    }

    @Test
    fun `disallowed redirect is closed before another DNS lookup or request`() = runBlocking {
        val events = mutableListOf<String>()
        val redirect = FakeRawResponse(
            code = 302,
            url = "https://movie.example/start",
            headers = mapOf("location" to listOf("https://evil.example/steal"))
        )
        val fetcher = ScriptedFetcher(listOf(redirect), events)
        val client = ProviderHttpSafetyClient(fetcher, RecordingResolver(events))

        assertFailsWith<ProviderHttpSafetyException> {
            client.get(
                "https://movie.example/start",
                normalizer = allowHosts("movie.example")
            )
        }

        assertEquals(
            listOf("resolve:movie.example", "fetch:https://movie.example/start"),
            events
        )
        assertTrue(redirect.closed)
    }

    @Test
    fun `redirect hop cap closes the last response and prevents another request`() = runBlocking {
        val events = mutableListOf<String>()
        val responses = listOf(
            FakeRawResponse(
                302,
                "https://movie.example/start",
                mapOf("Location" to listOf("/one"))
            ),
            FakeRawResponse(
                302,
                "https://movie.example/one",
                mapOf("Location" to listOf("/two"))
            )
        )
        val fetcher = ScriptedFetcher(responses, events)
        val client = ProviderHttpSafetyClient(
            fetcher,
            RecordingResolver(events),
            maxRedirectHops = 1
        )

        assertFailsWith<ProviderHttpSafetyException> {
            client.get(
                "https://movie.example/start",
                normalizer = allowHosts("movie.example")
            )
        }

        assertEquals(2, fetcher.requests.size)
        assertTrue(responses.all { it.closed })
        assertEquals(0, responses.sumOf { it.bytesRead })
    }

    @Test
    fun `private loopback link local multicast and unique local DNS answers are rejected`() {
        val forbidden = listOf(
            InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)),
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 1, 1)),
            InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 1)),
            InetAddress.getByAddress(
                ByteArray(16).apply {
                    this[0] = 0xfd.toByte()
                    this[15] = 1
                }
            ),
            Inet6Address.getByAddress(
                null,
                ByteArray(16).apply {
                    this[10] = 0xff.toByte()
                    this[11] = 0xff.toByte()
                    this[12] = 10
                    this[15] = 1
                },
                -1
            ),
            Inet6Address.getByAddress(
                null,
                ByteArray(16).apply {
                    this[12] = 192.toByte()
                    this[13] = 168.toByte()
                    this[14] = 1
                    this[15] = 1
                },
                -1
            ),
            InetAddress.getByName("64:ff9b::127.0.0.1"),
            InetAddress.getByName("2002:7f00:0001::"),
            InetAddress.getByName("100::1"),
            InetAddress.getByName("2001:20::1")
        )

        forbidden.forEach { address ->
            val fetcher = ScriptedFetcher(emptyList())
            val resolver = ProviderDnsResolver { listOf(address) }
            val client = ProviderHttpSafetyClient(fetcher, resolver)

            assertFailsWith<ProviderHttpSafetyException>(address.hostAddress) {
                runBlocking {
                    client.get(
                        "https://movie.example/start",
                        normalizer = allowHosts("movie.example")
                    )
                }
            }
            assertTrue(fetcher.requests.isEmpty())
        }
    }

    @Test
    fun `globally routed IPv6 DNS answer remains usable`() = runBlocking {
        val response = FakeRawResponse(
            code = 200,
            url = "https://movie.example/start",
            body = "ready"
        )
        val client = ProviderHttpSafetyClient(
            ScriptedFetcher(listOf(response)),
            ProviderDnsResolver {
                listOf(InetAddress.getByName("2606:4700:4700::1111"))
            }
        )

        assertEquals(
            "ready",
            client.get(
                "https://movie.example/start",
                normalizer = allowHosts("movie.example")
            ).body
        )
    }

    @Test
    fun `mixed public and private DNS answers reject the whole connection`() {
        val fetcher = ScriptedFetcher(emptyList())
        val resolver = ProviderDnsResolver {
            listOf(
                publicAddress(),
                InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
            )
        }
        val client = ProviderHttpSafetyClient(fetcher, resolver)

        assertFailsWith<ProviderHttpSafetyException> {
            runBlocking {
                client.get(
                    "https://movie.example/start",
                    normalizer = allowHosts("movie.example")
                )
            }
        }
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test
    fun `body reader probes exactly limit plus one then closes oversized response`() = runBlocking {
        val response = FakeRawResponse(
            code = 200,
            url = "https://movie.example/large",
            body = "123456789"
        )
        val client = ProviderHttpSafetyClient(
            ScriptedFetcher(listOf(response)),
            ProviderDnsResolver { listOf(publicAddress()) }
        )

        assertFailsWith<ProviderBodyTooLargeException> {
            client.get(
                "https://movie.example/large",
                normalizer = allowHosts("movie.example"),
                maxBodyBytes = 5
            )
        }

        assertEquals(6, response.bytesRead)
        assertTrue(response.closed)
    }

    @Test
    fun `prefix fetch returns bounded raw bytes when a media response is larger`() = runBlocking {
        val response = FakeRawResponse(
            code = 200,
            url = "https://media.example/video",
            body = "123456789"
        )
        val client = ProviderHttpSafetyClient(
            ScriptedFetcher(listOf(response)),
            ProviderDnsResolver { listOf(publicAddress()) }
        )

        val result = client.getPrefix(
            "https://media.example/video",
            normalizer = allowHosts("media.example"),
            maxBodyBytes = 5
        )

        assertEquals("12345", result.body)
        assertTrue(result.bodyTruncated)
        assertEquals(6, response.bytesRead)
        assertTrue(response.closed)
    }

    @Test
    fun `form post can return a redirect without following it`() = runBlocking {
        val response = FakeRawResponse(
            code = 302,
            url = "https://movie.example/ajax",
            headers = mapOf("Location" to listOf("/player"))
        )
        val fetcher = ScriptedFetcher(listOf(response))
        val client = ProviderHttpSafetyClient(
            fetcher,
            ProviderDnsResolver { listOf(publicAddress()) }
        )

        val result = client.postForm(
            url = "https://movie.example/ajax",
            form = mapOf("action" to "player", "id" to "42"),
            normalizer = allowHosts("movie.example"),
            followRedirects = false
        )

        assertEquals(302, result.code)
        assertEquals("/player", result.header("location"))
        assertEquals(1, fetcher.requests.size)
        assertEquals(ProviderHttpMethod.POST, fetcher.requests.single().method)
        assertEquals(
            mapOf("action" to "player", "id" to "42"),
            assertIs<ProviderHttpBody.Form>(fetcher.requests.single().body).values
        )
        assertFalse(fetcher.requests.single().allowRedirects)
        assertTrue(response.closed)
    }

    @Test
    fun `temporary redirect preserves form post while found converts it to get`() = runBlocking {
        suspend fun redirectedRequest(status: Int): ProviderHttpRequest {
            val fetcher = ScriptedFetcher(
                listOf(
                    FakeRawResponse(
                        status,
                        "https://movie.example/ajax",
                        mapOf("Location" to listOf("/player"))
                    ),
                    FakeRawResponse(200, "https://movie.example/player")
                )
            )
            ProviderHttpSafetyClient(
                fetcher,
                ProviderDnsResolver { listOf(publicAddress()) }
            ).postForm(
                "https://movie.example/ajax",
                mapOf("id" to "42"),
                allowHosts("movie.example")
            )
            return fetcher.requests.last()
        }

        val temporary = redirectedRequest(307)
        assertEquals(ProviderHttpMethod.POST, temporary.method)
        assertIs<ProviderHttpBody.Form>(temporary.body)

        val found = redirectedRequest(302)
        assertEquals(ProviderHttpMethod.GET, found.method)
        assertEquals(null, found.body)
    }

    @Test
    fun `cross origin redirect retains only explicitly safe request headers`() = runBlocking {
        val fetcher = ScriptedFetcher(
            listOf(
                FakeRawResponse(
                    302,
                    "https://movie.example/start",
                    mapOf("Location" to listOf("https://cdn.example/final"))
                ),
                FakeRawResponse(200, "https://cdn.example/final")
            )
        )
        val client = ProviderHttpSafetyClient(
            fetcher,
            ProviderDnsResolver { listOf(publicAddress()) }
        )

        client.get(
            url = "https://movie.example/start",
            normalizer = allowHosts("movie.example", "cdn.example"),
            headers = mapOf(
                "Accept" to "text/html",
                "Accept-Language" to "id",
                "User-Agent" to "Cloudstream",
                "Range" to "bytes=0-99",
                "Authorization" to "Bearer secret",
                "X-Fuck-ID" to "bootstrap-secret",
                "X-REF-ID" to "ref-secret",
                "X-REQUEST-ID" to "request-secret"
            ),
            referer = "https://movie.example/watch?token=secret",
            cookies = mapOf("session" to "secret")
        )

        val redirected = fetcher.requests.last()
        assertEquals(
            mapOf(
                "Accept" to "text/html",
                "Accept-Language" to "id",
                "User-Agent" to "Cloudstream",
                "Range" to "bytes=0-99"
            ),
            redirected.headers
        )
        assertEquals("https://movie.example/", redirected.referer)
        assertTrue(redirected.cookies.isEmpty())
    }

    @Test
    fun `terminal response exposes exact raw body bytes and decoded text`() = runBlocking {
        val raw = byteArrayOf(0x00, 0x7f, 0xff.toByte(), 0x41)
        val response = FakeRawResponse(
            code = 200,
            url = "https://media.example/probe",
            rawBody = raw
        )
        val client = ProviderHttpSafetyClient(
            ScriptedFetcher(listOf(response)),
            ProviderDnsResolver { listOf(publicAddress()) }
        )

        val result = client.get(
            "https://media.example/probe",
            normalizer = allowHosts("media.example")
        )

        assertContentEquals(raw, result.bodyBytes)
        assertEquals(raw.toString(Charsets.UTF_8), result.body)
    }

    @Test
    fun `player fetch adapters reject private DNS before page or API requests`() = runBlocking {
        val fetcher = ScriptedFetcher(emptyList())
        val client = ProviderHttpSafetyClient(
            fetcher,
            ProviderDnsResolver { host ->
                listOf(
                    InetAddress.getByAddress(
                        host,
                        byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())
                    )
                )
            }
        )
        val pageFetcher = safePlayerPageFetcher(client)
        val apiFetcher = safePlayerApiFetcher(client)
        val rebindingHost = "https://169.254.169.254.nip.io/private"

        assertFailsWith<ProviderHttpSafetyException> {
            pageFetcher(rebindingHost, "https://provider.example/watch")
        }
        assertFailsWith<ProviderHttpSafetyException> {
            apiFetcher(
                rebindingHost,
                "https://provider.example/watch",
                mapOf("Accept" to "application/json")
            )
        }
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test
    fun `player page adapter rejects an oversized response while streaming`() = runBlocking {
        val response = FakeRawResponse(
            code = 200,
            url = "https://player.example/embed/current",
            rawBody = ByteArray(PLAYER_PAGE_BODY_LIMIT_BYTES + 1)
        )
        val client = ProviderHttpSafetyClient(
            ScriptedFetcher(listOf(response)),
            ProviderDnsResolver { listOf(publicAddress()) }
        )

        assertFailsWith<ProviderBodyTooLargeException> {
            safePlayerPageFetcher(client)(
                "https://player.example/embed/current",
                "https://provider.example/watch"
            )
        }
        assertEquals(PLAYER_PAGE_BODY_LIMIT_BYTES + 1, response.bytesRead)
        assertTrue(response.closed)
    }

    @Test
    fun `raw request body is forwarded unchanged for Moviebox JSON posts`() = runBlocking {
        val response = FakeRawResponse(200, "https://api.example/search", body = "{}")
        val fetcher = ScriptedFetcher(listOf(response))
        val client = ProviderHttpSafetyClient(
            fetcher,
            ProviderDnsResolver { listOf(publicAddress()) }
        )
        val requestBody = "{}".toRequestBody("application/json".toMediaType())

        client.postBody(
            url = "https://api.example/search",
            requestBody = requestBody,
            normalizer = allowHosts("api.example")
        )

        assertSame(
            requestBody,
            assertIs<ProviderHttpBody.Raw>(fetcher.requests.single().body).value
        )
    }

    private fun allowHosts(
        vararg allowed: String,
        seen: MutableList<String>? = null
    ): ProviderUrlNormalizer = ProviderUrlNormalizer { candidate ->
        seen?.add(candidate)
        candidate.takeIf {
            runCatching { URI(it).host?.lowercase() in allowed }.getOrDefault(false)
        }
    }

    private fun publicAddress(): InetAddress =
        InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
}

private class RecordingResolver(
    private val events: MutableList<String>
) : ProviderDnsResolver {
    override suspend fun resolve(host: String): List<InetAddress> {
        events += "resolve:$host"
        return listOf(
            InetAddress.getByAddress(
                host,
                byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
            )
        )
    }
}

private class ScriptedFetcher(
    responses: List<FakeRawResponse>,
    private val events: MutableList<String> = mutableListOf()
) : ProviderHttpFetcher {
    private val remaining = ArrayDeque(responses)
    val requests = mutableListOf<ProviderHttpRequest>()

    override suspend fun fetch(
        request: ProviderHttpRequest,
        resolvedAddresses: List<InetAddress>
    ): ProviderHttpRawResponse {
        requests += request
        events += "fetch:${request.url}"
        assertTrue(resolvedAddresses.isNotEmpty())
        return remaining.removeFirst()
    }
}

private class FakeRawResponse(
    override val code: Int,
    override val url: String,
    override val headers: Map<String, List<String>> = emptyMap(),
    body: String = "",
    rawBody: ByteArray? = null
) : ProviderHttpRawResponse {
    private val bytes = rawBody ?: body.toByteArray(Charsets.UTF_8)
    var bytesRead: Int = 0
        private set
    var closed: Boolean = false
        private set

    override val charset = Charsets.UTF_8

    override fun bodyStream(): InputStream {
        return object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(): Int {
                val value = super.read()
                if (value >= 0) bytesRead++
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) bytesRead += count
                return count
            }
        }
    }

    override fun close() {
        closed = true
    }
}
