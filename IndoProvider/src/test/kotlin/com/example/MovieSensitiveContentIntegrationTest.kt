package com.example

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovieSensitiveContentIntegrationTest {
    private val sourceRoot = listOf(
        File("src/main/kotlin/com/example"),
        File("IndoProvider/src/main/kotlin/com/example")
    ).first { it.exists() }

    @Test
    fun `registered movie providers enforce shared policy at catalog boundaries`() {
        val pluginSource = File(sourceRoot, "IndoPlugin.kt").readText()
        val movieSection = pluginSource
            .substringAfter("// Movie & TV Series")
            .substringBefore("// Anime")
        val registered = Regex("""registerMainAPI\((\w+Provider)\(\)\)""")
            .findAll(movieSection)
            .map { match -> match.groupValues[1] }
            .toSet()

        assertEquals(
            setOf(
                "MovieboxProvider",
                "PencurimovieProvider",
                "SarangfilmProvider",
                "NomatProvider",
                "IndomaxProvider",
                "KawanfilmProvider",
                "LayarKacaProvider",
                "NgefilmProvider",
                "DutamovieProvider",
                "KitanontonProvider",
                "IndoxxiProvider",
                "FilmapikProvider",
                "IdlixProvider",
                "PusatfilmProvider",
                "KeBioskopProvider"
            ),
            registered
        )

        registered.forEach { providerName ->
            val fileName = "$providerName.kt"
            val source = File(sourceRoot, fileName).readText()
            assertTrue(
                source.contains("SensitiveContentPolicy.isBlocked"),
                "$fileName must use the shared sensitive-content policy"
            )
        }
    }
}
