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
        BaiduSearchEngine(),
        GoogleSearchEngine(),
    )

    suspend fun search(query: String): Result<List<SearchResult>> {
        val errors = mutableListOf<String>()

        for (engine in engines) {
            try {
                val results = withTimeout(WebConstants.TIMEOUT_MS) {
                    val url = engine.buildSearchUrl(query)
                    val response = httpClient.get(url) {
                        header(HttpHeaders.UserAgent, WebConstants.USER_AGENT)
                        header(HttpHeaders.AcceptLanguage, WebConstants.ACCEPT_LANGUAGE)
                        header(HttpHeaders.Accept, WebConstants.ACCEPT)
                        header("Sec-Fetch-Dest", WebConstants.SEC_FETCH_DEST)
                        header("Sec-Fetch-Mode", WebConstants.SEC_FETCH_MODE)
                        header("Sec-Fetch-Site", WebConstants.SEC_FETCH_SITE)
                        header("Sec-Fetch-User", WebConstants.SEC_FETCH_USER)
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
        private const val MAX_RESULTS = 5
    }
}
