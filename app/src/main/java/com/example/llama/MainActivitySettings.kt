package tr.maya

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

// ── Ayar yükleme / kaydetme ───────────────────────────────────────────────────

internal fun MainActivity.loadSettings() {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    contextSize       = prefs.getInt("context_size", 2048)
    predictLength     = prefs.getInt("predict_length", 512)
    temperature       = prefs.getFloat("temperature", 0.8f)
    topP              = prefs.getFloat("top_p", 0.95f)
    topK              = prefs.getInt("top_k", 40)
    noThinking        = prefs.getBoolean("no_thinking", false)
    autoLoadLastModel = prefs.getBoolean("auto_load_last_model", false)
    flashAttnMode     = prefs.getInt("flash_attn_mode", 1)
    useMmap           = prefs.getBoolean("use_mmap", true)
    useMlock          = prefs.getBoolean("use_mlock", false)
    bypassContextLength = prefs.getBoolean("bypass_context_length", false)
    appThemeMode      = prefs.getInt("app_theme_mode", MainActivity.THEME_SYSTEM)

    val oldEnabled = prefs.getBoolean("web_search_enabled", false)
    webSearchMode     = prefs.getString("web_search_mode",
                            if (oldEnabled) "trigger" else "off") ?: "off"
    webSearchQueryMode = prefs.getString("web_search_query_mode", "smart") ?: "smart"
    webSearchTriggers  = (prefs.getString("web_search_triggers", null)
        ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableList()
        ?: getDefaultTriggers().toMutableList())
    webSearchEngine      = prefs.getString("web_search_engine", "duckduckgo") ?: "duckduckgo"
    braveApiKey          = prefs.getString("brave_api_key", "") ?: ""
    searxngUrl           = prefs.getString("searxng_url", "https://searx.be") ?: "https://searx.be"
    webSearchResultCount = prefs.getInt("web_search_result_count", 5)
    webPageFetchEnabled  = prefs.getBoolean("web_page_fetch_enabled", false)

    urlFetchEnabled   = prefs.getBoolean("url_fetch_enabled", true)
    urlFetchCharLimit = prefs.getInt("url_fetch_char_limit", 5000)

    ReportProfile.migrateFromLegacy(this)
    reportProfiles = ReportProfile.loadAll(this)

    charName     = prefs.getString("char_name", "Asistan") ?: "Asistan"
    userName     = prefs.getString("user_name", "Kullanıcı") ?: "Kullanıcı"
    systemPrompt = prefs.getString("system_prompt", "") ?: ""

    customTemplates = loadCustomTemplatesFromPrefs().toMutableList()
    selectedCustomTemplateId = prefs.getString("selected_custom_tpl_id", null)

    skipAutoTitleConvIds.clear()
    skipAutoTitleConvIds.addAll(prefs.getStringSet("skip_auto_title_convs", emptySet())!!)

    characters = loadCharactersFromPrefs().toMutableList()
    activeCharacterId = prefs.getString("active_character_id", null)

    if (characters.isEmpty()) {
        val default = MayaCharacter(
            id = "default",
            name = charName,
            userName = userName,
            emoji = "🤖",
            systemPrompt = systemPrompt
        )
        characters.add(default)
        saveCharactersToPrefs(characters)
        activeCharacterId = "default"
        prefs.edit().putString("active_character_id", "default").apply()
    }

    applyActiveCharacterValues()
}

internal fun MainActivity.saveSettings() {
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit()
        .putInt("context_size", contextSize)
        .putInt("predict_length", predictLength)
        .putString("system_prompt", systemPrompt)
        .putFloat("temperature", temperature)
        .putFloat("top_p", topP)
        .putInt("top_k", topK)
        .putBoolean("no_thinking", noThinking)
        .putBoolean("auto_load_last_model", autoLoadLastModel)
        .putInt("flash_attn_mode", flashAttnMode)
        .putBoolean("use_mmap", useMmap)
        .putBoolean("use_mlock", useMlock)
        .putBoolean("bypass_context_length", bypassContextLength)
        .putInt("app_theme_mode", appThemeMode)
        .putString("web_search_mode", webSearchMode)
        .putBoolean("web_search_enabled", webSearchEnabled)
        .putString("web_search_query_mode", webSearchQueryMode)
        .putString("web_search_triggers", webSearchTriggers.joinToString("\n"))
        .putString("web_search_engine", webSearchEngine)
        .putString("brave_api_key", braveApiKey)
        .putString("searxng_url", searxngUrl)
        .putInt("web_search_result_count", webSearchResultCount)
        .putBoolean("web_page_fetch_enabled", webPageFetchEnabled)
        .putBoolean("url_fetch_enabled", urlFetchEnabled)
        .putInt("url_fetch_char_limit", urlFetchCharLimit)
        .putString("char_name", charName)
        .putString("user_name", userName)
        .putString("selected_custom_tpl_id", selectedCustomTemplateId)
        .apply()
}

// ── Yardımcı: Bölüm kartı ─────────────────────────────────────────────────────

/**
 * Ayarlar diyaloğundaki her kategoriyi görsel olarak ayıran kart yapısı.
 * Üstte emoji + başlık bandı, altında içerik alanı.
 * Dönen Pair: (kart LinearLayout, içerik LinearLayout)
 */
