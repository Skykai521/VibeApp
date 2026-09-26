package com.vibe.app.feature.agent.tool.web

interface WebSearchEngine {
    val name: String
    /** CSS selector that appears once results have rendered. */
    val resultsSelector: String
    fun buildSearchUrl(query: String): String
    fun parseResults(html: String): List<SearchResult>
}
