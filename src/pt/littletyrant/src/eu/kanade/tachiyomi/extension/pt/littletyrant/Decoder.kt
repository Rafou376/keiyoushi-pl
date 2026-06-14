package eu.kanade.tachiyomi.extension.pt.littletyrant

import keiyoushi.utils.parseAs
import org.jsoup.nodes.Document

class Decoder {
    fun extractPaths(document: Document, baseUrl: String): List<String> {
        val urlScript = document.selectFirst("script:containsData(var _proxyUrls)")?.data()
            ?: error("No image URLs")

        val match = PROXY_URLS_REGEX.find(urlScript) ?: error("Unable to parse pages")
        val urls = match.groupValues[1].parseAs<List<String>>()

        return urls.map { if (it.startsWith("http")) it else baseUrl + it }
    }

    companion object {
        private val PROXY_URLS_REGEX = Regex(
            """var _proxyUrls\s*=\s*(\[.*?\])""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
