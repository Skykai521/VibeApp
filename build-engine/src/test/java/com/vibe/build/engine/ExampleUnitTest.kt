package com.vibe.build.engine

import com.vibe.build.engine.model.BuildStatus
import com.vibe.build.engine.model.CompileInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun buildResultHelpers_work() {
        val success = com.vibe.build.engine.model.BuildResult.success(
            artifacts = emptyList(),
            logs = emptyList(),
        )
        assertEquals(BuildStatus.SUCCESS, success.status)
    }

    @Test
    fun compileInput_supportsWorkspaceBackedBuilds() {
        val input = CompileInput(
            projectId = "demo",
            projectName = "Demo",
            packageName = "com.example.demo",
            workingDirectory = "/tmp/demo",
            sourceFiles = mapOf("com/example/demo/MainActivity.java" to "class MainActivity {}"),
        )
        assertEquals("/tmp/demo", input.workingDirectory)
        assertTrue(input.sourceFiles.keys.first().contains("MainActivity.java"))
    }
}
