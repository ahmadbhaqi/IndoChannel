package com.example

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimeSensitiveContentIntegrationTest {
    private val sourceRoot = listOf(
        File("src/main/kotlin/com/example"),
        File("IndoProvider/src/main/kotlin/com/example")
    ).first { it.exists() }

    @Test
    fun `registered anime providers enforce shared policy at catalog detail and playback boundaries`() {
        val pluginSource = File(sourceRoot, "IndoPlugin.kt").readText()
        val animeSection = pluginSource.substringAfter("// Anime")
        val registered = Regex("""registerMainAPI\((\w+Provider)\(\)\)""")
            .findAll(animeSection)
            .map { match -> match.groupValues[1] }
            .toSet()
        val inheritedProviders = mapOf("ZoronimeProvider" to "KuronimeProvider")
        val directlyWiredProviders = registered - inheritedProviders.keys

        assertEquals(
            setOf(
                "KuramanimeProvider",
                "AnimasuProvider",
                "OtakudesuProvider",
                "SamehadakuProvider",
                "AnoboyProvider",
                "KuronimeProvider",
                "AnimeindoProvider",
                "OploverzProvider",
                "ZoronimeProvider"
            ),
            registered
        )

        directlyWiredProviders.forEach { providerName ->
            val fileName = "$providerName.kt"
            val source = File(sourceRoot, fileName).readText()
            assertTrue(
                source.contains("SensitiveContentPolicy.isBlocked"),
                "$fileName must use the shared sensitive-content policy"
            )
            assertTrue(
                source.contains("AnimePlaybackDataCodec.decode(data)"),
                "$fileName must re-evaluate category metadata carried into loadLinks"
            )
        }
        inheritedProviders.forEach { (providerName, parentName) ->
            val source = File(sourceRoot, "$providerName.kt").readText()
            assertTrue(
                source.contains("class $providerName : $parentName()"),
                "$providerName must inherit the filtered $parentName implementation"
            )
        }
    }
}
