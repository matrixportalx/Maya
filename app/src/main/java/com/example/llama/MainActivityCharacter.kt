package tr.maya

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// ── Karakter kurulumu (drawer) ────────────────────────────────────────────────

internal fun MainActivity.setupCharacters() {
    characterAdapter = CharacterAdapter(
        onSelect = { char ->
            setActiveCharacter(char)
            drawerLayout.closeDrawers()
            Toast.makeText(this, "🎭 ${char.name} aktif", Toast.LENGTH_SHORT).show()
        },
        onLongClick = { char -> showCharacterOptionsDialog(char) }
    )
    charactersRv.layoutManager = LinearLayoutManager(this)
    charactersRv.adapter = characterAdapter
    characterAdapter.activeId = activeCharacterId
    characterAdapter.submitList(characters.toList())

    btnAddCharacter.setOnClickListener { showCharacterEditDialog(null) }
}

internal fun MainActivity.showCharacterOptionsDialog(char: MayaCharacter) {
    val options = mutableListOf("✏️ Düzenle")
    if (characters.size > 1) options.add("🗑️ Sil")
    android.app.AlertDialog.Builder(this)
        .setTitle("${char.emoji} ${char.name}")
        .setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "✏️ Düzenle" -> showCharacterEditDialog(char)
                "🗑️ Sil"    -> confirmDeleteCharacter(char)
            }
        }
        .setNegativeButton("İptal", null).show()
}

internal fun MainActivity.showCharacterEditDialog(existing: MayaCharacter?) {
    val dp = resources.displayMetrics.density
    val isNew = existing == null

    val scrollView = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (8*dp).toInt())
    }
    scrollView.addView(layout)

    fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8*dp).toInt(); bottomMargin = (2*dp).toInt() }
    }
    fun field(hint: String, value: String = "", multiLine: Boolean = false) = android.widget.EditText(this).apply {
        this.hint = hint; setText(value)
        if (multiLine) { minLines = 3; maxLines = 8; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // ── Avatar önizleme + seçim butonu ───────────────────────────────────────
    var currentAvatarUri: String? = existing?.avatarUri

    val avatarRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8*dp).toInt() }
    }

    val avatarPreview = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(
            (72*dp).toInt(), (72*dp).toInt()
        ).apply { marginEnd = (12*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFF2E2E4A.toInt())
        }
        clipToOutline = true
    }

    // Avatar yükle
    fun loadAvatarPreview(uriStr: String?) {
        if (uriStr == MainActivity.DEFAULT_AVATAR_MARKER) {
            avatarPreview.setImageResource(R.drawable.maya_default_avatar)
            return
        }
        if (uriStr != null) {
            try {
                val bmp = loadRoundedBitmap(Uri.parse(uriStr), (72 * dp).toInt())
                if (bmp != null) {
                    avatarPreview.setImageBitmap(bmp)
                    return
                }
            } catch (_: Exception) {}
        }
        // Varsayılan emoji göster
        avatarPreview.setImageDrawable(null)
    }
    loadAvatarPreview(currentAvatarUri)

    val avatarBtnCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    val selectAvatarBtn = android.widget.Button(this).apply {
        text = "📷 Fotoğraf Seç"
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val removeAvatarBtn = android.widget.Button(this).apply {
        text = "✕ Kaldır"
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (4*dp).toInt() }
        visibility = if (currentAvatarUri != null) android.view.View.VISIBLE else android.view.View.GONE
    }

    avatarBtnCol.addView(selectAvatarBtn)
    avatarBtnCol.addView(removeAvatarBtn)
    avatarRow.addView(avatarPreview)
    avatarRow.addView(avatarBtnCol)

    // Geçici seçim diyalog için callback
    var tempAvatarUri: String? = currentAvatarUri

    selectAvatarBtn.setOnClickListener {
        // Doğrudan sistem galeri intent'i aç (diyalog açık olduğu için launcher yerine)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        // pendingAvatarCharacterId'ye özel bir işaretçi koy
        pendingAvatarCharacterId = existing?.id ?: "__new__"
        // Diyalog ID'sini geçici sakla — callback avatarPreview'ı güncellesin
        pendingAvatarDialogCallback = { uriStr ->
            tempAvatarUri = uriStr
            loadAvatarPreview(uriStr)
            removeAvatarBtn.visibility = android.view.View.VISIBLE
        }
        characterAvatarPickerLauncher.launch(intent)
    }

    removeAvatarBtn.setOnClickListener {
        tempAvatarUri = null
        avatarPreview.setImageDrawable(null)
        removeAvatarBtn.visibility = android.view.View.GONE
    }

    layout.addView(label("Avatar Fotoğrafı"))
    layout.addView(avatarRow)

    layout.addView(label("Emoji (Avatar yoksa gösterilir)"))
    val emojiField = field("🤖", existing?.emoji ?: "🤖")
    layout.addView(emojiField)

    layout.addView(label("Karakter adı ({{char}})"))
    val nameField = field("Maya", existing?.name ?: "Maya")
    layout.addView(nameField)

    layout.addView(label("Kullanıcı adı ({{user}})"))
    val userNameField = field("Kullanıcı", existing?.userName ?: "")
    layout.addView(userNameField)

    layout.addView(label("Sistem promptu"))
    val promptField = field("Karakterin kişiliğini, görevini tanımlayın...", existing?.systemPrompt ?: "", multiLine = true)
    layout.addView(promptField)

    android.app.AlertDialog.Builder(this)
        .setTitle(if (isNew) "➕ Yeni Karakter" else "✏️ Karakteri Düzenle")
        .setView(scrollView)
        .setPositiveButton("Kaydet") { _, _ ->
            val emoji   = emojiField.text.toString().trim().ifEmpty { "👩‍🦰" }
            val name    = nameField.text.toString().trim().ifEmpty { "Maya" }
            val uName   = userNameField.text.toString().trim().ifEmpty { "Kullanıcı" }
            val prompt  = promptField.text.toString().trim().ifEmpty { "{{date}} {{time}}. Senin adın {{char}}. Sen yararlı, zeki ve eğlenceli bir yapay zeka asistansın ve {{user}}'ın sadık bir dostusun." }

            val char = MayaCharacter(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name, userName = uName, emoji = emoji,
                systemPrompt = prompt,
                avatarUri = tempAvatarUri
            )
            if (isNew) {
                characters.add(char)
            } else {
                val idx = characters.indexOfFirst { it.id == char.id }
                if (idx >= 0) characters[idx] = char
            }
            saveCharactersToPrefs(characters)
            characterAdapter.submitList(characters.toList())
            if (char.id == activeCharacterId) setActiveCharacter(char)
            pendingAvatarDialogCallback = null
        }
        .setNegativeButton("İptal") { _, _ ->
            pendingAvatarDialogCallback = null
        }
        .show()
}

