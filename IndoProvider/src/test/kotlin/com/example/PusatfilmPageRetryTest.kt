package com.example

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class PusatfilmPageRetryTest {
    private fun response(code: Int, body: String) = ProviderHttpResult(
        code, "https://v5.pusatfilm21info.com/series-terbaru/page/1/", emptyMap(), body.toByteArray(), Charsets.UTF_8
    )

    @Test
    fun `transient status missing response and empty page retry once`(): Unit = runBlocking {
        val valid = response(200, "<html><body><article>Current series</article></body></html>")
        for (first in listOf(null, response(503, "Unavailable"), response(429, "Rate limited"), response(200, ""))) {
            var calls = 0
            val result = fetchPusatfilmProviderPageWithRetry {
                calls++
                if (calls == 1) first else valid
            }
            assertSame(valid, result)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `retry is bounded preserves permanent errors and parent cancellation`(): Unit = runBlocking {
        var calls = 0
        assertNull(fetchPusatfilmProviderPageWithRetry(attemptTimeoutMs = 10) { calls++; delay(1_000); null })
        assertEquals(2, calls)
        val missing = response(404, "Not found")
        calls = 0
        assertSame(missing, fetchPusatfilmProviderPageWithRetry { calls++; missing })
        assertEquals(1, calls)
        assertFailsWith<CancellationException> {
            fetchPusatfilmProviderPageWithRetry { throw CancellationException("parent cancelled") }
        }
    }
}
