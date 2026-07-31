package com.example

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BstationFallbackParserTest {
    @Test
    fun `search parser accepts only an exact full length title and year`() {
        val html = """
            <div class="bstar-video-card">
              <a href="//www.bilibili.tv/en/video/4794271995138048">
                <img alt="Rumah Sewa RM50 2014">
              </a>
              <span class="bstar-video-card__cover-mask-text--bold">1:20:40</span>
            </div>
            <div class="bstar-video-card">
              <a href="/en/video/1111111111111111">
                <img alt="Rumah Sewa RM50 2014 Trailer">
              </a>
              <span class="bstar-video-card__cover-mask-text--bold">2:10</span>
            </div>
        """.trimIndent()
        val request = NomatFallbackRequest("Rumah Sewa RM50", 2014)

        val candidates = BstationFallbackParser.searchCandidates(html)

        assertEquals(2, candidates.size)
        assertTrue(BstationFallbackParser.isExactCandidate(request, candidates[0]))
        assertFalse(BstationFallbackParser.isExactCandidate(request, candidates[1]))
        assertEquals("4794271995138048", candidates[0].aid)
        assertEquals(4_840, candidates[0].durationSeconds)
    }

    @Test
    fun `exact Bstation upload may use the adjacent production year`() {
        val html = """
            <div class="bstar-video-card">
              <a href="/en/video/4798844300564480">
                <img alt="THE MUSTANG 2018 SUB INDO">
              </a>
              <span class="bstar-video-card__cover-mask-text--bold">1:36:15</span>
            </div>
            <div class="bstar-video-card">
              <a href="/en/video/4798844300564481">
                <img alt="The Mustang Legacy 2018 Sub Indo">
              </a>
              <span class="bstar-video-card__cover-mask-text--bold">1:36:15</span>
            </div>
            <div class="bstar-video-card">
              <a href="/en/video/4798844300564482">
                <img alt="The Mustang 2017 Sub Indo">
              </a>
              <span class="bstar-video-card__cover-mask-text--bold">1:36:15</span>
            </div>
        """.trimIndent()
        val request = NomatFallbackRequest("The Mustang", 2019)
        val candidates = BstationFallbackParser.searchCandidates(html)

        assertTrue(BstationFallbackParser.isExactCandidate(request, candidates[0]))
        assertFalse(BstationFallbackParser.isExactCandidate(request, candidates[1]))
        assertFalse(BstationFallbackParser.isExactCandidate(request, candidates[2]))
    }

    @Test
    fun `Bstation fallback searches the release year first and accepts legacy numeric ids`() {
        val request = NomatFallbackRequest("Minions", 2015)
        val html = """
            <div class="bstar-video-card">
              <a href="/en/video/2041628374">
                <img alt="Minions 2015">
              </a>
              <span class="bstar-video-card__cover-mask-text--bold">1:30:59</span>
            </div>
        """.trimIndent()

        assertEquals(
            listOf(
                "https://www.bilibili.tv/en/search-result?q=Minions%202015",
                "https://www.bilibili.tv/en/search-result?q=Minions"
            ),
            BstationFallbackParser.searchUrls(request)
        )
        val candidate = BstationFallbackParser.searchCandidates(html).single()
        assertEquals("2041628374", candidate.aid)
        assertTrue(BstationFallbackParser.isExactCandidate(request, candidate))
        assertEquals(
            "https://api.bilibili.tv/intl/gateway/web/playurl" +
                "?platform=web&aid=2041628374",
            BstationFallbackParser.playUrl(candidate.aid)
        )
    }

    @Test
    fun `play response selects a trusted AVC video with matching audio`() {
        val response = parseJson<BstationPlayResponse>(
            """
            {
              "code": 0,
              "data": {
                "playurl": {
                  "video": [
                    {
                      "video_resource": {
                        "url": "https://upos-bstar1-mirrorakam.akamaized.net/video-hevc.m4s",
                        "quality": 64,
                        "codec_id": 12,
                        "width": 1280,
                        "height": 720
                      },
                      "audio_quality": 30216
                    },
                    {
                      "video_resource": {
                        "url": "https://upos-bstar1-mirrorakam.akamaized.net/video-avc.m4s",
                        "quality": 32,
                        "codec_id": 7,
                        "width": 854,
                        "height": 478
                      },
                      "audio_quality": 30216
                    }
                  ],
                  "audio_resource": [
                    {
                      "url": "https://upos-sz-mirrorcosbstar1.bilivideo.com/audio.m4s",
                      "quality": 30216,
                      "codec_id": 0
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val media = assertNotNull(BstationFallbackParser.playbackMedia(response))

        assertEquals(
            "https://upos-bstar1-mirrorakam.akamaized.net/video-avc.m4s",
            media.videoUrl
        )
        assertEquals(
            "https://upos-sz-mirrorcosbstar1.bilivideo.com/audio.m4s",
            media.audioUrl
        )
        assertEquals(478, media.height)
    }

    @Test
    fun `play response rejects an untrusted media host`() {
        val response = parseJson<BstationPlayResponse>(
            """
            {
              "code": 0,
              "data": {
                "playurl": {
                  "video": [{
                    "video_resource": {
                      "url": "https://evil.example/video.m4s",
                      "quality": 32,
                      "codec_id": 7,
                      "height": 480
                    },
                    "audio_quality": 30216
                  }],
                  "audio_resource": [{
                    "url": "https://evil.example/audio.m4s",
                    "quality": 30216
                  }]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(null, BstationFallbackParser.playbackMedia(response))
    }
}
