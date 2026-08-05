package com.example

import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Locale
import kotlinx.coroutines.CancellationException
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody

internal const val PROVIDER_HTTP_DEFAULT_BODY_LIMIT_BYTES = 2_000_000

internal enum class ProviderHttpMethod {
    GET,
    HEAD,
    POST
}

internal sealed class ProviderHttpBody {
    data class Form(val values: Map<String, String>) : ProviderHttpBody()
    data class Raw(val value: RequestBody) : ProviderHttpBody()
}

/**
 * A single network request. Redirects are never delegated to NiceHTTP: the
 * safety client issues each hop independently after URL and DNS validation.
 */
internal data class ProviderHttpRequest(
    val method: ProviderHttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val cookies: Map<String, String> = emptyMap(),
    val body: ProviderHttpBody? = null,
    val timeoutSeconds: Long = PROVIDER_HTTP_TIMEOUT_SECONDS
) {
    val allowRedirects: Boolean = false
}

/**
 * The fetcher must connect only to [resolvedAddresses]. The NiceHTTP adapter
 * below enforces that contract with a per-request pinned OkHttp DNS instance.
 */
internal fun interface ProviderHttpFetcher {
    suspend fun fetch(
        request: ProviderHttpRequest,
        resolvedAddresses: List<InetAddress>
    ): ProviderHttpRawResponse
}

internal fun interface ProviderDnsResolver {
    suspend fun resolve(host: String): List<InetAddress>
}

/**
 * Resolves an allowlisted host through an official DNS alias first, while
 * retaining the host's own answers as a fallback. The request URL, Host
 * header, and TLS SNI are never rewritten; only the pinned connection
 * addresses change after the safety client validates that they are public.
 */
