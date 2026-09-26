package com.vibe.app.feature.agent.tool

import com.vibe.build.engine.model.BuildLogEntry
import com.vibe.build.engine.model.BuildLogLevel
import com.vibe.build.engine.model.BuildResult
import com.vibe.build.engine.model.BuildStage
import com.vibe.build.engine.model.BuildStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildResultJsonTest {

    @Test
    fun `oversized error message and log messages are truncated`() {
        val result = BuildResult(
            status = BuildStatus.FAILED,
            artifacts = emptyList(),
            logs = listOf(
                BuildLogEntry(
                    stage = BuildStage.COMPILE,
                    level = BuildLogLevel.ERROR,
                    message = "e".repeat(5_000),
                ),
            ),
            errorMessage = "x".repeat(10_000),
        )
        val json = result.toFilteredJson()
        assertEquals(2_000, json["errorMessage"]!!.jsonPrimitive.content.length)
        val firstLog = json["logs"]!!.jsonArray.first().jsonObject
        assertEquals(500, firstLog["message"]!!.jsonPrimitive.content.length)
    }
}
