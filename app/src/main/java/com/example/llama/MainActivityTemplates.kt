package tr.maya

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ── Özel şablon yardımcıları ──────────────────────────────────────────────────

internal fun MainActivity.loadCustomTemplatesFromPrefs(): List<MayaTemplate> {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val json  = prefs.getString("custom_templates_json", null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MayaTemplate(
                id               = o.getString("id"),
                name             = o.optString("name", "Şablon ${i+1}"),
                bosToken         = o.optString("bosToken",         ""),
                sysPrefix        = o.optString("sysPrefix",        ""),
                sysSuffix        = o.optString("sysSuffix",        ""),
                inputPrefix      = o.optString("inputPrefix",      ""),
                inputSuffix      = o.optString("inputSuffix",      ""),
                outputPrefix     = o.optString("outputPrefix",     ""),
                outputSuffix     = o.optString("outputSuffix",     ""),
                lastOutputPrefix = o.optString("lastOutputPrefix", ""),
                stopSeq          = o.optString("stopSeq",          "")
            )
        }
    } catch (e: Exception) { emptyList() }
}

internal fun MainActivity.saveCustomTemplatesToPrefs() {
    val arr = JSONArray()
    customTemplates.forEach { t ->
        arr.put(JSONObject().apply {
            put("id",               t.id)
            put("name",             t.name)
            put("bosToken",         t.bosToken)
            put("sysPrefix",        t.sysPrefix)
            put("sysSuffix",        t.sysSuffix)
            put("inputPrefix",      t.inputPrefix)
            put("inputSuffix",      t.inputSuffix)
            put("outputPrefix",     t.outputPrefix)
            put("outputSuffix",     t.outputSuffix)
            put("lastOutputPrefix", t.lastOutputPrefix)
            put("stopSeq",          t.stopSeq)
        })
    }
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        .edit().putString("custom_templates_json", arr.toString()).apply()
}

// ── Şablon seçici ─────────────────────────────────────────────────────────────

internal fun MainActivity.showTemplatePickerDialog(entry: String) {
    val builtIns = listOf(
        "0 · Otomatik (GGUF'tan)",
        "1 · Aya / Command-R",
        "2 · ChatML  —  Qwen, LFM, Phi-3, Hermes, Yi…",
        "3 · Gemma 3  —  Gemma 2/3, PaliGemma…",
        "4 · Llama 3  —  Llama-3.x, Ministral…",
        "5 · Granite  —  IBM Granite 3.x / 4.x…",
        "6 · (Özel Şablon)",
        "7 · Gemma 4  —  Gemma 4B E4B, think destekli…"
    )
    val prefs    = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val modelKey = "template_${MainActivity.entryDisplayName(entry)}"
    val saved    = prefs.getInt(modelKey, 0)

    val savedCustomId = if (saved == 6)
        prefs.getString("${modelKey}_custom_id", null) else null

    fun rebuildAndShow() {
        val allItems = builtIns.toMutableList()
        customTemplates.forEach { allItems.add("  ${it.name}") }
        allItems.add("➕  Yeni şablon ekle…")
        allItems.add("⚙️  Şablonları yönet…")

        // Built-in sayısı: 0-7 arası (0..7 = 8 item, index 0..7)
        val builtInCount = builtIns.size  // 8

        val currentSel = when {
            saved in 0..5 -> saved
            saved == 7    -> 7
            saved == 6 && savedCustomId != null -> {
                val idx = customTemplates.indexOfFirst { it.id == savedCustomId }
                if (idx >= 0) builtInCount + idx else 0
            }
            else -> 0
        }

        android.app.AlertDialog.Builder(this).setTitle("⚙️ Sohbet Şablonu")
            .setSingleChoiceItems(allItems.toTypedArray(), currentSel) { dialog, which ->
                when {
                    // Built-in şablonlar: 0-5 ve 7 (6 = eski özel şablon placeholder)
                    which in 0..5 -> {
                        selectedTemplate = which
                        selectedCustomTemplateId = null
                        prefs.edit().putInt(modelKey, which)
                            .remove("${modelKey}_custom_id").apply()
                        dialog.dismiss()
                        loadModel(entry)
                    }
                    which == 7 -> {
                        // Gemma 4
                        selectedTemplate = 7
                        selectedCustomTemplateId = null
                        prefs.edit().putInt(modelKey, 7)
                            .remove("${modelKey}_custom_id").apply()
                        dialog.dismiss()
                        loadModel(entry)
                    }
                    which < builtInCount + customTemplates.size -> {
                        // Özel şablonlar: index builtInCount .. builtInCount+custom.size-1
                        val tpl = customTemplates[which - builtInCount]
                        selectedTemplate = 6
                        selectedCustomTemplateId = tpl.id
                        prefs.edit().putInt(modelKey, 6)
                            .putString("${modelKey}_custom_id", tpl.id).apply()
                        saveSettings()
                        dialog.dismiss()
                        loadModel(entry)
                    }
                    which == builtInCount + customTemplates.size -> {
                        // "Yeni şablon ekle"
                        dialog.dismiss()
                        showTemplateEditDialog(null, onSaved = { newTpl ->
                            selectedTemplate = 6
                            selectedCustomTemplateId = newTpl.id
                            prefs.edit().putInt(modelKey, 6)
                                .putString("${modelKey}_custom_id", newTpl.id).apply()
                            saveSettings()
                            loadModel(entry)
                        })
                    }
                    else -> {
                        // "Şablonları yönet"
                        dialog.dismiss()
                        showTemplateManagerDialog(entry)
                    }
                }
            }.setNegativeButton("İptal", null).show()
    }
    rebuildAndShow()
}

