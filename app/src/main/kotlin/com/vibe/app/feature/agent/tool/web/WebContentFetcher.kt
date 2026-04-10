package com.vibe.app.feature.agent.tool.web

import javax.inject.Inject
import javax.inject.Singleton

data class FetchedContent(
    val title: String,
    val content: String,
    val url: String,
)

@Singleton
class WebContentFetcher @Inject constructor(
    private val webViewExtractor: WebViewContentExtractor,
) {

    suspend fun fetch(url: String): Result<FetchedContent> {
        return webViewExtractor.extract(url).map { extraction ->
            FetchedContent(
                title = extraction.title,
                content = extraction.content,
                url = extraction.url,
            )
        }
    }
}
