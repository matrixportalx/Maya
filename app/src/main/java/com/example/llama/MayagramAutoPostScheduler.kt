package tr.maya

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import java.util.Calendar

/**
 * Mayagram otomatik (zamanlı) karakter paylaşımı için serbest dakika aralıklı
 * AlarmManager yönetimi. DailyReportScheduler ile aynı desen — exact alarm + her
 * tetiklemede kendini bir sonraki tur için yeniden kurar (AlarmManager.setRepeating
 * yerine setExactAndAllowWhileIdle tercih edildi: Doze modunda daha güvenilir
 * tetiklenir).
 *
 * v6.9: Aralık artık dakika bazında SERBEST girilebilir (örn. test için 1 dakika,
 * normal kullanım için 180 dakika gibi) — sabit saat slider'ı kaldırıldı.
 *
 * Ayarlar SharedPreferences'ta "llama_prefs" altında saklanır:
 *   mayagram_autopost_enabled          (Boolean) — özellik genel olarak açık mı
 *   mayagram_autopost_interval_minutes (Int)     — kaç dakikada bir tetiklensin (varsayılan 180)
 *   mayagram_autopost_daily_limit      (Int)     — günde en fazla kaç paylaşım (varsayılan 6, 0 = limitsiz)
 */
object MayagramAutoPostScheduler {

    private const val TAG = "MayagramAutoPostScheduler"
    const val ACTION_AUTO_POST = "tr.maya.MAYAGRAM_AUTO_POST_ALARM"
    const val PREFS_NAME       = "llama_prefs"
    const val KEY_ENABLED      = "mayagram_autopost_enabled"
    const val KEY_INTERVAL_MIN = "mayagram_autopost_interval_minutes"
    const val KEY_DAILY_LIMIT  = "mayagram_autopost_daily_limit"

    // Günlük sayaç takibi (Worker tarafından okunur/güncellenir)
    const val KEY_DAILY_COUNT      = "mayagram_autopost_daily_count"
    const val KEY_DAILY_COUNT_DATE = "mayagram_autopost_daily_count_date"

    const val DEFAULT_INTERVAL_MINUTES = 180   // 3 saat
    const val MIN_INTERVAL_MINUTES     = 1     // test amaçlı minimum 1 dakika
    const val MAX_INTERVAL_MINUTES     = 1440  // 24 saat

    const val DEFAULT_DAILY_LIMIT = 6
    const val MIN_DAILY_LIMIT     = 0          // 0 = limitsiz
    const val MAX_DAILY_LIMIT     = 150

    private const val REQUEST_CODE = 70001

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun getIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MINUTES)
            .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    fun getDailyLimit(context: Context): Int =
        prefs(context).getInt(KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT)
            .coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT)

    fun setEnabled(context: Context, enabled: Boolean, intervalMinutes: Int, dailyLimit: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_INTERVAL_MIN, intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES))
            .putInt(KEY_DAILY_LIMIT, dailyLimit.coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT))
            .apply()
        if (enabled) schedule(context) else cancel(context)
    }

    /** Bir sonraki tetiklemeyi şimdiden [interval] dakika sonrasına kurar. */
    fun schedule(context: Context) {
        val intervalMinutes = getIntervalMinutes(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = Calendar.getInstance().apply {
            add(Calendar.MINUTE, intervalMinutes)
        }.timeInMillis

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, buildPendingIntent(context))

        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        Log.i(TAG, "Otomatik paylaşım alarmı kuruldu: ${fmt.format(java.util.Date(triggerAt))} (her $intervalMinutes dakika)")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context))
        Log.i(TAG, "Otomatik paylaşım alarmı iptal edildi")
    }

    /** Uygulama açılışında veya boot sonrasında, ayar etkinse alarmı yeniden kurar. */
    fun rescheduleIfEnabled(context: Context) {
        if (isEnabled(context)) schedule(context)
    }

    // ── Günlük paylaşım sayacı ─────────────────────────────────────────────────

    /** Bugünün tarih damgasını "yyyyMMdd" formatında döner — gün değişimini tespit etmek için. */
    private fun todayStamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    /**
     * Bugün kaç paylaşım yapıldığını döner. Tarih değiştiyse (gece yarısını geçtiyse)
     * sayaç otomatik olarak 0'a döner — ayrı bir "gece yarısı resetleme" alarmı gerekmez,
     * çünkü her kontrol günü kendi içinde karşılaştırır.
     */
    fun getTodayCount(context: Context): Int {
        val p = prefs(context)
        val savedDate = p.getString(KEY_DAILY_COUNT_DATE, "")
        return if (savedDate == todayStamp()) p.getInt(KEY_DAILY_COUNT, 0) else 0
    }

    /** Günlük limite ulaşılıp ulaşılmadığını kontrol eder. Limit 0 ise (limitsiz) her zaman false döner. */
    fun isDailyLimitReached(context: Context): Boolean {
        val limit = getDailyLimit(context)
        if (limit <= 0) return false
        return getTodayCount(context) >= limit
    }

    /** Bir paylaşım başarıyla yapıldığında çağrılır — bugünkü sayacı 1 artırır (gün değiştiyse sıfırdan başlar). */
    fun incrementTodayCount(context: Context) {
        val p = prefs(context)
        val current = getTodayCount(context)  // gün değiştiyse zaten 0 döner
        p.edit()
            .putString(KEY_DAILY_COUNT_DATE, todayStamp())
            .putInt(KEY_DAILY_COUNT, current + 1)
            .apply()
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MayagramAutoPostReceiver::class.java).apply {
            action = ACTION_AUTO_POST
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