private fun MainActivity.makeSectionCard(
    parent: LinearLayout,
    emoji: String,
    title: String
): LinearLayout {
    val dp = resources.displayMetrics.density
    val isDark = MessageAdapter.isDarkTheme(this)

    // Dış kart — hafif köşe yuvarlaklığı ve border
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin    = (12 * dp).toInt()
            bottomMargin = (2 * dp).toInt()
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (10 * dp)
            setColor(if (isDark) 0xFF1C1C2E.toInt() else 0xFFF5F5FF.toInt())
            setStroke((1 * dp).toInt(), if (isDark) 0xFF2E2E4A.toInt() else 0xFFD8D8EC.toInt())
        }
    }

    // Başlık bandı
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // Sadece üst köşeler yuvarlak
            cornerRadii = floatArrayOf(
                10*dp, 10*dp,  // üst-sol
                10*dp, 10*dp,  // üst-sağ
                0f, 0f,         // alt-sağ
                0f, 0f          // alt-sol
            )
            setColor(if (isDark) 0xFF252540.toInt() else 0xFFEAEAFF.toInt())
        }
    }

    val emojiTv = TextView(this).apply {
        text = emoji
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * dp).toInt() }
    }

    val titleTv = TextView(this).apply {
        text = title
        textSize = 13f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(if (isDark) 0xFFCCCCFF.toInt() else 0xFF3333AA.toInt())
    }

    header.addView(emojiTv)
    header.addView(titleTv)

    // Ayırıcı çizgi
    val divider = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
        )
        setBackgroundColor(if (isDark) 0xFF2E2E4A.toInt() else 0xFFD8D8EC.toInt())
    }

    // İçerik alanı
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
    }

    card.addView(header)
    card.addView(divider)
    card.addView(content)
    parent.addView(card)

    return content
}

/** Kart içinde küçük alt başlık (bold, biraz soluk) */
private fun MainActivity.subLabel(text: String): TextView {
    val dp = resources.displayMetrics.density
    val isDark = MessageAdapter.isDarkTheme(this)
    return TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(if (isDark) 0xFFAAAAAA.toInt() else 0xFF666688.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt(); bottomMargin = (3 * dp).toInt() }
    }
}

/** Açıklama metni — küçük, soluk */
private fun MainActivity.hintText(text: String): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        this.text = text
        textSize = 11f
        alpha = 0.55f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (2 * dp).toInt(); bottomMargin = (6 * dp).toInt() }
    }
}

// ── Ayarlar diyaloğu ──────────────────────────────────────────────────────────

