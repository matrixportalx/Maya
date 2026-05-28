package tr.maya

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases API üzerinden güncelleme kontrolü ve APK indirme/kurma.
 *
 * Kullanım:
 *   AppUpdater.checkForUpdate(context, currentVersionName) { info ->
 *       if (info != null) { /* güncelleme var, info.show() */ }
 *   }
 */
object AppUpdater {

    // ── Kendi repo adresinizi buraya yazın ──────────────────────────────────
    private const val GITHUB_OWNER = "matrixportalx"   // GitHub kullanıcı adı
    private const val GITHUB_REPO  = "Maya"            // Repo adı
    // ───────────────────────────────────────────────────────────────────────

    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    data class UpdateInfo(
        val tagName: String,        // "v1.2.0"
        val versionName: String,    // "1.2.0"
        val releaseNotes: String,
        val apkUrl: String,
        val apkSize: Long           // bytes, 0 ise bilinmiyor
    )

    /**
     * Arka planda güncelleme kontrolü yapar.
     * [currentVersion]: BuildConfig.VERSION_NAME ("1.1.1" gibi)
     * [onResult]: Main thread'de çağrılır. Güncelleme yoksa null döner.
     */
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
                conn.setRequestProperty("User-Agent", "Maya-Android-App")
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    MainActivity.log("Updater", "GitHub API yanıt kodu: ${conn.responseCode}")
                    return@withContext null
                }

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val tagName = json.optString("tag_name", "")      // "v1.2.0"
                val body    = json.optString("body", "")           // release notları
                val assets  = json.optJSONArray("assets")

                // APK asset'ini bul
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

                // "v1.2.0" → "1.2.0"
                val remoteVersion = tagName.trimStart('v', 'V')

                if (!isNewerVersion(remoteVersion, currentVersion)) {
                    MainActivity.log("Updater", "Uygulama güncel: $currentVersion == $remoteVersion")
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

    /**
     * DownloadManager ile APK'yı indirir ve ardından kurulum ekranını açar.
     */
    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        try {
            val fileName = "Maya-${info.versionName}.apk"

            // İndirilmiş eski APK varsa sil
            val oldFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (oldFile.exists()) oldFile.delete()

            val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
                setTitle("Maya ${info.versionName} indiriliyor")
                setDescription("Güncelleme hazırlanıyor…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
                setMimeType("application/vnd.android.package-archive")
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)

            MainActivity.log("Updater", "İndirme başladı: downloadId=$downloadId")
            Toast.makeText(
                context,
                "Maya ${info.versionName} indiriliyor… Bildirim alanını takip edin.",
                Toast.LENGTH_LONG
            ).show()

            // İndirme tamamlandığında kurulum başlat
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id != downloadId) return

                    ctx.unregisterReceiver(this)

                    val query  = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status    = if (statusIdx >= 0) cursor.getInt(statusIdx)
                                        else DownloadManager.STATUS_FAILED
                        cursor.close()

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val apkFile = File(
                                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                                fileName
                            )
                            installApk(ctx, apkFile)
                        } else {
                            Toast.makeText(ctx, "İndirme başarısız oldu.", Toast.LENGTH_LONG).show()
                            MainActivity.log("Updater", "İndirme başarısız: status=$status")
                        }
                    }
                }
            }

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

        } catch (e: Exception) {
            Toast.makeText(context, "İndirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
            MainActivity.log("Updater", "downloadAndInstall hatası: ${e.message}")
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            MainActivity.log("Updater", "Kurulum başlatıldı: ${apkFile.absolutePath}")
        } catch (e: Exception) {
            Toast.makeText(context, "Kurulum başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            MainActivity.log("Updater", "installApk hatası: ${e.message}")
        }
    }

    /**
     * Basit semantik versiyon karşılaştırması: "1.2.0" > "1.1.1" → true
     * Her iki string de "major.minor.patch" formatında beklenir.
     * Farklı format varsa string karşılaştırmasına düşer.
     */
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
            false   // eşit
        } catch (_: Exception) {
            remote > current   // sayısal parse başarısız → string karşılaştır
        }
    }

    /**
     * MB olarak formatlar.
     */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return " (%.1f MB)".format(mb)
    }
}
