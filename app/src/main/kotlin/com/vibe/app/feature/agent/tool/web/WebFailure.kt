package com.vibe.app.feature.agent.tool.web

enum class WebFailureKind { BLOCKED, NO_RESULTS, TIMEOUT, NETWORK_ERROR }

data class EngineFailure(val engine: String, val kind: WebFailureKind, val detail: String) {
    fun describe(): String = when (kind) {
        WebFailureKind.BLOCKED -> "$engine blocked the request ($detail)"
        WebFailureKind.NO_RESULTS -> "$engine returned no parseable results"
        WebFailureKind.TIMEOUT -> "$engine timed out"
        WebFailureKind.NETWORK_ERROR -> "$engine network error ($detail)"
    }
}

class WebHttpBlockedException(val statusCode: Int) :
    RuntimeException("Main frame returned HTTP $statusCode")

class WebSearchFailedException(val failures: List<EngineFailure>) : RuntimeException(
    buildString {
        append("All search engines failed: ")
        append(failures.joinToString("; ") { it.describe() })
        append(". ")
        append(
            if (failures.any { it.kind == WebFailureKind.BLOCKED }) {
                "Search engines are rate-limiting this device right now — retry later, or proceed without web data."
            } else {
                "Consider rephrasing the query with different keywords."
            },
        )
    },
)
