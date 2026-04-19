package com.vibe.app.feature.agent.loop

private val THOUGHTS_TOOL_CALL_REGEX = Regex("""\[Tool]\s+(\S+)""")
private val THOUGHTS_TOOL_RESULT_REGEX = Regex("""\[Tool Result]\s+(\S+):\s*(ok|error|fail)""")
private val THOUGHTS_PLAN_REGEX = Regex("""\[Plan]\s+Created:\s+(.+)""")

private val REPEATABLE_TOOLS = setOf(
    "read_project_file",
    "list_project_files",
    "grep_project_files",
    "run_build_pipeline",
    "launch_app",
    "inspect_ui",
    "interact_ui",
    "close_app",
    "read_runtime_log",
    "fix_crash_guide",
    "web_search",
    "fetch_web_page",
    "search_icon",
)

internal fun buildTurnWorkSummary(thoughts: String): String? {
    if (thoughts.isBlank()) return null

    val toolOrder = LinkedHashMap<String, IntArray>() // name -> [ok, err]
    var planSummary: String? = null

    for (rawLine in thoughts.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        val resultMatch = THOUGHTS_TOOL_RESULT_REGEX.matchEntire(line)
        if (resultMatch != null) {
            val name = resultMatch.groupValues[1]
            val counts = toolOrder.getOrPut(name) { intArrayOf(0, 0) }
            if (resultMatch.groupValues[2] == "ok") counts[0]++ else counts[1]++
            continue
        }

        val callMatch = THOUGHTS_TOOL_CALL_REGEX.matchEntire(line)
        if (callMatch != null) {
            toolOrder.getOrPut(callMatch.groupValues[1]) { intArrayOf(0, 0) }
            continue
        }

        val planMatch = THOUGHTS_PLAN_REGEX.matchEntire(line)
        if (planMatch != null && planSummary == null) {
            planSummary = planMatch.groupValues[1]
        }
    }

    if (toolOrder.isEmpty() && planSummary == null) return null

    val (repeatableTools, nonRepeatableTools) = toolOrder.entries.partition { it.key in REPEATABLE_TOOLS }

    return buildString {
        append("[Earlier turn context]")
        planSummary?.let {
            append("\nPlan: ")
            append(it)
        }
        if (nonRepeatableTools.isNotEmpty()) {
            append("\nCompleted changes/actions: ")
            append(nonRepeatableTools.joinToString(separator = ", ") { (name, counts) -> formatToolCounts(name, counts) })
        }
        if (repeatableTools.isNotEmpty()) {
            append("\nTools used earlier: ")
            append(repeatableTools.joinToString(separator = ", ") { (name, counts) -> formatToolCounts(name, counts) })
            append("\nYou may call build/read/launch/inspect tools again whenever fresh state is needed.")
        }
    }
}

private fun formatToolCounts(name: String, counts: IntArray): String {
    val ok = counts[0]
    val err = counts[1]
    return when {
        ok > 0 && err == 0 && ok == 1 -> "$name(ok)"
        ok > 0 && err == 0 -> "$name(ok×$ok)"
        ok == 0 && err > 0 -> "$name(err×$err)"
        ok == 0 && err == 0 -> "$name(called)"
        else -> "$name(ok×$ok,err×$err)"
    }
}
