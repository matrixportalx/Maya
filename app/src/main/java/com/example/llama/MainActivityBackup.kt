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
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ── Yedekleme ─────────────────────────────────────────────────────────────────

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
        text = "Mayagram görselleri yedeğe gömülür; dosya boyutu büyüyebilir."
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
            val fileName = "maya_yedek_${suffix}_${System.currentTimeMillis()}.${if (isEncrypted) "maya" else "json"}"
            pendingBackupCallback = { uri -> performBackupToUri(uri, password, isEncrypted, inclConvs, inclSettings, inclMayagram) }
            backupSaveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (isEncrypted) "application/octet-stream" else "application/json"
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
        try 
            val jsonText = activity.buildBackupJson(inclConvs, inclSettings, inclMayagram)
            val conversations = if (inclConvs) activity.db.chatDao().getAllConversationsList() else emptyList()
            val mayagramPostCount = if (inclMayagram) activity.db.mayagramDao().postCount() else 0
            activity.contentResolver.openOutputStream(uri)?.use { out ->
                if (encrypt) out.write(encryptBackup(jsonText, password))
                else out.write(jsonText.toByteArray(Charsets.UTF_8))
            } ?: throw Exception("Dosya yazılamadı")
            withContext(Dispatchers.Main) {
                val parts = buildList {
                    if (inclConvs) add("${conversations.size} sohbet")
                    if (inclSettings) add("ayarlar")
                    if (inclMayagram) add("$mayagramPostCount Mayagram gönderisi")
                }
                Toast.makeText(activity, "${parts.joinToString(", ")} yedeklendi${if (encrypt) " (AES-256 şifreli)" else ""}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(activity, "Yedekleme hatası: ${e.message}", Toast.LENGTH_LONG).show() }
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
            val bytes = activity.contentResolver.openInputStream(uri)?.readBytes() ?: throw Exception("Dosya okunamadı")
            if (isEncryptedBackup(bytes)) {
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
                                    val jsonText = decryptBackup(bytes, pass)
                                    withContext(Dispatchers.Main) { activity.showRestoreSelectionDialog(jsonText) }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Toast.makeText(activity, "Şifre çözme hatası.", Toast.LENGTH_LONG).show() }
                                }
                            }
                        }.setNegativeButton("İptal", null).show()
                }
            } else {
                val jsonText = bytes.toString(Charsets.UTF_8)
                withContext(Dispatchers.Main) { activity.showRestoreSelectionDialog(jsonText) }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(activity, "Dosya okuma hatası: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
}

internal fun MainActivity.showRestoreSelectionDialog(jsonText: String) {
    val activity = this
    val root = try { JSONObject(jsonText) } catch (e: Exception) {
        Toast.makeText(this, "Geçersiz yedek dosyası", Toast.LENGTH_LONG).show(); return
    }
    val version     = root.optInt("version", 1)
    val hasConvs    = root.has("conversations") && root.getJSONArray("conversations").length() > 0
    val hasSettings = version >= 3 && root.has("settings")
    val hasMayagram = version >= 5 && root.has("mayagram") && root.getJSONObject("mayagram").optJSONArray("posts")?.length() ?: 0 > 0
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
            activity.lifecycleScope.launch(Dispatchers.IO) {
                activity.importJsonBackup(jsonText, doConvs, doSettings, mergeConvs, doMayagram, mergeMayagram)
            }
        }.setNegativeButton("İptal", null).show()
}

// ── Yedek JSON oluştur ────────────────────────────────────────────────────────

/**
 * Karakterlerin "file:" işaretçili (tavern içe aktarılan) avatar dosyalarını
 * base64 olarak gömer. Galeri content:// URI'leri ve gömülü drawable işaretçisi
 * değiştirilmeden kalır (zaten kalıcı izinli / kaynak tabanlı).
 */
private fun MainActivity.embedTavernAvatarsInCharactersJson(charactersJsonRaw: String): String {
    return try {
        val arr = JSONArray(charactersJsonRaw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val avatarUri = obj.optString("avatar_uri", "")
            if (avatarUri.startsWith("file:")) {
                val path = avatarUri.removePrefix("file:")
                val file = File(path)
                if (file.exists()) {
                    try {
                        val bytes = file.readBytes()
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        obj.put("avatar_file_b64", b64)
                        obj.put("avatar_file_name", file.name)
                    } catch (e: Exception) {
                        MainActivity.log("Backup", "Tavern avatar okunamadı (${file.name}): ${e.message}")
                    }
                }
            }
        }
        arr.toString()
    } catch (e: Exception) {
        MainActivity.log("Backup", "Tavern avatar gömme hatası: ${e.message}")
        charactersJsonRaw
    }
}

/**
 * Restore sırasında "avatar_file_b64" alanı bulunan karakterler için
 * dosyayı character_avatars/ dizinine yazar ve avatar_uri'yi günceller.
 * Geriye, temizlenmiş (b64 alanları çıkarılmış) characters_json döner.
 */
