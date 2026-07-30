package com.example

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

class GithubActionsRuntimeTest {
    @Test
    fun `workflows use Node 24 compatible action majors`() {
        val workflowsDirectory = findRepositoryRoot().resolve(".github/workflows")
        val workflowText = Files.newDirectoryStream(workflowsDirectory).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it) }
                .map { path -> String(Files.readAllBytes(path), Charsets.UTF_8) }
                .joinToString("\n")
        }
        val actionReferences = Regex(
            """uses:\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*)@v(\d+)\b"""
        ).findAll(workflowText)
            .groupBy(
                keySelector = { it.groupValues[1] },
                valueTransform = { it.groupValues[2].toInt() }
            )
        val minimumNode24Majors = mapOf(
            "actions/checkout" to 6,
            "actions/setup-java" to 5,
            "android-actions/setup-android" to 4,
            "actions/upload-artifact" to 6,
            "gradle/actions/setup-gradle" to 6
        )

        minimumNode24Majors.forEach { (action, minimumMajor) ->
            val majors = actionReferences[action].orEmpty()
            assertTrue(majors.isNotEmpty(), "$action is missing from the workflows")
            assertTrue(
                majors.all { it >= minimumMajor },
                "$action must use v$minimumMajor or newer, but found ${majors.sorted()}"
            )
        }
    }

    private fun findRepositoryRoot(): Path = generateSequence(
        Paths.get("").toAbsolutePath()
    ) { path -> path.parent }
        .firstOrNull { candidate ->
            Files.isDirectory(candidate.resolve(".github/workflows"))
        }
        ?: error("Could not locate .github/workflows from the test working directory")
}
