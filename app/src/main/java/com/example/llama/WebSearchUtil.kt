package tr.maya

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MainActivity'den bağımsız web arama yardımcı sınıfı.
 * WorkManager (DailyReportWorker) ve MainActivity tarafından ortak kullanılır.
 */
object WebSearchUtil {

    data class SearchConfig(
        val engine: String,          // "duckduckgo", "brave", "searxng"
        val braveApiKey: String,
        val searxngUrl: String,
        val resultCount: Int,
        val fetchPageContent: Boolean
    )

    fun loadConfig(context: Context): SearchConfig {
        val prefs = context.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        return SearchConfig(
            engine           = prefs.getString("web_search_engine", "duckduckgo") ?: "duckduckgo",
            braveApiKey      = prefs.getString("brave_api_key", "") ?: "",
            searxngUrl       = prefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be",
            resultCount      = prefs.getInt("web_search_result_count", 5),
            fetchPageContent = prefs.getBoolean("web_page_fetch_enabled", false)
        )
    }

    suspend fun search(query: String, config: SearchConfig): String {
        return try {
            when (config.engine) {
                "brave"   -> searchBrave(query, config)
                "searxng" -> searchSearxng(query, config)
                else      -> searchDuckDuckGo(query, config)
            }
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun searchDuckDuckGo(query: String, config: SearchConfig): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        conn.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext ""
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()

            val titlePattern   = Regex("""class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippetPattern = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val urlPattern     = Regex("""class="result__url"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

            val titleMatches   = titlePattern.findAll(html).toList()
            val snippetMatches = snippetPattern.findAll(html).toList()
            val urlMatches     = urlPattern.findAll(html).toList()

            val results = StringBuilder()
            val limit   = minOf(titleMatches.size, config.resultCount)
            val fetchUrls = mutableListOf<String>()

            for (i in 0 until limit) {
                val rawTitle = titleMatches[i].groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                val snippet  = snippetMatches.getOrNull(i)?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                val rawUrl   = urlMatches.getOrNull(i)?.groupValues?.get(1)?.trim() ?: ""
                if (rawTitle.isNotBlank()) {
                    results.appendLine("${i + 1}. $rawTitle")
                    if (snippet.isNotBlank()) results.appendLine("   ${snippet.take(350)}")
                    if (rawUrl.isNotBlank()) { results.appendLine("   Kaynak: $rawUrl"); if (fetchUrls.size < 2) fetchUrls.add(rawUrl) }
                }
            }

            if (config.fetchPageContent && fetchUrls.isNotEmpty()) {
                results.appendLine("\n--- SAYFA İÇERİKLERİ ---")
                fetchUrls.forEachIndexed { i, pageUrl ->
                    val content = fetchPage(pageUrl)
                    if (content.isNotBlank()) {
                        results.appendLine("Sayfa ${i + 1} ($pageUrl):")
                        results.appendLine(content)
                        results.appendLine()
                    }
                }
            }

            results.toString().trim()
        } finally { conn.disconnect() }
    }

    private suspend fun searchBrave(query: String, config: SearchConfig): String = withContext(Dispatchers.IO) {
        if (config.braveApiKey.isBlank()) return@withContext ""
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=${config.resultCount}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-Subscription-Token", config.braveApiKey)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext ""
            val inputStream = if ("gzip" == conn.contentEncoding)
                java.util.zip.GZIPInputStream(conn.inputStream) else conn.inputStream
            val response = inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json = JSONObject(response)
            val webResults = json.optJSONObject("web")?.optJSONArray("results") ?: return@withContext ""

            val results = StringBuilder()
            val fetchUrls = mutableListOf<String>()

            for (i in 0 until webResults.length()) {
                val item        = webResults.optJSONObject(i) ?: continue
                val title       = item.optString("title", "")
                val description = item.optString("description", "")
                val itemUrl     = item.optString("url", "")
                if (title.isNotBlank()) {
                    results.appendLine("${i + 1}. $title")
                    if (description.isNotBlank()) results.appendLine("   ${description.take(350)}")
                    if (itemUrl.isNotBlank()) { results.appendLine("   Kaynak: $itemUrl"); if (fetchUrls.size < 2) fetchUrls.add(itemUrl) }
                }
            }

            if (config.fetchPageContent && fetchUrls.isNotEmpty()) {
                results.appendLine("\n--- SAYFA İÇERİKLERİ (GÜNCEL) ---")
                fetchUrls.forEachIndexed { i, pageUrl ->
                    val content = fetchPage(pageUrl)
                    if (content.isNotBlank()) {
                        results.appendLine("Sayfa ${i + 1} ($pageUrl):")
                        results.appendLine(content); results.appendLine()
                    }
                }
            }
            results.toString().trim()
        } finally { conn.disconnect() }
    }

    private suspend fun searchSearxng(query: String, config: SearchConfig): String = withContext(Dispatchers.IO) {
        if (config.searxngUrl.isBlank()) return@withContext ""
        val base    = config.searxngUrl.trimEnd('/')
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("$base/search?q=$encoded&format=json&language=auto")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Maya/5.1 Android")
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext ""
            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json = JSONObject(response)
            val items = json.optJSONArray("results") ?: return@withContext ""

            val results   = StringBuilder()
            val fetchUrls = mutableListOf<String>()
            val limit     = minOf(items.length(), config.resultCount)

            for (i in 0 until limit) {
                val item    = items.optJSONObject(i) ?: continue
                val title   = item.optString("title", "")
                val content = item.optString("content", "")
                val itemUrl = item.optString("url", "")
                if (title.isNotBlank()) {
                    results.appendLine("${i + 1}. $title")
                    if (content.isNotBlank()) results.appendLine("   ${content.take(350)}")
                    if (itemUrl.isNotBlank()) { results.appendLine("   Kaynak: $itemUrl"); if (fetchUrls.size < 2) fetchUrls.add(itemUrl) }
                }
            }

            if (config.fetchPageContent && fetchUrls.isNotEmpty()) {
                results.appendLine("\n--- SAYFA İÇERİKLERİ (GÜNCEL) ---")
                fetchUrls.forEachIndexed { i, pageUrl ->
                    val content = fetchPage(pageUrl)
                    if (content.isNotBlank()) {
                        results.appendLine("Sayfa ${i + 1} ($pageUrl):")
                        results.appendLine(content); results.appendLine()
                    }
                }
            }
            results.toString().trim()
        } finally { conn.disconnect() }
    }

    private suspend fun fetchPage(pageUrl: String): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val conn = URL(pageUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            conn.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
            conn.connectTimeout = 7000
            conn.readTimeout    = 7000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext ""
                val html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val noScript = html
                    .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
                    .replace(Regex("<style[^>]*>.*?</style>",  RegexOption.DOT_MATCHES_ALL), " ")
                    .replace(Regex("<nav[^>]*>.*?</nav>",      RegexOption.DOT_MATCHES_ALL), " ")
                    .replace(Regex("<footer[^>]*>.*?</footer>",RegexOption.DOT_MATCHES_ALL), " ")
                val plain = noScript
                    .replace(Regex("<br\\s*/?>"), "\n")
                    .replace(Regex("<p[^>]*>"), "\n")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("&nbsp;"), " ").replace(Regex("&amp;"), "&")
                    .replace(Regex("&lt;"), "<").replace(Regex("&gt;"), ">")
                    .replace(Regex("\\s{3,}"), "\n").trim()
                plain.take(2000)
            } finally { conn.disconnect() }
        } catch (_: Exception) { "" }
    }
}
