package tr.maya

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

// ── Web araması kurulumu ──────────────────────────────────────────────────────

internal fun MainActivity.setupWebSearch() {
    updateWebSearchButton()
    btnWebSearch.setOnClickListener {
        webSearchMode = when (webSearchMode) {
            "off"     -> "trigger"
            "trigger" -> "always"
            else      -> "off"
        }
        getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit()
            .putString("web_search_mode", webSearchMode)
            .putBoolean("web_search_enabled", webSearchEnabled)
            .apply()
        updateWebSearchButton()
        updateActiveModelSubtitle()
        val msg = when (webSearchMode) {
            "off"     -> "🌐 Web araması kapalı"
            "trigger" -> "🌐 Tetikleyici mod — anahtar kelimeyle ara"
            else      -> "🌐 Her mesajda ara"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

internal fun MainActivity.updateWebSearchButton() {
    btnWebSearch.alpha = when (webSearchMode) {
        "off"     -> 0.3f
        "trigger" -> 0.75f
        else      -> 1.0f
    }
    btnWebSearch.text = when (webSearchMode) {
        "trigger" -> "🔍"
        else      -> "🌐"
    }
}

// ── Tetikleyici sistemi ───────────────────────────────────────────────────────

internal fun MainActivity.getDefaultTriggers(): List<String> = listOf(
    "internette ara", "web araması yap", "araştır", "araştırabilir misin",
    "araştırıp söyler misin", "google'la", "online ara", "arama yap",
    "son haberler", "son gelişmeler", "güncel haberler", "güncel bilgi",
    "güncel durum", "son dakika", "bugün ne oldu", "bu hafta ne oldu",
    "yeni haber", "yeni gelişme", "haberdar et", "haberleri göster",
    "neler oluyor", "gündemde ne var", "son durum nedir", "en son ne oldu",
    "haber var mı", "yeni bir şey var mı",
    "search the web", "search online", "look it up", "google it",
    "latest news", "recent news", "current news", "what's happening",
    "any updates", "breaking news"
)

internal fun MainActivity.containsTrigger(message: String): Boolean {
    val lower = message.lowercase(java.util.Locale("tr"))
    return webSearchTriggers.any { trigger ->
        lower.contains(trigger.lowercase(java.util.Locale("tr")))
    }
}

internal fun MainActivity.extractSimpleQuery(message: String): String {
    var q = message
    webSearchTriggers.sortedByDescending { it.length }.forEach { trigger ->
        q = q.replace(trigger, " ", ignoreCase = true)
    }
    q = q.replace("?", " ").replace(Regex("\\s+"), " ").trim()
    return if (q.length < 4) message.take(120) else q.take(120)
}

internal fun MainActivity.extractSmartQuery(message: String): String {
    val stopWords = setOf(
        "ve","veya","ile","bir","bu","şu","da","de","ki","mi","mı","mu","mü",
        "nasıl","neden","niçin","nerede","hangi","ne","ama","fakat","ancak",
        "sadece","bile","gibi","için","var","yok","olan","oldu","çok","daha",
        "sen","ben","biz","siz","onlar","hepsi","hiç","tüm","her","bazı",
        "the","a","an","is","are","was","were","has","have","did","will",
        "would","can","could","should","about","from","what","who","where",
        "when","why","how","which","that","this","with","for","not","its","they"
    )

    var cleaned = message
    webSearchTriggers.sortedByDescending { it.length }.forEach { trigger ->
        cleaned = cleaned.replace(trigger, " ", ignoreCase = true)
    }

    val entityPattern = Regex("[A-ZÇĞİÖŞÜ][a-zçğışöüA-ZÇĞİÖŞÜ0-9]+(?:[\\s-][A-ZÇĞİÖŞÜ][a-zçğışöüA-ZÇĞİÖŞÜ0-9]+)*")
    val entities = entityPattern.findAll(cleaned)
        .map { it.value.trim() }
        .filter { it.length > 1 }
        .distinct()
        .take(6)
        .toList()

    val words = cleaned
        .replace(Regex("[^a-zA-ZçğışöüÇĞİÖŞÜ0-9\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { w -> w.length > 3 && w.lowercase() !in stopWords }
        .distinct()

    val queryParts = (entities + words.filter { w ->
        entities.none { e -> e.contains(w, ignoreCase = true) }
    }).distinct()

    val query = queryParts.joinToString(" ").take(100).trim()
    return if (query.length < 4) extractSimpleQuery(message) else query
}

// ── URL tespiti ve okuma ──────────────────────────────────────────────────────

/**
 * Mesaj metninden http/https URL'lerini çıkarır.
 * Maksimum [limit] adet URL döner.
 */
internal fun extractUrlsFromMessage(message: String, limit: Int = 3): List<String> {
    val urlPattern = Regex("""https?://[^\s\)\]\>\"\']+""")
    return urlPattern.findAll(message)
        .map { it.value.trimEnd('.', ',', ';', '!', '?') }
        .filter { it.length > 10 }
        .distinct()
        .take(limit)
        .toList()
}

/**
 * Mesajdaki URL'leri çekip içeriklerini birleştirir.
 * Her URL için [charLimit] kadar karakter alınır.
 * URL fetch kapalıysa boş string döner.
 */
internal suspend fun MainActivity.fetchUrlsFromMessage(
    message: String
): String {
    if (!urlFetchEnabled) return ""

    val urls = extractUrlsFromMessage(message, limit = 3)
    if (urls.isEmpty()) return ""

    MainActivity.log("URLFetch", "${urls.size} URL tespit edildi: ${urls.joinToString(", ")}")

    val results = StringBuilder()
    results.appendLine("=== SAYFA İÇERİKLERİ ===")

    var fetchedCount = 0
    for (url in urls) {
        val content = fetchPageContent(url, charLimit = urlFetchCharLimit)
        if (content.isNotBlank()) {
            fetchedCount++
            results.appendLine("--- Kaynak: $url ---")
            results.appendLine(content)
            results.appendLine()
        } else {
            MainActivity.log("URLFetch", "İçerik alınamadı: $url")
        }
    }

    results.appendLine("=== SAYFA İÇERİKLERİ SONU ===")

    return if (fetchedCount > 0) {
        MainActivity.log("URLFetch", "$fetchedCount URL başarıyla çekildi, toplam ${results.length} karakter")
        results.toString().trim()
    } else {
        ""
    }
}

// ── Arama motorları ───────────────────────────────────────────────────────────

internal suspend fun MainActivity.performWebSearch(query: String): String {
    return try {
        when (webSearchEngine) {
            "brave"   -> performBraveSearch(query)
            "searxng" -> performSearxngSearch(query)
            else      -> performDuckDuckGoSearch(query)
        }
    } catch (e: Exception) {
        MainActivity.log("Maya", "Web arama hatası (${webSearchEngine}): ${e.message}")
        ""
    }
}

internal suspend fun MainActivity.performDuckDuckGoSearch(query: String): String = withContext(Dispatchers.IO) {
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
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            MainActivity.log("Maya", "DuckDuckGo HTML yanıt kodu: ${conn.responseCode}")
            return@withContext ""
        }
        val html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()

        val titlePattern   = Regex("""class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetPattern = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val urlPattern     = Regex("""class="result__url"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val titleMatches   = titlePattern.findAll(html).toList()
        val snippetMatches = snippetPattern.findAll(html).toList()
        val urlMatches     = urlPattern.findAll(html).toList()

        val results = StringBuilder()
        val limit   = minOf(titleMatches.size, webSearchResultCount)
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

        if (titleMatches.isEmpty()) MainActivity.log("Maya", "DuckDuckGo HTML parse: sonuç bulunamadı (${html.length} karakter alındı)")

        if (webPageFetchEnabled && fetchUrls.isNotEmpty()) {
            results.appendLine()
            results.appendLine("--- SAYFA İÇERİKLERİ ---")
            fetchUrls.forEachIndexed { i, pageUrl ->
                val content = fetchPageContent(pageUrl)
                if (content.isNotBlank()) {
                    results.appendLine("Sayfa ${i + 1} ($pageUrl):")
                    results.appendLine(content)
                    results.appendLine()
                }
            }
        }

        results.toString().trim()
    } finally {
        conn.disconnect()
    }
}

internal suspend fun MainActivity.performBraveSearch(query: String): String = withContext(Dispatchers.IO) {
    if (braveApiKey.isBlank()) return@withContext ""
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = URL("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$webSearchResultCount")
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("X-Subscription-Token", braveApiKey)
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            MainActivity.log("Maya", "Brave API yanıt kodu: ${conn.responseCode}")
            return@withContext ""
        }
        val inputStream = if ("gzip" == conn.contentEncoding)
            java.util.zip.GZIPInputStream(conn.inputStream)
        else conn.inputStream
        val response = inputStream.bufferedReader(Charsets.UTF_8).readText()
        val json = JSONObject(response)
        val webResults = json.optJSONObject("web")?.optJSONArray("results")
            ?: return@withContext ""

        val results  = StringBuilder()
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

        if (webPageFetchEnabled && fetchUrls.isNotEmpty()) {
            results.appendLine()
            results.appendLine("--- SAYFA İÇERİKLERİ (GÜNCEL) ---")
            fetchUrls.forEachIndexed { i, pageUrl ->
                val content = fetchPageContent(pageUrl)
                if (content.isNotBlank()) {
                    results.appendLine("Sayfa ${i + 1} ($pageUrl):")
                    results.appendLine(content)
                    results.appendLine()
                }
            }
        }

        results.toString().trim()
    } finally {
        conn.disconnect()
    }
}

internal suspend fun MainActivity.performSearxngSearch(query: String): String = withContext(Dispatchers.IO) {
    if (searxngUrl.isBlank()) return@withContext ""
    val base    = searxngUrl.trimEnd('/')
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = URL("$base/search?q=$encoded&format=json&language=auto")
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "Maya/5.1 Android")
    conn.connectTimeout = 8000
    conn.readTimeout = 10000
    try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            MainActivity.log("Maya", "SearXNG yanıt kodu: ${conn.responseCode}")
            return@withContext ""
        }
        val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val json = JSONObject(response)
        val items = json.optJSONArray("results") ?: return@withContext ""

        val results   = StringBuilder()
        val fetchUrls = mutableListOf<String>()
        val limit     = minOf(items.length(), webSearchResultCount)

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

        if (webPageFetchEnabled && fetchUrls.isNotEmpty()) {
            results.appendLine()
            results.appendLine("--- SAYFA İÇERİKLERİ (GÜNCEL) ---")
            fetchUrls.forEachIndexed { i, pageUrl ->
                val content = fetchPageContent(pageUrl)
                if (content.isNotBlank()) {
                    results.appendLine("Sayfa ${i + 1} ($pageUrl):")
                    results.appendLine(content)
                    results.appendLine()
                }
            }
        }

        results.toString().trim()
    } finally {
        conn.disconnect()
    }
}

