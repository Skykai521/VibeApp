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

        // Mobile Baidu uses div.c-result with data-url for real URLs;
        // Desktop Baidu uses div.result / div.c-container.
        // Try mobile selectors first, fall back to desktop.
        val mobileResults = parseMobileResults(doc)
        if (mobileResults.isNotEmpty()) return mobileResults

        return parseDesktopResults(doc)
    }

    private fun parseMobileResults(doc: org.jsoup.nodes.Document): List<SearchResult> {
        return doc.select("div.c-result").mapNotNull { element ->
            val titleEl = element.selectFirst("a.c-title-text, div.c-title a, span.c-title-text") ?: return@mapNotNull null
            val title = titleEl.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Prefer data-log url or article[rl-link-href], fall back to href
            val url = element.attr("data-url").takeIf { it.startsWith("http") }
                ?: titleEl.attr("href").takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val snippet = element.selectFirst("span.c-gap-top-small, div.c-abstract, span.c-color-text")?.text().orEmpty()
            SearchResult(title = title, snippet = snippet, url = url)
        }
    }

    private fun parseDesktopResults(doc: org.jsoup.nodes.Document): List<SearchResult> {
        return doc.select("div.result, div.c-container").mapNotNull { element ->
            val titleEl = element.selectFirst("h3 a") ?: return@mapNotNull null
            val title = titleEl.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Baidu desktop href is a tracking redirect; mu attribute has the real URL
            val url = element.attr("mu").takeIf { it.startsWith("http") }
                ?: titleEl.attr("href").takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val snippet = element.selectFirst("span.content-right_8Zs40, div.c-abstract, div.c-span-last")?.text().orEmpty()
            SearchResult(title = title, snippet = snippet, url = url)
        }
    }
}
