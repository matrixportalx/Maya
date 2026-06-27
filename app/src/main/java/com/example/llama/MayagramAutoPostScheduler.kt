package tr.maya

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import java.util.Calendar

/**
 * Mayagram otomatik (zamanlı) karakter paylaşımı için sabit aralıklı AlarmManager
 * yönetimi. DailyReportScheduler ile aynı desen — exact alarm + her tetiklemede
 * kendini bir sonraki tur için yeniden kurar (AlarmManager.setRepeating yerine
 * setExactAndAllowWhileIdle tercih edildi: Doze modunda daha güvenilir tetiklenir).
 *
 * Ayarlar SharedPreferences'ta "llama_prefs" altında saklanır:
 *   mayagram_autopost_enabled   (Boolean)  — özellik genel olarak açık mı
 *   mayagram_autopost_interval_hours (Int) — kaç saatte bir tetiklensin (varsayılan 3)
 */
object MayagramAutoPostScheduler {

    private const val TAG = "MayagramAutoPostScheduler"
    const val ACTION_AUTO_POST = "tr.maya.MAYAGRAM_AUTO_POST_ALARM"
    const val PREFS_NAME       = "llama_prefs"
    const val KEY_ENABLED      = "mayagram_autopost_enabled"
    const val KEY_INTERVAL_H   = "mayagram_autopost_interval_hours"

    const val DEFAULT_INTERVAL_HOURS = 3
    const val MIN_INTERVAL_HOURS     = 1
    const val MAX_INTERVAL_HOURS     = 24

    private const val REQUEST_CODE = 70001

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun getIntervalHours(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_H, DEFAULT_INTERVAL_HOURS)
            .coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)

    fun setEnabled(context: Context, enabled: Boolean, intervalHours: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_INTERVAL_H, intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS))
            .apply()
        if (enabled) schedule(context) else cancel(context)
    }

    /** Bir sonraki tetiklemeyi şimdiden [interval] saat sonrasına kurar. */
    fun schedule(context: Context) {
        val intervalHours = getIntervalHours(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, intervalHours)
        }.timeInMillis

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, buildPendingIntent(context))

        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        Log.i(TAG, "Otomatik paylaşım alarmı kuruldu: ${fmt.format(java.util.Date(triggerAt))} (her $intervalHours saat)")
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
