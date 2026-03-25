package tr.maya

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * AlarmManager tarafından tetiklenen BroadcastReceiver.
 *
 * İki durumda çalışır:
 *   1. Belirlenen saatte alarm tetiklendiğinde (ACTION_DAILY_REPORT)
 *      Intent extra'sında profileId taşır → Worker'a iletilir.
 *   2. Telefon yeniden başladığında (BOOT_COMPLETED) — tüm alarmlar silindiği için yeniden kurar.
 */
class DailyReportAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyReportAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            DailyReportScheduler.ACTION_DAILY_REPORT -> {
                val profileId = intent.getStringExtra(DailyReportScheduler.EXTRA_PROFILE_ID)
                if (profileId == null) {
                    Log.e(TAG, "profileId eksik, işlem iptal edildi"); return
                }
                Log.i(TAG, "Alarm tetiklendi: profileId=$profileId")

                // Worker'ı başlat — profileId'yi input data olarak ilet
                val inputData = Data.Builder()
                    .putString("profile_id", profileId)
                    .build()
                val request = OneTimeWorkRequestBuilder<DailyReportWorker>()
                    .setInputData(inputData)
                    .build()
                WorkManager.getInstance(context).enqueue(request)

                // Ertesi gün için bu profilin alarmını yeniden kur
                val profile = ReportProfile.loadAll(context).find { it.id == profileId }
                if (profile != null && profile.enabled) {
                    DailyReportScheduler.schedule(context, profile)
                    Log.i(TAG, "Ertesi gün alarmı kuruldu: '${profile.name}' ${profile.hour}:${"%02d".format(profile.minute)}")
                } else {
                    Log.w(TAG, "Profil bulunamadı veya devre dışı, ertesi gün alarmı kurulmadı: $profileId")
                }
            }

            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot tamamlandı, tüm alarmlar yenileniyor")
                ReportProfile.migrateFromLegacy(context)  // güvenli: zaten geçirildiyse atlar
                DailyReportScheduler.rescheduleAll(context)
            }
        }
    }
}
