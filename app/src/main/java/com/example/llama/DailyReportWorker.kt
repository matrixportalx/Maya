package tr.maya

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.arm.aichat.InferenceEngine
import com.arm.aichat.internal.InferenceEngineImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyReportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG           = "DailyReportWorker"
        private const val NOTIF_CHANNEL = "maya_daily_report"
        const val PREFS_NAME  = "maya_daily_report"

        // Rapor modeli için global SharedPreferences anahtarları (llama_prefs içinde)
        const val KEY_REPORT_MODEL_ENTRY    = "report_model_entry"
        const val KEY_REPORT_MODEL_TEMPLATE = "report_model_template"
        const val KEY_REPORT_MODEL_NO_THINK = "report_model_no_think"

        // Profil başına benzersiz bildirim ID'si
        fun notifId(pid: String)    = (pid.hashCode() and 0x7FFF) + 20000
        fun progressId(pid: String) = (pid.hashCode() and 0x7FFF) + 30000

        // SharedPreferences anahtar yardımcıları (profil bazlı)
        fun keyDate(pid: String)    = "report_date_$pid"
        fun keySummary(pid: String) = "final_summary_$pid"
        fun keyPending(pid: String) = "pending_report_$pid"

        // Geriye dönük uyumluluk
        const val KEY_SUMMARY = "final_summary"
        const val KEY_PENDING = "pending_report"
        const val KEY_DATE    = "report_date"

        private const val MAX_ARTICLE_CHARS = 1500
        private const val MAX_DESC_CHARS    = 400

        // Şablon index → formatlama mantığı için isimler (bilgi amaçlı)
        // Index 7 = Gemma 4
        val TEMPLATE_NAMES = arrayOf(
            "Otomatik (GGUF'tan)",
            "Aya / Command-R",
            "ChatML",
            "Gemma 3",
            "Llama 3",
            "Granite",
            "Özel Şablon",
            "Gemma 4"
        )
    }

    // Profil bilgileri — doWork() başında yüklenir
    private var profileId   = "default"
    private var profileName = "Günlük Rapor"

    override suspend fun doWork(): Result {
        val pid = inputData.getString("profile_id")
        val prefs   = applicationContext.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        val profile = if (pid != null) ReportProfile.loadAll(applicationContext).find { it.id == pid } else null
        profileId   = profile?.id   ?: pid ?: "default"
        profileName = profile?.name ?: "Günlük Rapor"

        Log.i(TAG, "Worker başladı — profil: '$profileName' ($profileId)")
        createNotificationChannel()
        setForeground(buildProgressForeground("📡 Haberler toplanıyor…"))

        return try {
            val topics = profile?.topics?.filter { it.isNotEmpty() }
                ?: (prefs.getString("daily_report_topics", "") ?: "")
                    .split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { listOf("Güncel haberler") }
            val engine = prefs.getString("web_search_engine", "duckduckgo") ?: "duckduckgo"
            val brave  = prefs.getString("brave_api_key", "") ?: ""
            val searx  = prefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be"
            val count  = prefs.getInt("web_search_result_count", 5).coerceIn(1, 10)
            val fetchFullArticle = prefs.getBoolean("web_page_fetch_enabled", true)
            val date   = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("tr")).format(Date())

            // ── 1. Veri toplama ───────────────────────────────────────────────
            val sb = StringBuilder()
            for (topic in topics) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (topic.startsWith("http://") || topic.startsWith("https://"))
                            fetchRss(topic, count, fetchFullArticle)
                        else
                            search(topic, engine, brave, searx, count)
                    } catch (e: Exception) {
                        Log.w(TAG, "Veri toplama hatası ($topic): ${e.message}"); ""
                    }
                }
                if (result.isNotBlank()) {
                    sb.appendLine("## $topic")
                    sb.appendLine(result)
                    sb.appendLine()
                }
            }

            val rawData = sb.toString().trim()
            Log.i(TAG, "Veri toplandı: ${rawData.length} karakter")
            if (rawData.isBlank()) { Log.w(TAG, "Hiç veri toplanamadı"); return Result.failure() }

            // ── 2. Model ile özetleme ─────────────────────────────────────────
            setForeground(buildProgressForeground("🧠 Model yükleniyor…"))
            val customPrompt = profile?.summaryPrompt?.trim() ?: ""
            val summary = tryGenerateSummary(rawData, prefs, customPrompt)

            // ── 3. Kaydet ve bildir ───────────────────────────────────────────
            val reportPrefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (summary.isNotEmpty()) {
                Log.i(TAG, "Özet üretildi: ${summary.length} karakter")
                reportPrefs.edit()
                    .putString(keySummary(profileId), summary)
                    .putString(keyDate(profileId),    date)
                    .putString(keyPending(profileId), rawData.take(10000))
                    .putString("last_profile_id",     profileId)
                    .putBoolean("report_needs_display", true)
                    .apply()
                sendNotification(date, summaryReady = true, preview = summary.take(100))
            } else {
                Log.w(TAG, "Özet üretilemedi, ham veri kaydedildi")
                reportPrefs.edit()
                    .putString(keyPending(profileId), rawData.take(10000))
                    .putString(keyDate(profileId),    date)
                    .remove(keySummary(profileId))
                    .putString("last_profile_id",     profileId)
                    .putBoolean("report_needs_display", true)
                    .apply()
                sendNotification(date, summaryReady = false, preview = null)
            }

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Worker hatası: ${e.message}", e)
            applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("last_worker_error", e.message).apply()
            Result.failure()
        }
    }

    // ── Arka plan model yükleme + özetleme ────────────────────────────────────

    private suspend fun tryGenerateSummary(
        rawData: String,
        prefs: android.content.SharedPreferences,
        customPrompt: String = ""
    ): String {
        val reportModelEntry = prefs.getString(KEY_REPORT_MODEL_ENTRY, null)
        val lastEntry = when {
            !reportModelEntry.isNullOrEmpty() -> {
                Log.i(TAG, "Rapor modeli kullanılıyor: $reportModelEntry")
                reportModelEntry
            }
            else -> {
                val fallback = prefs.getString("last_loaded_model", null)
                if (fallback != null) {
                    Log.i(TAG, "Rapor modeli ayarlanmamış, son yüklü model kullanılıyor: $fallback")
                }
                fallback
            }
        } ?: run {
            Log.w(TAG, "Kullanılabilir model bulunamadı, özet atlandı")
            return ""
        }

        val modelPath = resolveModelCachePath(lastEntry) ?: run {
            Log.w(TAG, "Model önbellekte yok: $lastEntry"); return ""
        }

        val reportTemplate = prefs.getInt(KEY_REPORT_MODEL_TEMPLATE, -1)
            .let { if (it < 0) null else it }
        val reportNoThink  = prefs.getBoolean(KEY_REPORT_MODEL_NO_THINK, false)

        Log.i(TAG, "Model dosyası: $modelPath | şablon: ${reportTemplate ?: "model ayarı"} | no_think: $reportNoThink")

        return try {
            val eng = InferenceEngineImpl.getInstance(applicationContext)

            var waited = 0
            while (eng.state.value is InferenceEngine.State.Uninitialized ||
                   eng.state.value is InferenceEngine.State.Initializing) {
                if (waited++ > 100) { Log.e(TAG, "Engine init zaman aşımı"); return "" }
                delay(200)
            }

            val st = eng.state.value
            if (st is InferenceEngine.State.Generating ||
                st is InferenceEngine.State.ProcessingUserPrompt ||
                st is InferenceEngine.State.ProcessingSystemPrompt) {
                Log.w(TAG, "Engine meşgul, özet atlandı"); return ""
            }

            if (st is InferenceEngine.State.ModelReady || st is InferenceEngine.State.Error) {
                eng.cleanUp()
                waited = 0
                while (eng.state.value !is InferenceEngine.State.Initialized) {
                    if (waited++ > 50) break; delay(200)
                }
            }

            if (eng.state.value !is InferenceEngine.State.Initialized) {
                Log.e(TAG, "Engine Initialized değil: ${eng.state.value}"); return ""
            }

            eng.applySettings(
                contextSize   = prefs.getInt("report_context_size", 8192).coerceIn(2048, 32768),
                temperature   = prefs.getFloat("temperature", 0.7f),
                topP          = prefs.getFloat("top_p", 0.95f),
                topK          = prefs.getInt("top_k", 40),
                flashAttnMode = prefs.getInt("flash_attn_mode", 1),
                useMmap       = prefs.getBoolean("use_mmap", true),
                useMlock      = prefs.getBoolean("use_mlock", false)
            )

            eng.loadModel(modelPath)
            waited = 0
            while (eng.state.value !is InferenceEngine.State.ModelReady) {
                if (waited++ > 300) { Log.e(TAG, "Model yükleme zaman aşımı"); return "" }
                delay(200)
            }
            Log.i(TAG, "Model hazır, özet üretimi başlıyor")
            setForeground(buildProgressForeground("✍️ Özet oluşturuluyor…"))

            val resultSb = StringBuilder()
            val impl = eng as? com.arm.aichat.internal.InferenceEngineImpl
            if (impl != null && reportTemplate != null) {
                val prompt = buildBypassSummaryPrompt(rawData, customPrompt, reportNoThink, reportTemplate)
                Log.i(TAG, "Bypass path: template=$reportTemplate prompt=${prompt.length} karakter")
                impl.sendBypassPrompt(prompt, 2048).collect { resultSb.append(it) }
            } else {
                val baseContent = buildString {
                    if (customPrompt.isNotEmpty()) {
                        append(customPrompt)
                    } else {
                        append("Aşağıdaki haberlerin TAMAMINI oku ve Türkçe olarak madde madde özetle.")
                        appendLine()
                        append("Her madde: **Başlık:** 1-2 cümle açıklama.")
                        appendLine()
                        append("Tüm haberleri özetle, hiçbirini atlama.")
                        appendLine()
                        append("Sadece maddeleri yaz. Giriş, kapanış veya 'Son:' bölümü ekleme.")
                    }
                    appendLine()
                    appendLine()
                    appendLine("=== HABERLER ===")
                    appendLine(rawData.take(15000))
                    append("=== SON ===")
                    if (reportNoThink) {
                        appendLine()
                        append("/no_think")
                    }
                }
                Log.i(TAG, "Jinja path: template=model-default prompt=${baseContent.length} karakter")
                eng.sendUserPrompt(baseContent, 2048).collect { resultSb.append(it) }
            }
            val result = resultSb.toString()
                .replace("<|END_RESPONSE|>", "")
                .replace("<|end_of_text|>", "")
                .replace("<|im_end|>", "")
                .replace("<|eot_id|>", "")
                .replace("<end_of_turn>", "")
                .replace("<|endoftext|>", "")
                // Gemma 4 stop tokenları
                .replace("<turn|>", "")
                .replace("<|turn>", "")
                // Gemma 4 think channel — içeriği koru, sadece marker'ları sil
                .replace("<|channel>", "")
                .replace("<channel|>", "")
                .replace(Regex("<\\|[^|]+\\|>"), "")
                // Qwen3 <think> bloğunu temizle
                .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                .trim()
            Log.i(TAG, "Özet: ${result.length} karakter")

            try {
                if (eng.state.value is InferenceEngine.State.ModelReady) {
                    eng.cleanUp(); Log.i(TAG, "Model serbest bırakıldı")
                }
            } catch (e: Exception) { Log.w(TAG, "Temizleme uyarısı: ${e.message}") }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Özet üretim hatası: ${e.message}", e); ""
        }
    }

    private fun resolveModelCachePath(entry: String): String? {
        return if (entry.startsWith("uri:")) {
            val raw = entry.removePrefix("uri:")
            val fileName = raw.substringAfterLast("%2F").substringAfterLast("/")
                .let { if (it.isBlank()) raw.substringAfterLast("/") else it }
            listOfNotNull(
                applicationContext.getExternalFilesDir("models")?.let { File(it, "model_$fileName") },
                applicationContext.externalCacheDir?.let { File(it, "model_$fileName") },
                File(applicationContext.cacheDir, "model_$fileName")
            ).firstOrNull { it.exists() && it.length() > 0 }?.absolutePath
        } else {
            File(entry).takeIf { it.exists() }?.absolutePath
        }
    }

    /**
     * Bypass path için tam formatlanmış özet promptu oluşturur.
     * templateIndex=7 → Gemma 4 formatı.
     * noThink=true ise think token eklenmez.
     */
    private fun buildBypassSummaryPrompt(
        rawData: String,
        customPrompt: String,
        noThink: Boolean,
        templateIndex: Int?
    ): String {
        val baseContent = buildString {
            if (customPrompt.isNotEmpty()) {
                append(customPrompt)
            } else {
                append("Aşağıdaki haberlerin TAMAMINI oku ve Türkçe olarak madde madde özetle.")
                appendLine()
                append("Her madde: **Başlık:** 1-2 cümle açıklama.")
                appendLine()
                append("Tüm haberleri özetle, hiçbirini atlama.")
                appendLine()
                append("Sadece maddeleri yaz. Giriş, kapanış veya \'Son:\' bölümü ekleme.")
            }
            appendLine()
            appendLine()
            appendLine("=== HABERLER ===")
            appendLine(rawData.take(15000))
            append("=== SON ===")
        }
        val noThinkSuffix = if (noThink) "\n/no_think" else ""

        return when (templateIndex) {
            3    -> // Gemma 3
                "<bos><start_of_turn>user\n$baseContent$noThinkSuffix<end_of_turn>\n<start_of_turn>model\n"
            4    -> // Llama 3
                "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$baseContent$noThinkSuffix<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            1    -> // Aya / Command-R
                "<BOS_TOKEN><|START_OF_TURN_TOKEN|><|USER_TOKEN|>$baseContent$noThinkSuffix<|END_OF_TURN_TOKEN|><|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>"
            5    -> // Granite
                "<|start_of_role|>user<|end_of_role|>$baseContent$noThinkSuffix<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>"
            7    -> {
                // Gemma 4 — think token system turn'de, noThink=true ise yok
                val useThink = !noThink
                buildString {
                    append("<bos>")
                    if (useThink) {
                        append("<|turn>system\n<|think|><turn|>\n")
                    }
                    append("<|turn>user\n$baseContent<turn|>\n")
                    append("<|turn>model\n")
                    if (useThink) append("<|channel>")
                }
            }
            else -> // ChatML (index 0, 2) — varsayılan
                "<|im_start|>user\n$baseContent$noThinkSuffix<|im_end|>\n<|im_start|>assistant\n"
        }
    }

    // ── RSS ───────────────────────────────────────────────────────────────────

    private fun fetchRss(url: String, count: Int, fetchFullArticle: Boolean): String {
        val xml = httpGet(url, mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
            "Accept"     to "application/rss+xml, application/xml, text/xml, */*"
        ))
        if (xml.isBlank()) return ""

        val items = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).take(count).toList()
        if (items.isEmpty()) return ""

        return items.mapIndexed { i, m ->
            val block = m.groupValues[1]
            val title = extractTag(block, "title")
            val link  = extractTag(block, "link").trim()
                .ifBlank {
                    Regex("""<link[^>]+rel=["']alternate["'][^>]+href=["']([^"']+)["']""")
                        .find(block)?.groupValues?.get(1)?.trim() ?: ""
                }
            val desc  = stripHtml(extractTag(block, "description"))
            val pub   = formatPubDate(extractTag(block, "pubDate"))

            val content: String = if (fetchFullArticle && link.startsWith("http")) {
                val fetched = fetchArticleContent(link)
                if (fetched.isNotBlank()) {
                    Log.d(TAG, "Makale çekildi (${fetched.length} kar): $link")
                    fetched.take(MAX_ARTICLE_CHARS)
                } else {
                    Log.w(TAG, "Makale fetch başarısız, description kullanılıyor: $link")
                    desc.take(MAX_DESC_CHARS)
                }
            } else {
                desc.take(MAX_DESC_CHARS)
            }

            buildString {
                append("${i + 1}. $title")
                if (pub.isNotEmpty()) append(" [$pub]")
                if (link.isNotBlank()) append("\n   🔗 $link")
                if (content.isNotEmpty()) append("\n   $content")
            }
        }.joinToString("\n\n")
    }

    private fun fetchArticleContent(pageUrl: String): String {
        return try {
            val html = httpGet(pageUrl, mapOf(
                "User-Agent"      to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Accept"          to "text/html,application/xhtml+xml"
            ))
            if (html.isBlank()) return ""

            val cleaned = html
                .replace(Regex("<script[^>]*>.*?</script>",   RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<style[^>]*>.*?</style>",     RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<nav[^>]*>.*?</nav>",         RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<header[^>]*>.*?</header>",   RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<footer[^>]*>.*?</footer>",   RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<aside[^>]*>.*?</aside>",     RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<form[^>]*>.*?</form>",       RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<iframe[^>]*>.*?</iframe>",   RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<noscript[^>]*>.*?</noscript>", RegexOption.DOT_MATCHES_ALL), " ")

            stripHtml(cleaned)
                .replace(Regex("\\s{3,}"), "  ")
                .trim()
        } catch (e: Exception) {
            Log.w(TAG, "fetchArticleContent hata ($pageUrl): ${e.message}")
            ""
        }
    }

    private fun extractTag(text: String, tag: String): String {
        val raw = Regex("""<$tag[^>]*>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: return ""
        return (Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1) ?: raw).trim()
    }

    private fun stripHtml(html: String) = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#8217;", "'").replace("&#8216;", "'")
        .replace("&#8220;", "\"").replace("&#8221;", "\"")
        .replace("&#8211;", "–").replace("&#8212;", "—")
        .replace("&#160;", " ").replace("&nbsp;", " ")
        .replace("&#8230;", "…")
        .replace(Regex("&#\\d+;"), "").replace(Regex("&[a-z]+;"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun formatPubDate(raw: String): String {
        if (raw.isEmpty()) return ""
        return try {
            SimpleDateFormat("dd MMM HH:mm", Locale("tr")).format(
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(raw) ?: return raw
            )
        } catch (e: Exception) { raw.take(16) }
    }

    // ── Web arama ─────────────────────────────────────────────────────────────

    private fun search(q: String, eng: String, brave: String, searx: String, count: Int) =
        when (eng) {
            "brave"   -> if (brave.isNotEmpty()) searchBrave(q, brave, count) else searchDDG(q, count)
            "searxng" -> searchSearx(q, searx, count)
            else      -> searchDDG(q, count)
        }

    private fun searchDDG(query: String, count: Int): String {
        val html = httpGet("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}",
            mapOf("User-Agent" to "Mozilla/5.0"))
        if (html.isEmpty()) return ""
        val titles   = Regex("""class="result__a"[^>]*>([^<]+)<""").findAll(html).map { it.groupValues[1].trim() }.take(count).toList()
        val snippets = Regex("""class="result__snippet"[^>]*>([^<]+)<""").findAll(html).map { it.groupValues[1].trim() }.take(count).toList()
        return titles.mapIndexed { i, t -> "${i+1}. $t\n   ${snippets.getOrElse(i){""}}".trimEnd() }.joinToString("\n")
    }

    private fun searchBrave(query: String, key: String, count: Int): String {
        val json = httpGet(
            "https://api.search.brave.com/res/v1/web/search?q=${URLEncoder.encode(query,"UTF-8")}&count=$count",
            mapOf("Accept" to "application/json", "X-Subscription-Token" to key))
        if (json.isEmpty()) return ""
        return try {
            val arr = org.json.JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return ""
            (0 until minOf(arr.length(), count)).joinToString("\n") { i ->
                val o = arr.getJSONObject(i); "${i+1}. ${o.optString("title")}\n   ${o.optString("description")}"
            }
        } catch (e: Exception) { "" }
    }

    private fun searchSearx(query: String, base: String, count: Int): String {
        val json = httpGet(
            "${base.trimEnd('/')}/search?q=${URLEncoder.encode(query,"UTF-8")}&format=json&categories=news",
            mapOf("User-Agent" to "Mozilla/5.0"))
        if (json.isEmpty()) return ""
        return try {
            val arr = org.json.JSONObject(json).optJSONArray("results") ?: return ""
            (0 until minOf(arr.length(), count)).joinToString("\n") { i ->
                val o = arr.getJSONObject(i); "${i+1}. ${o.optString("title")}\n   ${o.optString("content")}"
            }
        } catch (e: Exception) { "" }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun httpGet(urlStr: String, headers: Map<String, String>): String {
        return try {
            var conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            conn.requestMethod = "GET"; conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            var redirects = 0
            while (conn.responseCode in 301..308 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 20_000
                conn.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                redirects++
            }
            if (conn.responseCode != 200) { Log.w(TAG, "HTTP ${conn.responseCode} — $urlStr"); return "" }
            BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        } catch (e: Exception) { Log.w(TAG, "HTTP hata: ${e.message}"); "" }
    }

    // ── Bildirimler ───────────────────────────────────────────────────────────

    private fun buildProgressForeground(status: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📰 $profileName Hazırlanıyor")
            .setContentText(status)
            .setProgress(0, 0, true)
            .setOngoing(true).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(progressId(profileId), notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(progressId(profileId), notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL, "Günlük Rapor", NotificationManager.IMPORTANCE_DEFAULT)
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun sendNotification(date: String, summaryReady: Boolean, preview: String?) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("daily_report_ready", true)
            putExtra("report_profile_id", profileId)
        }
        val pi = PendingIntent.getActivity(applicationContext, notifId(profileId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = if (summaryReady && preview != null)
            preview.let { if (it.length > 80) it.take(80) + "…" else it }
        else "$date — Özet için uygulamayı açın"
        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (summaryReady) "📰 $profileName Hazır" else "📰 $profileName — Ham Veri")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).build()
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notifId(profileId), notif)
    }
}
