package com.example

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SamehadakuPlaybackSchedulerTest {
    @Test
    fun `successful streaming mirrors make download links a true fallback`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = SamehadakuPlaybackScheduler.resolve(
            streamingCandidates = listOf("blogger", "wibufile"),
            downloadCandidates = listOf("gofile"),
            streamResolver = { candidate ->
                attempts += "stream:$candidate"
                candidate == "blogger"
            },
            downloadResolver = { candidate ->
                attempts += "download:$candidate"
                true
            }
        )

        assertTrue(loaded)
        assertEquals(listOf("stream:blogger", "stream:wibufile"), attempts)
    }

    @Test
    fun `download fallback stops after its first successful candidate`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = SamehadakuPlaybackScheduler.resolve(
            streamingCandidates = listOf("dead-stream"),
            downloadCandidates = listOf("dead-download", "healthy-download", "slow-download"),
            streamResolver = { candidate ->
                attempts += "stream:$candidate"
                false
            },
            downloadResolver = { candidate ->
                attempts += "download:$candidate"
                candidate == "healthy-download"
            }
        )

        assertTrue(loaded)
        assertEquals(
            listOf("stream:dead-stream", "download:dead-download", "download:healthy-download"),
            attempts
        )
    }

    @Test
    fun `ordinary streaming failure does not hide a healthy sibling`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = SamehadakuPlaybackScheduler.resolve(
            streamingCandidates = listOf("throws", "healthy"),
            downloadCandidates = listOf("unused"),
            streamResolver = { candidate ->
                attempts += candidate
                if (candidate == "throws") error("mirror failed")
                true
            },
            downloadResolver = { candidate ->
                attempts += candidate
                true
            }
        )

        assertTrue(loaded)
        assertEquals(listOf("throws", "healthy"), attempts)
    }

    @Test
    fun `duplicate candidates are attempted only once`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = SamehadakuPlaybackScheduler.resolve(
            streamingCandidates = listOf("stream", "stream"),
            downloadCandidates = listOf("download", "download", "healthy"),
            streamResolver = { candidate ->
                attempts += "stream:$candidate"
                false
            },
            downloadResolver = { candidate ->
                attempts += "download:$candidate"
                candidate == "healthy"
            }
        )

        assertTrue(loaded)
        assertEquals(listOf("stream:stream", "download:download", "download:healthy"), attempts)
    }

    @Test
    fun `exhausted shared budget stops scheduling more mirrors`() = runBlocking {
        val attempts = mutableListOf<String>()

        val loaded = SamehadakuPlaybackScheduler.resolve(
            streamingCandidates = listOf("first", "second"),
            downloadCandidates = listOf("download"),
            streamResolver = { candidate ->
                attempts += candidate
                false
            },
            downloadResolver = { candidate ->
                attempts += candidate
                true
            },
            canContinue = { attempts.isEmpty() }
        )

        assertEquals(false, loaded)
        assertEquals(listOf("first"), attempts)
    }

    @Test
    fun `cancellation propagates without trying later candidates`() = runBlocking {
        val attempts = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            SamehadakuPlaybackScheduler.resolve(
                streamingCandidates = listOf("cancelled", "later"),
                downloadCandidates = listOf("download"),
                streamResolver = { candidate ->
                    attempts += candidate
                    throw CancellationException("stop")
                },
                downloadResolver = { candidate ->
                    attempts += candidate
                    true
                }
            )
        }
        assertEquals(listOf("cancelled"), attempts)
    }

    @Test
    fun `download cancellation propagates without trying its sibling`() = runBlocking {
        val attempts = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            SamehadakuPlaybackScheduler.resolve(
                streamingCandidates = listOf("dead-stream"),
                downloadCandidates = listOf("cancelled-download", "later-download"),
                streamResolver = { candidate ->
                    attempts += candidate
                    false
                },
                downloadResolver = { candidate ->
                    attempts += candidate
                    throw CancellationException("stop")
                }
            )
        }
        assertEquals(listOf("dead-stream", "cancelled-download"), attempts)
    }
}