/**
 * Bir URL'yi çekip sade metin döner.
 * [charLimit] parametresi yoksa [urlFetchCharLimit] değeri kullanılır.
 * Web araması iç fetch'leri için de bu fonksiyon kullanılır (eski 2000 sabit limiti kaldırıldı).
 */
internal suspend fun MainActivity.fetchPageContent(
    pageUrl: String,
    charLimit: Int = urlFetchCharLimit
): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val conn = URL(pageUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        conn.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        conn.connectTimeout = 8000
        conn.readTimeout    = 10000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext ""
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()

            val noScript = html
                .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<style[^>]*>.*?</style>",  RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<nav[^>]*>.*?</nav>",      RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<footer[^>]*>.*?</footer>",RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<header[^>]*>.*?</header>",RegexOption.DOT_MATCHES_ALL), " ")

            val plain = noScript
                .replace(Regex("<br\\s*/?>"), "\n")
                .replace(Regex("<p[^>]*>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&nbsp;"), " ")
                .replace(Regex("&amp;"),  "&")
                .replace(Regex("&lt;"),   "<")
                .replace(Regex("&gt;"),   ">")
                .replace(Regex("&quot;"), "\"")
                .replace(Regex("&#[0-9]+;"), " ")
                .replace(Regex("\\s{3,}"), "\n")
                .trim()

            val result = plain.take(charLimit)
            MainActivity.log("Maya", "fetchPageContent: $pageUrl → ${result.length} karakter (limit: $charLimit)")
            result
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        MainActivity.log("Maya", "fetchPageContent hata ($pageUrl): ${e.message}")
        ""
    }
}
