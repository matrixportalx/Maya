package tr.maya

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import tr.maya.data.Conversation
import tr.maya.data.DbMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ── Yedekleme ─────────────────────────────────────────────────────────────────
//
// v7.0: Yedekleme formatı ZIP tabanlıdır.
//   data.json          → sohbetler / ayarlar / mayagram metadata (sadece metin, base64 YOK)
//   media/avatar_*.png → tavern karakter avatarları (sadece "file:" ile başlayanlar)
//   media/post_*.png   → Mayagram gönderi görüntüleri
//
// Eski (v1-5) base64-içi-JSON formatı hâlâ OKUNABİLİR (geriye dönük uyumluluk),
// ama yeni yedekler artık ZIP olarak üretilir. Bu, büyük görüntülerin tek bir
// String/JSONObject içinde şişip OutOfMemoryError'a yol açmasını önler.

internal fun MainActivity.backupChats() {
    val dp = resources.displayMetrics.density
    val scrollView = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (8*dp).toInt())
    }
    scrollView.addView(layout)

    layout.addView(TextView(this).apply {
        text = "Neleri yedeklemek istiyorsunuz?"; textSize = 13f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    fun makeCheckBox(label: String, checked: Boolean = true): android.widget.CheckBox =
        android.widget.CheckBox(this).apply {
            text = label; isChecked = checked
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
        }

    val cbConversations = makeCheckBox("💬 Sohbetler")
    val cbSettings      = makeCheckBox("⚙️ Ayarlar & Karakterler")
    val cbMayagram      = makeCheckBox("📸 Mayagram (gönderiler, yorumlar, görseller)")
    layout.addView(cbConversations); layout.addView(cbSettings); layout.addView(cbMayagram)

    layout.addView(TextView(this).apply {
        text = "Yedek dosyası bir ZIP arşividir. Görseller ayrı dosyalar olarak eklenir, bellek şişmesi olmaz."
        textSize = 11f; alpha = 0.55f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (2*dp).toInt() }
    })

    layout.addView(View(this).apply {
        setBackgroundColor(0x22888888)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt()).apply { topMargin = (12*dp).toInt(); bottomMargin = (12*dp).toInt() }
    })
    layout.addView(TextView(this).apply {
        text = "Şifreleme (isteğe bağlı)"; textSize = 13f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6*dp).toInt() }
    })
    val passwordInput = android.widget.EditText(this).apply {
        hint = "Şifre — boş bırakırsanız şifresiz kaydedilir"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    layout.addView(passwordInput)

    AlertDialog.Builder(this).setTitle("💾 Yedekleme").setView(scrollView)
        .setPositiveButton("Yedekle") { _, _ ->
            val inclConvs    = cbConversations.isChecked
            val inclSettings = cbSettings.isChecked
            val inclMayagram = cbMayagram.isChecked
            if (!inclConvs && !inclSettings && !inclMayagram) { Toast.makeText(this, "En az bir seçenek işaretleyin", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            val password    = passwordInput.text.toString()
            val isEncrypted = password.isNotEmpty()
            val suffix      = buildString { if (inclConvs) append("s"); if (inclSettings) append("a"); if (inclMayagram) append("m") }
            val ext = if (isEncrypted) "maya" else "zip"
            val fileName = "maya_yedek_${suffix}_${System.currentTimeMillis()}.$ext"
            pendingBackupCallback = { uri -> performBackupToUri(uri, password, isEncrypted, inclConvs, inclSettings, inclMayagram) }
            backupSaveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, fileName)
            })
        }.setNegativeButton("İptal", null).show()
}

