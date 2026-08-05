package com.example

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadRangeValidationTest {
    @Test
    fun `rejects a server that advertises byte ranges but ignores the request`() {
        assertFalse(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = TOTAL,
                initial = initialProbe(
                    responseCode = 200,
                    contentRange = null,
                    responseContentLength = TOTAL
                ),
                nonZero = null
            )
        )
    }

    @Test
    fun `accepts consistent bounded and CloudStream open ended byte ranges`() {
        assertTrue(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = TOTAL,
                initial = initialProbe(),
                nonZero = nonZeroProbe()
            )
        )
    }

    @Test
    fun `accepts valid range gets when head is unsupported`() {
        assertTrue(
            parallelDownloadProbeIsValid(
                headResponseCode = 405,
                acceptRanges = null,
                headContentLength = 123L,
                initial = initialProbe(),
                nonZero = nonZeroProbe()
            )
        )
    }

    @Test
    fun `rejects a mismatched head size or nonzero range start`() {
        assertFalse(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = 2_097_152L,
                initial = initialProbe(),
                nonZero = nonZeroProbe()
            )
        )
        assertFalse(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = TOTAL,
                initial = initialProbe(),
                nonZero = nonZeroProbe(
                    contentRange = "bytes 0-65535/$TOTAL"
                )
            )
        )
    }

    @Test
    fun `rejects undersized or prematurely closed range bodies`() {
        assertFalse(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = TOTAL,
                initial = initialProbe(bodyByteCount = 128),
                nonZero = nonZeroProbe()
            )
        )
        assertFalse(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = TOTAL,
                initial = initialProbe(),
                nonZero = nonZeroProbe(bodyTruncated = false)
            )
        )
    }

    @Test
    fun `rejects a capped open ended response or inconsistent content length`() {
        assertFalse(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = TOTAL,
                initial = initialProbe(),
                nonZero = nonZeroProbe(
                    contentRange = "bytes $NON_ZERO_START-${NON_ZERO_START + 65_535L}/$TOTAL",
                    responseContentLength = 65_536L,
                    bodyTruncated = false
                )
            )
        )
        assertFalse(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = TOTAL,
                initial = initialProbe(responseContentLength = 1_024L),
                nonZero = nonZeroProbe()
            )
        )
    }

    @Test
    fun `rejects bytes beyond an exact bounded response`() {
        assertFalse(
            parallelDownloadProbeIsValid(
                headResponseCode = 200,
                acceptRanges = "bytes",
                headContentLength = TOTAL,
                initial = initialProbe(bodyTruncated = true),
                nonZero = nonZeroProbe()
            )
        )
    }

    @Test
    fun `keeps a non-range server eligible for CloudStream single stream download`() {
        listOf<String?>(null, "none").forEach { acceptRanges ->
            assertTrue(
                parallelDownloadProbeIsValid(
                    headResponseCode = 200,
                    acceptRanges = acceptRanges,
                    headContentLength = TOTAL,
                    initial = DownloadRangeProbeEvidence(
                        requestedStart = 0L,
                        requestedEnd = null,
                        maxBodyBytes = PROBE_BYTES,
                        responseCode = 200,
                        contentRange = null,
                        responseContentLength = TOTAL,
                        bodyByteCount = PROBE_BYTES,
                        bodyTruncated = true
                    ),
                    nonZero = null
                )
            )
        }
    }

    private fun initialProbe(
        responseCode: Int = 206,
        contentRange: String? = "bytes 0-65535/$TOTAL",
        responseContentLength: Long? = PROBE_BYTES.toLong(),
        bodyByteCount: Int = PROBE_BYTES,
        bodyTruncated: Boolean = false
    ) = DownloadRangeProbeEvidence(
        requestedStart = 0L,
        requestedEnd = PROBE_BYTES - 1L,
        maxBodyBytes = PROBE_BYTES,
        responseCode = responseCode,
        contentRange = contentRange,
        responseContentLength = responseContentLength,
        bodyByteCount = bodyByteCount,
        bodyTruncated = bodyTruncated
    )

    private fun nonZeroProbe(
        contentRange: String? = "bytes $NON_ZERO_START-${TOTAL - 1L}/$TOTAL",
        responseContentLength: Long? = TOTAL - NON_ZERO_START,
        bodyByteCount: Int = PROBE_BYTES,
        bodyTruncated: Boolean = true
    ) = DownloadRangeProbeEvidence(
        requestedStart = NON_ZERO_START,
        requestedEnd = null,
        maxBodyBytes = PROBE_BYTES,
        responseCode = 206,
        contentRange = contentRange,
        responseContentLength = responseContentLength,
        bodyByteCount = bodyByteCount,
        bodyTruncated = bodyTruncated
    )

    private companion object {
        const val TOTAL = 1_833_408_341L
        const val NON_ZERO_START = 10_485_760L
        const val PROBE_BYTES = 65_536
    }
}
