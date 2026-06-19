package tr.maya

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.maya.data.DbMessage
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.util.Base64
import android.content.Intent

// ── Dream API veri sınıfları ──────────────────────────────────────────────────

data class DreamRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val size: Int = 512,
    val steps: Int = 20,
    val cfg: Float = 7.0f,
    val seed: Long = -1L,
    val useOpenCl: Boolean = false
)

sealed class DreamEvent {
    data class Progress(val step: Int, val totalSteps: Int) : DreamEvent()
    data class Complete(
        val bitmap: Bitmap,
        val generationTimeMs: Long
    ) : DreamEvent()
    data class Error(val message: String) : DreamEvent()
}

// ── SSE Client ────────────────────────────────────────────────────────────────

/**
 * LocalDream API'ye SSE isteği atar.
 * Her event için [onEvent] callback'ini çağırır.
 * IO thread'de çalışır; coroutine iptal edilince bağlantıyı kapatır.
 */
internal suspend fun performDreamRequest(
    baseUrl: String,
    request: DreamRequest,
    onEvent: suspend (DreamEvent) -> Unit
) = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("prompt", request.prompt)
        put("negative_prompt", request.negativePrompt)
        put("size", request.size)
        put("steps", request.steps)
        put("cfg", request.cfg.toDouble())
        put("seed", request.seed)
        put("use_opencl", request.useOpenCl)
    }.toString()

    val url = URL("${baseUrl.trimEnd('/')}/generate")
    var conn: HttpURLConnection? = null

    try {
        conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 300_000   // görüntü üretimi uzun sürebilir
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            onEvent(DreamEvent.Error("HTTP ${conn.responseCode}"))
            return@withContext
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var line: String?

        while (isActive) {
            line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            if (!line.startsWith("data: ")) continue

            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break

            try {
                val json = JSONObject(data)
                when (json.optString("type")) {
                    "progress" -> {
                        val step  = json.optInt("step", 0)
                        val total = json.optInt("total_steps", 1)
                        onEvent(DreamEvent.Progress(step, total))
                    }
                    "complete" -> {
                        val b64       = json.getString("image")
                        val width     = json.getInt("width")
                        val height    = json.getInt("height")
                        val channels  = json.optInt("channels", 3)
                        val genTimeMs = json.optLong("generation_time_ms", 0L)

                        val bitmap = decodeDreamBitmap(b64, width, height, channels)
                        if (bitmap != null) {
                            onEvent(DreamEvent.Complete(bitmap, genTimeMs))
                        } else {
                            onEvent(DreamEvent.Error("Bitmap çözülemedi"))
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                MainActivity.log("DreamAPI", "SSE parse hatası: ${e.message}")
            }
        }
    } catch (e: Exception) {
        if (isActive) {
            onEvent(DreamEvent.Error(e.message ?: "Bağlantı hatası"))
        }
    } finally {
        conn?.disconnect()
    }
}

/**
 * Base64 RGB/RGBA byte dizisini Android Bitmap'e çevirir.
 * RGB888 → ARGB8888 dönüşümü elle yapılır (Bitmap.createBitmap pixel array ile).
 */
private fun decodeDreamBitmap(base64Data: String, width: Int, height: Int, channels: Int): Bitmap? {
    return try {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        val pixels = IntArray(width * height)

        if (channels == 3) {
            // RGB → ARGB
            for (i in pixels.indices) {
                val r = bytes[i * 3].toInt() and 0xFF
                val g = bytes[i * 3 + 1].toInt() and 0xFF
                val b = bytes[i * 3 + 2].toInt() and 0xFF
                pixels[i] = Color.rgb(r, g, b)
            }
        } else if (channels == 4) {
            // RGBA → ARGB
            for (i in pixels.indices) {
                val r = bytes[i * 4].toInt() and 0xFF
                val g = bytes[i * 4 + 1].toInt() and 0xFF
                val b = bytes[i * 4 + 2].toInt() and 0xFF
                val a = bytes[i * 4 + 3].toInt() and 0xFF
                pixels[i] = Color.argb(a, r, g, b)
            }
        } else {
            return null
        }

        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        MainActivity.log("DreamAPI", "Bitmap decode hatası: ${e.message}")
        null
    }
}

// ── Bitmap → dosya kaydetme ───────────────────────────────────────────────────

/**
 * Üretilen Bitmap'i cacheDir'e PNG olarak kaydeder ve path döner.
 */
private suspend fun saveDreamBitmap(context: Context, bitmap: Bitmap): String =
    withContext(Dispatchers.IO) {
        val file = File(context.externalCacheDir ?: context.cacheDir, "dream_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        file.absolutePath
    }

// ── Görüntü Oluştur diyaloğu ─────────────────────────────────────────────────

internal fun MainActivity.showDreamApiDialog() {
    if (!dreamApiEnabled) {
        Toast.makeText(this, "Dream API Ayarlar'dan etkinleştirin", Toast.LENGTH_LONG).show()
        return
    }

    val dp = resources.displayMetrics.density
    val isDark = MessageAdapter.isDarkTheme(this)

    val scroll = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
    }
    scroll.addView(layout)

    fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt(); bottomMargin = (3 * dp).toInt() }
    }

    fun styledEdit(hint: String, value: String = "", multiLine: Boolean = false) =
        android.widget.EditText(this).apply {
            this.hint = hint; setText(value)
            if (multiLine) { minLines = 3; maxLines = 6; isSingleLine = false; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
            setHintTextColor(if (isDark) 0xFF666666.toInt() else 0xFF999999.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(if (isDark) 0xFF1E1E2E.toInt() else 0xFFEEEEFF.toInt())
                setStroke((1 * dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAACC.toInt())
            }
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    // ── Prompt ────────────────────────────────────────────────────────────────
    layout.addView(label("Prompt"))
    val promptEdit = styledEdit("Bir sahne veya nesne tarif edin...", multiLine = true)
    layout.addView(promptEdit)

    // ── Negatif Prompt ────────────────────────────────────────────────────────
    layout.addView(label("Negatif Prompt (isteğe bağlı)"))
    val negativePromptEdit = styledEdit(
        "bad anatomy, blurry...",
        dreamDefaultNegativePrompt,
        multiLine = true
    )
    layout.addView(negativePromptEdit)

    // ── Boyut ─────────────────────────────────────────────────────────────────
    val sizeOptions = listOf(256, 512, 768, 1024)
    val currentSizeIdx = sizeOptions.indexOf(dreamSize).takeIf { it >= 0 } ?: 1

    layout.addView(label("Boyut: ${sizeOptions[currentSizeIdx]}px"))
    val sizeLabel = layout.getChildAt(layout.childCount - 1) as TextView
    val sizeBar = SeekBar(this).apply {
        max = sizeOptions.size - 1
        progress = currentSizeIdx
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                sizeLabel.text = "Boyut: ${sizeOptions[p]}px"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    layout.addView(sizeBar)

    // ── Steps ─────────────────────────────────────────────────────────────────
    layout.addView(label("Adım sayısı: $dreamSteps"))
    val stepsLabel = layout.getChildAt(layout.childCount - 1) as TextView
    val stepsBar = SeekBar(this).apply {
        max = 49; progress = dreamSteps - 1
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                stepsLabel.text = "Adım sayısı: ${p + 1}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    layout.addView(stepsBar)

    // ── CFG Scale ─────────────────────────────────────────────────────────────
    layout.addView(label("CFG Scale: %.1f".format(dreamCfg)))
    val cfgLabel = layout.getChildAt(layout.childCount - 1) as TextView
    val cfgBar = SeekBar(this).apply {
        max = 140; progress = ((dreamCfg - 1f) * 10).toInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                cfgLabel.text = "CFG Scale: %.1f".format(1f + p / 10f)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
    layout.addView(cfgBar)

    // ── Seed ──────────────────────────────────────────────────────────────────
    layout.addView(label("Seed (-1 = rastgele)"))
    val seedEdit = styledEdit("-1", dreamSeed.toString())
    seedEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
    layout.addView(seedEdit)

    // ── OpenCL toggle ─────────────────────────────────────────────────────────
    val openClRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() }
    }
    val openClLabel = TextView(this).apply {
        text = "OpenCL GPU hızlandırma"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION")
    val openClSwitch = android.widget.Switch(this).apply { isChecked = dreamUseOpenCl }
    openClRow.addView(openClLabel); openClRow.addView(openClSwitch)
    layout.addView(openClRow)

    // ── Progress alanı (gizli, üretim sırasında görünür) ─────────────────────
    val progressSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = android.view.View.GONE
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() }
    }
    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progress = 0
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val progressLabel = TextView(this).apply {
        text = "Hazırlanıyor…"; textSize = 12f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() }
    }
    val previewImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (300 * dp).toInt()).apply { topMargin = (8 * dp).toInt() }
        visibility = android.view.View.GONE
    }
    progressSection.addView(progressBar)
    progressSection.addView(progressLabel)
    progressSection.addView(previewImageView)
    layout.addView(progressSection)

    var activeJob: Job? = null

    val dialog = android.app.AlertDialog.Builder(this)
        .setTitle("🎨 Görüntü Oluştur")
        .setView(scroll)
        .setPositiveButton("Oluştur", null)   // listener aşağıda override edilecek
        .setNegativeButton("İptal") { _, _ -> activeJob?.cancel() }
        .create()

    dialog.setOnShowListener {
        val btn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        btn.setOnClickListener {
            val prompt = promptEdit.text.toString().trim()
            if (prompt.isEmpty()) {
                Toast.makeText(this, "Prompt boş olamaz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedSize = sizeOptions[sizeBar.progress]
            val steps  = stepsBar.progress + 1
            val cfg    = 1f + cfgBar.progress / 10f
            val seed   = seedEdit.text.toString().toLongOrNull() ?: -1L
            val useOcl = openClSwitch.isChecked

            // Ayarları kaydet
            dreamSteps      = steps
            dreamCfg        = cfg
            dreamSeed       = seed
            dreamSize       = selectedSize
            dreamUseOpenCl  = useOcl
            dreamDefaultNegativePrompt = negativePromptEdit.text.toString()
            saveDreamSettings()

            // UI: üretim modu
            btn.isEnabled = false
            progressSection.visibility = android.view.View.VISIBLE
            previewImageView.visibility = android.view.View.GONE
            progressBar.progress = 0
            progressLabel.text = "Bağlanıyor…"

            val req = DreamRequest(
                prompt         = prompt,
                negativePrompt = negativePromptEdit.text.toString(),
                size           = selectedSize,
                steps          = steps,
                cfg            = cfg,
                seed           = seed,
                useOpenCl      = useOcl
            )

            activeJob = lifecycleScope.launch {
                performDreamRequest(dreamApiUrl, req) { event ->
                    withContext(Dispatchers.Main) {
                        when (event) {
                            is DreamEvent.Progress -> {
                                val pct = if (event.totalSteps > 0)
                                    (event.step * 100 / event.totalSteps) else 0
                                progressBar.progress = pct
                                progressLabel.text = "Adım ${event.step} / ${event.totalSteps}"
                            }
                            is DreamEvent.Complete -> {
                                progressBar.progress = 100
                                progressLabel.text = "Tamamlandı (${event.generationTimeMs}ms)"
                                previewImageView.setImageBitmap(event.bitmap)
                                previewImageView.visibility = android.view.View.VISIBLE

                                // Dosyaya kaydet (cache) — galeri/paylaş butonları bu path'i kullanacak
                                val path = saveDreamBitmap(this@showDreamApiDialog, event.bitmap)

                                // Pozitif butonu "Galeriye Kaydet" olarak yeniden kullan
                                btn.text = "💾 Galeriye Kaydet"
                                btn.isEnabled = true
                                btn.setOnClickListener {
                                    saveDreamImageToGallery(path)
                                }

                                // Negatif butonu "Paylaş" yap
                                val negBtn = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                                negBtn?.text = "📤 Paylaş"
                                negBtn?.setOnClickListener {
                                    shareDreamImage(path)
                                }

                                // Neutral butonu "Kapat" yap (henüz yoksa diyalog builder'a eklenmeli — aşağıya bak)
                                val neutralBtn = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                                neutralBtn?.text = "Kapat"
                                neutralBtn?.setOnClickListener { dialog.dismiss() }
                            }

                            is DreamEvent.Error -> {
                                progressLabel.text = "❌ ${event.message}"
                                btn.isEnabled = true
                                Toast.makeText(this@showDreamApiDialog, "Hata: ${event.message}", Toast.LENGTH_LONG).show()
                                MainActivity.log("DreamAPI", "Hata: ${event.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    dialog.show()
}

// ── Üretilen görüntüyü sohbete ekle ──────────────────────────────────────────

internal fun MainActivity.addDreamImageToChat(prompt: String, imagePath: String, bitmap: Bitmap) {
    val userMsg = ChatMessage(
        content   = "🎨 $prompt",
        isUser    = true,
        timestamp = System.currentTimeMillis(),
        imagePath = imagePath
    )
    val assistantMsg = ChatMessage(
        content   = "![Oluşturulan görüntü]($imagePath)",
        isUser    = false,
        timestamp = System.currentTimeMillis() + 1
    )

    currentMessages.add(userMsg)
    currentMessages.add(assistantMsg)
    messageAdapter.submitList(currentMessages.toList())
    autoScroll = true
    messagesRv.post { messagesRv.scrollToPosition(currentMessages.size - 1) }

    val convId = currentConversationId
    lifecycleScope.launch(Dispatchers.IO) {
        db.chatDao().insertMessage(DbMessage(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = "user",
            content = "🎨 $prompt",
            timestamp = userMsg.timestamp,
            imagePath = imagePath
        ))
        db.chatDao().insertMessage(DbMessage(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = "assistant",
            content = "🎨 Görüntü oluşturuldu: $prompt",
            timestamp = assistantMsg.timestamp
        ))
        db.chatDao().touchConversation(convId, System.currentTimeMillis())
    }

    Toast.makeText(this, "🎨 Görüntü sohbete eklendi", Toast.LENGTH_SHORT).show()
    MainActivity.log("DreamAPI", "Görüntü eklendi: $imagePath")
}

// ── Dream API ayar yardımcıları ───────────────────────────────────────────────

internal fun MainActivity.loadDreamSettings() {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    dreamApiEnabled           = prefs.getBoolean("dream_api_enabled", false)
    dreamApiUrl               = prefs.getString("dream_api_url", "http://127.0.0.1:8081") ?: "http://127.0.0.1:8081"
    dreamSize                 = prefs.getInt("dream_size", 512)
    dreamSteps                = prefs.getInt("dream_steps", 20)
    dreamCfg                  = prefs.getFloat("dream_cfg", 7.0f)
    dreamSeed                 = prefs.getLong("dream_seed", -1L)
    dreamUseOpenCl            = prefs.getBoolean("dream_use_opencl", false)
    dreamDefaultNegativePrompt = prefs.getString("dream_negative_prompt",
        "bad anatomy, bad hands, missing fingers, extra fingers, blurry, low quality") ?: ""
}

internal fun MainActivity.saveDreamSettings() {
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit()
        .putBoolean("dream_api_enabled", dreamApiEnabled)
        .putString("dream_api_url", dreamApiUrl)
        .putInt("dream_size", dreamSize)
        .putInt("dream_steps", dreamSteps)
        .putFloat("dream_cfg", dreamCfg)
        .putLong("dream_seed", dreamSeed)
        .putBoolean("dream_use_opencl", dreamUseOpenCl)
        .putString("dream_negative_prompt", dreamDefaultNegativePrompt)
        .apply()
}

// ── Ayarlar diyaloğuna Dream API kartı ekle ───────────────────────────────────

/**
 * MainActivitySettings.kt içindeki showSettingsDialog()'dan çağrılır.
 * Bölüm kartını [parent] LinearLayout'a ekler ve değiştirilen değerleri
 * kaydetmek için lambda döner.
 */
internal fun MainActivity.buildDreamApiSettingsCard(
    parent: LinearLayout
): () -> Unit {
    val dp     = resources.displayMetrics.density
    val isDark = MessageAdapter.isDarkTheme(this)

    // Kart üretici (MainActivitySettings.kt'deki makeSectionCard ile aynı stil)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * dp).toInt(); bottomMargin = (2 * dp).toInt() }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * dp
            setColor(if (isDark) 0xFF1C1C2E.toInt() else 0xFFF5F5FF.toInt())
            setStroke((1 * dp).toInt(), if (isDark) 0xFF2E2E4A.toInt() else 0xFFD8D8EC.toInt())
        }
    }

    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(10*dp, 10*dp, 10*dp, 10*dp, 0f, 0f, 0f, 0f)
            setColor(if (isDark) 0xFF252540.toInt() else 0xFFEAEAFF.toInt())
        }
    }
    header.addView(TextView(this).apply {
        text = "🎨"; textSize = 16f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dp).toInt() }
    })
    header.addView(TextView(this).apply {
        text = "Dream API (Görüntü Oluşturma)"; textSize = 13f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(if (isDark) 0xFFCCCCFF.toInt() else 0xFF3333AA.toInt())
    })

    val divider = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
        setBackgroundColor(if (isDark) 0xFF2E2E4A.toInt() else 0xFFD8D8EC.toInt())
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
    }

    // ── Enable toggle ─────────────────────────────────────────────────────────
    val enableRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val enableLabel = TextView(this).apply {
        text = "Dream API etkin"; textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION")
    val enableSwitch = android.widget.Switch(this).apply { isChecked = dreamApiEnabled }
    enableRow.addView(enableLabel); enableRow.addView(enableSwitch)
    content.addView(enableRow)

    content.addView(TextView(this).apply {
        text = "Etkinleştirince menüde 🎨 butonu görünür."; textSize = 11f; alpha = 0.55f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * dp).toInt(); bottomMargin = (10 * dp).toInt() }
    })

    // ── URL ───────────────────────────────────────────────────────────────────
    content.addView(TextView(this).apply {
        text = "API Adresi"; textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(if (isDark) 0xFFAAAAAA.toInt() else 0xFF666688.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt(); bottomMargin = (3 * dp).toInt() }
    })
    val urlEdit = android.widget.EditText(this).apply {
        setText(dreamApiUrl)
        hint = "http://127.0.0.1:8081"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        setTextColor(if (isDark) 0xFFE0E0E0.toInt() else 0xFF222222.toInt())
        setHintTextColor(if (isDark) 0xFF666666.toInt() else 0xFF999999.toInt())
        background = GradientDrawable().apply {
            cornerRadius = 8 * dp
            setColor(if (isDark) 0xFF1E1E2E.toInt() else 0xFFEEEEFF.toInt())
            setStroke((1 * dp).toInt(), if (isDark) 0xFF555577.toInt() else 0xFFAAAACC.toInt())
        }
        setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    content.addView(urlEdit)
    content.addView(TextView(this).apply {
        text = "Cihazda çalışan LocalDream (veya uyumlu) servisi adresi."; textSize = 11f; alpha = 0.55f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
    })

    // ── Test bağlantısı butonu ────────────────────────────────────────────────
    val testBtn = android.widget.Button(this).apply {
        text = "🔌 Bağlantıyı Test Et"; isAllCaps = false; textSize = 12f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        background = GradientDrawable().apply { cornerRadius = 6 * dp; setColor(if (isDark) 0xFF1A3A5C.toInt() else 0xFFDDEEFF.toInt()) }
        setTextColor(if (isDark) 0xFF88AAFF.toInt() else 0xFF2244AA.toInt())
    }
    val testResultTv = TextView(this).apply {
        textSize = 11f; alpha = 0.75f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() }
    }
    testBtn.setOnClickListener {
        val testUrl = urlEdit.text.toString().trim().ifEmpty { "http://127.0.0.1:8081" }
        testResultTv.text = "Bağlanılıyor…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("${testUrl.trimEnd('/')}/generate").openConnection() as HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 5_000
                    conn.readTimeout    = 5_000
                    conn.connect()
                    // 404/405 de olsa bağlantı kurulduysa servis çalışıyordur
                    val code = conn.responseCode
                    conn.disconnect()
                    code < 500
                } catch (e: Exception) { false }
            }
            testResultTv.text = if (ok) "✅ Servis erişilebilir" else "❌ Bağlantı kurulamadı"
        }
    }
    content.addView(testBtn)
    content.addView(testResultTv)

    card.addView(header)
    card.addView(divider)
    card.addView(content)
    parent.addView(card)

    // Kaydet lambda'sı
    return {
        dreamApiEnabled = enableSwitch.isChecked
        dreamApiUrl     = urlEdit.text.toString().trim().ifEmpty { "http://127.0.0.1:8081" }
        saveDreamSettings()
    }
}
// ── Galeriye kaydet / Paylaş (Dream API üretimleri) ──────────────────────────

internal fun MainActivity.saveDreamImageToGallery(imagePath: String) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val src = File(imagePath)
            if (!src.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveDreamImageToGallery, "Dosya bulunamadı", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "maya_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Maya")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveDreamImageToGallery,
                        "✅ Galeriye kaydedildi (Pictures/Maya)", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveDreamImageToGallery, "Kaydetme başarısız", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@saveDreamImageToGallery, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.shareDreamImage(imagePath: String) {
    try {
        val file = File(imagePath)
        if (!file.exists()) { Toast.makeText(this, "Dosya bulunamadı", Toast.LENGTH_SHORT).show(); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Görüntüyü Paylaş"))
    } catch (e: Exception) {
        Toast.makeText(this, "Paylaşılamadı: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