// ── Şablon Yönetici ───────────────────────────────────────────────────────────

internal fun MainActivity.showTemplateManagerDialog(entry: String) {
    if (customTemplates.isEmpty()) {
        Toast.makeText(this, "Henüz özel şablon yok. Yeni ekleyin.", Toast.LENGTH_SHORT).show()
        showTemplateEditDialog(null, onSaved = { showTemplateManagerDialog(entry) })
        return
    }
    val names = customTemplates.map { it.name }.toTypedArray()
    android.app.AlertDialog.Builder(this)
        .setTitle("📋 Özel Şablonlar")
        .setItems(names) { _, which ->
            val tpl = customTemplates[which]
            android.app.AlertDialog.Builder(this)
                .setTitle(tpl.name)
                .setItems(arrayOf("✏️ Düzenle", "📋 Kopyala (baz al)", "🗑️ Sil")) { _, action ->
                    when (action) {
                        0 -> showTemplateEditDialog(tpl, onSaved = { showTemplateManagerDialog(entry) })
                        1 -> showTemplateEditDialog(
                            tpl.copy(id = UUID.randomUUID().toString(), name = "${tpl.name} (kopya)"),
                            forceNew = true,
                            onSaved = { showTemplateManagerDialog(entry) }
                        )
                        2 -> {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Şablonu Sil")
                                .setMessage("\"${tpl.name}\" silinsin mi?")
                                .setPositiveButton("Sil") { _, _ ->
                                    customTemplates.removeAll { it.id == tpl.id }
                                    saveCustomTemplatesToPrefs()
                                    if (selectedCustomTemplateId == tpl.id) {
                                        selectedCustomTemplateId = customTemplates.firstOrNull()?.id
                                        saveSettings()
                                    }
                                    Toast.makeText(this, "\"${tpl.name}\" silindi", Toast.LENGTH_SHORT).show()
                                    showTemplateManagerDialog(entry)
                                }.setNegativeButton("İptal") { _, _ -> showTemplateManagerDialog(entry) }.show()
                        }
                    }
                }.setNegativeButton("Geri") { _, _ -> showTemplatePickerDialog(entry) }.show()
        }
        .setPositiveButton("➕ Yeni Ekle") { _, _ ->
            showTemplateEditDialog(null, onSaved = { showTemplateManagerDialog(entry) })
        }
        .setNegativeButton("Kapat") { _, _ -> showTemplatePickerDialog(entry) }.show()
}

// ── Şablon Editörü ────────────────────────────────────────────────────────────

