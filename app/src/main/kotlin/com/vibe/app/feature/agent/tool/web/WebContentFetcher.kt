package com.vibe.app.feature.agent.tool.web

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class FetchedContent(
    val title: String,
    val content: String,
    val url: String,
)

@Singleton
class WebContentFetcher @Inject constructor(
    @Named("web") private val httpClient: HttpClient,
    private val webViewExtractor: WebViewContentExtractor,
) {

    /**
     * Fetch the content of [url]. Tries OkHttp/Ktor first with Readability4J extraction,
     * checks for anti-crawl responses, and falls back to WebView-based extraction.
     */
    suspend fun fetch(url: String): Result<FetchedContent> {
        // 1. Try HTTP client first
        val httpResult = tryHttpFetch(url)
        if (httpResult != null) {
            return Result.success(httpResult)
        }

        // 2. Fall back to WebView extraction
        Log.d(TAG, "HTTP fetch failed or blocked for $url, falling back to WebView")
        return webViewExtractor.extract(url).map { extraction ->
            FetchedContent(
                title = extraction.title,
                content = extraction.content,
                url = extraction.url,
            )
        }
    }

    /**
     * Try fetching via HTTP client. Returns null if the response looks like it was
     * blocked by anti-crawl mechanisms or if a non-recoverable error occurred that
     * warrants a WebView fallback.
     */
    private suspend fun tryHttpFetch(url: String): FetchedContent? {
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

                val statusCode = response.status.value

                // For hard HTTP errors that are NOT anti-crawl (e.g. 404, 500),
                // don't bother trying WebView either.
                if (!response.status.isSuccess() && statusCode != 403 && statusCode != 503) {
                    throw RuntimeException("HTTP $statusCode")
                }

                val responseContentType = response.contentType()
                if (responseContentType != null &&
                    !responseContentType.match(ContentType.Text.Html) &&
                    !responseContentType.match(ContentType.Text.Plain) &&
                    !responseContentType.match(ContentType.Application.Xml)
                ) {
                    throw RuntimeException("URL does not point to an HTML page (content-type: $responseContentType)")
                }

                val html = response.bodyAsText()

                // Check for anti-crawl responses
                if (WebConstants.isAntiCrawlResponse(statusCode, html)) {
                    Log.d(TAG, "Anti-crawl response detected for $url (status=$statusCode, bodyLen=${html.length})")
                    return@withTimeout null
                }

                val (title, content) = extractContent(url, html)
                FetchedContent(title = title, content = content, url = url)
            }
        } catch (e: RuntimeException) {
            // Hard errors (404, 500, non-HTML) — propagate without WebView fallback
            if (e.message?.startsWith("HTTP ") == true || e.message?.startsWith("URL does not") == true) {
                throw e
            }
            // Network / timeout errors — try WebView fallback
            Log.d(TAG, "HTTP fetch error for $url: ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "HTTP fetch error for $url: ${e.message}")
            null
        }
    }

    private fun extractContent(url: String, html: String): Pair<String, String> {
        // Try Readability4J first for high-quality article extraction
        try {
            val article = Readability4J(url, html).parse()
            val title = article.title.orEmpty().ifBlank { Jsoup.parse(html).title() }
            val text = article.textContent?.trim().orEmpty()
            if (text.isNotBlank()) {
                val truncated = if (text.length > WebConstants.MAX_CONTENT_LENGTH) {
                    text.take(WebConstants.MAX_CONTENT_LENGTH) + "\n... [truncated]"
                } else {
                    text
                }
                return Pair(title, truncated)
            }
        } catch (_: Exception) {
            // Fall through to Jsoup fallback
        }

        // Fallback: basic Jsoup extraction
        val doc = Jsoup.parse(html)
        val title = doc.title()
        doc.select("script, style, nav, header, footer, aside, iframe, noscript, svg").remove()
        val body = doc.body() ?: return Pair(title, "")
        var text = body.text()
        if (text.length > WebConstants.MAX_CONTENT_LENGTH) {
            text = text.take(WebConstants.MAX_CONTENT_LENGTH) + "\n... [truncated]"
        }
        return Pair(title, text)
    }

    companion object {
        private const val TAG = "WebContentFetcher"
    }
}
