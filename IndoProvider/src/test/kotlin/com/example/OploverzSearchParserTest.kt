package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OploverzSearchParserTest {
    @Test
    fun `search suggestion parser reads live ajax shape`() {
        val json = """
            {
                "status": "1",
                "more": "2",
                "data": [
                    {
                        "slug": "dr-stone",
                        "img": "Screenshot_3-1.jpg",
                        "title": "Dr. Stone"
                    },
                    {
                        "slug": "dr-stone-season-4-science-future",
                        "img": "dr-stone-season-4-science-future.jpg",
                        "title": "Dr. STONE Season 4 : Science Future"
                    }
                ]
            }
        """.trimIndent()

        assertEquals(
            listOf(
                OploverzSearchItem("dr-stone", "Screenshot_3-1.jpg", "Dr. Stone"),
                OploverzSearchItem(
                    "dr-stone-season-4-science-future",
                    "dr-stone-season-4-science-future.jpg",
                    "Dr. STONE Season 4 : Science Future"
                )
            ),
            OploverzSearchParser.parse(json)
        )
    }

    @Test
    fun `search suggestion parser rejects unsuccessful response`() {
        assertTrue(OploverzSearchParser.parse("""{"status":"0","data":[]}""").isEmpty())
    }
}
