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
    layout.addView(cbConversations); layout.addView(cbSettings)

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
            if (!inclConvs && !inclSettings) { Toast.makeText(this, "En az bir seçenek işaretleyin", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            val password    = passwordInput.text.toString()
            val isEncrypted = password.isNotEmpty()
            val suffix      = buildString { if (inclConvs) append("s"); if (inclSettings) append("a") }
            val fileName = "maya_yedek_${suffix}_${System.currentTimeMillis()}.${if (isEncrypted) "maya" else "json"}"
            pendingBackupCallback = { uri -> performBackupToUri(uri, password, isEncrypted, inclConvs, inclSettings) }
            backupSaveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (isEncrypted) "application/octet-stream" else "application/json"
                putExtra(Intent.EXTRA_TITLE, fileName)
            })
        }.setNegativeButton("İptal", null).show()
}

internal fun MainActivity.performBackupToUri(uri: Uri, password: String, encrypt: Boolean, inclConvs: Boolean, inclSettings: Boolean) {
    val activity = this
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val jsonText = activity.buildBackupJson(inclConvs, inclSettings)
            val conversations = if (inclConvs) activity.db.chatDao().getAllConversationsList() else emptyList()
            activity.contentResolver.openOutputStream(uri)?.use { out ->
                if (encrypt) out.write(encryptBackup(jsonText, password))
                else out.write(jsonText.toByteArray(Charsets.UTF_8))
            } ?: throw Exception("Dosya yazılamadı")
            withContext(Dispatchers.Main) {
                val parts = buildList {
                    if (inclConvs) add("${conversations.size} sohbet")
                    if (inclSettings) add("ayarlar")
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
    val convCount   = if (hasConvs) root.getJSONArray("conversations").length() else 0
    val reportCount = if (hasSettings && version >= 4) {
        root.optJSONObject("settings")?.optJSONArray("report_profiles_json")?.length() ?: 0
    } else 0

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
            if (!hasConvs && !hasSettings) append("  • (Tanınmayan format)\n")
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

    val cbConvs    = makeCheckBox("💬 Sohbetler ($convCount adet)", hasConvs)
    val cbSettings = makeCheckBox("⚙️ Ayarlar & Karakterler", hasSettings)
    layout.addView(cbConvs); layout.addView(cbSettings)

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

    AlertDialog.Builder(this).setTitle("📂 Geri Yükleme Seçenekleri").setView(scrollView)
        .setPositiveButton("Geri Yükle") { _, _ ->
            val doConvs    = cbConvs.isChecked
            val doSettings = cbSettings.isChecked
            if (!doConvs && !doSettings) { Toast.makeText(this, "En az bir seçenek işaretleyin", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            activity.lifecycleScope.launch(Dispatchers.IO) { activity.importJsonBackup(jsonText, doConvs, doSettings, mergeConvs) }
        }.setNegativeButton("İptal", null).show()
}

// ── Yedek JSON oluştur ────────────────────────────────────────────────────────

internal suspend fun MainActivity.buildBackupJson(inclConvs: Boolean = true, inclSettings: Boolean = true): String {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val root = JSONObject()
    root.put("version", 4); root.put("exportedAt", System.currentTimeMillis())

    if (inclSettings) {
        val settingsObj = JSONObject().apply {
            put("context_size", contextSize); put("predict_length", predictLength)
            put("system_prompt", systemPrompt); put("temperature", temperature.toDouble())
            put("top_p", topP.toDouble()); put("top_k", topK)
            put("no_thinking", noThinking); put("auto_load_last_model", autoLoadLastModel)
            put("flash_attn_mode", flashAttnMode)
            put("char_name", charName); put("user_name", userName)
            put("last_loaded_model", prefs.getString("last_loaded_model", null) ?: "")
            put("characters_json", prefs.getString("characters_json", null) ?: "[]")
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

internal suspend fun MainActivity.importJsonBackup(jsonText: String, doConvs: Boolean = true, doSettings: Boolean = true, mergeConvs: Boolean = false) {
    val activity = this
    try {
        val root    = JSONObject(jsonText)
        val version = root.optInt("version", 1)
        val prefs   = activity.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        var settingsRestored = false
        var convCount        = 0
        var msgCount         = 0

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
            if (s.has("characters_json") && s.getString("characters_json").let { it.isNotEmpty() && it != "[]" })
                editor.putString("characters_json", s.getString("characters_json"))
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

        withContext(Dispatchers.Main) {
            if (doConvs) {
                activity.currentMessages.clear()
                activity.messageAdapter.submitList(emptyList())
                activity.lifecycleScope.launch { activity.ensureActiveConversation() }
            }
            val parts = buildList {
                if (doConvs && convCount > 0) add("$convCount sohbet ($msgCount mesaj)${if (mergeConvs) " eklendi" else " geri yüklendi"}")
                if (settingsRestored) add("ayarlar & karakterler geri yüklendi")
            }
            AlertDialog.Builder(activity).setTitle("✅ Geri Yükleme Tamamlandı")
                .setMessage(parts.joinToString("\n• ", prefix = "• "))
                .setPositiveButton("Tamam", null).show()
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { Toast.makeText(activity, "Geri yükleme hatası: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
