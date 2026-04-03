package tr.maya

import android.content.Context
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
    flashAttnMode     = prefs.getInt("flash_attn_mode", 1)  // 0=Kapalı, 1=Otomatik, 2=Açık
    useMmap           = prefs.getBoolean("use_mmap", true)
    useMlock          = prefs.getBoolean("use_mlock", false)
    bypassContextLength = prefs.getBoolean("bypass_context_length", false)
    // v5.8: Tema ayarı
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

    // v5.5: URL okuma ayarları
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
        // v5.8: Tema ayarı
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
        // v5.5: URL okuma ayarları
        .putBoolean("url_fetch_enabled", urlFetchEnabled)
        .putInt("url_fetch_char_limit", urlFetchCharLimit)
        .putString("char_name", charName)
        .putString("user_name", userName)
        .putString("selected_custom_tpl_id", selectedCustomTemplateId)
        .apply()
}

// ── Ayarlar diyaloğu ──────────────────────────────────────────────────────────

internal fun MainActivity.showSettingsDialog() {
    val ctx = this
    val dp = resources.displayMetrics.density
    val scrollView = ScrollView(ctx)
    val layout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt())
    }
    scrollView.addView(layout)

    fun sectionTitle(text: String) = TextView(ctx).apply {
        this.text = text; textSize = 14f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12*dp).toInt(); bottomMargin = (4*dp).toInt() }
    }

    // ── Context Window ────────────────────────────────────────────────────────
    layout.addView(sectionTitle("Context Window (token)"))
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
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (8*dp); setColor(0xFF2A2A2A.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setTextColor(0xFFE0E0E0.toInt()); setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
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
    ctxRow.addView(ctxBar); ctxRow.addView(ctxEdit); layout.addView(ctxRow)
    layout.addView(TextView(ctx).apply {
        text = "Modelin toplam hafızası (256–32768). RAM kullanımını doğrudan etkiler. Model desteklemiyorsa llama.cpp kısıtlar."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })

    // ── Generated Tokens ─────────────────────────────────────────────────────
    layout.addView(sectionTitle("Generated Tokens (yanıt uzunluğu)"))
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
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (8*dp); setColor(0xFF2A2A2A.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setTextColor(0xFFE0E0E0.toInt()); setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
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
    predictRow.addView(predictBar); predictRow.addView(predictEdit); layout.addView(predictRow)
    layout.addView(TextView(ctx).apply {
        text = "Tek yanıtta üretilebilecek maksimum token sayısı (128–4096)."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })

    // ── Temperature ───────────────────────────────────────────────────────────
    layout.addView(sectionTitle("Temperature: %.2f".format(temperature)))
    val tempLabel = layout.getChildAt(layout.childCount - 1) as TextView
    val tempBar = SeekBar(ctx).apply {
        max = 200; progress = (temperature * 100).toInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { tempLabel.text = "Temperature: %.2f".format(p / 100f) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    layout.addView(tempBar)

    // ── Top-P ─────────────────────────────────────────────────────────────────
    layout.addView(sectionTitle("Top-P: %.2f".format(topP)))
    val topPLabel = layout.getChildAt(layout.childCount - 1) as TextView
    val topPBar = SeekBar(ctx).apply {
        max = 100; progress = (topP * 100).toInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { topPLabel.text = "Top-P: %.2f".format(p / 100f) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    layout.addView(topPBar)

    // ── Top-K ─────────────────────────────────────────────────────────────────
    layout.addView(sectionTitle("Top-K: $topK"))
    val topKLabel = layout.getChildAt(layout.childCount - 1) as TextView
    val topKBar = SeekBar(ctx).apply {
        max = 200; progress = topK
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { topKLabel.text = "Top-K: ${maxOf(1, p)}" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    layout.addView(topKBar)

    // ── Qwen3 ─────────────────────────────────────────────────────────────────
    layout.addView(sectionTitle("Qwen3 / Gemma 4 Düşünme Ayarı"))
    val noThinkingRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val noThinkingLabel = TextView(ctx).apply {
        text = "💭 Düşünme modunu kapat"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val noThinkingSwitch = Switch(ctx).apply { isChecked = noThinking }
    noThinkingRow.addView(noThinkingLabel); noThinkingRow.addView(noThinkingSwitch); layout.addView(noThinkingRow)
    layout.addView(TextView(ctx).apply {
        text = "Qwen3 için /no_think, Gemma 4 için <|think|> tokenını kapatır. Gereksiz düşünme bloklarını önler."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    // ── Model Yükleme ─────────────────────────────────────────────────────────
    layout.addView(sectionTitle("Model Yükleme"))
    val autoLoadRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val autoLoadLabel = TextView(ctx).apply {
        text = "🚀 Son modeli otomatik yükle"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val autoLoadSwitch = Switch(ctx).apply { isChecked = autoLoadLastModel }
    autoLoadRow.addView(autoLoadLabel); autoLoadRow.addView(autoLoadSwitch); layout.addView(autoLoadRow)
    layout.addView(TextView(ctx).apply {
        text = "Uygulama açılınca son yüklü model otomatik hazırlanır."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    // ── Bypass Context Length ─────────────────────────────────────────────────
    layout.addView(sectionTitle("Sohbet"))
    val bypassRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val bypassLabel = TextView(ctx).apply {
        text = "🔁 Bypass Context Length (sonsuz sohbet)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val bypassSwitch = Switch(ctx).apply { isChecked = bypassContextLength }
    bypassRow.addView(bypassLabel); bypassRow.addView(bypassSwitch); layout.addView(bypassRow)
    layout.addView(TextView(ctx).apply {
        text = "Her turda context sıfırlanır ve son mesajlar baştan encode edilir. " +
               "KV shifting olmaz; model bağlamı her zaman tutarlı kalır. " +
               "İlk token biraz daha geç gelebilir."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    // ── Performans ────────────────────────────────────────────────────────────
    layout.addView(sectionTitle("Performans"))
    layout.addView(TextView(ctx).apply {
        text = "⚡ Flash Attention"; textSize = 13f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (4*dp).toInt() }
    })
    val flashAttnGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
    val rbFlashOff  = RadioButton(ctx).apply {
        text = "⛔ Kapalı"; id = android.view.View.generateViewId()
        isChecked = (flashAttnMode == 0)
        layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val rbFlashAuto = RadioButton(ctx).apply {
        text = "✨ Otomatik"; id = android.view.View.generateViewId()
        isChecked = (flashAttnMode == 1)
        layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val rbFlashOn   = RadioButton(ctx).apply {
        text = "⚡ Açık"; id = android.view.View.generateViewId()
        isChecked = (flashAttnMode == 2)
        layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    flashAttnGroup.addView(rbFlashOff); flashAttnGroup.addView(rbFlashAuto); flashAttnGroup.addView(rbFlashOn)
    layout.addView(flashAttnGroup)
    layout.addView(TextView(ctx).apply {
        text = "Otomatik: model destekliyorsa açar (önerilen, llama.cpp varsayılanı). " +
               "Açık: zorla etkinleştirir — desteklenmeyen modellerde sorun çıkabilir. " +
               "Değişiklik sonraki model yüklemede etkinleşir."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })

    val mmapRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val mmapLabel = TextView(ctx).apply {
        text = "🗂️ Use mmap (bellek eşleme)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val mmapSwitch = Switch(ctx).apply { isChecked = useMmap }
    mmapRow.addView(mmapLabel); mmapRow.addView(mmapSwitch); layout.addView(mmapRow)
    layout.addView(TextView(ctx).apply {
        text = "Modeli RAM'e kopyalamak yerine doğrudan diskten okur. Açık olması önerilir. Değişiklik sonraki model yüklemede etkinleşir."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })

    val mlockRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val mlockLabel = TextView(ctx).apply {
        text = "🔒 Use mlock (RAM'de kilitle)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val mlockSwitch = Switch(ctx).apply { isChecked = useMlock }
    mlockRow.addView(mlockLabel); mlockRow.addView(mlockSwitch); layout.addView(mlockRow)
    layout.addView(TextView(ctx).apply {
        text = "Modeli swap'a yazılmaktan korur. Büyük modellerde RAM yetersizse kapatın. Değişiklik sonraki model yüklemede etkinleşir."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    // ── İnternet Araması ──────────────────────────────────────────────────────
    layout.addView(sectionTitle("🌐 İnternet Araması"))

    layout.addView(TextView(ctx).apply {
        text = "Arama Modu"; textSize = 12f; alpha = 0.75f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })
    val modeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
    val rbModeOff     = RadioButton(ctx).apply { text = "⛔ Kapalı — web araması yapma"; id = android.view.View.generateViewId(); isChecked = (webSearchMode == "off") }
    val rbModeTrigger = RadioButton(ctx).apply { text = "🔍 Tetikleyici — anahtar kelime içeriyorsa ara"; id = android.view.View.generateViewId(); isChecked = (webSearchMode == "trigger") }
    val rbModeAlways  = RadioButton(ctx).apply { text = "🌐 Her zaman — tüm mesajlarda ara"; id = android.view.View.generateViewId(); isChecked = (webSearchMode == "always") }
    modeGroup.addView(rbModeOff); modeGroup.addView(rbModeTrigger); modeGroup.addView(rbModeAlways)
    layout.addView(modeGroup)
    layout.addView(TextView(ctx).apply {
        text = "Tetikleyici mod: sadece \"internette ara\", \"son haberler\" gibi tetikleyici içeren mesajlarda arama yapar. Her zaman mod: merhaba, teşekkür gibi mesajlarda da arama yapar."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (10*dp).toInt() }
    })

    layout.addView(TextView(ctx).apply {
        text = "Sorgu Oluşturma"; textSize = 12f; alpha = 0.75f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })
    val queryGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
    val rbQuerySimple = RadioButton(ctx).apply { text = "✂️ Basit — tetikleyicileri sil, kalan metni kullan"; id = android.view.View.generateViewId(); isChecked = (webSearchQueryMode == "simple") }
    val rbQuerySmart  = RadioButton(ctx).apply { text = "🧠 Akıllı — anahtar kelime + varlık çıkarımı (önerilen)"; id = android.view.View.generateViewId(); isChecked = (webSearchQueryMode == "smart") }
    queryGroup.addView(rbQuerySimple); queryGroup.addView(rbQuerySmart)
    layout.addView(queryGroup)
    layout.addView(TextView(ctx).apply {
        text = "Akıllı mod: uzun mesajdan isimleri, markaları, önemli kelimeleri otomatik çıkarır."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (10*dp).toInt() }
    })

    layout.addView(TextView(ctx).apply {
        text = "Tetikleyici Kelimeler"; textSize = 12f; alpha = 0.75f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })
    val triggersEdit = android.widget.EditText(ctx).apply {
        setText(webSearchTriggers.joinToString("\n"))
        hint = "Her satıra bir tetikleyici kelime"
        minLines = 5; maxLines = 10; isSingleLine = false
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setTextColor(0xFFE0E0E0.toInt()); setHintTextColor(0xFF666666.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 8*dp; setColor(0xFF1E1E1E.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (2*dp).toInt() }
    }
    layout.addView(triggersEdit)
    val resetTriggersBtn = android.widget.Button(ctx).apply {
        text = "↩ Varsayılanlara Sıfırla"; textSize = 12f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (10*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6*dp; setColor(0xFF1A2A3A.toInt()) }
        setTextColor(0xFF88AAFF.toInt())
    }
    resetTriggersBtn.setOnClickListener { triggersEdit.setText(getDefaultTriggers().joinToString("\n")) }
    layout.addView(resetTriggersBtn)

    layout.addView(TextView(ctx).apply {
        text = "Arama Motoru"; textSize = 12f; alpha = 0.75f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4*dp).toInt(); bottomMargin = (4*dp).toInt() }
    })
    val engineGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
    val rbDDG     = RadioButton(ctx).apply { text = "DuckDuckGo (ücretsiz, API anahtarı gereksiz)"; id = android.view.View.generateViewId(); isChecked = (webSearchEngine == "duckduckgo") }
    val rbBrave   = RadioButton(ctx).apply { text = "Brave Search (ücretsiz, API anahtarı gerekir)"; id = android.view.View.generateViewId(); isChecked = (webSearchEngine == "brave") }
    val rbSearxng = RadioButton(ctx).apply { text = "SearXNG (açık kaynak, kendi instance)"; id = android.view.View.generateViewId(); isChecked = (webSearchEngine == "searxng") }
    engineGroup.addView(rbDDG); engineGroup.addView(rbBrave); engineGroup.addView(rbSearxng)
    layout.addView(engineGroup)

    layout.addView(TextView(ctx).apply {
        text = "Brave API Anahtarı (Brave seçiliyse)"; textSize = 12f; alpha = 0.75f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8*dp).toInt(); bottomMargin = (2*dp).toInt() }
    })
    val braveKeyEdit = android.widget.EditText(ctx).apply {
        setText(braveApiKey)
        hint = "BSA... (brave.com/search/api adresinden alın)"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (8*dp); setColor(0xFF2A2A2A.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setTextColor(0xFFE0E0E0.toInt()); setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    layout.addView(braveKeyEdit)

    layout.addView(TextView(ctx).apply {
        text = "SearXNG Instance URL (SearXNG seçiliyse)"; textSize = 12f; alpha = 0.75f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8*dp).toInt(); bottomMargin = (2*dp).toInt() }
    })
    val searxngUrlEdit = android.widget.EditText(ctx).apply {
        setText(searxngUrl)
        hint = "https://searx.be"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (8*dp); setColor(0xFF2A2A2A.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setTextColor(0xFFE0E0E0.toInt()); setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    layout.addView(searxngUrlEdit)
    layout.addView(TextView(ctx).apply {
        text = "Herkese açık SearXNG instance örnekleri: searx.be, search.bus-hit.me, search.ononoki.org"; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })

    val resultCountInitial = webSearchResultCount.coerceIn(1, 10)
    layout.addView(TextView(ctx).apply {
        text = "Sonuç Sayısı: $resultCountInitial"; textSize = 12f; alpha = 0.75f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8*dp).toInt(); bottomMargin = (2*dp).toInt() }
    })
    val resultCountLabel = layout.getChildAt(layout.childCount - 1) as TextView
    val resultCountBar = SeekBar(ctx).apply {
        max = 9
        progress = resultCountInitial - 1
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { resultCountLabel.text = "Sonuç Sayısı: ${p + 1}" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    layout.addView(resultCountBar)
    layout.addView(TextView(ctx).apply {
        text = "Modele gönderilecek arama sonucu sayısı (1–10). Az sonuç = daha az token tüketimi."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    val fetchRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val fetchLabel = TextView(ctx).apply {
        text = "📄 Sayfa içeriğini de getir (daha doğru)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val fetchSwitch = Switch(ctx).apply { isChecked = webPageFetchEnabled }
    fetchRow.addView(fetchLabel); fetchRow.addView(fetchSwitch); layout.addView(fetchRow)
    layout.addView(TextView(ctx).apply {
        text = "İlk 2 sonucun sayfasını açar ve içeriğini modele gönderir. Dolar kuru, hava durumu gibi anlık verilerde çok daha doğru. Biraz daha yavaş (~2-5 sn ekstra)."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12*dp).toInt() }
    })

    // ── URL Okuma (v5.5) ──────────────────────────────────────────────────────
    layout.addView(sectionTitle("🔗 URL Okuma"))

    val urlFetchRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val urlFetchLabel = TextView(ctx).apply {
        text = "🔗 URL'leri otomatik oku"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val urlFetchSwitch = Switch(ctx).apply { isChecked = urlFetchEnabled }
    urlFetchRow.addView(urlFetchLabel); urlFetchRow.addView(urlFetchSwitch); layout.addView(urlFetchRow)
    layout.addView(TextView(ctx).apply {
        text = "Mesajında bir URL varsa Maya o sayfayı otomatik çeker ve içeriği modele iletir. " +
               "Web araması kapalı olsa bile çalışır. " +
               "Makale çevirisi, özetleme ve analiz için kullanışlıdır."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    layout.addView(TextView(ctx).apply {
        text = "Sayfa başına maksimum karakter"; textSize = 12f; alpha = 0.75f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })
    val urlCharLimitRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (2*dp).toInt() }
    }
    val urlCharLimitEdit = android.widget.EditText(ctx).apply {
        setText(urlFetchCharLimit.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        hint = "5000"
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (8*dp); setColor(0xFF2A2A2A.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setTextColor(0xFFE0E0E0.toInt()); setHintTextColor(0xFF666666.toInt())
        setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
    }
    val btn2k = android.widget.Button(ctx).apply {
        text = "2K"; textSize = 11f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (6*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6*dp; setColor(0xFF1A2A3A.toInt()) }
        setTextColor(0xFF88AAFF.toInt())
        setOnClickListener { urlCharLimitEdit.setText("2000") }
    }
    val btn5k = android.widget.Button(ctx).apply {
        text = "5K"; textSize = 11f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (4*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6*dp; setColor(0xFF1A3A1A.toInt()) }
        setTextColor(0xFF88FF88.toInt())
        setOnClickListener { urlCharLimitEdit.setText("5000") }
    }
    val btn10k = android.widget.Button(ctx).apply {
        text = "10K"; textSize = 11f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (4*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6*dp; setColor(0xFF3A1A1A.toInt()) }
        setTextColor(0xFFFF8888.toInt())
        setOnClickListener { urlCharLimitEdit.setText("10000") }
    }
    urlCharLimitRow.addView(urlCharLimitEdit)
    urlCharLimitRow.addView(btn2k)
    urlCharLimitRow.addView(btn5k)
    urlCharLimitRow.addView(btn10k)
    layout.addView(urlCharLimitRow)
    layout.addView(TextView(ctx).apply {
        text = "Daha fazla karakter = daha uzun makaleler okunabilir ama model context'ini doldurabilir. " +
               "2K: hızlı/kısa  •  5K: önerilen  •  10K: uzun makaleler (büyük context gerektirir)."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4*dp).toInt(); bottomMargin = (12*dp).toInt() }
    })

    // ── Rapor Modeli (Global) ─────────────────────────────────────────────────
    layout.addView(sectionTitle("🤖 Rapor Modeli"))

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
        setTextColor(0xFFB0C8FF.toInt())
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val reportModelSelectBtn = android.widget.Button(ctx).apply {
        text = "Seç"; textSize = 12f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6*dp; setColor(0xFF1A3A5C.toInt()) }
        setTextColor(0xFF88AAFF.toInt())
    }
    val reportModelClearBtn = android.widget.Button(ctx).apply {
        text = "✕"; textSize = 12f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (4*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6*dp; setColor(0xFF3A1A1A.toInt()) }
        setTextColor(0xFFFF8888.toInt())
    }
    reportModelRow.addView(reportModelNameLabel)
    reportModelRow.addView(reportModelSelectBtn)
    reportModelRow.addView(reportModelClearBtn)
    layout.addView(reportModelRow)

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
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    reportModelClearBtn.setOnClickListener {
        reportModelEntry = null
        reportModelNameLabel.text = reportModelDisplayName()
    }

    layout.addView(TextView(ctx).apply {
        text = "Sadece daha önce uygulamada yüklenmiş (önbellekte hazır) modeller listelenir. " +
               "Ayarlanmazsa en son yüklenen model kullanılır."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    layout.addView(TextView(ctx).apply {
        text = "Şablon"; textSize = 12f; alpha = 0.75f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })
    // Tüm şablon isimleri — index 0..7 + "Model varsayılanı" başa ekleniyor
    val templateNames = DailyReportWorker.TEMPLATE_NAMES
    val templateDisplayNames = arrayOf("Model varsayılanı (önerilen)") + templateNames
    var currentTemplateSelection = if (reportModelTemplate < 0) 0 else reportModelTemplate + 1

    val templateGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
    val templateRadios = templateDisplayNames.mapIndexed { i, name ->
        RadioButton(ctx).apply {
            text = name; id = android.view.View.generateViewId()
            isChecked = (i == currentTemplateSelection)
            setTextColor(0xFFE0E0E0.toInt())
            setOnCheckedChangeListener { _, checked -> if (checked) currentTemplateSelection = i }
        }
    }
    templateRadios.forEach { templateGroup.addView(it) }
    layout.addView(templateGroup)
    layout.addView(TextView(ctx).apply {
        text = "Rapor özetlemesi için kullanılacak konuşma şablonu. " +
               "\"Model varsayılanı\" seçiliyse GGUF metadata'sındaki şablon kullanılır. " +
               "Qwen3/Qwen3.5 için ChatML, Gemma 3 için Gemma 3, Gemma 4 için Gemma 4 seçin."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    })

    val reportNoThinkRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    val reportNoThinkLabel = TextView(ctx).apply {
        text = "💭 Düşünmeyi kapat (Qwen3 / Gemma 4)"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION") val reportNoThinkSwitch = Switch(ctx).apply { isChecked = reportModelNoThink }
    reportNoThinkRow.addView(reportNoThinkLabel); reportNoThinkRow.addView(reportNoThinkSwitch)
    layout.addView(reportNoThinkRow)
    layout.addView(TextView(ctx).apply {
        text = "Qwen3'te <think> bloğunu, Gemma 4'te <|think|> tokenını devre dışı bırakır. " +
               "Token tasarrufu sağlar. Diğer modelleri etkilemez."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12*dp).toInt() }
    })

    // ── Rapor Context Boyutu ─────────────────────────────────────────────────
    layout.addView(sectionTitle("📐 Rapor Context Boyutu (token)"))
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
        setText(reportContextSize.toString())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        layoutParams = LinearLayout.LayoutParams((80*dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8*dp).toInt() }
        gravity = android.view.Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (8*dp); setColor(0xFF2A2A2A.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setTextColor(0xFFE0E0E0.toInt()); setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
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
    rCtxRow.addView(rCtxBar); rCtxRow.addView(rCtxEdit); layout.addView(rCtxRow)
    layout.addView(TextView(ctx).apply {
        text = "Rapor özetleme sırasında kullanılacak context boyutu (2048–32768). " +
               "Thinking modeli kapalıysa 8192 genellikle yeterli. " +
               "Thinking açıksa veya haberler uzunsa 16384+ önerilebilir."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12*dp).toInt() }
    })

    // ── Rapor Profilleri ──────────────────────────────────────────────────────
    layout.addView(sectionTitle("📰 Rapor Profilleri"))

    val profileListContainer = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    layout.addView(profileListContainer)

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
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 8*dp; setColor(0xFF252535.toInt()) }
                setPadding((8*dp).toInt(), (4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt())
                minimumHeight = (48*dp).toInt()
            }

            @Suppress("DEPRECATION") val toggle = Switch(ctx).apply {
                isChecked = profile.enabled
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }

            val nameTime = TextView(ctx).apply {
                text = "%s  %d:%02d".format(profile.name, profile.hour, profile.minute)
                textSize = 13f; setTextColor(0xFFE0E0E0.toInt()); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    marginStart = (10*dp).toInt(); marginEnd = (4*dp).toInt()
                }
            }

            fun iconBtn(label: String, color: Int) = android.widget.Button(ctx).apply {
                text = label; textSize = 14f
                setBackgroundColor(0x00000000); setTextColor(color)
                setPadding((10*dp).toInt(), 0, (10*dp).toInt(), 0)
                minWidth = 0; minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }
            val testBtn = iconBtn("▶", 0xFF55CC77.toInt())
            val editBtn = iconBtn("✎", 0xFF88AAFF.toInt())

            toggle.setOnCheckedChangeListener { _, checked ->
                reportProfiles[idx] = profile.copy(enabled = checked)
                ReportProfile.saveAll(this, reportProfiles)
                if (checked) DailyReportScheduler.schedule(this, reportProfiles[idx])
                else DailyReportScheduler.cancel(this, profile)
            }
            editBtn.setOnClickListener { showProfileEditDialog(profile) { refreshProfileList() } }
            testBtn.setOnClickListener {
                try {
                    getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().remove("last_worker_error").apply()
                    val data = androidx.work.Data.Builder()
                        .putString("profile_id", profile.id).build()
                    val req = androidx.work.OneTimeWorkRequestBuilder<DailyReportWorker>()
                        .setInputData(data).build()
                    val wm = androidx.work.WorkManager.getInstance(applicationContext)
                    wm.enqueue(req)
                    wm.getWorkInfoByIdLiveData(req.id).observe(this@showSettingsDialog) { info ->
                        if (info == null) return@observe
                        when (info.state) {
                            androidx.work.WorkInfo.State.SUCCEEDED -> runOnUiThread {
                                Toast.makeText(ctx, "✅ '${profile.name}' tamamlandı", Toast.LENGTH_SHORT).show()
                            }
                            androidx.work.WorkInfo.State.FAILED -> runOnUiThread {
                                val err = getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE)
                                    .getString("last_worker_error", "Bilinmeyen hata") ?: "Bilinmeyen hata"
                                android.app.AlertDialog.Builder(this@showSettingsDialog)
                                    .setTitle("❌ Worker Hatası").setMessage(err)
                                    .setPositiveButton("Tamam", null).show()
                            }
                            else -> {}
                        }
                    }
                    Toast.makeText(ctx, "▶ '${profile.name}' başlatıldı…", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.app.AlertDialog.Builder(ctx).setTitle("❌ Hata").setMessage(e.message)
                        .setPositiveButton("Tamam", null).show()
                }
            }

            row.addView(toggle); row.addView(nameTime); row.addView(testBtn); row.addView(editBtn)
            profileListContainer.addView(row)
        }
    }
    refreshProfileList()

    val addProfileBtn = android.widget.Button(ctx).apply {
        text = "+ Profil Ekle"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8*dp).toInt(); bottomMargin = (4*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 8*dp; setColor(0xFF1A3A5C.toInt()) }
        setTextColor(0xFF88AAFF.toInt())
    }
    addProfileBtn.setOnClickListener { showProfileEditDialog(null) { refreshProfileList() } }
    layout.addView(addProfileBtn)
    layout.addView(TextView(ctx).apply {
        text = "Her profil kendi saatinde çalışır ve ayrı bildirim gönderir. https:// ile başlayan konular RSS olarak çekilir."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12*dp).toInt() }
    })

    // ── Tema (v5.8) ───────────────────────────────────────────────────────────
    layout.addView(sectionTitle("🌓 Uygulama Teması"))
    val themeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
    val rbThemeSystem = RadioButton(ctx).apply {
        text = "⚙️ Sistem"; id = android.view.View.generateViewId()
        isChecked = (appThemeMode == MainActivity.THEME_SYSTEM)
    }
    val rbThemeDark = RadioButton(ctx).apply {
        text = "🌙 Karanlık"; id = android.view.View.generateViewId()
        isChecked = (appThemeMode == MainActivity.THEME_DARK)
    }
    val rbThemeLight = RadioButton(ctx).apply {
        text = "☀️ Aydınlık"; id = android.view.View.generateViewId()
        isChecked = (appThemeMode == MainActivity.THEME_LIGHT)
    }
    themeGroup.addView(rbThemeSystem); themeGroup.addView(rbThemeDark); themeGroup.addView(rbThemeLight)
    layout.addView(themeGroup)
    layout.addView(TextView(ctx).apply {
        text = "Değişiklik kaydedilince hemen uygulanır."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12*dp).toInt() }
    })

    // ── Önbellek ──────────────────────────────────────────────────────────────
    layout.addView(sectionTitle("Önbellek Yönetimi"))
    layout.addView(TextView(ctx).apply {
        text = "Modeller önbellekte saklanır, her yüklemede kopyalanmaz. Listeden kaldırılan modeller önbellekten de silinir."; textSize = 11f; alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    })
    val cacheStatusLabel = TextView(ctx).apply {
        text = getCacheInfo(); textSize = 12f
        setTextColor(0xFF9090C0.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    }
    layout.addView(cacheStatusLabel)
    val clearCacheBtn = android.widget.Button(ctx).apply {
        text = "🗑️ Önbelleği Temizle"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    clearCacheBtn.setOnClickListener { clearUnusedModelCache(cacheStatusLabel) }
    layout.addView(clearCacheBtn)

    // ── İptal / Kaydet butonları ──────────────────────────────────────────────
    val btnRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() }
    }
    val btnIptal = android.widget.Button(ctx).apply {
        text = "✖ İptal"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dp).toInt() }
    }
    val btnKaydet = android.widget.Button(ctx).apply { text = "✔ Kaydet" }
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
        useMmap           = mmapSwitch.isChecked
        useMlock          = mlockSwitch.isChecked
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
        urlFetchCharLimit    = urlCharLimitEdit.text.toString().toIntOrNull()
            ?.coerceIn(500, 50000) ?: 5000

        // ── Rapor modeli ayarlarını kaydet ──────────────────────────────────
        reportModelNoThink = reportNoThinkSwitch.isChecked
        val reportCtxVal = rCtxEdit.text.toString().toIntOrNull()?.coerceIn(2048, 32768) ?: 8192
        val reportCtxAligned = (reportCtxVal / 1024) * 1024
        val templateToSave = if (currentTemplateSelection == 0) -1 else currentTemplateSelection - 1
        getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit().apply {
            if (reportModelEntry != null)
                putString(DailyReportWorker.KEY_REPORT_MODEL_ENTRY, reportModelEntry)
            else
                remove(DailyReportWorker.KEY_REPORT_MODEL_ENTRY)
            putInt(DailyReportWorker.KEY_REPORT_MODEL_TEMPLATE, templateToSave)
            putBoolean(DailyReportWorker.KEY_REPORT_MODEL_NO_THINK, reportModelNoThink)
            putInt("report_context_size", reportCtxAligned)
        }.apply()

        // ── v5.8: Tema ayarını kaydet ve uygula ─────────────────────────────
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
