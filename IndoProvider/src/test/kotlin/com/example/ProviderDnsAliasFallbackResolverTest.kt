package com.example

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ProviderDnsAliasFallbackResolverTest {
    @Test
    fun `official alias addresses are tried before broken primary addresses`() = runBlocking {
        val lookups = mutableListOf<String>()
        val healthyAlias = address("pusatfilm.id", 104, 21, 59, 83)
        val brokenPrimary = address("v4.pusatfilm21info.com", 72, 251, 7, 22)
        val resolver = ProviderDnsAliasFallbackResolver(
            delegate = ProviderDnsResolver { host ->
                lookups += host
                when (host) {
                    "pusatfilm.id" -> listOf(healthyAlias)
                    "v4.pusatfilm21info.com" -> listOf(brokenPrimary)
                    else -> throw UnknownHostException(host)
                }
            },
            aliases = mapOf("v4.pusatfilm21info.com" to "pusatfilm.id")
        )

        val resolved = resolver.resolve("V4.PUSATFILM21INFO.COM.")

        assertContentEquals(listOf(healthyAlias, brokenPrimary), resolved)
        assertEquals(
            listOf("pusatfilm.id", "v4.pusatfilm21info.com"),
            lookups
        )
    }

    @Test
    fun `primary addresses remain usable when official alias lookup fails`() = runBlocking {
        val primary = address("v4.pusatfilm21info.com", 104, 21, 17, 52)
        val resolver = ProviderDnsAliasFallbackResolver(
            delegate = ProviderDnsResolver { host ->
                when (host) {
                    "pusatfilm.id" -> throw UnknownHostException(host)
                    "v4.pusatfilm21info.com" -> listOf(primary)
                    else -> emptyList()
                }
            },
            aliases = mapOf("v4.pusatfilm21info.com" to "pusatfilm.id")
        )

        assertContentEquals(
            listOf(primary),
            resolver.resolve("v4.pusatfilm21info.com")
        )
    }

    @Test
    fun `unrelated providers never resolve through the alias`() = runBlocking {
        val lookups = mutableListOf<String>()
        val kawanfilm = address("web.kawanfilm21.co", 104, 21, 77, 18)
        val resolver = ProviderDnsAliasFallbackResolver(
            delegate = ProviderDnsResolver { host ->
                lookups += host
                listOf(kawanfilm)
            },
            aliases = mapOf("v4.pusatfilm21info.com" to "pusatfilm.id")
        )

        assertContentEquals(
            listOf(kawanfilm),
            resolver.resolve("web.kawanfilm21.co")
        )
        assertEquals(listOf("web.kawanfilm21.co"), lookups)
    }

    private fun address(host: String, a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(
            host,
            byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
        )
}
