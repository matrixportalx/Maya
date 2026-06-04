package tr.maya

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {

    internal const val GITHUB_OWNER = "matrixportalx"
    internal const val GITHUB_REPO  = "Maya"
    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    // İndirme süreci için sessiz kanal
    private const val NOTIF_CHANNEL_DOWNLOAD  = "maya_update_download"
    // Tamamlanma / güncelleme mevcut için sesli kanal
    private const val NOTIF_CHANNEL_READY     = "maya_update_ready"

    private const val NOTIF_ID_DOWNLOAD = 9001
    private const val NOTIF_ID_READY    = 9002

    // ── Otomatik güncelleme SharedPreferences anahtarları ─────────────────────
    const val PREF_AUTO_UPDATE_ENABLED  = "auto_update_enabled"
    const val PREF_AUTO_UPDATE_INTERVAL = "auto_update_interval_ms"
    const val PREF_LAST_CHECK           = "last_update_check"

    // ── Aralık sabitleri (ms) ─────────────────────────────────────────────────
    const val INTERVAL_1H     = 1L  * 60 * 60 * 1000
    const val INTERVAL_3H     = 3L  * 60 * 60 * 1000
    const val INTERVAL_6H     = 6L  * 60 * 60 * 1000
    const val INTERVAL_12H    = 12L * 60 * 60 * 1000
    const val INTERVAL_DAILY  = 24L * 60 * 60 * 1000
    const val INTERVAL_3DAYS  = 3L  * 24 * 60 * 60 * 1000
    const val INTERVAL_WEEKLY = 7L  * 24 * 60 * 60 * 1000

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val releaseNotes: String,
        val apkUrl: String,
        val apkSize: Long,
        val apkFileName: String = ""
    )

    // ── Otomatik (sessiz) güncelleme kontrolü ─────────────────────────────────
    fun shouldCheckNow(context: Context): Boolean {
        val prefs = context.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        val enabled  = prefs.getBoolean(PREF_AUTO_UPDATE_ENABLED, true)
        if (!enabled) return false
        val interval = prefs.getLong(PREF_AUTO_UPDATE_INTERVAL, INTERVAL_DAILY)
        val last     = prefs.getLong(PREF_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= interval
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
            .edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    // ── Güncelleme kontrolü ───────────────────────────────────────────────────

    suspend fun checkForUpdate(
        context: Context,
        currentVersion: String,
        onResult: (UpdateInfo?) -> Unit
    ) {
        val info = withContext(Dispatchers.IO) {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                conn.setRequestProperty("User-Agent", "Maya/1.0")
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    MainActivity.log("Updater", "GitHub API yanıt kodu: ${conn.responseCode}")
                    return@withContext null
                }

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val tagName = json.optString("tag_name", "")
                val body    = json.optString("body", "")
                val assets  = json.optJSONArray("assets")

                var apkUrl      = ""
                var apkSize     = 0L
                var apkFileName = ""

                if (assets != null) {
                    val deviceAbis = Build.SUPPORTED_ABIS.toList()
                    MainActivity.log("Updater", "Cihaz ABI'leri: ${deviceAbis.joinToString()}")

                    data class ApkAsset(val name: String, val url: String, val size: Long)
                    val apkAssets = mutableListOf<ApkAsset>()

                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name  = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkAssets.add(ApkAsset(
                                name = name,
                                url  = asset.optString("browser_download_url", ""),
                                size = asset.optLong("size", 0L)
                            ))
                        }
                    }

                    MainActivity.log("Updater", "Bulunan APK'lar: ${apkAssets.map { it.name }}")

                    val selected = deviceAbis.firstNotNullOfOrNull { abi ->
                        apkAssets.find { asset ->
                            asset.name.contains(abi.replace("-", ""), ignoreCase = true) ||
                            asset.name.contains(abi, ignoreCase = true)
                        }
                    } ?: apkAssets.find { asset ->
                        asset.name.contains("universal", ignoreCase = true)
                    } ?: apkAssets.firstOrNull()

                    if (selected != null) {
                        apkUrl      = selected.url
                        apkSize     = selected.size
                        apkFileName = selected.name
                        MainActivity.log("Updater", "Seçilen APK: $apkFileName")
                    }
                }

                if (apkUrl.isEmpty()) {
                    MainActivity.log("Updater", "Uygun APK asset bulunamadı")
                    return@withContext null
                }

                val remoteVersion = tagName.trimStart('v', 'V')
                if (!isNewerVersion(remoteVersion, currentVersion)) {
                    MainActivity.log("Updater", "Uygulama güncel: $currentVersion")
                    return@withContext null
                }

                MainActivity.log("Updater", "Güncelleme mevcut: $currentVersion → $remoteVersion")
                UpdateInfo(
                    tagName      = tagName,
                    versionName  = remoteVersion,
                    releaseNotes = body.take(800),
                    apkUrl       = apkUrl,
                    apkSize      = apkSize,
                    apkFileName  = apkFileName
                )
            } catch (e: Exception) {
                MainActivity.log("Updater", "Güncelleme kontrolü hatası: ${e.message}")
                null
            }
        }
        withContext(Dispatchers.Main) { onResult(info) }
    }

    // ── İndirme + Kurulum ─────────────────────────────────────────────────────

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val fileName = info.apkFileName.ifEmpty { "Maya-${info.versionName}.apk" }
        val destFile = File(context.cacheDir, fileName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                Toast.makeText(
                    context,
                    "Maya için kurulum iznini açın, ardından tekrar deneyin.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        if (destFile.exists() && destFile.length() > 10_000_000L) {
            MainActivity.log("Updater", "APK önbellekte, kurulum başlatılıyor")
            installApk(context, destFile)
            return
        }

        if (destFile.exists()) destFile.delete()

        createNotifChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun downloadNotif(progress: Int) {
            val nb = NotificationCompat.Builder(context, NOTIF_CHANNEL_DOWNLOAD)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Maya ${info.versionName} indiriliyor")
                .setContentText("%$progress")
                .setOngoing(true)
                .setSilent(true)
            nb.setProgress(100, progress, progress == 0)
            nm.notify(NOTIF_ID_DOWNLOAD, nb.build())
        }

        withContext(Dispatchers.IO) {
            try {
                downloadNotif(0)
                MainActivity.log("Updater", "İndirme başlıyor: ${info.apkUrl}")

                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Maya/1.0")
                conn.connectTimeout = 15_000
                conn.readTimeout    = 60_000
                conn.instanceFollowRedirects = true

                var finalConn = conn
                var redirects = 0
                while (finalConn.responseCode in 301..308 && redirects < 5) {
                    val location = finalConn.getHeaderField("Location") ?: break
                    finalConn.disconnect()
                    finalConn = URL(location).openConnection() as HttpURLConnection
                    finalConn.requestMethod = "GET"
                    finalConn.setRequestProperty("User-Agent", "Maya/1.0")
                    finalConn.connectTimeout = 15_000
                    finalConn.readTimeout    = 60_000
                    redirects++
                }

                val totalBytes = finalConn.contentLengthLong
                    .takeIf { it > 0 } ?: info.apkSize.takeIf { it > 0 } ?: -1L

                finalConn.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(128 * 1024)
                        var downloaded = 0L
                        var lastNotifPct = -1
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (totalBytes > 0) {
                                val pct = (downloaded * 100 / totalBytes).toInt()
                                if (pct != lastNotifPct && pct % 5 == 0) {
                                    lastNotifPct = pct
                                    withContext(Dispatchers.Main) { downloadNotif(pct) }
                                }
                            }
                        }
                    }
                }
                finalConn.disconnect()

                MainActivity.log("Updater", "İndirme tamamlandı: ${destFile.length()} bytes")

                withContext(Dispatchers.Main) {
                    // Sessiz indirme bildirimini kaldır
                    nm.cancel(NOTIF_ID_DOWNLOAD)

                    // Sesli "hazır" bildirimi göster
                    showReadyNotification(context, info, nm)

                    installApk(context, destFile)
                }

            } catch (e: Exception) {
                destFile.delete()
                MainActivity.log("Updater", "İndirme hatası: ${e.message}")
                withContext(Dispatchers.Main) {
                    nm.cancel(NOTIF_ID_DOWNLOAD)
                    Toast.makeText(context, "İndirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Güncelleme indirilip hazır olduğunda sesli bildirim gönderir.
     * Kullanıcı bildirimi tıkladığında uygulamayı açar.
     */
    private fun showReadyNotification(
        context: Context,
        info: UpdateInfo,
        nm: NotificationManager
    ) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID_READY, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_READY)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("🆕 Maya ${info.versionName} hazır!")
            .setContentText("Güncelleme indirildi. Kurmak için dokunun.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Maya ${info.versionName} başarıyla indirildi. Kurulum ekranını açmak için dokunun.")
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            // Ses + titreşim için varsayılan efekti kullan
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIF_ID_READY, notif)
    }

    // ── APK Kurulum ───────────────────────────────────────────────────────────

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(context, "APK bulunamadı.", Toast.LENGTH_LONG).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }

            context.startActivity(intent)
            MainActivity.log("Updater", "Kurulum ekranı açıldı: ${apkFile.name}")

        } catch (e: Exception) {
            Toast.makeText(context, "Kurulum başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            MainActivity.log("Updater", "installApk hatası: ${e.message}")
        }
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────

    /**
     * İki ayrı kanal oluşturur:
     *   - maya_update_download : sessiz, süreç bildirimi (Importance LOW)
     *   - maya_update_ready    : sesli + titreşimli, tamamlanma (Importance HIGH)
     */
    private fun createNotifChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Sessiz indirme kanalı
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_DOWNLOAD,
                    "Maya Güncelleme — İndiriliyor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )

            // Sesli tamamlanma kanalı
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_READY,
                    "Maya Güncelleme — Hazır",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Güncelleme indirildiğinde ses ve titreşimle bildirir"
                    enableVibration(true)
                    enableLights(true)
                    setShowBadge(true)
                }
            )
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        return try {
            val r = remote.split(".").map { it.toInt() }
            val c = current.split(".").map { it.toInt() }
            val size = maxOf(r.size, c.size)
            for (i in 0 until size) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv > cv) return true
                if (rv < cv) return false
            }
            false
        } catch (_: Exception) {
            remote > current
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return " (%.1f MB)".format(mb)
    }
}
