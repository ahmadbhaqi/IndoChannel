package com.example

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class ProviderDomainTest {
    @Test
    fun `idlix uses requested active domain`() {
        val source = listOf(
            File("src/main/kotlin/com/example/IdlixProvider.kt"),
            File("IndoProvider/src/main/kotlin/com/example/IdlixProvider.kt")
        ).first { it.exists() }.readText()

        assertTrue(
            source.contains("""override var mainUrl = "https://z2.idlixku.com""""),
            "IdlixProvider mainUrl should use https://z2.idlixku.com"
        )
    }
}
