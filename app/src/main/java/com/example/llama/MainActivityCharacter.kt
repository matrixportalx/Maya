package tr.maya

import android.content.Context
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import org.json.JSONArray
import org.json.JSONObject
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

    layout.addView(label("Emoji"))
    val emojiField = field("🤖", existing?.emoji ?: "🤖")
    layout.addView(emojiField)

    layout.addView(label("Karakter adı ({{char}})"))
    val nameField = field("Asistan", existing?.name ?: "")
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
            val emoji   = emojiField.text.toString().trim().ifEmpty { "🤖" }
            val name    = nameField.text.toString().trim().ifEmpty { "Asistan" }
            val uName   = userNameField.text.toString().trim().ifEmpty { "Kullanıcı" }
            val prompt  = promptField.text.toString().trim()

            val char = MayaCharacter(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name, userName = uName, emoji = emoji, systemPrompt = prompt
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
        }
        .setNegativeButton("İptal", null).show()
}

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
                systemPrompt = obj.optString("system_prompt", "")
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
    messageAdapter.charName  = char.name
    messageAdapter.charEmoji = char.emoji
    messageAdapter.userName  = char.userName
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
