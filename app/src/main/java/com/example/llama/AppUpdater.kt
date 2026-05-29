package tr.maya

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    private const val GITHUB_OWNER = "matrixportalx"
    private const val GITHUB_REPO  = "Maya"
    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private const val NOTIF_CHANNEL = "maya_update"
    private const val NOTIF_ID      = 9001

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val releaseNotes: String,
        val apkUrl: String,
        val apkSize: Long
    )

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

                var apkUrl  = ""
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name  = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl  = asset.optString("browser_download_url", "")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (apkUrl.isEmpty()) {
                    MainActivity.log("Updater", "APK asset bulunamadı")
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
                    apkSize      = apkSize
                )
            } catch (e: Exception) {
                MainActivity.log("Updater", "Güncelleme kontrolü hatası: ${e.message}")
                null
            }
        }
        withContext(Dispatchers.Main) { onResult(info) }
    }

    // ── İndirme + Kurulum (DownloadManager YOK, Maya kendi indiriyor) ─────────

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val fileName = "Maya-${info.versionName}.apk"
        val destFile = File(context.cacheDir, fileName)

        // Kurulum izni kontrolü — yoksa ayarlara yönlendir, indirme başlatma
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

        // APK zaten varsa tekrar indirme
        if (destFile.exists() && destFile.length() > 10_000_000L) {
            MainActivity.log("Updater", "APK önbellekte, kurulum başlatılıyor")
            installApk(context, destFile)
            return
        }

        if (destFile.exists()) destFile.delete()

        createNotifChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // İndirme progress bildirimi
        fun notif(progress: Int, done: Boolean = false) {
            val title = if (done) "Maya ${info.versionName} indirildi" else "Maya ${info.versionName} indiriliyor"
            val text  = if (done) "Kurulum başlatılıyor…" else "%$progress"
            val nb = NotificationCompat.Builder(context, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(!done)
                .setSilent(true)
            if (!done) {
                nb.setProgress(100, progress, progress == 0)
            }
            nm.notify(NOTIF_ID, nb.build())
        }

        withContext(Dispatchers.IO) {
            try {
                notif(0)
                MainActivity.log("Updater", "İndirme başlıyor: ${info.apkUrl}")

                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Maya/1.0")
                conn.connectTimeout = 15_000
                conn.readTimeout    = 60_000
                conn.instanceFollowRedirects = true

                // GitHub redirect'lerini takip et
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
                        val buf = ByteArray(128 * 1024)  // 128 KB chunk
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
                                    withContext(Dispatchers.Main) { notif(pct) }
                                }
                            }
                        }
                    }
                }
                finalConn.disconnect()

                MainActivity.log("Updater", "İndirme tamamlandı: ${destFile.length()} bytes")

                withContext(Dispatchers.Main) {
                    notif(100, done = true)
                    nm.cancel(NOTIF_ID)
                    installApk(context, destFile)
                }

            } catch (e: Exception) {
                destFile.delete()
                MainActivity.log("Updater", "İndirme hatası: ${e.message}")
                withContext(Dispatchers.Main) {
                    nm.cancel(NOTIF_ID)
                    Toast.makeText(context, "İndirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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

    private fun createNotifChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL,
                "Maya Güncelleme",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
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