internal fun MainActivity.showSettingsDialog() {
    val ctx = this
    val dp = resources.displayMetrics.density
    val isDark = MessageAdapter.isDarkTheme(this)

    val scrollView = ScrollView(ctx)
    val layout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12*dp).toInt(), (8*dp).toInt(), (12*dp).toInt(), (16*dp).toInt())
    }
    scrollView.addView(layout)

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 1: MODEL PARAMETRELERİ
    // ═══════════════════════════════════════════════════════════════════════
    val secModel = makeSectionCard(layout, "🧠", "Model Parametreleri")

    // Context Window
    secModel.addView(subLabel("Context Window (token)"))
    val ctxRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val ctxStep = 256; val ctxMin = 256; val ctxMax = 32768
    val ctxBar = SeekBar(ctx).apply {
        max = (ctxMax - ctxMin) / ctxStep
        progress = ((contextSize.coerceIn(ctxMin, ctxMax) - ctxMin) / ctxStep)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val ctxEdit = android.widget.EditText(ctx).apply {
        setText(contextSize.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams((72*dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8*dp).toInt() }
        gravity = android.view.Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = (8*dp)
            setColor(if (isDark) 0xFF2A2A3A.toInt() else 0xFFEEEEFF.toInt())
            setStroke((1*dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAAAA.toInt())
        }
        setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
        setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    ctxBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) ctxEdit.setText((ctxMin + p * ctxStep).toString()) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })
    ctxEdit.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val v = s.toString().toIntOrNull() ?: return
            val prog = (v.coerceIn(ctxMin, ctxMax) - ctxMin) / ctxStep
            if (ctxBar.progress != prog) ctxBar.progress = prog
        }
    })
    ctxRow.addView(ctxBar); ctxRow.addView(ctxEdit); secModel.addView(ctxRow)
    secModel.addView(hintText("Modelin toplam hafızası (256–32768). RAM kullanımını doğrudan etkiler."))

    // Generated Tokens
    secModel.addView(subLabel("Generated Tokens (yanıt uzunluğu)"))
    val predictRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val predictStep = 128; val predictMin = 128; val predictMax = 4096
    val predictBar = SeekBar(ctx).apply {
        max = (predictMax - predictMin) / predictStep
        progress = ((predictLength.coerceIn(predictMin, predictMax) - predictMin) / predictStep)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val predictEdit = android.widget.EditText(ctx).apply {
        setText(predictLength.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams((72*dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8*dp).toInt() }
        gravity = android.view.Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = (8*dp)
            setColor(if (isDark) 0xFF2A2A3A.toInt() else 0xFFEEEEFF.toInt())
            setStroke((1*dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAAAA.toInt())
        }
        setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
        setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    predictBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) predictEdit.setText((predictMin + p * predictStep).toString()) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })
    predictEdit.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val v = s.toString().toIntOrNull() ?: return
            val prog = (v.coerceIn(predictMin, predictMax) - predictMin) / predictStep
            if (predictBar.progress != prog) predictBar.progress = prog
        }
    })
    predictRow.addView(predictBar); predictRow.addView(predictEdit); secModel.addView(predictRow)
    secModel.addView(hintText("Tek yanıtta üretilebilecek maksimum token (128–4096)."))

    // Temperature
    secModel.addView(subLabel("Temperature: %.2f".format(temperature)))
    val tempLabel = secModel.getChildAt(secModel.childCount - 1) as TextView
    val tempBar = SeekBar(ctx).apply {
        max = 200; progress = (temperature * 100).toInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { tempLabel.text = "Temperature: %.2f".format(p / 100f) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    secModel.addView(tempBar)

    // Top-P
    secModel.addView(subLabel("Top-P: %.2f".format(topP)))
    val topPLabel = secModel.getChildAt(secModel.childCount - 1) as TextView
    val topPBar = SeekBar(ctx).apply {
        max = 100; progress = (topP * 100).toInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { topPLabel.text = "Top-P: %.2f".format(p / 100f) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    secModel.addView(topPBar)

    // Top-K
    secModel.addView(subLabel("Top-K: $topK"))
    val topKLabel = secModel.getChildAt(secModel.childCount - 1) as TextView
    val topKBar = SeekBar(ctx).apply {
        max = 200; progress = topK
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { topKLabel.text = "Top-K: ${maxOf(1, p)}" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    secModel.addView(topKBar)

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 2: DÜŞÜNME MODU
    // ═══════════════════════════════════════════════════════════════════════
    val secThink = makeSectionCard(layout, "💭", "Düşünme Modu (Qwen3 / Gemma 4)")

    val noThinkingRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val noThinkingLabel = TextView(ctx).apply {
        text = "Düşünme modunu kapat"
        textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val noThinkingSwitch = Switch(ctx).apply { isChecked = noThinking }
    noThinkingRow.addView(noThinkingLabel); noThinkingRow.addView(noThinkingSwitch)
    secThink.addView(noThinkingRow)
    secThink.addView(hintText("Qwen3 için /no_think, Gemma 4 için <|think|> tokenını kapatır."))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 3: MODEL YÜKLEME & SOHBET
    // ═══════════════════════════════════════════════════════════════════════
    val secLoad = makeSectionCard(layout, "🚀", "Model Yükleme & Sohbet")

    val autoLoadRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val autoLoadLabel = TextView(ctx).apply {
        text = "Son modeli otomatik yükle"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val autoLoadSwitch = Switch(ctx).apply { isChecked = autoLoadLastModel }
    autoLoadRow.addView(autoLoadLabel); autoLoadRow.addView(autoLoadSwitch)
    secLoad.addView(autoLoadRow)
    secLoad.addView(hintText("Uygulama açılınca son yüklü model otomatik hazırlanır."))

    val bypassRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4*dp).toInt() }
    }
    val bypassLabel = TextView(ctx).apply {
        text = "Bypass Context Length (sonsuz sohbet)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val bypassSwitch = Switch(ctx).apply { isChecked = bypassContextLength }
    bypassRow.addView(bypassLabel); bypassRow.addView(bypassSwitch)
    secLoad.addView(bypassRow)
    secLoad.addView(hintText("Her turda context sıfırlanır, son mesajlar baştan encode edilir. İlk token biraz daha geç gelebilir."))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 4: PERFORMANS
    // ═══════════════════════════════════════════════════════════════════════
    val secPerf = makeSectionCard(layout, "⚡", "Performans")

    secPerf.addView(subLabel("Flash Attention"))
    val flashAttnGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
    val rbFlashOff  = RadioButton(ctx).apply {
        text = "⛔ Kapalı"; id = android.view.View.generateViewId(); isChecked = (flashAttnMode == 0)
        layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val rbFlashAuto = RadioButton(ctx).apply {
        text = "✨ Otomatik"; id = android.view.View.generateViewId(); isChecked = (flashAttnMode == 1)
        layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val rbFlashOn   = RadioButton(ctx).apply {
        text = "⚡ Açık"; id = android.view.View.generateViewId(); isChecked = (flashAttnMode == 2)
        layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    flashAttnGroup.addView(rbFlashOff); flashAttnGroup.addView(rbFlashAuto); flashAttnGroup.addView(rbFlashOn)
    secPerf.addView(flashAttnGroup)
    secPerf.addView(hintText("Otomatik: model destekliyorsa açar (önerilen). Değişiklik sonraki model yüklemede etkinleşir."))

    val mmapRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4*dp).toInt() }
    }
    val mmapLabel = TextView(ctx).apply {
        text = "Use mmap (bellek eşleme)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val mmapSwitch = Switch(ctx).apply { isChecked = useMmap }
    mmapRow.addView(mmapLabel); mmapRow.addView(mmapSwitch); secPerf.addView(mmapRow)
    secPerf.addView(hintText("Modeli RAM'e kopyalamak yerine doğrudan diskten okur. Açık olması önerilir."))

    val mlockRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4*dp).toInt() }
    }
    val mlockLabel = TextView(ctx).apply {
        text = "Use mlock (RAM'de kilitle)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val mlockSwitch = Switch(ctx).apply { isChecked = useMlock }
    mlockRow.addView(mlockLabel); mlockRow.addView(mlockSwitch); secPerf.addView(mlockRow)
    secPerf.addView(hintText("Modeli swap'a yazılmaktan korur. Büyük modellerde RAM yetersizse kapatın."))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 5: İNTERNET ARAMASI
    // ═══════════════════════════════════════════════════════════════════════
    val secWeb = makeSectionCard(layout, "🌐", "İnternet Araması")

    secWeb.addView(subLabel("Arama Modu"))
    val modeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
    val rbModeOff     = RadioButton(ctx).apply { text = "⛔ Kapalı"; id = android.view.View.generateViewId(); isChecked = (webSearchMode == "off") }
    val rbModeTrigger = RadioButton(ctx).apply { text = "🔍 Tetikleyici — anahtar kelime içeriyorsa ara"; id = android.view.View.generateViewId(); isChecked = (webSearchMode == "trigger") }
    val rbModeAlways  = RadioButton(ctx).apply { text = "🌐 Her zaman — tüm mesajlarda ara"; id = android.view.View.generateViewId(); isChecked = (webSearchMode == "always") }
    modeGroup.addView(rbModeOff); modeGroup.addView(rbModeTrigger); modeGroup.addView(rbModeAlways)
    secWeb.addView(modeGroup)
    secWeb.addView(hintText("Tetikleyici mod: sadece \"internette ara\", \"son haberler\" gibi ifadeler içeren mesajlarda çalışır."))

    secWeb.addView(subLabel("Sorgu Oluşturma"))
    val queryGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
    val rbQuerySimple = RadioButton(ctx).apply { text = "✂️ Basit — tetikleyicileri sil, kalan metni kullan"; id = android.view.View.generateViewId(); isChecked = (webSearchQueryMode == "simple") }
    val rbQuerySmart  = RadioButton(ctx).apply { text = "🧠 Akıllı — anahtar kelime + varlık çıkarımı (önerilen)"; id = android.view.View.generateViewId(); isChecked = (webSearchQueryMode == "smart") }
    queryGroup.addView(rbQuerySimple); queryGroup.addView(rbQuerySmart)
    secWeb.addView(queryGroup)

    secWeb.addView(subLabel("Tetikleyici Kelimeler"))
    val triggersEdit = android.widget.EditText(ctx).apply {
        setText(webSearchTriggers.joinToString("\n"))
        hint = "Her satıra bir tetikleyici kelime"
        minLines = 4; maxLines = 8; isSingleLine = false
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
        setHintTextColor(if (isDark) 0xFF666666.toInt() else 0xFF999999.toInt())
        background = GradientDrawable().apply {
            cornerRadius = 8*dp
            setColor(if (isDark) 0xFF1E1E2E.toInt() else 0xFFEEEEFF.toInt())
            setStroke((1*dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAACC.toInt())
        }
        setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    secWeb.addView(triggersEdit)
    val resetTriggersBtn = android.widget.Button(ctx).apply {
        text = "↩ Varsayılanlara Sıfırla"; textSize = 12f; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
        background = GradientDrawable().apply { cornerRadius = 6*dp; setColor(if (isDark) 0xFF1A2A3A.toInt() else 0xFFDDEEFF.toInt()) }
        setTextColor(if (isDark) 0xFF88AAFF.toInt() else 0xFF2244AA.toInt())
    }
    resetTriggersBtn.setOnClickListener { triggersEdit.setText(getDefaultTriggers().joinToString("\n")) }
    secWeb.addView(resetTriggersBtn)

    secWeb.addView(subLabel("Arama Motoru"))
    val engineGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
    val rbDDG     = RadioButton(ctx).apply { text = "DuckDuckGo (ücretsiz, API anahtarı gereksiz)"; id = android.view.View.generateViewId(); isChecked = (webSearchEngine == "duckduckgo") }
    val rbBrave   = RadioButton(ctx).apply { text = "Brave Search (ücretsiz, API anahtarı gerekir)"; id = android.view.View.generateViewId(); isChecked = (webSearchEngine == "brave") }
    val rbSearxng = RadioButton(ctx).apply { text = "SearXNG (açık kaynak, kendi instance)"; id = android.view.View.generateViewId(); isChecked = (webSearchEngine == "searxng") }
    engineGroup.addView(rbDDG); engineGroup.addView(rbBrave); engineGroup.addView(rbSearxng)
    secWeb.addView(engineGroup)

    secWeb.addView(subLabel("Brave API Anahtarı (Brave seçiliyse)"))
    val braveKeyEdit = android.widget.EditText(ctx).apply {
        setText(braveApiKey)
        hint = "BSA... (brave.com/search/api adresinden alın)"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = (8*dp)
            setColor(if (isDark) 0xFF1E1E2E.toInt() else 0xFFEEEEFF.toInt())
            setStroke((1*dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAACC.toInt())
        }
        setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
        setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    secWeb.addView(braveKeyEdit)

    secWeb.addView(subLabel("SearXNG Instance URL (SearXNG seçiliyse)"))
    val searxngUrlEdit = android.widget.EditText(ctx).apply {
        setText(searxngUrl)
        hint = "https://searx.be"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = (8*dp)
            setColor(if (isDark) 0xFF1E1E2E.toInt() else 0xFFEEEEFF.toInt())
            setStroke((1*dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAACC.toInt())
        }
        setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
        setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    secWeb.addView(searxngUrlEdit)
    secWeb.addView(hintText("Örnekler: searx.be, search.bus-hit.me, search.ononoki.org"))

    val resultCountInitial = webSearchResultCount.coerceIn(1, 10)
    secWeb.addView(subLabel("Sonuç Sayısı: $resultCountInitial"))
    val resultCountLabel = secWeb.getChildAt(secWeb.childCount - 1) as TextView
    val resultCountBar = SeekBar(ctx).apply {
        max = 9; progress = resultCountInitial - 1
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { resultCountLabel.text = "Sonuç Sayısı: ${p + 1}" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    secWeb.addView(resultCountBar)
    secWeb.addView(hintText("Modele gönderilecek arama sonucu (1–10). Az sonuç = daha az token."))

    val fetchRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4*dp).toInt() }
    }
    val fetchLabel = TextView(ctx).apply {
        text = "Sayfa içeriğini de getir (daha doğru)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val fetchSwitch = Switch(ctx).apply { isChecked = webPageFetchEnabled }
    fetchRow.addView(fetchLabel); fetchRow.addView(fetchSwitch); secWeb.addView(fetchRow)
    secWeb.addView(hintText("İlk 2 sonucun sayfasını açar ve içeriğini modele gönderir. ~2-5 sn ekstra süre."))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 6: URL OKUMA
    // ═══════════════════════════════════════════════════════════════════════
    val secUrl = makeSectionCard(layout, "🔗", "URL Okuma")

    val urlFetchRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val urlFetchLabel = TextView(ctx).apply {
        text = "URL'leri otomatik oku"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val urlFetchSwitch = Switch(ctx).apply { isChecked = urlFetchEnabled }
    urlFetchRow.addView(urlFetchLabel); urlFetchRow.addView(urlFetchSwitch); secUrl.addView(urlFetchRow)
    secUrl.addView(hintText("Mesajında URL varsa Maya o sayfayı çeker ve modele iletir. Web araması kapalıyken de çalışır."))

    secUrl.addView(subLabel("Sayfa başına maksimum karakter"))
    val urlCharLimitRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (2*dp).toInt() }
    }
    val urlCharLimitEdit = android.widget.EditText(ctx).apply {
        setText(urlFetchCharLimit.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "5000"
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = (8*dp)
            setColor(if (isDark) 0xFF1E1E2E.toInt() else 0xFFEEEEFF.toInt())
            setStroke((1*dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAACC.toInt())
        }
        setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
        setHintTextColor(if (isDark) 0xFF666666.toInt() else 0xFF999999.toInt())
        setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    fun quickBtn(label: String, bgColor: Int, textColor: Int, value: String) = android.widget.Button(ctx).apply {
        text = label; textSize = 11f; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (4*dp).toInt() }
        background = GradientDrawable().apply { cornerRadius = 6*dp; setColor(bgColor) }
        setTextColor(textColor)
        setOnClickListener { urlCharLimitEdit.setText(value) }
    }
    urlCharLimitRow.addView(urlCharLimitEdit)
    urlCharLimitRow.addView(quickBtn("2K", if (isDark) 0xFF1A2A3A.toInt() else 0xFFDDEEFF.toInt(), if (isDark) 0xFF88AAFF.toInt() else 0xFF2244AA.toInt(), "2000"))
    urlCharLimitRow.addView(quickBtn("5K", if (isDark) 0xFF1A3A1A.toInt() else 0xFFDDFFDD.toInt(), if (isDark) 0xFF88FF88.toInt() else 0xFF226622.toInt(), "5000"))
    urlCharLimitRow.addView(quickBtn("10K", if (isDark) 0xFF3A1A1A.toInt() else 0xFFFFDDDD.toInt(), if (isDark) 0xFFFF8888.toInt() else 0xFFAA2222.toInt(), "10000"))
    secUrl.addView(urlCharLimitRow)
    secUrl.addView(hintText("2K: hızlı/kısa  •  5K: önerilen  •  10K: uzun makaleler (büyük context gerektirir)"))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 7: RAPOR MODELİ
    // ═══════════════════════════════════════════════════════════════════════
    val secReportModel = makeSectionCard(layout, "🤖", "Rapor Modeli")

    val reportPrefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    var reportModelEntry    = reportPrefs.getString(DailyReportWorker.KEY_REPORT_MODEL_ENTRY, null)
    var reportModelTemplate = reportPrefs.getInt(DailyReportWorker.KEY_REPORT_MODEL_TEMPLATE, -1)
    var reportModelNoThink  = reportPrefs.getBoolean(DailyReportWorker.KEY_REPORT_MODEL_NO_THINK, false)

    fun reportModelDisplayName(): String {
        val entry = reportModelEntry ?: return "Ayarlanmamış (son yüklü model kullanılır)"
        return MainActivity.entryDisplayName(entry)
    }

    val reportModelRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val reportModelNameLabel = TextView(ctx).apply {
        text = reportModelDisplayName(); textSize = 12f
        setTextColor(if (isDark) 0xFFB0C8FF.toInt() else 0xFF2244AA.toInt())
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val reportModelSelectBtn = android.widget.Button(ctx).apply {
        text = "Seç"; textSize = 12f; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8*dp).toInt() }
        background = GradientDrawable().apply { cornerRadius = 6*dp; setColor(if (isDark) 0xFF1A3A5C.toInt() else 0xFFDDEEFF.toInt()) }
        setTextColor(if (isDark) 0xFF88AAFF.toInt() else 0xFF2244AA.toInt())
    }
    val reportModelClearBtn = android.widget.Button(ctx).apply {
        text = "✕"; textSize = 12f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (4*dp).toInt() }
        background = GradientDrawable().apply { cornerRadius = 6*dp; setColor(if (isDark) 0xFF3A1A1A.toInt() else 0xFFFFDDDD.toInt()) }
        setTextColor(if (isDark) 0xFFFF8888.toInt() else 0xFFAA2222.toInt())
    }
    reportModelRow.addView(reportModelNameLabel); reportModelRow.addView(reportModelSelectBtn); reportModelRow.addView(reportModelClearBtn)
    secReportModel.addView(reportModelRow)

    reportModelSelectBtn.setOnClickListener {
        val savedModels = reportPrefs.getStringSet("saved_models", mutableSetOf())!!.toMutableList()
        val modelsDir = getExternalFilesDir("models") ?: filesDir
        val cachedModels = savedModels.filter { entry ->
            if (MainActivity.isUriEntry(entry)) {
                val name = MainActivity.entryDisplayName(entry)
                java.io.File(modelsDir, "model_$name").exists()
            } else {
                java.io.File(entry).exists()
            }
        }
        if (cachedModels.isEmpty()) {
            Toast.makeText(ctx, "Önbellekte model yok. Önce bir modeli normal şekilde yükleyin.", Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        val names = cachedModels.map { entry ->
            val name = MainActivity.entryDisplayName(entry)
            if (entry == reportModelEntry) "✓ $name" else name
        }.toTypedArray()
        android.app.AlertDialog.Builder(ctx).setTitle("Rapor Modeli Seç")
            .setItems(names) { _, which ->
                reportModelEntry = cachedModels[which]
                reportModelNameLabel.text = reportModelDisplayName()
            }.setNegativeButton("İptal", null).show()
    }
    reportModelClearBtn.setOnClickListener {
        reportModelEntry = null; reportModelNameLabel.text = reportModelDisplayName()
    }
    secReportModel.addView(hintText("Sadece önbellekte hazır modeller listelenir. Ayarlanmazsa son yüklenen model kullanılır."))

    secReportModel.addView(subLabel("Şablon"))
    val templateNames = DailyReportWorker.TEMPLATE_NAMES
    val templateDisplayNames = arrayOf("Model varsayılanı (önerilen)") + templateNames
    var currentTemplateSelection = if (reportModelTemplate < 0) 0 else reportModelTemplate + 1
    val templateGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
    val templateRadios = templateDisplayNames.mapIndexed { i, name ->
        RadioButton(ctx).apply {
            text = name; id = android.view.View.generateViewId(); isChecked = (i == currentTemplateSelection)
            setOnCheckedChangeListener { _, checked -> if (checked) currentTemplateSelection = i }
        }
    }
    templateRadios.forEach { templateGroup.addView(it) }
    secReportModel.addView(templateGroup)

    val reportNoThinkRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8*dp).toInt() }
    }
    val reportNoThinkLabel = TextView(ctx).apply {
        text = "Düşünmeyi kapat (Qwen3 / Gemma 4)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val reportNoThinkSwitch = Switch(ctx).apply { isChecked = reportModelNoThink }
    reportNoThinkRow.addView(reportNoThinkLabel); reportNoThinkRow.addView(reportNoThinkSwitch)
    secReportModel.addView(reportNoThinkRow)
    secReportModel.addView(hintText("Token tasarrufu sağlar. Diğer modelleri etkilemez."))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 8: RAPOR CONTEXT BOYUTU
    // ═══════════════════════════════════════════════════════════════════════
    val secReportCtx = makeSectionCard(layout, "📐", "Rapor Context Boyutu")

    val reportCtxPrefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    var reportContextSize = reportCtxPrefs.getInt("report_context_size", 8192)
    val rCtxRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val rCtxStep = 1024; val rCtxMin = 2048; val rCtxMax = 32768
    val rCtxBar = SeekBar(ctx).apply {
        max = (rCtxMax - rCtxMin) / rCtxStep
        progress = ((reportContextSize.coerceIn(rCtxMin, rCtxMax) - rCtxMin) / rCtxStep)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val rCtxEdit = android.widget.EditText(ctx).apply {
        setText(reportContextSize.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams((80*dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8*dp).toInt() }
        gravity = android.view.Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = (8*dp)
            setColor(if (isDark) 0xFF2A2A3A.toInt() else 0xFFEEEEFF.toInt())
            setStroke((1*dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAACC.toInt())
        }
        setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
        setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    rCtxBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) rCtxEdit.setText((rCtxMin + p * rCtxStep).toString()) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })
    rCtxEdit.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val v = s.toString().toIntOrNull() ?: return
            val prog = (v.coerceIn(rCtxMin, rCtxMax) - rCtxMin) / rCtxStep
            if (rCtxBar.progress != prog) rCtxBar.progress = prog
        }
    })
    rCtxRow.addView(rCtxBar); rCtxRow.addView(rCtxEdit); secReportCtx.addView(rCtxRow)
    secReportCtx.addView(hintText("Thinking kapalıysa 8192 yeterli. Thinking açıksa 16384+ önerilebilir."))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 9: RAPOR PROFİLLERİ
    // ═══════════════════════════════════════════════════════════════════════
    val secProfiles = makeSectionCard(layout, "📰", "Rapor Profilleri")

    val profileListContainer = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    secProfiles.addView(profileListContainer)

    fun refreshProfileList() {
        profileListContainer.removeAllViews()
        if (reportProfiles.isEmpty()) {
            profileListContainer.addView(TextView(ctx).apply {
                text = "Henüz profil yok. Aşağıdan ekleyin."; textSize = 11f; alpha = 0.5f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
            })
        }
        reportProfiles.forEachIndexed { idx, profile ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
                background = GradientDrawable().apply { cornerRadius = 8*dp; setColor(if (isDark) 0xFF252535.toInt() else 0xFFEEEEFF.toInt()) }
                setPadding((8*dp).toInt(), (4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt()); minimumHeight = (48*dp).toInt()
            }
            @Suppress("DEPRECATION") val toggle = Switch(ctx).apply {
                isChecked = profile.enabled
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            }
            val nameTime = TextView(ctx).apply {
                text = "%s  %d:%02d".format(profile.name, profile.hour, profile.minute)
                textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = android.view.Gravity.CENTER_VERTICAL; marginStart = (10*dp).toInt(); marginEnd = (4*dp).toInt() }
            }
            fun iconBtn(label: String, color: Int) = android.widget.Button(ctx).apply {
                text = label; textSize = 14f; setBackgroundColor(0x00000000); setTextColor(color)
                setPadding((10*dp).toInt(), 0, (10*dp).toInt(), 0); minWidth = 0; minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            }
            val testBtn = iconBtn("▶", 0xFF55CC77.toInt())
            val editBtn = iconBtn("✎", if (isDark) 0xFF88AAFF.toInt() else 0xFF2244AA.toInt())
            toggle.setOnCheckedChangeListener { _, checked ->
                reportProfiles[idx] = profile.copy(enabled = checked)
                ReportProfile.saveAll(this, reportProfiles)
                if (checked) DailyReportScheduler.schedule(this, reportProfiles[idx])
                else DailyReportScheduler.cancel(this, profile)
            }
            editBtn.setOnClickListener { showProfileEditDialog(profile) { refreshProfileList() } }
            testBtn.setOnClickListener {
                try {
                    getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE).edit().remove("last_worker_error").apply()
                    val data = androidx.work.Data.Builder().putString("profile_id", profile.id).build()
                    val req = androidx.work.OneTimeWorkRequestBuilder<DailyReportWorker>().setInputData(data).build()
                    val wm = androidx.work.WorkManager.getInstance(applicationContext)
                    wm.enqueue(req)
                    wm.getWorkInfoByIdLiveData(req.id).observe(this@showSettingsDialog) { info ->
                        if (info == null) return@observe
                        when (info.state) {
                            androidx.work.WorkInfo.State.SUCCEEDED -> runOnUiThread { Toast.makeText(ctx, "✅ '${profile.name}' tamamlandı", Toast.LENGTH_SHORT).show() }
                            androidx.work.WorkInfo.State.FAILED -> runOnUiThread {
                                val err = getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE).getString("last_worker_error", "Bilinmeyen hata") ?: "Bilinmeyen hata"
                                android.app.AlertDialog.Builder(this@showSettingsDialog).setTitle("❌ Worker Hatası").setMessage(err).setPositiveButton("Tamam", null).show()
                            }
                            else -> {}
                        }
                    }
                    Toast.makeText(ctx, "▶ '${profile.name}' başlatıldı…", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.app.AlertDialog.Builder(ctx).setTitle("❌ Hata").setMessage(e.message).setPositiveButton("Tamam", null).show()
                }
            }
            row.addView(toggle); row.addView(nameTime); row.addView(testBtn); row.addView(editBtn)
            profileListContainer.addView(row)
        }
    }
    refreshProfileList()

    val addProfileBtn = android.widget.Button(ctx).apply {
        text = "+ Profil Ekle"; textSize = 13f; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8*dp).toInt() }
        background = GradientDrawable().apply { cornerRadius = 8*dp; setColor(if (isDark) 0xFF1A3A5C.toInt() else 0xFFDDEEFF.toInt()) }
        setTextColor(if (isDark) 0xFF88AAFF.toInt() else 0xFF2244AA.toInt())
    }
    addProfileBtn.setOnClickListener { showProfileEditDialog(null) { refreshProfileList() } }
    secProfiles.addView(addProfileBtn)
    secProfiles.addView(hintText("Her profil kendi saatinde çalışır. https:// ile başlayan konular RSS olarak çekilir."))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 10: UYGULAMA TEMASI
    // ═══════════════════════════════════════════════════════════════════════
    val secTheme = makeSectionCard(layout, "🌓", "Uygulama Teması")

    val themeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
    val rbThemeSystem = RadioButton(ctx).apply { text = "⚙️ Sistem"; id = android.view.View.generateViewId(); isChecked = (appThemeMode == MainActivity.THEME_SYSTEM) }
    val rbThemeDark   = RadioButton(ctx).apply { text = "🌙 Karanlık"; id = android.view.View.generateViewId(); isChecked = (appThemeMode == MainActivity.THEME_DARK) }
    val rbThemeLight  = RadioButton(ctx).apply { text = "☀️ Aydınlık"; id = android.view.View.generateViewId(); isChecked = (appThemeMode == MainActivity.THEME_LIGHT) }
    themeGroup.addView(rbThemeSystem); themeGroup.addView(rbThemeDark); themeGroup.addView(rbThemeLight)
    secTheme.addView(themeGroup)
    secTheme.addView(hintText("Değişiklik kaydedilince hemen uygulanır."))

    // ═══════════════════════════════════════════════════════════════════════
    // BÖLÜM 11: ÖNBELLEK YÖNETİMİ
    // ═══════════════════════════════════════════════════════════════════════
    val secCache = makeSectionCard(layout, "🗂️", "Önbellek Yönetimi")

    val cacheStatusLabel = TextView(ctx).apply {
        text = getCacheInfo(); textSize = 12f
        setTextColor(if (isDark) 0xFF9090C0.toInt() else 0xFF6666AA.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    }
    secCache.addView(cacheStatusLabel)
    val clearCacheBtn = android.widget.Button(ctx).apply {
        text = "🗑️ Önbelleği Temizle"; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    clearCacheBtn.setOnClickListener { clearUnusedModelCache(cacheStatusLabel) }
    secCache.addView(clearCacheBtn)
    secCache.addView(hintText("Listeden kaldırılan modeller önbellekten de silinir."))

    // ═══════════════════════════════════════════════════════════════════════
    // KAYDET / İPTAL
    // ═══════════════════════════════════════════════════════════════════════
    val btnRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (16*dp).toInt() }
    }
    val btnIptal = android.widget.Button(ctx).apply {
        text = "✖ İptal"; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8*dp).toInt() }
    }
    val btnKaydet = android.widget.Button(ctx).apply { text = "✔ Kaydet"; isAllCaps = false }
    btnRow.addView(btnIptal); btnRow.addView(btnKaydet); layout.addView(btnRow)

    val dialog = android.app.AlertDialog.Builder(this).setTitle("⚙️ Ayarlar").setView(scrollView).create()

    btnIptal.setOnClickListener { dialog.dismiss() }
    btnKaydet.setOnClickListener {
        val ctxVal = ctxEdit.text.toString().toIntOrNull()?.coerceIn(256, 32768) ?: contextSize
        contextSize = (ctxVal / 256) * 256
        val predictVal = predictEdit.text.toString().toIntOrNull()?.coerceIn(128, 4096) ?: predictLength
        predictLength = (predictVal / 128) * 128
        temperature       = tempBar.progress / 100f
        topP              = topPBar.progress / 100f
        topK              = maxOf(1, topKBar.progress)
        noThinking        = noThinkingSwitch.isChecked
        autoLoadLastModel = autoLoadSwitch.isChecked
        flashAttnMode     = when {
            rbFlashOff.isChecked -> 0
            rbFlashOn.isChecked  -> 2
            else                 -> 1
        }
        useMmap             = mmapSwitch.isChecked
        useMlock            = mlockSwitch.isChecked
        bypassContextLength = bypassSwitch.isChecked
        webSearchMode       = when {
            rbModeTrigger.isChecked -> "trigger"
            rbModeAlways.isChecked  -> "always"
            else                    -> "off"
        }
        webSearchQueryMode  = if (rbQuerySmart.isChecked) "smart" else "simple"
        webSearchTriggers   = triggersEdit.text.toString()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            .ifEmpty { getDefaultTriggers().toMutableList() }
        webSearchEngine     = when {
            rbBrave.isChecked   -> "brave"
            rbSearxng.isChecked -> "searxng"
            else                -> "duckduckgo"
        }
        braveApiKey          = braveKeyEdit.text.toString().trim()
        searxngUrl           = searxngUrlEdit.text.toString().trim().ifEmpty { "https://searx.be" }
        webSearchResultCount = resultCountBar.progress + 1
        webPageFetchEnabled  = fetchSwitch.isChecked
        urlFetchEnabled      = urlFetchSwitch.isChecked
        urlFetchCharLimit    = urlCharLimitEdit.text.toString().toIntOrNull()?.coerceIn(500, 50000) ?: 5000

        reportModelNoThink = reportNoThinkSwitch.isChecked
        val reportCtxVal = rCtxEdit.text.toString().toIntOrNull()?.coerceIn(2048, 32768) ?: 8192
        val reportCtxAligned = (reportCtxVal / 1024) * 1024
        val templateToSave = if (currentTemplateSelection == 0) -1 else currentTemplateSelection - 1
        getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit().apply {
            if (reportModelEntry != null) putString(DailyReportWorker.KEY_REPORT_MODEL_ENTRY, reportModelEntry)
            else remove(DailyReportWorker.KEY_REPORT_MODEL_ENTRY)
            putInt(DailyReportWorker.KEY_REPORT_MODEL_TEMPLATE, templateToSave)
            putBoolean(DailyReportWorker.KEY_REPORT_MODEL_NO_THINK, reportModelNoThink)
            putInt("report_context_size", reportCtxAligned)
        }.apply()

        appThemeMode = when {
            rbThemeDark.isChecked  -> MainActivity.THEME_DARK
            rbThemeLight.isChecked -> MainActivity.THEME_LIGHT
            else                   -> MainActivity.THEME_SYSTEM
        }
        getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit()
            .putInt("app_theme_mode", appThemeMode).apply()
        MainActivity.applyThemeMode(appThemeMode)

        updateWebSearchButton()
        updateActiveModelSubtitle()
        saveSettings()
        Toast.makeText(this, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
        dialog.dismiss()
    }
    dialog.show()
}
