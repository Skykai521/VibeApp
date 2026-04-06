package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreatePlanTool @Inject constructor() : AgentTool {

    override val definition = AgentToolDefinition(
        name = "create_plan",
        description = "Create a structured execution plan before starting complex tasks. " +
            "Use this when the user's request involves multiple files, multiple steps, or complex logic. " +
            "Each step should be a concrete, actionable task description.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("summary", stringProp("Brief description of the overall goal"))
                put("steps", objectArrayProp(
                    description = "Ordered list of steps to execute",
                    properties = buildJsonObject {
                        put("description", stringProp("Concrete, actionable step description"))
                    },
                    required = requiredFields("description"),
                ))
            })
            put("required", requiredFields("summary", "steps"))
        },
    )

    override suspend fun execute(
        call: AgentToolCall,
        context: AgentToolContext,
    ): AgentToolResult {
        val summary = call.arguments.requireString("summary")
        val stepsArray = call.arguments.jsonObject["steps"]?.jsonArray
            ?: return call.errorResult("Missing required field: steps")

        if (stepsArray.isEmpty()) {
            return call.errorResult("Plan must have at least one step")
        }

        val steps = stepsArray.mapIndexed { index, element ->
            val description = element.jsonObject["description"]?.jsonPrimitive?.content
                ?: return call.errorResult("Step ${index + 1} missing description")
            mapOf("id" to (index + 1).toString(), "description" to description, "status" to "PENDING")
        }

        return call.result(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("summary", JsonPrimitive(summary))
            put("total_steps", JsonPrimitive(steps.size))
            put("steps", buildJsonArray {
                steps.forEach { step ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(step["id"]!!.toInt()))
                        put("description", JsonPrimitive(step["description"]!!))
                        put("status", JsonPrimitive(step["status"]!!))
                    })
                }
            })
            put("hint", JsonPrimitive(
                "Plan created with ${steps.size} steps. Execute each step sequentially, " +
                    "calling update_plan_step as you complete each one."
            ))
        })
    }
}

@Singleton
class UpdatePlanStepTool @Inject constructor() : AgentTool {

    override val definition = AgentToolDefinition(
        name = "update_plan_step",
        description = "Update the status of a plan step after attempting it. " +
            "Call this after completing or failing each step in the plan.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("step_id", intProp("The 1-based ID of the step to update"))
                put("status", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New status: completed, failed, or skipped"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("completed"))
                        add(JsonPrimitive("failed"))
                        add(JsonPrimitive("skipped"))
                    })
                })
                put("notes", stringProp("Optional notes about the result or reason for failure"))
            })
            put("required", requiredFields("step_id", "status"))
        },
    )

    override suspend fun execute(
        call: AgentToolCall,
        context: AgentToolContext,
    ): AgentToolResult {
        val stepId = call.arguments.optionalInt("step_id", default = -1)
        if (stepId < 1) {
            return call.errorResult("Invalid step_id: must be a positive integer")
        }

        val statusStr = call.arguments.requireString("status")
        val status = when (statusStr.lowercase()) {
            "completed" -> "COMPLETED"
            "failed" -> "FAILED"
            "skipped" -> "SKIPPED"
            else -> return call.errorResult("Invalid status: $statusStr. Must be completed, failed, or skipped.")
        }

        val notes = call.arguments.optionalString("notes")

        return call.result(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("step_id", JsonPrimitive(stepId))
            put("status", JsonPrimitive(status))
            notes?.let { put("notes", JsonPrimitive(it)) }
            put("hint", JsonPrimitive("Step $stepId updated to $status. Continue with the next pending step."))
        })
    }
}
