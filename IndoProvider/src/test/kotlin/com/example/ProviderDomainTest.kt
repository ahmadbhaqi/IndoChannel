package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class ProviderDomainTest {
    private val sourceRoot = listOf(
        File("src/main/kotlin/com/example"),
        File("IndoProvider/src/main/kotlin/com/example")
    ).first { it.exists() }

    private fun source(fileName: String): String = File(sourceRoot, fileName).readText()

    @Test
    fun `idlix uses requested active domain`() {
        assertTrue(
            source("IdlixProvider.kt").contains("""override var mainUrl = "https://z2.idlixku.com""""),
            "IdlixProvider mainUrl should use https://z2.idlixku.com"
        )
    }

    @Test
    fun `new movie providers use requested domains`() {
        val expectedDomains = mapOf(
            "IndoxxiProvider.kt" to """override var mainUrl = "https://comblank.com"""",
            "FilmapikProvider.kt" to """override var mainUrl = "https://filmapik.fitness"""",
            "IndofilmProvider.kt" to """override var mainUrl = "https://yuhhaber.com"""",
            "KitanontonProvider.kt" to """override var mainUrl = "https://kitanonton.com""""
        )

        expectedDomains.forEach { (fileName, expected) ->
            assertTrue(source(fileName).contains(expected), "$fileName should contain $expected")
        }
    }

    @Test
    fun `new anime providers use requested domains`() {
        val expectedDomains = mapOf(
            "AnimeindoProvider.kt" to """override var mainUrl = "https://anime-indo.lol"""",
            "OploverzProvider.kt" to """override var mainUrl = "https://plus.oploverz.ltd"""",
            "ZoronimeProvider.kt" to """override var mainUrl = "https://zoronime.live"""",
            "MiranimeProvider.kt" to """override var mainUrl = "https://miranime.net""""
        )

        expectedDomains.forEach { (fileName, expected) ->
            assertTrue(source(fileName).contains(expected), "$fileName should contain $expected")
        }
    }

    @Test
    fun `plugin registers new providers`() {
        val plugin = source("IndoPlugin.kt")
        val expectedRegistrations = listOf(
            "registerMainAPI(IndoxxiProvider())",
            "registerMainAPI(FilmapikProvider())",
            "registerMainAPI(IndofilmProvider())",
            "registerMainAPI(AnimeindoProvider())",
            "registerMainAPI(OploverzProvider())",
            "registerMainAPI(ZoronimeProvider())",
            "registerMainAPI(MiranimeProvider())"
        )

        expectedRegistrations.forEach { expected ->
            assertTrue(plugin.contains(expected), "IndoPlugin.kt should contain $expected")
        }
    }

    @Test
    fun `movie providers use one shared resolution session`() {
        val providers = listOf(
            "NgefilmProvider.kt",
            "DutamovieProvider.kt",
            "PusatfilmProvider.kt",
            "RebahinProvider.kt",
            "GomovProvider.kt",
            "IdlixProvider.kt",
            "KitanontonProvider.kt",
            "FilmapikProvider.kt"
        )

        val sessionCreation = Regex("""\bLinkResolutionSession\s*\(""")
        val legacyCalls = listOf("loadExtractorWithResult", "loadResolvedExtractorWithResult")
        providers.forEach { fileName ->
            val source = source(fileName)
            assertEquals(
                1,
                sessionCreation.findAll(source).count(),
                "$fileName should create exactly one shared session"
            )
            legacyCalls.forEach { legacyCall ->
                assertFalse(
                    Regex("""\b$legacyCall\s*\(""").containsMatchIn(source),
                    "$fileName should not call $legacyCall"
                )
            }
        }
    }

    @Test
    fun `plugin keeps every provider registered`() {
        val plugin = source("IndoPlugin.kt")
        val expected = listOf(
            "LayarKacaProvider", "NgefilmProvider", "PusatfilmProvider", "DutamovieProvider",
            "RebahinProvider", "CgvindoProvider", "KitanontonProvider", "GomovProvider",
            "IdlixProvider", "JuraganFilmProvider", "IndoxxiProvider", "FilmapikProvider",
            "IndofilmProvider", "OtakudesuProvider", "SamehadakuProvider", "AnoboyProvider",
            "KuronimeProvider", "AnimeindoProvider", "OploverzProvider", "ZoronimeProvider",
            "MiranimeProvider"
        )

        expected.forEach { provider ->
            assertTrue(plugin.contains("registerMainAPI($provider())"), "$provider must remain visible")
        }
    }

    @Test
    fun `per candidate provider fetches rethrow cancellation and skip ordinary failures`() {
        val providers = listOf(
            "NgefilmProvider.kt",
            "DutamovieProvider.kt",
            "GomovProvider.kt"
        )
        val perCandidateFetches = listOf(
            """app\s*\.\s*get\s*\(\s*fixUrl\s*\(\s*ele\s*\.\s*attr\s*\(\s*"href"\s*\)\s*\)\s*\)""",
            """app\s*\.\s*post\s*\("""
        )

        providers.forEach { fileName ->
            val source = source(fileName)
            perCandidateFetches.forEach { fetchPattern ->
                val cancellationSafeFetch = Regex(
                    """(?s)try\s*\{(?:(?!catch\s*\().)*?$fetchPattern(?:(?!catch\s*\().)*?}\s*catch\s*\(\s*(\w+)\s*:\s*CancellationException\s*\)\s*\{\s*throw\s+\1\s*}\s*catch\s*\(\s*_\s*:\s*Exception\s*\)\s*\{\s*null\s*}"""
                )
                assertTrue(
                    cancellationSafeFetch.containsMatchIn(source),
                    "$fileName should isolate $fetchPattern with cancellation-safe catches"
                )
            }
        }
    }

    @Test
    fun `Pusatfilm iterates every matching iframe`() {
        val loadLinksSource = source("PusatfilmProvider.kt")
            .substringAfter("override suspend fun loadLinks")
        val allIframeIteration = Regex(
            """(?s)document\s*\.\s*select\s*\(\s*"[^"]*iframe[^"]*"\s*\)(?:(?!selectFirst)[\s\S])*?\.\s*forEach\s*\{"""
        )

        assertTrue(
            allIframeIteration.containsMatchIn(loadLinksSource),
            "PusatfilmProvider should iterate the complete iframe selection"
        )
        assertFalse(
            Regex("""\bselectFirst\s*\(""").containsMatchIn(loadLinksSource),
            "PusatfilmProvider loadLinks should not stop at the first iframe"
        )
    }
}