internal fun MainActivity.performBackupToUri(
    uri: Uri,
    password: String,
    encrypt: Boolean,
    inclConvs: Boolean,
    inclSettings: Boolean,
    inclMayagram: Boolean = false
) {
    val activity = this
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val conversations = if (inclConvs) activity.db.chatDao().getAllConversationsList() else emptyList()
            val mayagramPostCount = if (inclMayagram) activity.db.mayagramDao().postCount() else 0

            // ── ZIP'i geçici bir dosyaya yaz (doğrudan contentResolver stream'ine
            //    yazmak şifreleme öncesi boyutu bilmeyi gerektirir; geçici dosya
            //    şifreleme adımı için de aracı olur) ──
            val tmpZip = File(activity.cacheDir, "backup_tmp_${System.currentTimeMillis()}.zip")
            try {
                ZipOutputStream(tmpZip.outputStream().buffered()).use { zos ->
                    val dataJson = activity.buildBackupJsonAndCollectMedia(
                        zos, inclConvs, inclSettings, inclMayagram
                    )
                    zos.putNextEntry(ZipEntry("data.json"))
                    zos.write(dataJson.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                activity.contentResolver.openOutputStream(uri)?.use { out ->
                    if (encrypt) {
                        encryptBackupStream(tmpZip, out, password)
                    } else {
                        tmpZip.inputStream().use { inp -> inp.copyTo(out) }
                    }
                } ?: throw Exception("Dosya yazılamadı")

            } finally {
                try { tmpZip.delete() } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                val parts = buildList {
                    if (inclConvs) add("${conversations.size} sohbet")
                    if (inclSettings) add("ayarlar")
                    if (inclMayagram) add("$mayagramPostCount Mayagram gönderisi")
                }
                Toast.makeText(activity, "${parts.joinToString(", ")} yedeklendi${if (encrypt) " (AES-256 şifreli)" else ""}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            // NOT: Throwable yakalanır — büyük dosyalarda OutOfMemoryError gibi
            // Error türleri de Exception değildir ve sessizce uygulamayı çökertebilir.
            MainActivity.log("Backup", "Yedekleme hatası: ${e.javaClass.simpleName}: ${e.message}")
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Yedekleme hatası: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.showRestorePicker() {
    backupRestoreLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
    })
}

internal fun MainActivity.handleRestoreFile(uri: Uri) {
    val activity = this
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val tmpFile = File(activity.cacheDir, "restore_tmp_${System.currentTimeMillis()}.bin")
            activity.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { out -> input.copyTo(out) }
            } ?: throw Exception("Dosya okunamadı")

            val header = ByteArray(4)
            tmpFile.inputStream().use { it.read(header) }
            val looksEncrypted = header.size == 4 && String(header) == "MAYA"

            if (looksEncrypted) {
                withContext(Dispatchers.Main) {
                    val passInput = android.widget.EditText(activity).apply {
                        hint = "Yedekleme şifresi"
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        setPadding(48, 24, 48, 24)
                    }
                    AlertDialog.Builder(activity).setTitle("🔐 Şifreli Yedek")
                        .setMessage("Bu yedek şifrelenmiş. Şifreyi girin:").setView(passInput)
                        .setPositiveButton("Çöz") { _, _ ->
                            val pass = passInput.text.toString()
                            if (pass.isEmpty()) { Toast.makeText(activity, "Şifre boş olamaz", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                            activity.lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val decryptedZip = File(activity.cacheDir, "restore_decrypted_${System.currentTimeMillis()}.zip")
                                    decryptBackupStream(tmpFile, decryptedZip, pass)
                                    withContext(Dispatchers.Main) { activity.showRestoreSelectionDialogFromZip(decryptedZip) }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Toast.makeText(activity, "Şifre çözme hatası: ${e.message}", Toast.LENGTH_LONG).show() }
                                } finally {
                                    try { tmpFile.delete() } catch (_: Exception) {}
                                }
                            }
                        }.setNegativeButton("İptal", null).show()
                }
            } else {
                // Şifresiz: ya yeni ZIP formatı ya da eski (v1-5) düz JSON formatı
                val isZip = try {
                    tmpFile.inputStream().use { ins ->
                        val magic = ByteArray(4); ins.read(magic)
                        magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() // "PK" zip magic
                    }
                } catch (_: Exception) { false }

                if (isZip) {
                    withContext(Dispatchers.Main) { activity.showRestoreSelectionDialogFromZip(tmpFile) }
                } else {
                    // Eski format: dosyanın tamamı JSON metni
                    val jsonText = tmpFile.readText(Charsets.UTF_8)
                    try { tmpFile.delete() } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { activity.showRestoreSelectionDialogLegacy(jsonText) }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(activity, "Dosya okuma hatası: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
}

// ── ZIP'ten data.json'u oku ve restore seçim diyaloğunu göster ───────────────

internal fun MainActivity.showRestoreSelectionDialogFromZip(zipFile: File) {
    val dataJson = try {
        readZipEntryAsText(zipFile, "data.json")
    } catch (e: Exception) {
        Toast.makeText(this, "Yedek dosyası okunamadı: ${e.message}", Toast.LENGTH_LONG).show()
        try { zipFile.delete() } catch (_: Exception) {}
        return
    }
    if (dataJson == null) {
        Toast.makeText(this, "Geçersiz yedek: data.json bulunamadı", Toast.LENGTH_LONG).show()
        try { zipFile.delete() } catch (_: Exception) {}
        return
    }
    showRestoreSelectionDialogCommon(dataJson) { doConvs, doSettings, mergeConvs, doMayagram, mergeMayagram ->
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                importBackupFromZip(zipFile, dataJson, doConvs, doSettings, mergeConvs, doMayagram, mergeMayagram)
            } finally {
                try { zipFile.delete() } catch (_: Exception) {}
            }
        }
    }
}

/** Eski (v1-5) düz JSON formatı için — base64 medya gömülüyse doğrudan JSON içinden çözülür. */
internal fun MainActivity.showRestoreSelectionDialogLegacy(jsonText: String) {
    showRestoreSelectionDialogCommon(jsonText) { doConvs, doSettings, mergeConvs, doMayagram, mergeMayagram ->
        lifecycleScope.launch(Dispatchers.IO) {
            importJsonBackupLegacy(jsonText, doConvs, doSettings, mergeConvs, doMayagram, mergeMayagram)
        }
    }
}

/**
 * Hem ZIP hem legacy format için ortak seçim diyaloğu.
 * [onConfirm] geri yükleme parametreleriyle çağrılır; çağıran taraf uygun import fonksiyonunu tetikler.
 */
private fun MainActivity.showRestoreSelectionDialogCommon(
    dataJsonText: String,
    onConfirm: (doConvs: Boolean, doSettings: Boolean, mergeConvs: Boolean, doMayagram: Boolean, mergeMayagram: Boolean) -> Unit
) {
    val root = try { JSONObject(dataJsonText) } catch (e: Exception) {
        Toast.makeText(this, "Geçersiz yedek dosyası", Toast.LENGTH_LONG).show(); return
    }
    val version     = root.optInt("version", 1)
    val hasConvs    = root.has("conversations") && root.getJSONArray("conversations").length() > 0
    val hasSettings = version >= 3 && root.has("settings")
    val hasMayagram = version >= 5 && root.has("mayagram") && (root.optJSONObject("mayagram")?.optJSONArray("posts")?.length() ?: 0) > 0
    val convCount   = if (hasConvs) root.getJSONArray("conversations").length() else 0
    val reportCount = if (hasSettings && version >= 4) {
        root.optJSONObject("settings")?.optJSONArray("report_profiles_json")?.length() ?: 0
    } else 0
    val mayagramPostCount    = if (hasMayagram) root.getJSONObject("mayagram").optJSONArray("posts")?.length() ?: 0 else 0
    val mayagramCommentCount = if (hasMayagram) root.getJSONObject("mayagram").optJSONArray("comments")?.length() ?: 0 else 0

    val dp = resources.displayMetrics.density
    val scrollView = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (8*dp).toInt())
    }
    scrollView.addView(layout)

    layout.addView(TextView(this).apply {
        text = buildString {
            append("Bu yedek dosyasında:\n")
            if (hasConvs)    append("  • $convCount sohbet\n")
            if (hasSettings) append("  • Ayarlar & Karakterler\n")
            if (reportCount > 0) append("  • $reportCount rapor profili\n")
            if (hasMayagram) append("  • $mayagramPostCount Mayagram gönderisi, $mayagramCommentCount yorum\n")
            if (!hasConvs && !hasSettings && !hasMayagram) append("  • (Tanınmayan format)\n")
        }
        textSize = 13f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12*dp).toInt() }
    })
    layout.addView(TextView(this).apply {
        text = "Neleri geri yüklemek istiyorsunuz?"; textSize = 13f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    fun makeCheckBox(label: String, enabled: Boolean): android.widget.CheckBox =
        android.widget.CheckBox(this).apply {
            text = label; isChecked = enabled; isEnabled = enabled
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
        }

    val cbConvs     = makeCheckBox("💬 Sohbetler ($convCount adet)", hasConvs)
    val cbSettings  = makeCheckBox("⚙️ Ayarlar & Karakterler", hasSettings)
    val cbMayagram  = makeCheckBox("📸 Mayagram ($mayagramPostCount gönderi, $mayagramCommentCount yorum)", hasMayagram)
    layout.addView(cbConvs); layout.addView(cbSettings); layout.addView(cbMayagram)

    var mergeConvs = false
    if (hasConvs) {
        layout.addView(View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt()).apply { topMargin = (12*dp).toInt(); bottomMargin = (12*dp).toInt() }
        })
        layout.addView(TextView(this).apply {
            text = "Sohbet geri yükleme yöntemi:"; textSize = 13f; alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6*dp).toInt() }
        })
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbOverwrite = RadioButton(this).apply { text = "Mevcut sohbetlerin üzerine yaz"; id = View.generateViewId(); isChecked = true }
        val rbMerge     = RadioButton(this).apply { text = "Mevcut sohbetlere ekle (birleştir)"; id = View.generateViewId() }
        rg.addView(rbOverwrite); rg.addView(rbMerge)
        rg.setOnCheckedChangeListener { _, checkedId -> mergeConvs = (checkedId == rbMerge.id) }
        layout.addView(rg)
    }

    var mergeMayagram = true
    if (hasMayagram) {
        layout.addView(View(this).apply {
            setBackgroundColor(0x22888888)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt()).apply { topMargin = (12*dp).toInt(); bottomMargin = (12*dp).toInt() }
        })
        layout.addView(TextView(this).apply {
            text = "Mayagram geri yükleme yöntemi:"; textSize = 13f; alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6*dp).toInt() }
        })
        val rgM = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbMergeM     = RadioButton(this).apply { text = "Mevcut akışa ekle (birleştir)"; id = View.generateViewId(); isChecked = true }
        val rbOverwriteM = RadioButton(this).apply { text = "Mevcut Mayagram akışının üzerine yaz"; id = View.generateViewId() }
        rgM.addView(rbMergeM); rgM.addView(rbOverwriteM)
        rgM.setOnCheckedChangeListener { _, checkedId -> mergeMayagram = (checkedId == rbMergeM.id) }
        layout.addView(rgM)
    }

    AlertDialog.Builder(this).setTitle("📂 Geri Yükleme Seçenekleri").setView(scrollView)
        .setPositiveButton("Geri Yükle") { _, _ ->
            val doConvs    = cbConvs.isChecked
            val doSettings = cbSettings.isChecked
            val doMayagram = cbMayagram.isChecked
            if (!doConvs && !doSettings && !doMayagram) { Toast.makeText(this, "En az bir seçenek işaretleyin", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            onConfirm(doConvs, doSettings, mergeConvs, doMayagram, mergeMayagram)
        }.setNegativeButton("İptal", null).show()
}

// ── ZIP yardımcıları ──────────────────────────────────────────────────────────

/** ZIP içinden tek bir entry'yi metin olarak okur; bulunamazsa null döner. */
private fun readZipEntryAsText(zipFile: File, entryName: String): String? {
    ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            if (entry.name == entryName) {
                return zis.readBytes().toString(Charsets.UTF_8)
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return null
}

/** ZIP içinden tek bir entry'yi belirtilen hedef dosyaya stream halinde çıkarır. Bulunursa true döner. */
private fun extractZipEntryToFile(zipFile: File, entryName: String, destFile: File): Boolean {
    ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            if (entry.name == entryName) {
                destFile.outputStream().buffered().use { out -> zis.copyTo(out) }
                return true
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return false
}

// ── Yedek JSON oluştur + medyayı ZIP'e stream et ─────────────────────────────

/**
 * data.json metnini üretir; bu sırada karşılaşılan tüm medya dosyalarını
 * (tavern avatarları, mayagram görüntüleri) doğrudan [zos] ZIP akışına yazar.
 * Hiçbir görüntü base64'e çevrilip bellekte String olarak tutulmaz —
 * her dosya kendi ZipEntry'sine stream edilir.
 */
internal suspend fun MainActivity.buildBackupJsonAndCollectMedia(
    zos: ZipOutputStream,
    inclConvs: Boolean,
    inclSettings: Boolean,
    inclMayagram: Boolean
): String {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val root = JSONObject()
    root.put("version", 7); root.put("exportedAt", System.currentTimeMillis())

    if (inclSettings) {
        val rawCharactersJson = prefs.getString("characters_json", null) ?: "[]"
        val charactersJsonWithRefs = referenceTavernAvatarsAndStream(rawCharactersJson, zos)

        val settingsObj = JSONObject().apply {
            put("context_size", contextSize); put("predict_length", predictLength)
            put("system_prompt", systemPrompt); put("temperature", temperature.toDouble())
            put("top_p", topP.toDouble()); put("top_k", topK)
            put("no_thinking", noThinking); put("auto_load_last_model", autoLoadLastModel)
            put("flash_attn_mode", flashAttnMode)
            put("char_name", charName); put("user_name", userName)
            put("last_loaded_model", prefs.getString("last_loaded_model", null) ?: "")
            put("characters_json", charactersJsonWithRefs)
            put("active_character_id", prefs.getString("active_character_id", null) ?: "")
            put("report_profiles_json", prefs.getString("report_profiles_json", null) ?: "[]")
            put("custom_templates_json", prefs.getString("custom_templates_json", null) ?: "[]")
        }
        root.put("settings", settingsObj)
    }

    if (inclConvs) {
        val conversations = db.chatDao().getAllConversationsList()
        val allMessages   = db.chatDao().getAllMessages()
        val convsArray = JSONArray()
        for (conv in conversations) {
            val convObj = JSONObject()
            convObj.put("id", conv.id); convObj.put("title", conv.title); convObj.put("updatedAt", conv.updatedAt)
            val msgsArray = JSONArray()
            allMessages.filter { it.conversationId == conv.id }.forEach { msg ->
                msgsArray.put(JSONObject().apply {
                    put("id", msg.id); put("role", msg.role); put("content", msg.content); put("timestamp", msg.timestamp)
                })
            }
            convObj.put("messages", msgsArray); convsArray.put(convObj)
        }
        root.put("conversations", convsArray)
    }

    if (inclMayagram) {
        val posts = db.mayagramDao().getAllPostsList()
        val postsArray = JSONArray()
        val commentsArray = JSONArray()

        for (post in posts) {
            val postObj = JSONObject().apply {
                put("id", post.id)
                put("characterId", post.characterId)
                put("characterName", post.characterName)
                put("characterEmoji", post.characterEmoji)
                put("characterAvatarUri", post.characterAvatarUri ?: "")
                put("caption", post.caption)
                put("dreamPrompt", post.dreamPrompt ?: "")
                put("timestamp", post.timestamp)
                put("likeCount", post.likeCount)
                put("isLikedByUser", post.isLikedByUser)
            }

            if (post.imagePath != null) {
                val imgFile = File(post.imagePath)
                if (imgFile.exists()) {
                    val mediaEntryName = "media/post_${post.id}.png"
                    try {
                        zos.putNextEntry(ZipEntry(mediaEntryName))
                        imgFile.inputStream().buffered().use { it.copyTo(zos) }
                        zos.closeEntry()
                        postObj.put("image_media_ref", mediaEntryName)
                    } catch (e: Exception) {
                        MainActivity.log("Backup", "Mayagram görüntüsü ZIP'e eklenemedi (${imgFile.name}): ${e.message}")
                    }
                }
            }
            postsArray.put(postObj)

            val comments = db.mayagramDao().getComments(post.id)
            comments.forEach { c ->
                commentsArray.put(JSONObject().apply {
                    put("id", c.id)
                    put("postId", c.postId)
                    put("authorId", c.authorId)
                    put("authorName", c.authorName)
                    put("authorEmoji", c.authorEmoji)
                    put("authorAvatarUri", c.authorAvatarUri ?: "")
                    put("content", c.content)
                    put("timestamp", c.timestamp)
                })
            }
        }

        val mayagramObj = JSONObject().apply {
            put("posts", postsArray)
            put("comments", commentsArray)
        }
        root.put("mayagram", mayagramObj)
    }

    return root.toString()
}

/**
 * characters_json içindeki "file:" ile başlayan (tavern içe aktarılan) avatar
 * dosyalarını ZIP'e stream eder ve JSON'da "avatar_media_ref" alanı ekler.
 * Galeri content:// URI'leri ve gömülü drawable işaretçisi değiştirilmeden kalır.
 */
private fun referenceTavernAvatarsAndStream(charactersJsonRaw: String, zos: ZipOutputStream): String {
    return try {
        val arr = JSONArray(charactersJsonRaw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val avatarUri = obj.optString("avatar_uri", "")
            if (avatarUri.startsWith("file:")) {
                val path = avatarUri.removePrefix("file:")
                val file = File(path)
                if (file.exists()) {
                    val charId = obj.optString("id", UUID.randomUUID().toString())
                    val mediaEntryName = "media/avatar_$charId.png"
                    try {
                        zos.putNextEntry(ZipEntry(mediaEntryName))
                        file.inputStream().buffered().use { it.copyTo(zos) }
                        zos.closeEntry()
                        obj.put("avatar_media_ref", mediaEntryName)
                    } catch (e: Exception) {
                        MainActivity.log("Backup", "Tavern avatar ZIP'e eklenemedi (${file.name}): ${e.message}")
                    }
                }
            }
        }
        arr.toString()
    } catch (e: Exception) {
        MainActivity.log("Backup", "Tavern avatar referans hatası: ${e.message}")
        charactersJsonRaw
    }
}

// ── ZIP'ten geri yükleme ──────────────────────────────────────────────────────

/**
 * characters_json içindeki "avatar_media_ref" alanlarını bulup, ilgili ZIP entry'sini
 * character_avatars/ dizinine çıkarır ve avatar_uri'yi günceller. Eski "avatar_file_b64"
 * (v6.4 ara sürüm) alanı varsa onu da destekler.
 */
private fun MainActivity.restoreTavernAvatarsFromZip(charactersJsonRaw: String, zipFile: File): String {
    return try {
        val arr = JSONArray(charactersJsonRaw)
        val avatarsDir = getCharacterAvatarsDir()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val mediaRef = obj.optString("avatar_media_ref", "")
            val legacyB64 = obj.optString("avatar_file_b64", "")
            if (mediaRef.isNotEmpty()) {
                val fileName = mediaRef.substringAfterLast("/")
                val destFile = File(avatarsDir, fileName)
                val ok = extractZipEntryToFile(zipFile, mediaRef, destFile)
                if (ok) obj.put("avatar_uri", "file:${destFile.absolutePath}")
                else MainActivity.log("Backup", "ZIP içinde avatar bulunamadı: $mediaRef")
                obj.remove("avatar_media_ref")
            } else if (legacyB64.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(legacyB64, android.util.Base64.NO_WRAP)
                    val fileName = obj.optString("avatar_file_name", "tavern_${UUID.randomUUID()}.png")
                    val destFile = File(avatarsDir, fileName.ifBlank { "tavern_${UUID.randomUUID()}.png" })
                    destFile.writeBytes(bytes)
                    obj.put("avatar_uri", "file:${destFile.absolutePath}")
                } catch (e: Exception) {
                    MainActivity.log("Backup", "Eski format avatar geri yüklenemedi: ${e.message}")
                }
            }
            obj.remove("avatar_file_b64")
            obj.remove("avatar_file_name")
        }
        arr.toString()
    } catch (e: Exception) {
        MainActivity.log("Backup", "Tavern avatar restore hatası: ${e.message}")
        charactersJsonRaw
    }
}

internal suspend fun MainActivity.importBackupFromZip(
    zipFile: File,
    dataJsonText: String,
    doConvs: Boolean,
    doSettings: Boolean,
    mergeConvs: Boolean,
    doMayagram: Boolean,
    mergeMayagram: Boolean
) {
    val activity = this
    try {
        val root    = JSONObject(dataJsonText)
        val prefs   = activity.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        var settingsRestored = false
        var convCount = 0; var msgCount = 0
        var mayagramPostCount = 0; var mayagramCommentCount = 0

        if (doSettings && root.has("settings")) {
            val s = root.getJSONObject("settings")
            val editor = prefs.edit()
            if (s.has("context_size"))         editor.putInt("context_size", s.getInt("context_size"))
            if (s.has("predict_length"))        editor.putInt("predict_length", s.getInt("predict_length"))
            if (s.has("system_prompt"))         editor.putString("system_prompt", s.getString("system_prompt"))
            if (s.has("temperature"))           editor.putFloat("temperature", s.getDouble("temperature").toFloat())
            if (s.has("top_p"))                 editor.putFloat("top_p", s.getDouble("top_p").toFloat())
            if (s.has("top_k"))                 editor.putInt("top_k", s.getInt("top_k"))
            if (s.has("no_thinking"))           editor.putBoolean("no_thinking", s.getBoolean("no_thinking"))
            if (s.has("auto_load_last_model"))  editor.putBoolean("auto_load_last_model", s.getBoolean("auto_load_last_model"))
            if (s.has("flash_attn_mode"))       editor.putInt("flash_attn_mode", s.getInt("flash_attn_mode"))
            else if (s.has("flash_attn"))       editor.putInt("flash_attn_mode", if (s.getBoolean("flash_attn")) 2 else 0)
            if (s.has("char_name"))             editor.putString("char_name", s.getString("char_name"))
            if (s.has("user_name"))             editor.putString("user_name", s.getString("user_name"))
            if (s.has("last_loaded_model") && s.getString("last_loaded_model").isNotEmpty())
                editor.putString("last_loaded_model", s.getString("last_loaded_model"))
            if (s.has("characters_json") && s.getString("characters_json").let { it.isNotEmpty() && it != "[]" }) {
                val restored = activity.restoreTavernAvatarsFromZip(s.getString("characters_json"), zipFile)
                editor.putString("characters_json", restored)
            }
            if (s.has("active_character_id") && s.getString("active_character_id").isNotEmpty())
                editor.putString("active_character_id", s.getString("active_character_id"))
            if (s.has("report_profiles_json") && s.getString("report_profiles_json").let { it.isNotEmpty() && it != "[]" })
                editor.putString("report_profiles_json", s.getString("report_profiles_json"))
            if (s.has("custom_templates_json") && s.getString("custom_templates_json").let { it.isNotEmpty() && it != "[]" })
                editor.putString("custom_templates_json", s.getString("custom_templates_json"))
            editor.apply()
            withContext(Dispatchers.Main) { activity.loadSettings() }
            settingsRestored = true
        }

        if (doConvs && root.has("conversations")) {
            val convsArray = root.getJSONArray("conversations")
            if (!mergeConvs) { activity.db.chatDao().deleteAllMessages(); activity.db.chatDao().deleteAllConversations() }
            for (i in 0 until convsArray.length()) {
                val convObj = convsArray.getJSONObject(i)
                val convId  = convObj.getString("id")
                if (mergeConvs) {
                    val exists = try { activity.db.chatDao().getMessages(convId).isNotEmpty() } catch (_: Exception) { false }
                    val finalId = if (exists) UUID.randomUUID().toString() else convId
                    activity.db.chatDao().insertConversation(Conversation(id = finalId, title = convObj.getString("title"), updatedAt = convObj.getLong("updatedAt")))
                    convCount++
                    val msgsArray = convObj.getJSONArray("messages")
                    for (j in 0 until msgsArray.length()) {
                        val msgObj = msgsArray.getJSONObject(j)
                        activity.db.chatDao().insertMessage(DbMessage(id = UUID.randomUUID().toString(), conversationId = finalId, role = msgObj.getString("role"), content = msgObj.getString("content"), timestamp = msgObj.getLong("timestamp")))
                        msgCount++
                    }
                } else {
                    activity.db.chatDao().insertConversation(Conversation(id = convId, title = convObj.getString("title"), updatedAt = convObj.getLong("updatedAt")))
                    convCount++
                    val msgsArray = convObj.getJSONArray("messages")
                    for (j in 0 until msgsArray.length()) {
                        val msgObj = msgsArray.getJSONObject(j)
                        activity.db.chatDao().insertMessage(DbMessage(id = msgObj.getString("id"), conversationId = convId, role = msgObj.getString("role"), content = msgObj.getString("content"), timestamp = msgObj.getLong("timestamp")))
                        msgCount++
                    }
                }
            }
        }

        if (doMayagram && root.has("mayagram")) {
            val mg = root.getJSONObject("mayagram")
            val postsArray = mg.optJSONArray("posts") ?: JSONArray()
            val commentsArray = mg.optJSONArray("comments") ?: JSONArray()

            if (!mergeMayagram) {
                val existingPosts = activity.db.mayagramDao().getAllPostsList()
                existingPosts.forEach { p ->
                    activity.db.mayagramDao().deleteCommentsForPost(p.id)
                    activity.db.mayagramDao().deletePost(p.id)
                    p.imagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
                }
            }

            val postIdMap = mutableMapOf<String, String>()
            val mayagramImagesDir = File(activity.getExternalFilesDir(null), "Mayagram").also { it.mkdirs() }

            for (i in 0 until postsArray.length()) {
                val postObj = postsArray.getJSONObject(i)
                val originalId = postObj.getString("id")

                val exists = if (mergeMayagram) {
                    try { activity.db.mayagramDao().getAllPostsList().any { it.id == originalId } } catch (_: Exception) { false }
                } else false
                val finalId = if (exists) UUID.randomUUID().toString() else originalId
                postIdMap[originalId] = finalId

                var imagePath: String? = null
                val mediaRef = postObj.optString("image_media_ref", "")
                val legacyB64 = postObj.optString("image_b64", "")
                if (mediaRef.isNotEmpty()) {
                    val fileName = "post_${finalId}.png"
                    val destFile = File(mayagramImagesDir, fileName)
                    val ok = extractZipEntryToFile(zipFile, mediaRef, destFile)
                    if (ok) imagePath = destFile.absolutePath
                    else MainActivity.log("Backup", "ZIP içinde Mayagram görüntüsü bulunamadı: $mediaRef")
                } else if (legacyB64.isNotEmpty()) {
                    try {
                        val bytes = android.util.Base64.decode(legacyB64, android.util.Base64.NO_WRAP)
                        val fileName = postObj.optString("image_file_name", "post_${System.currentTimeMillis()}_$i.png")
                        val destFile = File(mayagramImagesDir, fileName.ifBlank { "post_${System.currentTimeMillis()}_$i.png" })
                        destFile.writeBytes(bytes)
                        imagePath = destFile.absolutePath
                    } catch (e: Exception) {
                        MainActivity.log("Backup", "Eski format Mayagram görüntüsü geri yüklenemedi: ${e.message}")
                    }
                }

                activity.db.mayagramDao().insertPost(
                    MayagramPost(
                        id = finalId,
                        characterId = postObj.optString("characterId", ""),
                        characterName = postObj.optString("characterName", "Karakter"),
                        characterEmoji = postObj.optString("characterEmoji", "🤖"),
                        characterAvatarUri = postObj.optString("characterAvatarUri", "").ifEmpty { null },
                        caption = postObj.optString("caption", ""),
                        imagePath = imagePath,
                        dreamPrompt = postObj.optString("dreamPrompt", "").ifEmpty { null },
                        timestamp = postObj.optLong("timestamp", System.currentTimeMillis()),
                        likeCount = postObj.optInt("likeCount", 0),
                        isLikedByUser = postObj.optBoolean("isLikedByUser", false)
                    )
                )
                mayagramPostCount++
            }

            for (i in 0 until commentsArray.length()) {
                val cObj = commentsArray.getJSONObject(i)
                val originalPostId = cObj.getString("postId")
                val mappedPostId = postIdMap[originalPostId] ?: originalPostId
                activity.db.mayagramDao().insertComment(
                    MayagramComment(
                        id = UUID.randomUUID().toString(),
                        postId = mappedPostId,
                        authorId = cObj.optString("authorId", "user"),
                        authorName = cObj.optString("authorName", "Kullanıcı"),
                        authorEmoji = cObj.optString("authorEmoji", "👤"),
                        authorAvatarUri = cObj.optString("authorAvatarUri", "").ifEmpty { null },
                        content = cObj.optString("content", ""),
                        timestamp = cObj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
                mayagramCommentCount++
            }
        }

        withContext(Dispatchers.Main) {
            if (doConvs) {
                activity.currentMessages.clear()
                activity.messageAdapter.submitList(emptyList())
                activity.lifecycleScope.launch { activity.ensureActiveConversation() }
            }
            val parts = buildList {
                if (doConvs && convCount > 0) add("$convCount sohbet ($msgCount mesaj)${if (mergeConvs) " eklendi" else " geri yüklendi"}")
                if (settingsRestored) add("ayarlar & karakterler geri yüklendi")
                if (doMayagram && mayagramPostCount > 0) add("$mayagramPostCount Mayagram gönderisi ($mayagramCommentCount yorum)${if (mergeMayagram) " eklendi" else " geri yüklendi"}")
            }
            AlertDialog.Builder(activity).setTitle("✅ Geri Yükleme Tamamlandı")
                .setMessage(parts.joinToString("\n• ", prefix = "• "))
                .setPositiveButton("Tamam", null).show()
        }
    } catch (e: Throwable) {
        MainActivity.log("Backup", "Geri yükleme hatası: ${e.javaClass.simpleName}: ${e.message}")
        withContext(Dispatchers.Main) { Toast.makeText(activity, "Geri yükleme hatası: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}

// ── Eski (v1-5) düz JSON format desteği (geriye dönük uyumluluk) ─────────────

internal suspend fun MainActivity.importJsonBackupLegacy(
    jsonText: String,
    doConvs: Boolean = true,
    doSettings: Boolean = true,
    mergeConvs: Boolean = false,
    doMayagram: Boolean = false,
    mergeMayagram: Boolean = true
) {
    val activity = this
    try {
        val root    = JSONObject(jsonText)
        val version = root.optInt("version", 1)
        val prefs   = activity.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        var settingsRestored = false
        var convCount        = 0
        var msgCount         = 0
        var mayagramPostCount = 0
        var mayagramCommentCount = 0

        if (doSettings && version >= 3 && root.has("settings")) {
            val s = root.getJSONObject("settings")
            val editor = prefs.edit()
            if (s.has("context_size"))         editor.putInt("context_size", s.getInt("context_size"))
            if (s.has("predict_length"))        editor.putInt("predict_length", s.getInt("predict_length"))
            if (s.has("system_prompt"))         editor.putString("system_prompt", s.getString("system_prompt"))
            if (s.has("temperature"))           editor.putFloat("temperature", s.getDouble("temperature").toFloat())
            if (s.has("top_p"))                 editor.putFloat("top_p", s.getDouble("top_p").toFloat())
            if (s.has("top_k"))                 editor.putInt("top_k", s.getInt("top_k"))
            if (s.has("no_thinking"))           editor.putBoolean("no_thinking", s.getBoolean("no_thinking"))
            if (s.has("auto_load_last_model"))  editor.putBoolean("auto_load_last_model", s.getBoolean("auto_load_last_model"))
            if (s.has("flash_attn_mode"))       editor.putInt("flash_attn_mode", s.getInt("flash_attn_mode"))
            else if (s.has("flash_attn"))       editor.putInt("flash_attn_mode", if (s.getBoolean("flash_attn")) 2 else 0)
            if (s.has("char_name"))             editor.putString("char_name", s.getString("char_name"))
            if (s.has("user_name"))             editor.putString("user_name", s.getString("user_name"))
            if (s.has("last_loaded_model") && s.getString("last_loaded_model").isNotEmpty())
                editor.putString("last_loaded_model", s.getString("last_loaded_model"))
            if (s.has("characters_json") && s.getString("characters_json").let { it.isNotEmpty() && it != "[]" }) {
                val restored = activity.restoreTavernAvatarsLegacyB64(s.getString("characters_json"))
                editor.putString("characters_json", restored)
            }
            if (s.has("active_character_id") && s.getString("active_character_id").isNotEmpty())
                editor.putString("active_character_id", s.getString("active_character_id"))
            if (s.has("report_profiles_json") && s.getString("report_profiles_json").let { it.isNotEmpty() && it != "[]" })
                editor.putString("report_profiles_json", s.getString("report_profiles_json"))
            if (s.has("custom_templates_json") && s.getString("custom_templates_json").let { it.isNotEmpty() && it != "[]" })
                editor.putString("custom_templates_json", s.getString("custom_templates_json"))
            editor.apply()
            withContext(Dispatchers.Main) { activity.loadSettings() }
            settingsRestored = true
        }

        if (doConvs && root.has("conversations")) {
            val convsArray = root.getJSONArray("conversations")
            if (!mergeConvs) { activity.db.chatDao().deleteAllMessages(); activity.db.chatDao().deleteAllConversations() }
            for (i in 0 until convsArray.length()) {
                val convObj = convsArray.getJSONObject(i)
                val convId  = convObj.getString("id")
                if (mergeConvs) {
                    val exists = try { activity.db.chatDao().getMessages(convId).isNotEmpty() } catch (_: Exception) { false }
                    val finalId = if (exists) UUID.randomUUID().toString() else convId
                    activity.db.chatDao().insertConversation(Conversation(id = finalId, title = convObj.getString("title"), updatedAt = convObj.getLong("updatedAt")))
                    convCount++
                    val msgsArray = convObj.getJSONArray("messages")
                    for (j in 0 until msgsArray.length()) {
                        val msgObj = msgsArray.getJSONObject(j)
                        activity.db.chatDao().insertMessage(DbMessage(id = UUID.randomUUID().toString(), conversationId = finalId, role = msgObj.getString("role"), content = msgObj.getString("content"), timestamp = msgObj.getLong("timestamp")))
                        msgCount++
                    }
                } else {
                    activity.db.chatDao().insertConversation(Conversation(id = convId, title = convObj.getString("title"), updatedAt = convObj.getLong("updatedAt")))
                    convCount++
                    val msgsArray = convObj.getJSONArray("messages")
                    for (j in 0 until msgsArray.length()) {
                        val msgObj = msgsArray.getJSONObject(j)
                        activity.db.chatDao().insertMessage(DbMessage(id = msgObj.getString("id"), conversationId = convId, role = msgObj.getString("role"), content = msgObj.getString("content"), timestamp = msgObj.getLong("timestamp")))
                        msgCount++
                    }
                }
            }
        }

        if (doMayagram && version >= 5 && root.has("mayagram")) {
            val mg = root.getJSONObject("mayagram")
            val postsArray = mg.optJSONArray("posts") ?: JSONArray()
            val commentsArray = mg.optJSONArray("comments") ?: JSONArray()

            if (!mergeMayagram) {
                val existingPosts = activity.db.mayagramDao().getAllPostsList()
                existingPosts.forEach { p ->
                    activity.db.mayagramDao().deleteCommentsForPost(p.id)
                    activity.db.mayagramDao().deletePost(p.id)
                    p.imagePath?.let { path -> try { File(path).delete() } catch (_: Exception) {} }
                }
            }

            val postIdMap = mutableMapOf<String, String>()
            val mayagramImagesDir = File(activity.getExternalFilesDir(null), "Mayagram").also { it.mkdirs() }

            for (i in 0 until postsArray.length()) {
                val postObj = postsArray.getJSONObject(i)
                val originalId = postObj.getString("id")
                val exists = if (mergeMayagram) {
                    try { activity.db.mayagramDao().getAllPostsList().any { it.id == originalId } } catch (_: Exception) { false }
                } else false
                val finalId = if (exists) UUID.randomUUID().toString() else originalId
                postIdMap[originalId] = finalId

                var imagePath: String? = null
                val imageB64 = postObj.optString("image_b64", "")
                if (imageB64.isNotEmpty()) {
                    try {
                        val bytes = android.util.Base64.decode(imageB64, android.util.Base64.NO_WRAP)
                        val fileName = postObj.optString("image_file_name", "post_${System.currentTimeMillis()}_$i.png")
                        val destFile = File(mayagramImagesDir, fileName.ifBlank { "post_${System.currentTimeMillis()}_$i.png" })
                        destFile.writeBytes(bytes)
                        imagePath = destFile.absolutePath
                    } catch (e: Exception) {
                        MainActivity.log("Backup", "Mayagram görüntüsü geri yüklenemedi: ${e.message}")
                    }
                }

                activity.db.mayagramDao().insertPost(
                    MayagramPost(
                        id = finalId,
                        characterId = postObj.optString("characterId", ""),
                        characterName = postObj.optString("characterName", "Karakter"),
                        characterEmoji = postObj.optString("characterEmoji", "🤖"),
                        characterAvatarUri = postObj.optString("characterAvatarUri", "").ifEmpty { null },
                        caption = postObj.optString("caption", ""),
                        imagePath = imagePath,
                        dreamPrompt = postObj.optString("dreamPrompt", "").ifEmpty { null },
                        timestamp = postObj.optLong("timestamp", System.currentTimeMillis()),
                        likeCount = postObj.optInt("likeCount", 0),
                        isLikedByUser = postObj.optBoolean("isLikedByUser", false)
                    )
                )
                mayagramPostCount++
            }

            for (i in 0 until commentsArray.length()) {
                val cObj = commentsArray.getJSONObject(i)
                val originalPostId = cObj.getString("postId")
                val mappedPostId = postIdMap[originalPostId] ?: originalPostId
                activity.db.mayagramDao().insertComment(
                    MayagramComment(
                        id = UUID.randomUUID().toString(),
                        postId = mappedPostId,
                        authorId = cObj.optString("authorId", "user"),
                        authorName = cObj.optString("authorName", "Kullanıcı"),
                        authorEmoji = cObj.optString("authorEmoji", "👤"),
                        authorAvatarUri = cObj.optString("authorAvatarUri", "").ifEmpty { null },
                        content = cObj.optString("content", ""),
                        timestamp = cObj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
                mayagramCommentCount++
            }
        }

        withContext(Dispatchers.Main) {
            if (doConvs) {
                activity.currentMessages.clear()
                activity.messageAdapter.submitList(emptyList())
                activity.lifecycleScope.launch { activity.ensureActiveConversation() }
            }
            val parts = buildList {
                if (doConvs && convCount > 0) add("$convCount sohbet ($msgCount mesaj)${if (mergeConvs) " eklendi" else " geri yüklendi"}")
                if (settingsRestored) add("ayarlar & karakterler geri yüklendi")
                if (doMayagram && mayagramPostCount > 0) add("$mayagramPostCount Mayagram gönderisi ($mayagramCommentCount yorum)${if (mergeMayagram) " eklendi" else " geri yüklendi"}")
            }
            AlertDialog.Builder(activity).setTitle("✅ Geri Yükleme Tamamlandı")
                .setMessage(parts.joinToString("\n• ", prefix = "• "))
                .setPositiveButton("Tamam", null).show()
        }
    } catch (e: Throwable) {
        MainActivity.log("Backup", "Geri yükleme hatası (legacy): ${e.javaClass.simpleName}: ${e.message}")
        withContext(Dispatchers.Main) { Toast.makeText(activity, "Geri yükleme hatası: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}

private fun MainActivity.restoreTavernAvatarsLegacyB64(charactersJsonRaw: String): String {
    return try {
        val arr = JSONArray(charactersJsonRaw)
        val avatarsDir = getCharacterAvatarsDir()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val b64 = obj.optString("avatar_file_b64", "")
            if (b64.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    val fileName = obj.optString("avatar_file_name", "tavern_${UUID.randomUUID()}.png")
                    val destFile = File(avatarsDir, fileName.ifBlank { "tavern_${UUID.randomUUID()}.png" })
                    destFile.writeBytes(bytes)
                    obj.put("avatar_uri", "file:${destFile.absolutePath}")
                } catch (e: Exception) {
                    MainActivity.log("Backup", "Eski format avatar geri yüklenemedi: ${e.message}")
                }
                obj.remove("avatar_file_b64")
                obj.remove("avatar_file_name")
            }
        }
        arr.toString()
    } catch (e: Exception) {
        charactersJsonRaw
    }
}

// ── Şifreleme / Çözme (TAM stream tabanlı — büyük ZIP dosyaları için bellek dostu) ──
//
// ÖNEMLİ: cipher.doFinal(tümVeri) ASLA kullanılmaz — bu, hem kaynak hem şifreli
// veriyi aynı anda bellekte tutar ve büyük yedeklerde OutOfMemoryError'a yol açar.
// Bunun yerine CipherInputStream / CipherOutputStream ile sabit boyutlu (64KB)
// arabellek parçaları halinde şifreleme/şifre çözme yapılır. AES/GCM bunu sorunsuz
// destekler — GCM doğrulama etiketi akışın kapanışında (close()) otomatik eklenir.

private const val BACKUP_STREAM_BUFFER_SIZE = 64 * 1024

/**
 * [srcFile] dosyasını AES-256-GCM ile parça parça şifreleyip [out]'a yazar.
 * Format: "MAYA" + salt(16) + iv(12) + şifreli_veri(streamed)
 */
internal fun encryptBackupStream(srcFile: File, out: OutputStream, password: String) {
    val rng = SecureRandom()
    val salt = ByteArray(16).also { rng.nextBytes(it) }
    val iv   = ByteArray(12).also { rng.nextBytes(it) }
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val keyBytes = factory.generateSecret(PBEKeySpec(password.toCharArray(), salt, 310_000, 256)).encoded
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))

    out.write("MAYA".toByteArray())
    out.write(salt)
    out.write(iv)

    javax.crypto.CipherOutputStream(out, cipher).use { cos ->
        srcFile.inputStream().buffered().use { input ->
            val buf = ByteArray(BACKUP_STREAM_BUFFER_SIZE)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                cos.write(buf, 0, n)
            }
        }
        // cos.close() (use bloğu sonunda) GCM doğrulama etiketini akışın sonuna ekler.
    }
}

/** [srcFile] (MAYA+salt+iv+şifreli_veri formatında) parça parça çözüp [destFile]'a yazar. */
internal fun decryptBackupStream(srcFile: File, destFile: File, password: String) {
    srcFile.inputStream().buffered().use { input ->
        val magic = ByteArray(4)
        if (readFullyOrThrow(input, magic) != 4 || String(magic) != "MAYA") {
            throw IllegalArgumentException("Bu dosya Maya yedek dosyası değil")
        }
        val salt = ByteArray(16); readFullyOrThrow(input, salt)
        val iv   = ByteArray(12); readFullyOrThrow(input, iv)

        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 310_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))

        javax.crypto.CipherInputStream(input, cipher).use { cis ->
            destFile.outputStream().buffered().use { out ->
                val buf = ByteArray(BACKUP_STREAM_BUFFER_SIZE)
                var n: Int
                while (cis.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
            }
        }
    }
}

/** [buf] tamamen dolana kadar okur (kısmi read() çağrılarını birleştirir). Okunan toplam byte sayısını döner. */
private fun readFullyOrThrow(input: java.io.InputStream, buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
        val n = input.read(buf, off, buf.size - off)
        if (n == -1) break
        off += n
    }
    return off
}