// Diyalog içindeki avatar seçimi için callback (launcher → diyalog köprüsü)
internal var pendingAvatarDialogCallback: ((String) -> Unit)? = null

internal fun MainActivity.confirmDeleteCharacter(char: MayaCharacter) {
    android.app.AlertDialog.Builder(this)
        .setTitle("Karakteri Sil")
        .setMessage("\"${char.emoji} ${char.name}\" silinsin mi?")
        .setPositiveButton("Sil") { _, _ ->
            characters.removeAll { it.id == char.id }
            saveCharactersToPrefs(characters)
            if (char.id == activeCharacterId && characters.isNotEmpty()) {
                setActiveCharacter(characters.first())
            }
            characterAdapter.submitList(characters.toList())
            Toast.makeText(this, "\"${char.name}\" silindi", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("İptal", null).show()
}

// ── Avatar işleme ─────────────────────────────────────────────────────────────

/**
 * Karakter avatar URI seçildiğinde çağrılır.
 * URI'ye kalıcı okuma izni alır, avatarları klasöründe kopyalar.
 */
internal fun MainActivity.handleCharacterAvatarSelected(uri: Uri, charId: String?) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {}

    val uriStr = uri.toString()

    // Eğer bir diyalog callback'i bekliyorsa ona ilet
    pendingAvatarDialogCallback?.invoke(uriStr)

    // Eğer doğrudan mevcut bir karakter için seçildiyse güncelle
    if (charId != null && charId != "__new__") {
        val idx = characters.indexOfFirst { it.id == charId }
        if (idx >= 0) {
            characters[idx] = characters[idx].copy(avatarUri = uriStr)
            saveCharactersToPrefs(characters)
            characterAdapter.submitList(characters.toList())
            if (charId == activeCharacterId) {
                messageAdapter.charAvatarUri = uriStr
                messageAdapter.notifyDataSetChanged()
            }
        }
    }
}

/**
 * Kullanıcı avatar URI seçildiğinde çağrılır.
 */
internal fun MainActivity.handleUserAvatarSelected(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {}

    userAvatarUri = uri.toString()
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        .edit().putString("user_avatar_uri", userAvatarUri).apply()

    messageAdapter.userAvatarUri = userAvatarUri
    messageAdapter.notifyDataSetChanged()

    Toast.makeText(this, "✅ Kullanıcı fotoğrafı güncellendi", Toast.LENGTH_SHORT).show()
}

/**
 * URI'den yuvarlak Bitmap yükler.
 */
internal fun MainActivity.loadRoundedBitmap(uri: Uri, sizePx: Int): Bitmap? {
    return try {
        val input = contentResolver.openInputStream(uri) ?: return null
        val raw = BitmapFactory.decodeStream(input)
        input.close()
        raw ?: return null

        val size = raw.width.coerceAtMost(raw.height)
        val x = (raw.width - size) / 2
        val y = (raw.height - size) / 2
        val cropped = Bitmap.createBitmap(raw, x, y, size, size)
        val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)

        // Yuvarlak maske uygula
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawOval(
            android.graphics.RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
            paint
        )
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        output
    } catch (_: Exception) { null }
}

