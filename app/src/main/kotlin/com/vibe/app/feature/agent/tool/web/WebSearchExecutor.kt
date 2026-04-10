package com.vibe.app.feature.agent.tool.web

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchExecutor @Inject constructor(
    private val webViewExtractor: WebViewContentExtractor,
) {

    private val engines: List<WebSearchEngine> = listOf(
        BingSearchEngine(),
        BaiduSearchEngine(),
        GoogleSearchEngine(),
    )

    suspend fun search(query: String): Result<List<SearchResult>> {
        val errors = mutableListOf<String>()

        for (engine in engines) {
            try {
                val url = engine.buildSearchUrl(query)
                val htmlResult = webViewExtractor.extractRawHtml(url)
                val html = htmlResult.getOrNull()
                if (html.isNullOrBlank()) {
                    errors.add("${engine.name}: WebView returned empty HTML")
                    continue
                }
                val results = engine.parseResults(html)
                if (results.isNotEmpty()) {
                    Log.d(TAG, "${engine.name} returned ${results.size} results for: $query")
                    return Result.success(results.take(MAX_RESULTS))
                }
                errors.add("${engine.name}: no results parsed")
            } catch (e: Exception) {
                Log.w(TAG, "${engine.name} failed for query: $query", e)
                errors.add("${engine.name}: ${e.message}")
            }
        }

        return Result.failure(
            RuntimeException("All search engines failed: ${errors.joinToString("; ")}")
        )
    }

    companion object {
        private const val TAG = "WebSearchExecutor"
        private const val MAX_RESULTS = 5
    }
}
