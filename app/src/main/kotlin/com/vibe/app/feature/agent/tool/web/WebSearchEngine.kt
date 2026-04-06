package com.vibe.app.feature.agent.tool.web

interface WebSearchEngine {
    val name: String
    fun buildSearchUrl(query: String): String
    fun parseResults(html: String): List<SearchResult>
}
