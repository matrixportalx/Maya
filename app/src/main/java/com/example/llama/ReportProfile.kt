package tr.maya

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Tek bir günlük rapor profilini temsil eder.
 * Her profilin kendi adı, saati, konu/RSS listesi ve özet prompt'u vardır.
 */
data class ReportProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val topics: List<String>,   // https:// → RSS feed,  düz metin → web araması
    val summaryPrompt: String = "" // boş = varsayılan prompt kullanılır
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id",            id)
        put("name",          name)
        put("hour",          hour)
        put("minute",        minute)
        put("enabled",       enabled)
        put("topics",        JSONArray().also { arr -> topics.forEach { arr.put(it) } })
        put("summaryPrompt", summaryPrompt)
    }

    companion object {

        fun fromJson(o: JSONObject): ReportProfile {
            val topicsArr = o.optJSONArray("topics")
            val topics = if (topicsArr != null)
                (0 until topicsArr.length()).map { topicsArr.getString(it) }
            else emptyList()
            return ReportProfile(
                id            = o.optString("id",            UUID.randomUUID().toString()),
                name          = o.optString("name",          "Rapor"),
                hour          = o.optInt("hour",             8),
                minute        = o.optInt("minute",           0),
                enabled       = o.optBoolean("enabled",      true),
                topics        = topics,
                summaryPrompt = o.optString("summaryPrompt", "")
            )
        }

        fun loadAll(context: Context): MutableList<ReportProfile> {
            val prefs = context.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
            val json  = prefs.getString("report_profiles_json", null) ?: return mutableListOf()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.toMutableList()
            } catch (e: Exception) { mutableListOf() }
        }

        fun saveAll(context: Context, profiles: List<ReportProfile>) {
            val arr = JSONArray().also { a -> profiles.forEach { a.put(it.toJson()) } }
            context.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
                .edit().putString("report_profiles_json", arr.toString()).apply()
        }

        /**
         * Eski tekli rapor ayarlarını (daily_report_enabled/hour/minute/topics) yeni formata geçirir.
         * Daha önce geçirilmişse (report_profiles_json varsa) hiçbir şey yapmaz.
         */
        fun migrateFromLegacy(context: Context): Boolean {
            val prefs = context.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
            if (prefs.contains("report_profiles_json")) return false
            val enabled = prefs.getBoolean("daily_report_enabled", false)
            if (!enabled) return false
            val hour    = prefs.getInt("daily_report_hour", 8)
            val minute  = prefs.getInt("daily_report_minute", 0)
            val topics  = (prefs.getString("daily_report_topics", "") ?: "")
                .split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("Güncel haberler") }
            saveAll(context, listOf(
                ReportProfile(name = "Günlük Rapor", hour = hour, minute = minute,
                              enabled = true, topics = topics)
            ))
            return true
        }
    }
}
