package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class IdlixApiParserTest {
    @Test
    fun `gate parser calculates wait from epoch milliseconds`() {
        val json = """
            {
                "kind": "gate",
                "gateToken": "gate-ms",
                "serverNow": 1784185000000,
                "unlockAt": 1784185015000
            }
        """.trimIndent()

        assertEquals(IdlixGateInfo("gate-ms", 15_000L), IdlixApiParser.gate(json))
    }

    @Test
    fun `gate parser also accepts epoch seconds`() {
        val json = """
            {
                "kind": "gate",
                "gateToken": "gate-seconds",
                "serverNow": 1784185000,
                "unlockAt": 1784185015
            }
        """.trimIndent()

        assertEquals(IdlixGateInfo("gate-seconds", 15_000L), IdlixApiParser.gate(json))
    }

    @Test
    fun `claim and redeem parsers read the pentos exchange`() {
        val claimJson = """
            {
                "kind": "pentos",
                "claim": "signed-claim",
                "redeemUrl": "https://e2e.majorplay.net/api/play"
            }
        """.trimIndent()
        val redeemJson = """
            {
                "url": "https://e2e.majorplay.net/config-123.json?token=abc"
            }
        """.trimIndent()

        assertEquals(
            IdlixClaimInfo("signed-claim", "https://e2e.majorplay.net/api/play"),
            IdlixApiParser.claim(claimJson)
        )
        assertEquals(
            "https://e2e.majorplay.net/config-123.json?token=abc",
            IdlixApiParser.redeemedUrl(redeemJson)
        )
    }

    @Test
    fun `season episode parser falls back to embedded default season`() {
        val json = """
            {
                "defaultSeason": {
                    "seasonNumber": 2,
                    "episodes": [
                        {
                            "id": "episode-two",
                            "episodeNumber": 3,
                            "name": "Episode Three"
                        }
                    ]
                }
            }
        """.trimIndent()

        assertEquals(
            listOf(
                IdlixEpisodeItem(
                    id = "episode-two",
                    seasonNumber = 2,
                    episodeNumber = 3,
                    name = "Episode Three"
                )
            ),
            IdlixApiParser.seasonEpisodes(json)
        )
    }
}
