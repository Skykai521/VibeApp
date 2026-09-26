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

    private val circuitBreaker = EngineCircuitBreaker()

    suspend fun search(query: String): Result<List<SearchResult>> {
        val failures = mutableListOf<EngineFailure>()

        val eligible = engines.filterNot { circuitBreaker.isOpen(it.name) }.ifEmpty { engines }
        for (engine in eligible) {
            val url = engine.buildSearchUrl(query)
            val htmlResult = webViewExtractor.extractRawHtml(url, engine.resultsSelector)
            val error = htmlResult.exceptionOrNull()
            if (error != null) {
                if (error is kotlinx.coroutines.CancellationException &&
                    error !is kotlinx.coroutines.TimeoutCancellationException
                ) throw error
                failures += when (error) {
                    is WebHttpBlockedException -> {
                        circuitBreaker.recordBlocked(engine.name)
                        EngineFailure(engine.name, WebFailureKind.BLOCKED, "HTTP ${error.statusCode}")
                    }
                    is kotlinx.coroutines.TimeoutCancellationException ->
                        EngineFailure(engine.name, WebFailureKind.TIMEOUT, "${WebConstants.WEBVIEW_TIMEOUT_MS / 1000}s")
                    else ->
                        EngineFailure(engine.name, WebFailureKind.NETWORK_ERROR, error.message ?: "unknown")
                }
                continue
            }
            val html = htmlResult.getOrNull().orEmpty()
            val results = runCatching { engine.parseResults(html) }.getOrElse { e ->
                failures += EngineFailure(engine.name, WebFailureKind.NETWORK_ERROR, "parse error: ${e.message}")
                continue
            }
            if (results.isNotEmpty()) {
                Log.d(TAG, "${engine.name} returned ${results.size} results for: $query")
                return Result.success(results.take(MAX_RESULTS))
            }
            failures += if (BlockedPageDetector.isBlockedPage(html)) {
                circuitBreaker.recordBlocked(engine.name)
                EngineFailure(engine.name, WebFailureKind.BLOCKED, "captcha/anti-bot page")
            } else {
                EngineFailure(engine.name, WebFailureKind.NO_RESULTS, "no results parsed")
            }
        }

        return Result.failure(WebSearchFailedException(failures))
    }

    companion object {
        private const val TAG = "WebSearchExecutor"
        private const val MAX_RESULTS = 5
    }
}