private fun MainActivity.extractTavernAvatarsFromCharactersJson(charactersJsonRaw: String): String {
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
                    val safeFileName = if (fileName.isBlank()) "tavern_${UUID.randomUUID()}.png" else fileName
                    val destFile = File(avatarsDir, safeFileName)
                    destFile.writeBytes(bytes)
                    obj.put("avatar_uri", "file:${destFile.absolutePath}")
                } catch (e: Exception) {
                    MainActivity.log("Backup", "Tavern avatar geri yüklenemedi: ${e.message}")
                }
                obj.remove("avatar_file_b64")
                obj.remove("avatar_file_name")
            }
        }
        arr.toString()
    } catch (e: Exception) {
        MainActivity.log("Backup", "Tavern avatar çıkarma hatası: ${e.message}")
        charactersJsonRaw
    }
}

internal suspend fun MainActivity.buildBackupJson(
    inclConvs: Boolean = true,
    inclSettings: Boolean = true,
    inclMayagram: Boolean = false
): String {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val root = JSONObject()
    root.put("version", 5); root.put("exportedAt", System.currentTimeMillis())

    if (inclSettings) {
        val rawCharactersJson = prefs.getString("characters_json", null) ?: "[]"
        val charactersJsonWithAvatars = embedTavernAvatarsInCharactersJson(rawCharactersJson)

        val settingsObj = JSONObject().apply {
            put("context_size", contextSize); put("predict_length", predictLength)
            put("system_prompt", systemPrompt); put("temperature", temperature.toDouble())
            put("top_p", topP.toDouble()); put("top_k", topK)
            put("no_thinking", noThinking); put("auto_load_last_model", autoLoadLastModel)
            put("flash_attn_mode", flashAttnMode)
            put("char_name", charName); put("user_name", userName)
            put("last_loaded_model", prefs.getString("last_loaded_model", null) ?: "")
            put("characters_json", charactersJsonWithAvatars)
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
        val posts    = db.mayagramDao().getAllPostsList()
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

            // Görüntüyü base64 olarak göm
            if (post.imagePath != null) {
                val imgFile = File(post.imagePath)
                if (imgFile.exists()) {
                    try {
                        val bytes = imgFile.readBytes()
                        postObj.put("image_b64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                        postObj.put("image_file_name", imgFile.name)
                    } catch (e: Exception) {
                        MainActivity.log("Backup", "Mayagram görüntüsü okunamadı (${imgFile.name}): ${e.message}")
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

    return root.toString(2)
}

// ── Şifreleme / Çözme ─────────────────────────────────────────────────────────

internal fun encryptBackup(jsonText: String, password: String): ByteArray {
    val rng = SecureRandom()
    val salt = ByteArray(16).also { rng.nextBytes(it) }
    val iv   = ByteArray(12).also { rng.nextBytes(it) }
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val keyBytes = factory.generateSecret(PBEKeySpec(password.toCharArray(), salt, 310_000, 256)).encoded
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
    return "MAYA".toByteArray() + salt + iv + cipher.doFinal(jsonText.toByteArray(Charsets.UTF_8))
}

internal fun decryptBackup(data: ByteArray, password: String): String {
    require(data.size > 32) { "Geçersiz yedek dosyası" }
    require(String(data.slice(0..3).toByteArray()) == "MAYA") { "Bu dosya Maya yedek dosyası değil" }
    val salt = data.slice(4..19).toByteArray()
    val iv   = data.slice(20..31).toByteArray()
    val enc  = data.slice(32 until data.size).toByteArray()
    val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, 310_000, 256)).encoded
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
    return String(cipher.doFinal(enc), Charsets.UTF_8)
}

internal fun isEncryptedBackup(data: ByteArray): Boolean =
    data.size >= 4 && String(data.slice(0..3).toByteArray()) == "MAYA"

// ── Geri yükleme ─────────────────────────────────────────────────────────────

internal suspend fun MainActivity.importJsonBackup(
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
            // Yeni format: flash_attn_mode (Int, 0=Kapalı/1=Otomatik/2=Açık)
            if (s.has("flash_attn_mode"))       editor.putInt("flash_attn_mode", s.getInt("flash_attn_mode"))
            // Eski format geriye dönük uyumluluk: flash_attn (Boolean) → flash_attn_mode'a dönüştür
            else if (s.has("flash_attn"))       editor.putInt("flash_attn_mode", if (s.getBoolean("flash_attn")) 2 else 0)
            if (s.has("char_name"))             editor.putString("char_name", s.getString("char_name"))
            if (s.has("user_name"))             editor.putString("user_name", s.getString("user_name"))
            if (s.has("last_loaded_model") && s.getString("last_loaded_model").isNotEmpty())
                editor.putString("last_loaded_model", s.getString("last_loaded_model"))
            if (s.has("characters_json") && s.getString("characters_json").let { it.isNotEmpty() && it != "[]" }) {
                // v6.4+: tavern "file:" avatarları base64 olarak gömülü olabilir — diske geri yaz
                val restoredCharactersJson = activity.extractTavernAvatarsFromCharactersJson(s.getString("characters_json"))
                editor.putString("characters_json", restoredCharactersJson)
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

            // Restore sırasında postId eşlemesi (merge modunda çakışan ID'ler yeniden üretilir)
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
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { Toast.makeText(activity, "Geri yükleme hatası: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
