package tr.maya

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Her ReportProfile için ayrı AlarmManager exact-alarm yönetir.
 *
 * Akış:
 *   schedule(profile) → profil ID'sini intent extra olarak taşıyan alarm kur
 *   → DailyReportAlarmReceiver tetiklenir → DailyReportWorker başlatılır
 *   → Worker tamamlanınca receiver ertesi gün için alarmı yeniden kurar
 */
object DailyReportScheduler {

    private const val TAG = "DailyReportScheduler"
    const val ACTION_DAILY_REPORT = "tr.maya.DAILY_REPORT_ALARM"
    const val EXTRA_PROFILE_ID    = "profile_id"

    /** Tek bir profil için alarm kur. Profil devre dışıysa alarmı iptal eder. */
    fun schedule(context: Context, profile: ReportProfile) {
        if (!profile.enabled) { cancel(context, profile); return }

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, profile.hour)
            set(Calendar.MINUTE,      profile.minute)
            set(Calendar.SECOND,      0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(Calendar.getInstance())) target.add(Calendar.DAY_OF_MONTH, 1)

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.timeInMillis, buildPendingIntent(context, profile))

        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        Log.i(TAG, "'${profile.name}' alarmı kuruldu: ${fmt.format(target.time)}")
    }

    /** Tek bir profil için alarmı iptal et. */
    fun cancel(context: Context, profile: ReportProfile) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context, profile))
        Log.i(TAG, "'${profile.name}' alarmı iptal edildi")
    }

    /** Tüm profilleri SharedPreferences'tan yükleyip hepsini yeniden planlar.
     *  Boot tamamlandığında ve toplu yenileme gerektiğinde kullanılır. */
    fun rescheduleAll(context: Context) {
        val profiles = ReportProfile.loadAll(context)
        profiles.forEach { if (it.enabled) schedule(context, it) else cancel(context, it) }
        Log.i(TAG, "Tüm alarmlar yenilendi (${profiles.size} profil)")
    }

    /** Geriye dönük uyumluluk — eski çağrı noktaları için. */
    fun rescheduleIfEnabled(context: Context) = rescheduleAll(context)

    // Her profil benzersiz requestCode alır → farklı PendingIntent → aynı anda birden fazla alarm çalışabilir
    private fun profileRequestCode(profile: ReportProfile) =
        (profile.id.hashCode() and 0x7FFF) + 10000

    private fun buildPendingIntent(context: Context, profile: ReportProfile): PendingIntent {
        val intent = Intent(context, DailyReportAlarmReceiver::class.java).apply {
            action = ACTION_DAILY_REPORT
            putExtra(EXTRA_PROFILE_ID, profile.id)
        }
        return PendingIntent.getBroadcast(
            context,
            profileRequestCode(profile),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