// ── Karakter yardımcıları ─────────────────────────────────────────────────────

internal fun MainActivity.loadCharactersFromPrefs(): List<MayaCharacter> {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val json = prefs.getString("characters_json", null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            MayaCharacter(
                id = obj.getString("id"),
                name = obj.getString("name"),
                userName = obj.optString("user_name", "Kullanıcı"),
                emoji = obj.optString("emoji", "🤖"),
                systemPrompt = obj.optString("system_prompt", ""),
                avatarUri = obj.optString("avatar_uri", "").ifEmpty { null }
            )
        }
    } catch (e: Exception) { emptyList() }
}

internal fun MainActivity.saveCharactersToPrefs(list: List<MayaCharacter>) {
    val arr = JSONArray()
    list.forEach { char ->
        arr.put(JSONObject().apply {
            put("id", char.id); put("name", char.name)
            put("user_name", char.userName); put("emoji", char.emoji)
            put("system_prompt", char.systemPrompt)
            put("avatar_uri", char.avatarUri ?: "")
        })
    }
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        .edit().putString("characters_json", arr.toString()).apply()
}

internal fun MainActivity.applyActiveCharacterValues() {
    val char = characters.find { it.id == activeCharacterId }
    if (char != null) {
        charName     = char.name
        userName     = char.userName
        systemPrompt = char.systemPrompt
    }
}

internal fun MainActivity.setActiveCharacter(char: MayaCharacter) {
    activeCharacterId = char.id
    charName          = char.name
    userName          = char.userName
    systemPrompt      = char.systemPrompt
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        .edit().putString("active_character_id", char.id).apply()
    saveSettings()
    characterAdapter.activeId = char.id
    characterAdapter.notifyDataSetChanged()
    messageAdapter.charName     = char.name
    messageAdapter.charEmoji    = char.emoji
    messageAdapter.charAvatarUri = char.avatarUri
    messageAdapter.userName     = char.userName
    messageAdapter.notifyDataSetChanged()
    updateActiveModelSubtitle()
    if (loadedModelPath != null && selectedTemplate == 0 && systemPrompt.isNotEmpty()) {
        lifecycleScope.launch {
            try { engine.setSystemPrompt(applyPersona(systemPrompt)) } catch (_: Exception) {}
        }
    }
}

// ── Persona yardımcısı ────────────────────────────────────────────────────────

internal fun MainActivity.applyPersona(text: String): String {
    val now = java.util.Calendar.getInstance()
    val dateStr = String.format("%d %s %d",
        now.get(java.util.Calendar.DAY_OF_MONTH),
        arrayOf("Ocak","Şubat","Mart","Nisan","Mayıs","Haziran",
                 "Temmuz","Ağustos","Eylül","Ekim","Kasım","Aralık")[now.get(java.util.Calendar.MONTH)],
        now.get(java.util.Calendar.YEAR))
    val timeStr = String.format("%02d:%02d",
        now.get(java.util.Calendar.HOUR_OF_DAY),
        now.get(java.util.Calendar.MINUTE))
    return text
        .replace("{{char}}", charName)
        .replace("{{user}}", userName)
        .replace("{{date}}", dateStr)
        .replace("{{time}}", timeStr)
}

internal fun MainActivity.stripCharPrefix(text: String): String {
    val p1 = "$charName: "
    val p2 = "$charName:"
    return when {
        text.startsWith(p1) -> text.removePrefix(p1)
        text.startsWith(p2) -> text.removePrefix(p2).trimStart()
        else -> text
    }
}
