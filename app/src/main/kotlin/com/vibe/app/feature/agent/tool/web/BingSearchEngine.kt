package com.vibe.app.feature.agent.tool.web

import org.jsoup.Jsoup
import java.net.URLEncoder

class BingSearchEngine : WebSearchEngine {

    override val name: String = "Bing"

    override fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://cn.bing.com/search?q=$encoded&ensearch=0"
    }

    override fun parseResults(html: String): List<SearchResult> {
        val doc = Jsoup.parse(html)
        return doc.select("li.b_algo").mapNotNull { element ->
            val titleEl = element.selectFirst("h2 a") ?: return@mapNotNull null
            val title = titleEl.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = titleEl.attr("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val snippet = element.selectFirst("div.b_caption p")?.text().orEmpty()
            SearchResult(title = title, snippet = snippet, url = url)
        }
    }
}
