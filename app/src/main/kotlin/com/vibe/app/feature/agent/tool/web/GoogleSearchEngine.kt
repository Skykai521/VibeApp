package com.vibe.app.feature.agent.tool.web

import org.jsoup.Jsoup
import java.net.URLEncoder

class GoogleSearchEngine : WebSearchEngine {

    override val name: String = "Google"

    override fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://www.google.com/search?q=$encoded&hl=en"
    }

    override fun parseResults(html: String): List<SearchResult> {
        val doc = Jsoup.parse(html)
        return doc.select("div.g").mapNotNull { element ->
            val titleEl = element.selectFirst("h3") ?: return@mapNotNull null
            val title = titleEl.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val linkEl = element.selectFirst("a[href^=http]") ?: return@mapNotNull null
            val url = linkEl.attr("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val snippet = element.selectFirst("div[data-sncf], div.VwiC3b, span.aCOpRe")?.text().orEmpty()
            SearchResult(title = title, snippet = snippet, url = url)
        }
    }
}
