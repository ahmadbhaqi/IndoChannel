package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideonodePlayerParserTest {
    @Test
    fun `current player url creates the documented form request`() {
        assertEquals(
            VideonodePlayerRequest("https://videonode.de/api.php", "hydrax", "7l6aDgnOfoN5gOg-JtZ7XQ"),
            VideonodePlayerParser.request("https://videonode.de/iframe3/hydrax/7l6aDgnOfoN5gOg-JtZ7XQ?v=1")
        )
        assertNull(VideonodePlayerParser.request("https://foreign.example/iframe3/hydrax/current"))
        assertNull(VideonodePlayerParser.request("http://videonode.de/iframe3/hydrax/current"))
        assertNull(VideonodePlayerParser.request("https://videonode.de/iframe3/unknown/current"))
        assertNull(VideonodePlayerParser.request("https://user@videonode.de/iframe3/hydrax/current"))
    }

    @Test
    fun `api response exposes a remote embed and rejects invalid destinations`() {
        assertEquals(
            "https://abyssplayer.com/d99zgqG1U",
            VideonodePlayerParser.embedUrl("""{"embedUrl":"https:\/\/abyssplayer.com\/d99zgqG1U"}""")
        )
        assertNull(VideonodePlayerParser.embedUrl("""{"embedUrl":"http://127.0.0.1/private"}"""))
        assertNull(VideonodePlayerParser.embedUrl("""{"error":"unavailable"}"""))
        assertNull(VideonodePlayerParser.embedUrl("not json"))
    }
}
