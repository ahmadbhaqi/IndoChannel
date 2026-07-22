package com.example

import kotlin.test.Test
import kotlin.test.assertFalse

class AndroidApiCompatibilityTest {
    @Test
    fun `main bytecode avoids Base64 API unavailable below Android 26`() {
        val classLoader = requireNotNull(javaClass.classLoader)
        val bytecode = requireNotNull(
            classLoader.getResourceAsStream(
                "com/example/FirestreamPlayerParser.class"
            )
        ).use { it.readBytes() }
            .toString(Charsets.ISO_8859_1)

        assertFalse(
            bytecode.contains("java/util/Base64"),
            "Firestream parser must use Base64Compat for the supported Android API range"
        )
    }
}
