package tr.maya

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * AlarmManager tarafından tetiklenen BroadcastReceiver — Mayagram otomatik paylaşımı.
 *
 * İki durumda çalışır:
 *   1. Alarm tetiklendiğinde (ACTION_AUTO_POST) → Worker'ı başlatır, ardından
 *      bir sonraki tur için alarmı yeniden kurar.
 *   2. Telefon yeniden başladığında (BOOT_COMPLETED) — tüm alarmlar silindiği
 *      için, ayar etkinse alarmı yeniden kurar.
 */
class MayagramAutoPostReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MayagramAutoPostReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            MayagramAutoPostScheduler.ACTION_AUTO_POST -> {
                if (!MayagramAutoPostScheduler.isEnabled(context)) {
                    Log.i(TAG, "Otomatik paylaşım kapalı, alarm yok sayıldı")
                    return
                }
                if (MayagramAutoPostScheduler.isDailyLimitReached(context)) {
                    Log.i(TAG, "Günlük paylaşım limitine ulaşıldı, bu tur atlandı")
                    // Yine de bir sonraki tur için alarmı yeniden kur — gece yarısı geçince sayaç sıfırlanır.
                    MayagramAutoPostScheduler.schedule(context)
                    return
                }
                Log.i(TAG, "Otomatik paylaşım alarmı tetiklendi")

                val request = OneTimeWorkRequestBuilder<MayagramAutoPostWorker>().build()
                WorkManager.getInstance(context).enqueue(request)

                // Bir sonraki tur için alarmı yeniden kur
                MayagramAutoPostScheduler.schedule(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot tamamlandı, otomatik paylaşım alarmı kontrol ediliyor")
                MayagramAutoPostScheduler.rescheduleIfEnabled(context)
            }
        }
    }
}
