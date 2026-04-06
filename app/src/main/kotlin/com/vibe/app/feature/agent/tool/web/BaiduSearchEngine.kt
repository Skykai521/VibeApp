package com.vibe.app.feature.agent.tool.web

import org.jsoup.Jsoup
import java.net.URLEncoder

class BaiduSearchEngine : WebSearchEngine {

    override val name: String = "Baidu"

    override fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://www.baidu.com/s?wd=$encoded"
    }

    override fun parseResults(html: String): List<SearchResult> {
        val doc = Jsoup.parse(html)
        return doc.select("div.result, div.c-container").mapNotNull { element ->
            val titleEl = element.selectFirst("h3 a") ?: return@mapNotNull null
            val title = titleEl.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = titleEl.attr("href").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val snippet = element.selectFirst("span.content-right_8Zs40, div.c-abstract, div.c-span-last")?.text().orEmpty()
            SearchResult(title = title, snippet = snippet, url = url)
        }
    }
}