internal class ProviderDnsAliasFallbackResolver(
    private val delegate: ProviderDnsResolver,
    aliases: Map<String, String>
) : ProviderDnsResolver {
    private val aliasesByHost = aliases.entries.associate { (host, alias) ->
        requireNotNull(canonicalDnsHost(host)) { "Invalid DNS fallback host" } to
            requireNotNull(canonicalDnsHost(alias)) { "Invalid DNS fallback alias" }
    }

    override suspend fun resolve(host: String): List<InetAddress> {
        val normalizedHost = canonicalDnsHost(host) ?: return delegate.resolve(host)
        val alias = aliasesByHost[normalizedHost]
            ?: return delegate.resolve(normalizedHost)
        val aliasAddresses = resolveOrEmpty(alias)
        val primaryAddresses = resolveOrEmpty(normalizedHost)
        val combined = (aliasAddresses + primaryAddresses).distinctBy { address ->
            address.address.joinToString(separator = ".") { octet ->
                (octet.toInt() and 0xff).toString()
            }
        }
        if (combined.isEmpty()) {
            throw UnknownHostException(
                "DNS lookup returned no addresses for $normalizedHost or its official alias"
            )
        }
        return combined
    }

    private suspend fun resolveOrEmpty(host: String): List<InetAddress> = try {
        delegate.resolve(host)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun interface ProviderUrlNormalizer {
    fun normalize(candidate: String): String?
}

/**
 * Minimal response surface used by the safety client. Implementations retain
 * ownership of their network response until [close] is called.
 */
internal interface ProviderHttpRawResponse : Closeable {
    val code: Int
    val url: String
    val headers: Map<String, List<String>>
    val charset: Charset
    fun bodyStream(): InputStream
}

internal class ProviderHttpResult(
    val code: Int,
    val url: String,
    val headers: Map<String, List<String>>,
    val bodyBytes: ByteArray,
    val charset: Charset,
    val bodyTruncated: Boolean = false
) {
    val body: String by lazy(LazyThreadSafetyMode.NONE) {
        bodyBytes.toString(charset)
    }

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

internal open class ProviderHttpSafetyException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal class ProviderBodyTooLargeException(
    val limitBytes: Int
) : ProviderHttpSafetyException("HTTP response body exceeds $limitBytes bytes")

/**
 * Strict provider HTTP client:
 * - caller allowlists/normalizes the initial URL and every redirect;
 * - DNS is resolved and checked immediately before every fetch;
 * - every fetch receives pinned, already-checked addresses;
 * - redirects and terminal responses are always closed;
 * - terminal bodies are read through a limit+1 probe.
 */
internal class ProviderHttpSafetyClient(
    private val fetcher: ProviderHttpFetcher,
    private val resolver: ProviderDnsResolver = SystemProviderDnsResolver,
    private val maxRedirectHops: Int = 5,
    private val defaultMaxBodyBytes: Int = PROVIDER_HTTP_DEFAULT_BODY_LIMIT_BYTES
) {
    private val sessionCookies = ProviderSessionCookieStore()

    init {
        require(maxRedirectHops in 0..MAX_REDIRECT_HOPS)
        require(defaultMaxBodyBytes in 0..MAX_BODY_LIMIT_BYTES)
    }

    suspend fun get(
        url: String,
        normalizer: ProviderUrlNormalizer,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        maxBodyBytes: Int = defaultMaxBodyBytes,
        timeoutSeconds: Long = PROVIDER_HTTP_TIMEOUT_SECONDS
    ): ProviderHttpResult = execute(
        initialRequest = ProviderHttpRequest(
            method = ProviderHttpMethod.GET,
            url = url,
            headers = headers,
            referer = referer,
            cookies = cookies,
            timeoutSeconds = timeoutSeconds
        ),
        normalizer = normalizer,
        followRedirects = followRedirects,
        maxBodyBytes = maxBodyBytes,
        truncateOversizedBody = false
    )

    suspend fun getPrefix(
        url: String,
        normalizer: ProviderUrlNormalizer,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        maxBodyBytes: Int,
        timeoutSeconds: Long = PROVIDER_HTTP_TIMEOUT_SECONDS
    ): ProviderHttpResult = execute(
        initialRequest = ProviderHttpRequest(
            method = ProviderHttpMethod.GET,
            url = url,
            headers = headers,
            referer = referer,
            cookies = cookies,
            timeoutSeconds = timeoutSeconds
        ),
        normalizer = normalizer,
        followRedirects = followRedirects,
        maxBodyBytes = maxBodyBytes,
        truncateOversizedBody = true
    )

    suspend fun head(
        url: String,
        normalizer: ProviderUrlNormalizer,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        timeoutSeconds: Long = PROVIDER_HTTP_TIMEOUT_SECONDS
    ): ProviderHttpResult = execute(
        initialRequest = ProviderHttpRequest(
            method = ProviderHttpMethod.HEAD,
            url = url,
            headers = headers,
            referer = referer,
            cookies = cookies,
            timeoutSeconds = timeoutSeconds
        ),
        normalizer = normalizer,
        followRedirects = followRedirects,
        maxBodyBytes = 0,
        truncateOversizedBody = true,
        readBody = false
    )

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        normalizer: ProviderUrlNormalizer,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        maxBodyBytes: Int = defaultMaxBodyBytes,
        timeoutSeconds: Long = PROVIDER_HTTP_TIMEOUT_SECONDS
    ): ProviderHttpResult = execute(
        initialRequest = ProviderHttpRequest(
            method = ProviderHttpMethod.POST,
            url = url,
            headers = headers,
            referer = referer,
            cookies = cookies,
            body = ProviderHttpBody.Form(form),
            timeoutSeconds = timeoutSeconds
        ),
        normalizer = normalizer,
        followRedirects = followRedirects,
        maxBodyBytes = maxBodyBytes,
        truncateOversizedBody = false
    )

    suspend fun postBody(
        url: String,
        requestBody: RequestBody,
        normalizer: ProviderUrlNormalizer,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        maxBodyBytes: Int = defaultMaxBodyBytes,
        timeoutSeconds: Long = PROVIDER_HTTP_TIMEOUT_SECONDS
    ): ProviderHttpResult = execute(
        initialRequest = ProviderHttpRequest(
            method = ProviderHttpMethod.POST,
            url = url,
            headers = headers,
            referer = referer,
            cookies = cookies,
            body = ProviderHttpBody.Raw(requestBody),
            timeoutSeconds = timeoutSeconds
        ),
        normalizer = normalizer,
        followRedirects = followRedirects,
        maxBodyBytes = maxBodyBytes,
        truncateOversizedBody = false
    )

    private suspend fun execute(
        initialRequest: ProviderHttpRequest,
        normalizer: ProviderUrlNormalizer,
        followRedirects: Boolean,
        maxBodyBytes: Int,
        truncateOversizedBody: Boolean,
        readBody: Boolean = true
    ): ProviderHttpResult {
        require(maxBodyBytes in 0..MAX_BODY_LIMIT_BYTES)
        require(initialRequest.timeoutSeconds > 0L)

        var request = initialRequest.copy(
            url = normalizeUrl(initialRequest.url, normalizer).url
        )
        var redirectsFollowed = 0

        while (true) {
            val destination = parseNormalizedUrl(request.url)
            val addresses = resolvePublicAddresses(destination.host)
            val requestWithSession = request.copy(
                cookies = sessionCookies.cookiesFor(request.url).toMutableMap().apply {
                    putAll(request.cookies)
                }
            )
            val response = fetcher.fetch(requestWithSession, addresses)

            response.use { currentResponse ->
                sessionCookies.saveFromResponse(
                    requestUrl = requestWithSession.url,
                    headers = currentResponse.headers
                )
                if (followRedirects && currentResponse.code in REDIRECT_CODES) {
                    if (redirectsFollowed >= maxRedirectHops) {
                        throw ProviderHttpSafetyException(
                            "HTTP redirect limit of $maxRedirectHops exceeded"
                        )
                    }
                    val location = currentResponse.header("Location")
                        ?: throw ProviderHttpSafetyException(
                            "HTTP ${currentResponse.code} response has no Location header"
                        )
                    val redirectCandidate = resolveRedirect(request.url, location)
                    val redirect = normalizeUrl(redirectCandidate, normalizer)
                    request = redirectRequest(request, redirect.url, currentResponse.code)
                    redirectsFollowed++
                    return@use
                }

                val responseUrl = normalizeUrl(currentResponse.url, normalizer).url
                val boundedBody = if (readBody) {
                    readBoundedBody(
                        currentResponse,
                        maxBodyBytes,
                        truncateOversizedBody
                    )
                } else {
                    BoundedProviderBody(ByteArray(0), truncated = false)
                }
                return ProviderHttpResult(
                    code = currentResponse.code,
                    url = responseUrl,
                    headers = currentResponse.headers,
                    bodyBytes = boundedBody.bytes,
                    charset = currentResponse.charset,
                    bodyTruncated = boundedBody.truncated
                )
            }
        }
    }

    private suspend fun resolvePublicAddresses(host: String): List<InetAddress> {
        val addresses = try {
            resolver.resolve(host)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProviderHttpSafetyException) {
            throw error
        } catch (error: Exception) {
            throw ProviderHttpSafetyException("DNS lookup failed for $host", error)
        }.toList()

        if (addresses.isEmpty()) {
            throw ProviderHttpSafetyException("DNS lookup returned no addresses for $host")
        }
        val rejected = addresses.firstOrNull { !isPublicProviderAddress(it) }
        if (rejected != null) {
            throw ProviderHttpSafetyException(
                "DNS lookup for $host returned a non-public address"
            )
        }
        return addresses
    }

    private fun normalizeUrl(
        candidate: String,
        normalizer: ProviderUrlNormalizer
    ): NormalizedProviderUrl {
        val bounded = candidate.trim().takeIf {
            it.isNotEmpty() &&
                it.length <= MAX_URL_LENGTH &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
        } ?: throw ProviderHttpSafetyException("Unsafe or oversized HTTP URL")
        val normalized = try {
            normalizer.normalize(bounded)
        } catch (error: Exception) {
            throw ProviderHttpSafetyException("Provider URL normalizer failed", error)
        } ?: throw ProviderHttpSafetyException("HTTP URL is outside the provider allowlist")
        return parseNormalizedUrl(normalized)
    }

    private fun parseNormalizedUrl(value: String): NormalizedProviderUrl {
        val bounded = value.trim().takeIf {
            it.isNotEmpty() &&
                it.length <= MAX_URL_LENGTH &&
                it.none { character -> character.code < 0x20 || character.code == 0x7f }
        } ?: throw ProviderHttpSafetyException("Unsafe or oversized normalized HTTP URL")
        val uri = try {
            URI(bounded)
        } catch (error: Exception) {
            throw ProviderHttpSafetyException("Malformed normalized HTTP URL", error)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in HTTP_SCHEMES || uri.userInfo != null || uri.rawFragment != null) {
            throw ProviderHttpSafetyException("Normalized URL is not a safe HTTP URL")
        }
        val host = canonicalDnsHost(uri.host)
            ?: throw ProviderHttpSafetyException("Normalized URL has no valid host")
        if (host == "localhost" || host.endsWith(".localhost")) {
            throw ProviderHttpSafetyException("Localhost destinations are forbidden")
        }
        if (uri.port == 0 || uri.port > 65_535) {
            throw ProviderHttpSafetyException("Normalized URL has an invalid port")
        }
        return NormalizedProviderUrl(uri.toASCIIString(), host)
    }

    private fun resolveRedirect(responseUrl: String, location: String): String {
        val bounded = location.trim().takeIf {
            it.isNotEmpty() &&
                it.length <= MAX_URL_LENGTH &&
                it.none { character ->
                    character.code < 0x20 ||
                        character.code == 0x7f ||
                        character.isWhitespace()
                }
        } ?: throw ProviderHttpSafetyException("Unsafe or oversized redirect Location")
        return try {
            URI(responseUrl).resolve(bounded).toASCIIString()
        } catch (error: Exception) {
            throw ProviderHttpSafetyException("Malformed redirect Location", error)
        }
    }

    private fun redirectRequest(
        previous: ProviderHttpRequest,
        url: String,
        status: Int
    ): ProviderHttpRequest {
        val preserveBody = status == 307 || status == 308
        val nextMethod = if (previous.method == ProviderHttpMethod.POST && !preserveBody) {
            ProviderHttpMethod.GET
        } else {
            previous.method
        }
        val sameOrigin = providerOrigin(previous.url) == providerOrigin(url)
        return previous.copy(
            method = nextMethod,
            url = url,
            headers = if (sameOrigin) {
                previous.headers
            } else {
                previous.headers.filterKeys(String::isSafeCrossOriginHeader)
            },
            referer = if (sameOrigin) {
                previous.referer
            } else {
                previous.referer?.toOriginOnlyReferer()
            },
            cookies = if (sameOrigin) previous.cookies else emptyMap(),
            body = previous.body.takeIf { nextMethod == ProviderHttpMethod.POST }
        )
    }

    private fun readBoundedBody(
        response: ProviderHttpRawResponse,
        maxBodyBytes: Int,
        truncateOversizedBody: Boolean
    ): BoundedProviderBody {
        val probeLimit = maxBodyBytes + 1
        val bytes = response.bodyStream().use { input ->
            val output = ByteArrayOutputStream(
                maxBodyBytes.coerceAtMost(DEFAULT_BUFFER_SIZE)
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (total < probeLimit) {
                val count = input.read(
                    buffer,
                    0,
                    minOf(buffer.size, probeLimit - total)
                )
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
            if (total > maxBodyBytes && !truncateOversizedBody) {
                throw ProviderBodyTooLargeException(maxBodyBytes)
            }
            output.toByteArray()
        }
        return BoundedProviderBody(
            bytes = if (bytes.size > maxBodyBytes) bytes.copyOf(maxBodyBytes) else bytes,
            truncated = bytes.size > maxBodyBytes
        )
    }

    private companion object {
        const val MAX_REDIRECT_HOPS = 10
        const val MAX_BODY_LIMIT_BYTES = 8_000_000
        const val MAX_URL_LENGTH = 8_192
        val HTTP_SCHEMES = setOf("http", "https")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * Production adapter for NiceHTTP 0.4.11. A new client view is created for
 * every hop with redirect handling disabled and DNS pinned to the addresses
 * checked by [ProviderHttpSafetyClient]. The shared Requests/baseClient object
 * is never mutated.
 */
internal class NiceHttpProviderFetcher(
    private val requests: Requests
) : ProviderHttpFetcher {
    override suspend fun fetch(
        request: ProviderHttpRequest,
        resolvedAddresses: List<InetAddress>
    ): ProviderHttpRawResponse {
        val host = canonicalDnsHost(URI(request.url).host)
            ?: throw ProviderHttpSafetyException("Request URL has no valid host")
        if (resolvedAddresses.isEmpty() ||
            resolvedAddresses.any { !isPublicProviderAddress(it) }
        ) {
            throw ProviderHttpSafetyException("Fetcher received unsafe DNS addresses")
        }
        val pinnedDns = ProviderPinnedDns(host, resolvedAddresses.toList())
        val isolatedPool = ConnectionPool()
        val pinnedClient = requests.baseClient.newBuilder()
            .dns(pinnedDns)
            .connectionPool(isolatedPool)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val pinnedRequests = Requests().apply {
            baseClient = pinnedClient
            defaultHeaders = emptyMap()
        }
        val combinedHeaders = mergeProviderRequestHeaders(
            requests.defaultHeaders,
            request.headers
        )
        val explicitHeaderReferer = combinedHeaders.entries
            .lastOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
        val outgoingHeaders = combinedHeaders.filterKeys {
            !it.equals("Referer", ignoreCase = true)
        }
        val outgoingReferer = request.referer ?: explicitHeaderReferer
        val response = try {
            when (val body = request.body) {
                null -> {
                    when (request.method) {
                        ProviderHttpMethod.GET -> pinnedRequests.get(
                            request.url,
                            headers = outgoingHeaders,
                            referer = outgoingReferer,
                            cookies = request.cookies,
                            allowRedirects = false,
                            cacheTime = requests.defaultCacheTime,
                            cacheUnit = requests.defaultCacheTimeUnit,
                            timeout = request.timeoutSeconds
                        )

                        ProviderHttpMethod.HEAD -> pinnedRequests.head(
                            request.url,
                            headers = outgoingHeaders,
                            referer = outgoingReferer,
                            cookies = request.cookies,
                            allowRedirects = false,
                            cacheTime = requests.defaultCacheTime,
                            cacheUnit = requests.defaultCacheTimeUnit,
                            timeout = request.timeoutSeconds
                        )

                        ProviderHttpMethod.POST ->
                            throw ProviderHttpSafetyException("POST request has no body")
                    }
                }

                is ProviderHttpBody.Form -> {
                    if (request.method != ProviderHttpMethod.POST) {
                        throw ProviderHttpSafetyException("GET request cannot have a form body")
                    }
                    pinnedRequests.post(
                        request.url,
                        headers = outgoingHeaders,
                        referer = outgoingReferer,
                        cookies = request.cookies,
                        data = body.values,
                        allowRedirects = false,
                        cacheTime = requests.defaultCacheTime,
                        cacheUnit = requests.defaultCacheTimeUnit,
                        timeout = request.timeoutSeconds
                    )
                }

                is ProviderHttpBody.Raw -> {
                    if (request.method != ProviderHttpMethod.POST) {
                        throw ProviderHttpSafetyException("GET request cannot have a raw body")
                    }
                    pinnedRequests.post(
                        request.url,
                        headers = outgoingHeaders,
                        referer = outgoingReferer,
                        cookies = request.cookies,
                        requestBody = body.value,
                        allowRedirects = false,
                        cacheTime = requests.defaultCacheTime,
                        cacheUnit = requests.defaultCacheTimeUnit,
                        timeout = request.timeoutSeconds
                    )
                }
            }
        } catch (error: Exception) {
            isolatedPool.evictAll()
            throw error
        }
        return NiceHttpRawResponse(response, isolatedPool)
    }
}

internal object SystemProviderDnsResolver : ProviderDnsResolver {
    override suspend fun resolve(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList()
}

private class ProviderPinnedDns(
    private val expectedHost: String,
    private val addresses: List<InetAddress>
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (canonicalDnsHost(hostname) != expectedHost) {
            throw UnknownHostException("Unexpected DNS lookup while using a pinned provider client")
        }
        return addresses
    }
}

private class NiceHttpRawResponse(
    private val response: NiceResponse,
    private val connectionPool: ConnectionPool
) : ProviderHttpRawResponse {
    override val code: Int = response.code
    override val url: String = response.url
    override val headers: Map<String, List<String>> = response.headers.toMultimap()
    override val charset: Charset =
        runCatching { response.body.contentType()?.charset(Charsets.UTF_8) }
            .getOrNull()
            ?: Charsets.UTF_8

    override fun bodyStream(): InputStream = response.body.byteStream()

    override fun close() {
        try {
            response.okhttpResponse.close()
        } finally {
            connectionPool.evictAll()
        }
    }
}

private data class NormalizedProviderUrl(
    val url: String,
    val host: String
)

private data class BoundedProviderBody(
    val bytes: ByteArray,
    val truncated: Boolean
)

private data class ProviderOrigin(
    val scheme: String,
    val host: String,
    val port: Int
)

/**
 * Small per-client cookie jar for provider handshakes. OkHttp performs the
 * domain, host-only, path, Secure, and expiry checks; this class only bounds
 * storage and adapts matching cookies to NiceHTTP's map-shaped API.
 */
private class ProviderSessionCookieStore {
    private val lock = Any()
    private val cookies = linkedMapOf<ProviderCookieKey, Cookie>()

    fun saveFromResponse(
        requestUrl: String,
        headers: Map<String, List<String>>
    ) {
        val url = requestUrl.toHttpUrlOrNull() ?: return
        val parsed = headers.entries
            .asSequence()
            .filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { (_, values) -> values.asSequence() }
            .take(MAX_SET_COOKIE_VALUES_PER_RESPONSE)
            .filter { it.length <= MAX_SET_COOKIE_HEADER_LENGTH }
            .mapNotNull { value -> runCatching { Cookie.parse(url, value) }.getOrNull() }
            .toList()
        if (parsed.isEmpty()) return

        val now = System.currentTimeMillis()
        synchronized(lock) {
            removeExpired(now)
            parsed.forEach { cookie ->
                val key = ProviderCookieKey(
                    name = cookie.name,
                    domain = cookie.domain,
                    path = cookie.path
                )
                cookies.remove(key)
                if (cookie.expiresAt > now) {
                    cookies[key] = cookie
                }
            }
            while (cookies.size > MAX_STORED_COOKIES) {
                val oldest = cookies.entries.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
    }

    fun cookiesFor(requestUrl: String): Map<String, String> {
        val url = requestUrl.toHttpUrlOrNull() ?: return emptyMap()
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            removeExpired(now)
            val mostSpecificByName = linkedMapOf<String, Cookie>()
            cookies.values.forEach { cookie ->
                if (!cookie.matches(url)) return@forEach
                val existing = mostSpecificByName[cookie.name]
                if (existing == null || cookie.path.length > existing.path.length) {
                    mostSpecificByName[cookie.name] = cookie
                }
            }
            mostSpecificByName.mapValuesTo(linkedMapOf()) { (_, cookie) -> cookie.value }
        }
    }

    private fun removeExpired(now: Long) {
        val iterator = cookies.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAt <= now) {
                iterator.remove()
            }
        }
    }

    private companion object {
        const val MAX_STORED_COOKIES = 128
        const val MAX_SET_COOKIE_VALUES_PER_RESPONSE = 32
        const val MAX_SET_COOKIE_HEADER_LENGTH = 4_096
    }
}

private data class ProviderCookieKey(
    val name: String,
    val domain: String,
    val path: String
)

private fun ProviderHttpRawResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

private fun providerOrigin(url: String): ProviderOrigin? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    val host = canonicalDnsHost(uri.host) ?: return null
    val port = when {
        uri.port >= 0 -> uri.port
        scheme == "http" -> 80
        scheme == "https" -> 443
        else -> return null
    }
    return ProviderOrigin(scheme, host, port)
}

private fun String.toOriginOnlyReferer(): String? {
    val uri = runCatching { URI(this) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
        ?.takeIf { it == "http" || it == "https" }
        ?: return null
    if (uri.userInfo != null) return null
    val host = canonicalDnsHost(uri.host) ?: return null
    val port = uri.port
    if (port == 0 || port > 65_535) return null
    val explicitPort = port.takeIf {
        it >= 0 && !(
            (scheme == "http" && it == 80) ||
                (scheme == "https" && it == 443)
            )
    }
    val authorityHost = if (host.contains(':')) "[$host]" else host
    return buildString {
        append(scheme)
        append("://")
        append(authorityHost)
        explicitPort?.let { append(':').append(it) }
        append('/')
    }
}

private fun inheritedSafeHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filterKeys(String::isSafeCrossOriginHeader)

internal fun mergeProviderRequestHeaders(
    inherited: Map<String, String>,
    explicit: Map<String, String>
): Map<String, String> = linkedMapOf<String, String>().apply {
    putAll(inheritedSafeHeaders(inherited))
    explicit.forEach { (name, value) ->
        keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
        put(name, value)
    }
}

private fun String.isSafeCrossOriginHeader(): Boolean =
    equals("Accept", ignoreCase = true) ||
        equals("Accept-Language", ignoreCase = true) ||
        equals("User-Agent", ignoreCase = true) ||
        equals("Range", ignoreCase = true)

private fun canonicalDnsHost(raw: String?): String? {
    val host = raw
        ?.trim()
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.trimEnd('.')
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return if (host.contains(':')) {
        host.lowercase(Locale.ROOT)
    } else {
        runCatching { IDN.toASCII(host).lowercase(Locale.ROOT) }.getOrNull()
    }
}

private fun isPublicProviderAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) return false

    return when (address) {
        is Inet4Address -> isPublicProviderIpv4(address.address)
        is Inet6Address -> isPublicProviderIpv6(address.address)

        else -> false
    }
}

private fun isPublicProviderIpv6(bytes: ByteArray): Boolean {
    if (bytes.size != 16) return false
    embeddedIpv4Address(bytes)?.let { return isPublicProviderIpv4(it) }

    val octets = bytes.map { it.toInt() and 0xff }
    val globalUnicast = (octets[0] and 0xe0) == 0x20
    if (!globalUnicast) return false

    val teredo = octets[0] == 0x20 &&
        octets[1] == 0x01 &&
        octets[2] == 0x00 &&
        octets[3] == 0x00
    val benchmarking = octets.take(6) == listOf(0x20, 0x01, 0x00, 0x02, 0x00, 0x00)
    val orchid = octets[0] == 0x20 &&
        octets[1] == 0x01 &&
        octets[2] == 0x00 &&
        (octets[3] and 0xf0) in setOf(0x10, 0x20)
    val documentation = octets.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)
    val sixToFour = octets[0] == 0x20 && octets[1] == 0x02
    val retiredSixBone = octets[0] == 0x3f && octets[1] == 0xfe
    val documentationV2 = octets[0] == 0x3f &&
        octets[1] == 0xff &&
        (octets[2] and 0xf0) == 0
    return !teredo &&
        !benchmarking &&
        !orchid &&
        !documentation &&
        !sixToFour &&
        !retiredSixBone &&
        !documentationV2
}

private fun embeddedIpv4Address(bytes: ByteArray): ByteArray? {
    if (bytes.size != 16) return null
    val mapped =
        bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() &&
            bytes[11] == 0xff.toByte()
    val compatible = bytes.take(12).all { it == 0.toByte() }
    return bytes.copyOfRange(12, 16).takeIf { mapped || compatible }
}

private fun isPublicProviderIpv4(bytes: ByteArray): Boolean {
    if (bytes.size != 4) return false
    val a = bytes[0].toInt() and 0xff
    val b = bytes[1].toInt() and 0xff
    val c = bytes[2].toInt() and 0xff
    return when {
        a == 0 || a == 10 || a == 127 || a >= 224 -> false
        a == 100 && b in 64..127 -> false
        a == 169 && b == 254 -> false
        a == 172 && b in 16..31 -> false
        a == 192 && b == 168 -> false
        a == 192 && b == 0 && c in setOf(0, 2) -> false
        a == 192 && b == 88 && c == 99 -> false
        a == 198 && b in 18..19 -> false
        a == 198 && b == 51 && c == 100 -> false
        a == 203 && b == 0 && c == 113 -> false
        else -> true
    }
}
