package com.example

import kotlin.test.Test
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
            "FilmapikProvider.kt" to """override var mainUrl = "https://filmapik.to"""",
            "IndofilmProvider.kt" to """override var mainUrl = "https://yuhhaber.com""""
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
}
