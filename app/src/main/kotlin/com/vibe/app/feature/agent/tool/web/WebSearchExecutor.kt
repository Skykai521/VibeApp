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
            val url = engine.buildSearchUrl(query)

            // 1. Try OkHttp/Ktor first
            val httpResults = tryHttpSearch(engine, url)
            if (httpResults != null && httpResults.isNotEmpty()) {
                Log.d(TAG, "${engine.name} (HTTP) returned ${httpResults.size} results for: $query")
                return Result.success(httpResults.take(MAX_RESULTS))
            }

            // 2. Fall back to WebView
            val webViewResults = tryWebViewSearch(engine, url)
            if (webViewResults != null && webViewResults.isNotEmpty()) {
                Log.d(TAG, "${engine.name} (WebView) returned ${webViewResults.size} results for: $query")
                return Result.success(webViewResults.take(MAX_RESULTS))
            }

            errors.add("${engine.name}: no results parsed")
        }

        return Result.failure(
            RuntimeException("All search engines failed: ${errors.joinToString("; ")}")
        )
    }

    private suspend fun tryHttpSearch(engine: WebSearchEngine, url: String): List<SearchResult>? {
        return try {
            withTimeout(WebConstants.TIMEOUT_MS) {
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
        } catch (e: Exception) {
            Log.w(TAG, "${engine.name} HTTP search failed: ${e.message}")
            null
        }
    }

    private suspend fun tryWebViewSearch(engine: WebSearchEngine, url: String): List<SearchResult>? {
        return try {
            Log.d(TAG, "${engine.name}: falling back to WebView for search")
            val htmlResult = webViewExtractor.extractRawHtml(url)
            val html = htmlResult.getOrNull()
            if (html.isNullOrBlank()) {
                Log.w(TAG, "${engine.name}: WebView returned empty HTML")
                return null
            }
            engine.parseResults(html)
        } catch (e: Exception) {
            Log.w(TAG, "${engine.name} WebView search failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "WebSearchExecutor"
        private const val MAX_RESULTS = 5
    }
}