internal fun MainActivity.showTemplateEditDialog(
    existing: MayaTemplate?,
    forceNew: Boolean = false,
    onSaved: (MayaTemplate) -> Unit
) {
    val isNew = existing == null || forceNew
    val dp  = resources.displayMetrics.density
    val sv  = ScrollView(this)
    val lay = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
    }
    sv.addView(lay)

    fun label(txt: String): TextView = TextView(this).apply {
        text = txt; textSize = 11f; alpha = 0.65f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10*dp).toInt(); bottomMargin = (2*dp).toInt() }
    }
    fun field(value: String): android.widget.EditText = android.widget.EditText(this).apply {
        setText(value); textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setSingleLine(false); minLines = 1; maxLines = 3
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    lay.addView(label("Şablon Adı"))
    val fName = field(existing?.name ?: "").apply { hint = "Örn: DeepSeek-R2, Mistral-v3…" }
    lay.addView(fName)

    val presetScroll = android.widget.HorizontalScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10*dp).toInt(); bottomMargin = (6*dp).toInt() }
    }
    val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    presetScroll.addView(presetRow)
    lay.addView(TextView(this).apply {
        text = "Ön ayar (baz al):"; textSize = 11f; alpha = 0.65f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8*dp).toInt(); bottomMargin = (2*dp).toInt() }
    })
    lay.addView(presetScroll)

    val fBosToken    = field(existing?.bosToken         ?: "").apply { hint = "Örn: <bos>, <|begin_of_text|>, <|startoftext|> — boş bırakılabilir" }
    val fSysPrefix   = field(existing?.sysPrefix        ?: "")
    val fSysSuffix   = field(existing?.sysSuffix        ?: "")
    val fInPrefix    = field(existing?.inputPrefix      ?: "")
    val fInSuffix    = field(existing?.inputSuffix      ?: "")
    val fOutPrefix   = field(existing?.outputPrefix     ?: "")
    val fOutSuffix   = field(existing?.outputSuffix     ?: "")
    val fLastOut     = field(existing?.lastOutputPrefix ?: "")
    val fStopSeq     = field(existing?.stopSeq          ?: "")

    data class TplPreset(val name: String, val bos: String, val sysPrefix: String, val sysSuffix: String,
        val inPrefix: String, val inSuffix: String, val outPrefix: String,
        val outSuffix: String, val lastOut: String, val stop: String)

    val presets = listOf(
        TplPreset("ChatML",   "",
            "<|im_start|>system\n", "<|im_end|>\n",
            "<|im_start|>user\n",   "<|im_end|>\n",
            "<|im_start|>assistant\n", "<|im_end|>\n",
            "<|im_start|>assistant\n", "<|im_end|>"),
        TplPreset("Granite",  "",
            "<|start_of_role|>system<|end_of_role|>", "<|end_of_text|>\n",
            "<|start_of_role|>user<|end_of_role|>",   "<|end_of_text|>\n",
            "<|start_of_role|>assistant<|end_of_role|>", "<|end_of_text|>\n",
            "<|start_of_role|>assistant<|end_of_role|>", "<|end_of_text|>"),
        TplPreset("Llama 3",  "<|begin_of_text|>",
            "<|start_header_id|>system<|end_header_id|>\n\n", "<|eot_id|>",
            "<|start_header_id|>user<|end_header_id|>\n\n",   "<|eot_id|>",
            "<|start_header_id|>assistant<|end_header_id|>\n\n", "<|eot_id|>",
            "<|start_header_id|>assistant<|end_header_id|>\n\n", "<|eot_id|>"),
        TplPreset("Gemma 3",  "<bos>",
            "", "",
            "<start_of_turn>user\n", "<end_of_turn>\n",
            "<start_of_turn>model\n", "<end_of_turn>\n",
            "<start_of_turn>model\n", "<end_of_turn>"),
        TplPreset("Gemma 4",  "<bos>",
            "<|turn>system\n<|think|>", "<turn|>\n",
            "<|turn>user\n",            "<turn|>\n",
            "<|turn>model\n",           "<turn|>\n",
            "<|turn>model\n",           "<turn|>"),
        TplPreset("Aya / C-R", "<BOS_TOKEN>",
            "<|START_OF_TURN_TOKEN|><|SYSTEM_TOKEN|>", "<|END_OF_TURN_TOKEN|>",
            "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>",   "<|END_OF_TURN_TOKEN|>",
            "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>", "<|END_OF_TURN_TOKEN|>",
            "<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>", "<|END_OF_TURN_TOKEN|>"),
        TplPreset("DeepSeek", "",
            "", "",
            "<｜User｜>", "",
            "<｜Assistant｜>", "<｜end▁of▁sentence｜>",
            "<｜Assistant｜>", "<｜end▁of▁sentence｜>"),
        TplPreset("Mistral v3", "",
            "[INST]", "[/INST]",
            "[INST]", "[/INST]",
            "", "</s>",
            "", "</s>"),
        TplPreset("Phi-4",    "",
            "<|system|>\n", "<|end|>\n",
            "<|user|>\n",   "<|end|>\n",
            "<|assistant|>\n", "<|end|>\n",
            "<|assistant|>\n", "<|end|>")
    )

    presets.forEach { p ->
        val btn = Button(this).apply {
            text = p.name; textSize = 10f; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (4*dp).toInt() }
            setOnClickListener {
                fBosToken.setText(p.bos)
                fSysPrefix.setText(p.sysPrefix); fSysSuffix.setText(p.sysSuffix)
                fInPrefix.setText(p.inPrefix);   fInSuffix.setText(p.inSuffix)
                fOutPrefix.setText(p.outPrefix);  fOutSuffix.setText(p.outSuffix)
                fLastOut.setText(p.lastOut);      fStopSeq.setText(p.stop)
                if (fName.text.isBlank()) fName.setText(p.name)
            }
        }
        presetRow.addView(btn)
    }

    lay.addView(TextView(this).apply {
        text = "ℹ️  \\n → Enter'a bas (kaçış dizisi değil, gerçek satır sonu)"
        textSize = 10f; alpha = 0.5f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8*dp).toInt() }
    })

    listOf(
        "BOS Token (başa eklenir — genellikle boş)"  to fBosToken,
        "Sistem Prefix"                              to fSysPrefix,
        "Sistem Suffix"                              to fSysSuffix,
        "Kullanıcı Prefix"                           to fInPrefix,
        "Kullanıcı Suffix"                           to fInSuffix,
        "Asistan Prefix"                             to fOutPrefix,
        "Asistan Suffix"                             to fOutSuffix,
        "Son Asistan Prefix (generation)"            to fLastOut,
        "Stop Sequence"                              to fStopSeq
    ).forEach { (lbl, et) -> lay.addView(label(lbl)); lay.addView(et) }

    android.app.AlertDialog.Builder(this)
        .setTitle(if (isNew) "➕ Yeni Şablon" else "✏️ Şablonu Düzenle")
        .setView(sv)
        .setPositiveButton("Kaydet") { _, _ ->
            val name = fName.text.toString().trim().ifEmpty { "Özel Şablon" }
            val tpl = MayaTemplate(
                id               = if (isNew) UUID.randomUUID().toString() else existing!!.id,
                name             = name,
                bosToken         = fBosToken.text.toString(),
                sysPrefix        = fSysPrefix.text.toString(),
                sysSuffix        = fSysSuffix.text.toString(),
                inputPrefix      = fInPrefix.text.toString(),
                inputSuffix      = fInSuffix.text.toString(),
                outputPrefix     = fOutPrefix.text.toString(),
                outputSuffix     = fOutSuffix.text.toString(),
                lastOutputPrefix = fLastOut.text.toString(),
                stopSeq          = fStopSeq.text.toString()
            )
            if (isNew) {
                customTemplates.add(tpl)
            } else {
                val idx = customTemplates.indexOfFirst { it.id == tpl.id }
                if (idx >= 0) customTemplates[idx] = tpl
            }
            saveCustomTemplatesToPrefs()
            onSaved(tpl)
        }
        .setNegativeButton("İptal", null).show()
}
