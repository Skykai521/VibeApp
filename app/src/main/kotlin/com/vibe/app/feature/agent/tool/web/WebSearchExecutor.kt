package com.vibe.app.feature.agent.tool.web

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class WebSearchExecutor @Inject constructor(
    @Named("web") private val httpClient: HttpClient,
) {

    private val engines: List<WebSearchEngine> = listOf(
        BingSearchEngine(),
        GoogleSearchEngine(),
        BaiduSearchEngine(),
    )

    suspend fun search(query: String): Result<List<SearchResult>> {
        val errors = mutableListOf<String>()

        for (engine in engines) {
            try {
                val results = withTimeout(TIMEOUT_MS) {
                    val url = engine.buildSearchUrl(query)
                    val response = httpClient.get(url) {
                        header(HttpHeaders.UserAgent, USER_AGENT)
                        header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
                    }
                    if (!response.status.isSuccess()) {
                        throw RuntimeException("HTTP ${response.status.value} from ${engine.name}")
                    }
                    val html = response.bodyAsText()
                    engine.parseResults(html)
                }
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
        private const val TIMEOUT_MS = 10_000L
        private const val MAX_RESULTS = 5
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
