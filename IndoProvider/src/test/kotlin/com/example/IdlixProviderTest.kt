package com.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class IdlixProviderTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `provider display name uses normal capitalization`() {
        assertEquals("Idlix", IdlixProvider().name)
    }

    @Test
    fun `dedicated series catalog hint survives missing contentType field`() {
        val node = mapper.readTree(
            """
            {
              "title": "The East Palace",
              "slug": "the-east-palace-2026",
              "posterPath": "/east-palace.jpg"
            }
            """.trimIndent()
        )

        val result = IdlixParser.searchResult(IdlixProvider(), node, "series")

        assertEquals(
            "https://z2.idlixku.com/series/the-east-palace-2026",
            result?.url
        )
    }

    @Test
    fun `explicitly unavailable search records are not exposed as playable cards`() {
        val node = mapper.readTree(
            """
            {
              "title": "Unavailable Movie",
              "slug": "unavailable-movie-2026",
              "contentType": "movie",
              "hasVideo": false
            }
            """.trimIndent()
        )

        assertNull(IdlixParser.searchResult(IdlixProvider(), node))
    }

    @Test
    fun `series cards remain visible when video lives on their episodes`() {
        val node = mapper.readTree(
            """
            {
              "title": "The Vampire Lestat",
              "slug": "the-vampire-lestat-2026",
              "isPublished": true,
              "hasVideo": false
            }
            """.trimIndent()
        )

        assertEquals(
            "https://z2.idlixku.com/series/the-vampire-lestat-2026",
            IdlixParser.searchResult(IdlixProvider(), node, "series")?.url
        )
    }

    @Test
    fun `search pagination continues until every reported result is covered`() {
        assertTrue(
            IdlixParser.shouldLoadNextSearchPage(
                total = 200,
                loadedCount = 30,
                currentPageCount = 30,
                currentPage = 1
            )
        )
        assertFalse(
            IdlixParser.shouldLoadNextSearchPage(
                total = 30,
                loadedCount = 30,
                currentPageCount = 30,
                currentPage = 1
            )
        )
        assertFalse(
            IdlixParser.shouldLoadNextSearchPage(
                total = 200,
                loadedCount = 42,
                currentPageCount = 12,
                currentPage = 2
            )
        )
        assertFalse(
            IdlixParser.shouldLoadNextSearchPage(
                total = 5_000,
                loadedCount = 600,
                currentPageCount = 30,
                currentPage = 20
            )
        )
    }

    @Test
    fun `search pager carries cookies deduplicates in order and stops on repeated pages`() = runBlocking {
        val requests = mutableListOf<Triple<Int, Map<String, String>, Long>>()
        var nowMs = 0L

        val results = IdlixSearchPager.collect(
            pageSize = 2,
            maxPages = 10,
            budgetMs = 1_000L,
            nowMs = { nowMs },
            key = { it }
        ) { page, cookies, timeoutMs ->
            requests += Triple(page, cookies, timeoutMs)
            nowMs += 100L
            when (page) {
                1 -> IdlixSearchPager.Page(listOf("alpha", "beta"), 10, mapOf("session" to "one"))
                2 -> IdlixSearchPager.Page(listOf("beta", "gamma"), 10, mapOf("session" to "two"))
                else -> IdlixSearchPager.Page(listOf("beta", "gamma"), 10, mapOf("session" to "three"))
            }
        }

        assertEquals(listOf("alpha", "beta", "gamma"), results)
        assertEquals(listOf(1, 2, 3), requests.map { it.first })
        assertEquals(emptyMap(), requests[0].second)
        assertEquals(mapOf("session" to "one"), requests[1].second)
        assertEquals(mapOf("session" to "two"), requests[2].second)
        assertEquals(listOf(1_000L, 900L, 800L), requests.map { it.third })
    }

    @Test
    fun `search pager enforces one aggregate deadline across all pages`() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        var nowMs = 0L

        val results = IdlixSearchPager.collect(
            pageSize = 1,
            maxPages = 20,
            budgetMs = 50L,
            nowMs = { nowMs },
            key = { it }
        ) { page, _, _ ->
            requestedPages += page
            nowMs = 50L
            IdlixSearchPager.Page(listOf("only-result"), 200, emptyMap())
        }

        assertEquals(listOf("only-result"), results)
        assertEquals(listOf(1), requestedPages)
    }

    @Test
    fun `playback data is scoped to the current IDLIX content page`() {
        val id = "f7b4b404-606f-4380-b0d8-51030729942b"
        val page = "https://z2.idlixku.com/movie/ghost-board-2026"
        val encoded = IdlixParser.encodePlayback("movie", id, page)

        assertEquals(
            IdlixParser.PlaybackRequest("movie", id, page),
            IdlixParser.decodePlayback(encoded, "https://z2.idlixku.com")
        )
        assertNull(
            IdlixParser.decodePlayback(
                IdlixParser.encodePlayback("movie", id, "https://evil.example/movie/ghost-board-2026"),
                "https://z2.idlixku.com"
            )
        )
        assertNull(IdlixParser.decodePlayback("https://z2.idlixku.com/movie/ghost-board-2026", "https://z2.idlixku.com"))
    }

    @Test
    fun `master parser carries the signed token to video and audio playlists`() {
        val masterUrl = "https://e2e.majorplay.net/v/z2/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"
        val manifest = IdlixParser.masterManifest(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,URI="/v/z2/video-id/p/key/audio.json",GROUP-ID="audio"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
            /v/z2/video-id/p/key/video-1080.json
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="audio"
            /v/z2/video-id/p/key/video-720.json
            """.trimIndent(),
            masterUrl,
            maxHeight = 720
        )

        assertEquals(1, manifest?.streams?.size)
        assertEquals(720, manifest?.streams?.single()?.height)
        assertEquals(
            "https://e2e.majorplay.net/v/z2/video-id/p/key/video-720.json" +
                "?t=signed-token&pm=browser",
            manifest?.streams?.single()?.url
        )
        assertEquals(
            listOf(
                "https://e2e.majorplay.net/v/z2/video-id/p/key/audio.json" +
                    "?t=signed-token&pm=browser"
            ),
            manifest?.audioUrls
        )
    }

    @Test
    fun `master parser preserves signed subtitle renditions`() {
        val masterUrl = "https://e2e.majorplay.net/v/z2/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"
        val manifest = IdlixParser.masterManifest(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="/v/z2/video-id/sub/id.vtt",GROUP-ID="subs",LANGUAGE="id",NAME="Bahasa Indonesia",DEFAULT=YES
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="/v/z2/video-id/sub/en.vtt",GROUP-ID="subs",LANGUAGE="en",NAME="English"
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="/v/z2/video-id/sub/unknown.vtt",GROUP-ID="subs",NAME="Subtitle"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,SUBTITLES="subs"
            /v/z2/video-id/p/key/video-720.json
            """.trimIndent(),
            masterUrl,
            maxHeight = 720
        )

        assertEquals(
            listOf(
                IdlixParser.SubtitleTrack(
                    "Bahasa Indonesia",
                    "https://e2e.majorplay.net/v/z2/video-id/sub/id.vtt" +
                        "?t=signed-token&pm=browser"
                )
            ),
            manifest?.subtitles
        )
    }

    @Test
    fun `relative playback siblings inherit the signed master token`() {
        val masterUrl = "https://e2e.majorplay.net/v/z2/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"

        assertEquals(
            "https://e2e.majorplay.net/v/z2/video-id/sub/id.vtt" +
                "?t=signed-token&pm=browser",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "/v/z2/video-id/sub/id.vtt"
            )
        )
        assertEquals(
            "https://e2e.majorplay.net/v/z2/video-id/sub/id.vtt" +
                "?t=signed-token&pm=browser",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://e2e.majorplay.net/v/z2/video-id/sub/id.vtt?t=&pm="
            )
        )
        assertEquals(
            "https://e2e.majorplay.net/v/z2/video-id/sub/id.vtt" +
                "?download=1&t=signed-token&pm=browser",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://e2e.majorplay.net/v/z2/video-id/sub/id.vtt" +
                    "?t=expired&download=1&pm=download"
            )
        )
        assertEquals(
            "https://e2e.majorplay.net/v/z2/other-id/sub/id.vtt",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://e2e.majorplay.net/v/z2/other-id/sub/id.vtt"
            )
        )
    }

    @Test
    fun `direct play info is accepted only for a trusted top level master`() {
        val trusted = mapper.readTree(
            """
            {
              "url": "https://e2e.majorplay.net/v/z2/video-id/config-615304.json?t=signed-token&pm=browser",
              "subtitles": []
            }
            """.trimIndent()
        )
        val hostile = mapper.readTree(
            """
            {
              "url": "https://evil.example/v/z2/video-id/config-615304.json?t=signed-token",
              "subtitles": []
            }
            """.trimIndent()
        )
        val unsigned = mapper.readTree(
            """
            {
              "url": "https://e2e.majorplay.net/v/z2/video-id/config-615304.json?t=",
              "subtitles": []
            }
            """.trimIndent()
        )
        val gateWithUrl = mapper.readTree(
            """
            {
              "kind": "gate",
              "url": "https://e2e.majorplay.net/v/z2/video-id/config-615304.json?t=signed-token",
              "subtitles": []
            }
            """.trimIndent()
        )

        assertNotNull(IdlixParser.directPlayback(trusted))
        assertNull(IdlixParser.directPlayback(hostile))
        assertNull(IdlixParser.directPlayback(unsigned))
        assertNull(IdlixParser.directPlayback(gateWithUrl))
        assertFalse(
            IdlixParser.isTrustedMasterUrl(
                "https://e2e.majorplay.net/v/z2%2Fvideo-id/config-615304.json?t=signed-token"
            )
        )
    }

    @Test
    fun `gate wait normalizes seconds and milliseconds without mixing units`() {
        assertEquals(15_000L, IdlixParser.gateWaitMs(1_789_000_000L, 1_789_000_015L))
        assertEquals(
            15_000L,
            IdlixParser.gateWaitMs(1_789_000_000_000L, 1_789_000_015_000L)
        )
        assertNull(IdlixParser.gateWaitMs(1_789_000_000L, 1_789_000_015_000L))
        assertEquals(0L, IdlixParser.gateWaitMs(1_789_000_015_000L, 1_789_000_000_000L))
    }

    @Test
    fun `gate polling honors the full pending delay and previous response`() = runBlocking {
        data class ClaimFixture(val kind: String, val remainingMs: Long)

        val responses = listOf(
            ClaimFixture("pending", 12_000L),
            ClaimFixture("pentos", 0L)
        )
        val sleeps = mutableListOf<Long>()
        val previousKinds = mutableListOf<String?>()
        val requestBudgets = mutableListOf<Long>()
        var nowMs = 0L
        var index = 0

        val result = IdlixParser.pollGateClaim(
            initialWaitMs = 15_000L,
            pendingBudgetMs = 20_000L,
            sleep = { wait ->
                sleeps += wait
                nowMs += wait
            },
            request = { previous, requestBudgetMs ->
                previousKinds += previous?.kind
                requestBudgets += requestBudgetMs
                responses.getOrNull(index++)
            },
            kind = ClaimFixture::kind,
            remainingMs = ClaimFixture::remainingMs,
            nowMs = { nowMs }
        )

        assertEquals("pentos", result?.kind)
        assertEquals(listOf(15_000L, 12_400L), sleeps)
        assertEquals(listOf(null, "pending"), previousKinds)
        assertEquals(listOf(20_000L, 7_600L), requestBudgets)
    }

    @Test
    fun `gate polling counts request duration against its deadline`() = runBlocking {
        data class ClaimFixture(val kind: String, val remainingMs: Long)

        var nowMs = 0L
        var requests = 0
        val result = IdlixParser.pollGateClaim(
            initialWaitMs = 0L,
            pendingBudgetMs = 20_000L,
            sleep = { nowMs += it },
            request = { _, requestBudgetMs ->
                requests++
                assertEquals(20_000L, requestBudgetMs)
                nowMs += 21_000L
                ClaimFixture("pending", 0L)
            },
            kind = ClaimFixture::kind,
            remainingMs = ClaimFixture::remainingMs,
            nowMs = { nowMs }
        )

        assertNull(result)
        assertEquals(1, requests)
    }

    @Test
    fun `redeemed subtitles resolve signed siblings and carry playback headers`() = runBlocking {
        val masterUrl = "https://e2e.majorplay.net/v/z2/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"
        val redeemed = mapper.readTree(
            """
            {
              "subtitles": [
                {"label":"Bahasa Indonesia","lang":"id","path":"/v/z2/video-id/sub/id.vtt"},
                {"lang":"en","url":"https://e2e.majorplay.net/v/z2/video-id/sub/en.vtt"},
                {"label":"Indonesian","lang":"en","path":"/v/z2/video-id/sub/conflict.vtt"},
                {"path":"/v/z2/video-id/sub/unknown.vtt"},
                {"label":"Wrong video","path":"/v/z2/other-id/sub/id.vtt"},
                {"label":"Foreign","path":"https://evil.example/sub/id.vtt"}
              ]
            }
            """.trimIndent()
        )
        val tracks = IdlixParser.redeemedSubtitles(redeemed, masterUrl)

        assertEquals(
            listOf(
                IdlixParser.SubtitleTrack(
                    "Bahasa Indonesia",
                    "https://e2e.majorplay.net/v/z2/video-id/sub/id.vtt" +
                        "?t=signed-token&pm=browser"
                )
            ),
            tracks
        )

        val headers = mapOf(
            "User-Agent" to "fixture-agent",
            "Referer" to "https://z2.idlixku.com/movie/example",
            "Origin" to "https://z2.idlixku.com"
        )
        val subtitle = newIdlixSubtitleFile(tracks.first(), headers)
        assertEquals("id", subtitle.lang)
        assertEquals(headers, subtitle.headers)
    }

    @Test
    fun `playback trust follows the current IDLIX media CSP`() {
        assertTrue(
            IdlixParser.isTrustedPlaybackAsset(
                "https://edge.asia9sports.com/v/z2/video-id/video-720.m3u8"
            )
        )
        assertTrue(
            IdlixParser.isTrustedPlaybackAsset(
                "https://edge.akamaized.net/v/z2/video-id/segment.ts"
            )
        )
    }

    @Test
    fun `current compact master layout keeps signed subtitle siblings`() {
        val masterUrl = "https://e2e.majorplay.net/v/video-id/config-964732.json" +
            "?t=signed-token&pm=browser"
        val redeemed = mapper.readTree(
            """
            {
              "url": "$masterUrl",
              "subtitles": [
                {
                  "lang": "id",
                  "label": "Indonesian",
                  "path": "https://e2e.majorplay.net/v/video-id/subs-legacy/subtitle.vtt"
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(IdlixParser.isTrustedMasterUrl(masterUrl))
        assertNotNull(IdlixParser.directPlayback(redeemed))
        assertEquals(
            listOf(
                IdlixParser.SubtitleTrack(
                    "Indonesian",
                    "https://e2e.majorplay.net/v/video-id/subs-legacy/subtitle.vtt" +
                        "?t=signed-token&pm=browser"
                )
            ),
            IdlixParser.redeemedSubtitles(redeemed, masterUrl)
        )
        val manifest = IdlixParser.masterManifest(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,URI="/v/video-id/audio/audio.json",GROUP-ID="audio"
            #EXT-X-MEDIA:TYPE=SUBTITLES,URI="/v/video-id/subs-legacy/subtitle.vtt",GROUP-ID="subs",NAME="Indonesian"
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="audio",SUBTITLES="subs"
            /v/video-id/video/video-720.json
            """.trimIndent(),
            masterUrl,
            maxHeight = 720
        )
        assertEquals(
            "https://e2e.majorplay.net/v/video-id/video/video-720.json" +
                "?t=signed-token&pm=browser",
            manifest?.streams?.single()?.url
        )
        assertEquals(
            "https://e2e.majorplay.net/v/video-id/audio/audio.json" +
                "?t=signed-token&pm=browser",
            manifest?.audioUrls?.single()
        )
        assertEquals(
            "https://e2e.majorplay.net/v/video-id/subs-legacy/subtitle.vtt" +
                "?t=signed-token&pm=browser",
            manifest?.subtitles?.single()?.url
        )
        val otherVideoSubtitle =
            "https://e2e.majorplay.net/v/other-video/subs-legacy/subtitle.vtt"
        assertEquals(
            otherVideoSubtitle,
            IdlixParser.playbackRequestUrl(masterUrl, otherVideoSubtitle)
        )
    }

    @Test
    fun `player exposes signed variants as named resolution links with shared audio`() = runBlocking {
        val masterUrl = "https://e2e.majorplay.net/v/z5/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"
        val manifest = IdlixParser.masterManifest(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,URI="/v/z5/video-id/p/key/audio.json",GROUP-ID="audio"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
            /v/z5/video-id/p/key/video-1080.json
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="audio"
            /v/z5/video-id/p/key/video-720.json
            """.trimIndent(),
            masterUrl,
            maxHeight = 1080
        )!!

        val links = newIdlixVariantLinks(
            source = "IDLIX",
            masterUrl = masterUrl,
            pageUrl = "https://z2.idlixku.com/movie/example",
            manifest = manifest,
            headers = mapOf("User-Agent" to "fixture")
        )

        assertEquals(listOf("IDLIX 1080p", "IDLIX 720p"), links.map { it.name })
        assertEquals(listOf(1080, 720), links.map { it.quality })
        assertEquals(manifest.streams.map { it.url }, links.map { it.url })
        assertTrue(links.all { it.type == ExtractorLinkType.M3U8 })
        assertTrue(links.all { it.extractorData == masterUrl })
        assertTrue(links.all { it.audioTracksCompat().single().url == manifest.audioUrls.single() })
    }

    @Test
    fun `playback cookie cache reuses only fresh page cookies`() {
        var nowMs = 1_000L
        val cache = IdlixPlaybackCookieCache(ttlMs = 5_000L, nowMs = { nowMs })
        val pageUrl = "https://z2.idlixku.com/movie/example"

        cache.put(pageUrl, mapOf("cf_clearance" to "fresh"))
        assertEquals(mapOf("cf_clearance" to "fresh"), cache.get(pageUrl))
        assertNull(cache.get("https://z2.idlixku.com/movie/other"))

        nowMs = 6_001L
        assertNull(cache.get(pageUrl))
    }

    @Test
    fun `legacy runtime falls back to the signed master when variants need external audio`() {
        val masterUrl = "https://e2e.majorplay.net/v/z5/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"
        val manifest = IdlixParser.MasterManifest(
            streams = listOf(
                IdlixParser.Stream(
                    "https://e2e.majorplay.net/v/z5/video-id/p/key/video-720.json" +
                        "?t=signed-token&pm=browser",
                    720
                )
            ),
            audioUrls = listOf(
                "https://e2e.majorplay.net/v/z5/video-id/p/key/audio.json" +
                    "?t=signed-token&pm=browser"
            ),
            subtitles = emptyList()
        )

        assertEquals(
            listOf(IdlixParser.Stream(masterUrl, Qualities.Unknown.value)),
            IdlixParser.playbackStreams(manifest, masterUrl, externalAudioSupported = false)
        )
    }

    @Test
    fun `rejected cached playback retries once with fresh cookies`() = runBlocking {
        val attempts = mutableListOf<String>()
        var refreshes = 0

        val result = resolveWithFreshCookies(
            cachedCookies = "stale",
            attempt = { cookies ->
                attempts += cookies
                "resolved".takeIf { cookies == "fresh" }
            },
            invalidate = {},
            refresh = {
                refreshes++
                "fresh"
            }
        )

        assertEquals("resolved", result)
        assertEquals(listOf("stale", "fresh"), attempts)
        assertEquals(1, refreshes)
    }

    @Test
    fun `rejected playback evicts stale cookies and retains a fresh preflight`() = runBlocking {
        val pageUrl = "https://z2.idlixku.com/series/example"
        val cache = IdlixPlaybackCookieCache()
        val attempts = mutableListOf<String>()
        cache.put(pageUrl, mapOf("session" to "stale"))

        val result = resolveWithFreshCookies(
            cachedCookies = cache.get(pageUrl),
            attempt = { cookies ->
                attempts += cookies.getValue("session")
                null
            },
            invalidate = { cache.remove(pageUrl) },
            refresh = {
                mapOf("session" to "fresh").also { cache.put(pageUrl, it) }
            }
        )

        assertNull(result)
        assertEquals(listOf("stale", "fresh"), attempts)
        assertEquals(mapOf("session" to "fresh"), cache.get(pageUrl))
    }

    @Test
    fun `playback request inherits master tokens only inside the trusted video path`() {
        val masterUrl = "https://e2e.majorplay.net/v/z5/video-id/config-615304.json" +
            "?t=signed-token&pm=browser"

        assertEquals(
            "https://g2.akademivo.website/v/z5/video-id/p/key/audio.json" +
                "?t=signed-token&pm=browser",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://g2.akademivo.website/v/z5/video-id/p/key/audio.json"
            )
        )
        assertEquals(
            "https://g2.akademivo.website/v/z5/other-id/p/key/audio.json",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://g2.akademivo.website/v/z5/other-id/p/key/audio.json"
            )
        )
        assertEquals(
            "https://evil.example/v/z5/video-id/p/key/audio.json",
            IdlixParser.playbackRequestUrl(
                masterUrl,
                "https://evil.example/v/z5/video-id/p/key/audio.json"
            )
        )
    }

    @Test
    fun `master parser rejects HTML and foreign playback hosts`() {
        assertNull(
            IdlixParser.masterManifest(
                "<html><title>Just a moment...</title></html>",
                "https://e2e.majorplay.net/v/z2/id/config-1.json?t=token",
                720
            )
        )
        assertNull(
            IdlixParser.masterManifest(
                "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nhttps://evil.example/video.m3u8",
                "https://e2e.majorplay.net/v/z2/id/config-1.json?t=token",
                720
            )
        )
        assertFalse(IdlixParser.isTrustedRedeemUrl("https://evil.example/api/play"))
        assertTrue(IdlixParser.isTrustedRedeemUrl("https://e2e.majorplay.net/api/play"))
    }

    @Test
    fun `series parser emits only published episodes with video`() {
        val season = mapper.readTree(
            """
            {
              "episodes": [
                {
                  "id": "c1d62dd4-1cfd-4a5f-b053-e9c11ac9f3cb",
                  "episodeNumber": 1,
                  "name": "Episode 1",
                  "overview": "Ready",
                  "stillPath": "/still-one.jpg",
                  "isPublished": true,
                  "hasVideo": true
                },
                {
                  "id": "28b823da-356c-4019-8e55-3e4c6c55baef",
                  "episodeNumber": 2,
                  "name": "Episode 2",
                  "isPublished": true,
                  "hasVideo": false
                }
              ]
            }
            """.trimIndent()
        )

        val episodes = IdlixParser.episodes(
            IdlixProvider(),
            season,
            1,
            "https://z2.idlixku.com/series/the-east-palace-2026"
        )

        assertEquals(1, episodes.size)
        assertEquals(1, episodes.single().episode)
        assertEquals(1, episodes.single().season)
        assertEquals("Ready", episodes.single().description)
        assertEquals(
            IdlixParser.PlaybackRequest(
                "episode",
                "c1d62dd4-1cfd-4a5f-b053-e9c11ac9f3cb",
                "https://z2.idlixku.com/series/the-east-palace-2026"
            ),
            IdlixParser.decodePlayback(episodes.single().data, "https://z2.idlixku.com")
        )
    }

    @Test
    fun `series parser unwraps the current season endpoint response`() {
        val response = mapper.readTree(
            """
            {
              "series": {
                "id": "70df8519-3b7a-4d8d-9640-f2f05bfb0a5c",
                "slug": "young-sheldon-2017"
              },
              "season": {
                "id": "ecd7f050-7df7-4632-9502-d91712de9170",
                "seasonNumber": 1,
                "episodeCount": 22,
                "episodes": [
                  {
                    "id": "9ca1e44e-b846-43a0-90c6-e92a50f15523",
                    "episodeNumber": 1,
                    "name": "Pilot",
                    "isPublished": true,
                    "hasVideo": true
                  },
                  {
                    "id": "50a45345-d8a1-494b-8bb9-a46e851fb8f3",
                    "episodeNumber": 22,
                    "name": "Vanilla Ice Cream",
                    "isPublished": true,
                    "hasVideo": true
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val episodes = IdlixParser.episodes(
            IdlixProvider(),
            response,
            1,
            "https://z2.idlixku.com/series/young-sheldon-2017"
        )

        assertEquals(listOf(1, 22), episodes.mapNotNull { it.episode })
        assertTrue(episodes.all { it.season == 1 })
    }

    @Test
    fun `invalid season endpoint payload falls back to the matching embedded season`() {
        val fallback = mapper.readTree(
            """
            {
              "seasonNumber": 1,
              "episodes": [
                {
                  "id": "9ca1e44e-b846-43a0-90c6-e92a50f15523",
                  "episodeNumber": 1,
                  "isPublished": true,
                  "hasVideo": true
                }
              ]
            }
            """.trimIndent()
        )
        val invalidCandidates = listOf(
            mapper.readTree("""{"error":"temporarily unavailable"}"""),
            mapper.readTree("""{"season":{"seasonNumber":1}}"""),
            mapper.readTree("""{"season":{"seasonNumber":2,"episodes":[]}}""")
        )

        invalidCandidates.forEach { endpoint ->
            assertEquals(
                fallback,
                IdlixParser.selectSeasonPayload(endpoint, fallback, expectedSeasonNumber = 1)
            )
        }
    }

    @Test
    fun `matching endpoint season payload takes precedence over embedded fallback`() {
        val endpoint = mapper.readTree(
            """{"season":{"seasonNumber":1,"episodes":[]}}"""
        )
        val fallback = mapper.readTree(
            """{"seasonNumber":1,"episodes":[{"episodeNumber":1}]}"""
        )

        assertEquals(
            endpoint,
            IdlixParser.selectSeasonPayload(endpoint, fallback, expectedSeasonNumber = 1)
        )
    }
}
