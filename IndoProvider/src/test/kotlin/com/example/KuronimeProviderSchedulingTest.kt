package com.example

import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KuronimeProviderSchedulingTest {
    private val kuro = "https://cdn.kuroplayer.xyz/720p/master.m3u8"
    private val mirror = "https://www.mp4upload.com/embed-mirror.html"

    @Test
    fun `failed Kuroplayer probe does not suppress a healthy source API mirror`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = KuronimeSourceScheduler.resolve(
            candidates = listOf(kuro, mirror),
            resolveKuroplayer = { candidate ->
                attempts += "kuro:$candidate"
                false
            },
            resolveGeneric = { candidate ->
                attempts += "generic:$candidate"
                candidate == mirror
            }
        )

        assertTrue(loaded)
        assertEquals(listOf("kuro:$kuro", "generic:$mirror"), attempts)
    }

    @Test
    fun `verified Kuroplayer link does not suppress a source API mirror`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = KuronimeSourceScheduler.resolve(
            candidates = listOf(kuro, mirror),
            resolveKuroplayer = { candidate ->
                attempts += "kuro:$candidate"
                true
            },
            resolveGeneric = { candidate ->
                attempts += "generic:$candidate"
                true
            }
        )

        assertTrue(loaded)
        assertEquals(listOf("kuro:$kuro", "generic:$mirror"), attempts)
    }

    @Test
    fun `all failed source API candidates report no resolved links`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = KuronimeSourceScheduler.resolve(
            candidates = listOf(kuro, mirror),
            resolveKuroplayer = { candidate ->
                attempts += "kuro:$candidate"
                false
            },
            resolveGeneric = { candidate ->
                attempts += "generic:$candidate"
                false
            }
        )

        assertFalse(loaded)
        assertEquals(listOf("kuro:$kuro", "generic:$mirror"), attempts)
    }

    @Test
    fun `failed source API candidate does not prevent a later mirror from resolving`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = KuronimeSourceScheduler.resolve(
            candidates = listOf(kuro, mirror),
            resolveKuroplayer = { candidate ->
                attempts += "kuro:$candidate"
                throw IllegalStateException("dead Kuroplayer")
            },
            resolveGeneric = { candidate ->
                attempts += "generic:$candidate"
                true
            }
        )

        assertTrue(loaded)
        assertEquals(listOf("kuro:$kuro", "generic:$mirror"), attempts)
    }

    @Test
    fun `cancellation from a source API candidate propagates without trying later mirrors`() = runBlocking {
        val attempts = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            KuronimeSourceScheduler.resolve(
                candidates = listOf(kuro, mirror),
                resolveKuroplayer = { candidate ->
                    attempts += "kuro:$candidate"
                    throw CancellationException("cancelled")
                },
                resolveGeneric = { candidate ->
                    attempts += "generic:$candidate"
                    true
                }
            )
        }

        assertEquals(listOf("kuro:$kuro"), attempts)
    }
}
