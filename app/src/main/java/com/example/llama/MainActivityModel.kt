package tr.maya

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.InferenceEngine
import com.arm.aichat.internal.InferenceEngineImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// ── Model depolama dizini ─────────────────────────────────────────────────────

internal fun MainActivity.getModelsDir(): File {
    val dir = getExternalFilesDir("models") ?: File(filesDir, "models")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

internal fun MainActivity.migrateModelsFromCacheToFilesDir() {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    if (prefs.getBoolean("models_migration_done_v2", false)) return

    val oldDir = externalCacheDir ?: return
    val newDir = getModelsDir()
    var moved = 0
    oldDir.listFiles()?.forEach { file ->
        if (file.name.startsWith("model_") || file.name.startsWith("mmproj_")) {
            val dest = File(newDir, file.name)
            if (!dest.exists()) {
                try {
                    file.renameTo(dest).also { ok ->
                        if (ok) { moved++; MainActivity.log("Maya", "Taşındı: ${file.name}") }
                        else {
                            file.inputStream().use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
                            file.delete(); moved++
                            MainActivity.log("Maya", "Kopyalanıp taşındı: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    MainActivity.log("Maya", "Taşıma hatası ${file.name}: ${e.message}")
                }
            } else {
                file.delete()
            }
        }
    }
    prefs.edit().putBoolean("models_migration_done_v2", true).apply()
    if (moved > 0) MainActivity.log("Maya", "Migration tamamlandı: $moved dosya taşındı → ${newDir.absolutePath}")
}

internal fun MainActivity.getCacheFileForModel(modelName: String): File {
    return File(getModelsDir(), "model_$modelName")
}

internal fun MainActivity.formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    val mb = bytes / (1024.0 * 1024.0)
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(mb)
}

internal fun MainActivity.getCacheInfo(): String {
    val dir = getModelsDir()
    val files = dir.listFiles()?.filter { it.name.startsWith("model_") || it.name.startsWith("mmproj_") } ?: emptyList()
    if (files.isEmpty()) return "Model dosyası yok"
    val totalBytes = files.sumOf { it.length() }
    return "${files.size} dosya • ${formatBytes(totalBytes)}"
}

internal fun MainActivity.clearUnusedModelCache(statusLabel: android.widget.TextView? = null) {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val savedModels = prefs.getStringSet("saved_models", mutableSetOf())!!
    val knownNames = savedModels
        .filter { MainActivity.isUriEntry(it) }
        .map { "model_${MainActivity.entryDisplayName(it)}" }
        .toSet()
    val modelsDir = getModelsDir()
    var totalDeleted = 0L; var fileCount = 0
    modelsDir.listFiles()?.forEach { file ->
        if (file.name.startsWith("model_") && file.name !in knownNames) {
            totalDeleted += file.length(); fileCount++; file.delete()
            MainActivity.log("Maya", "Sahipsiz model silindi: ${file.name}")
        }
    }
    (externalCacheDir ?: cacheDir).listFiles()?.forEach { file ->
        if (file.name.startsWith("maya_img_") && !currentMessages.any { it.imagePath == file.absolutePath }) {
            totalDeleted += file.length(); fileCount++; file.delete()
            MainActivity.log("Maya", "Geçici resim silindi: ${file.name}")
        }
    }
    val msg = if (fileCount > 0) "$fileCount dosya silindi (${formatBytes(totalDeleted)} kazanıldı)"
              else "Temizlenecek sahipsiz dosya yok"
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    statusLabel?.text = getCacheInfo()
}

// ── Model yükleme ─────────────────────────────────────────────────────────────

internal fun MainActivity.loadModel(entry: String) {
    lifecycleScope.launch {
        var progressDialog: android.app.ProgressDialog? = null
        try {
            val modelName = MainActivity.entryDisplayName(entry)

            if (engine.state.value is InferenceEngine.State.ModelReady ||
                engine.state.value is InferenceEngine.State.Error) {
                engine.cleanUp()
            }
            var waited = 0
            while (engine.state.value !is InferenceEngine.State.Initialized && waited < 100) {
                delay(100); waited++
            }

            (engine as? InferenceEngineImpl)
                ?.applySettings(contextSize, temperature, topP, topK, flashAttnMode, useMmap, useMlock)

            val pathToLoad: String

            if (MainActivity.isUriEntry(entry)) {
                val uri = MainActivity.entryToUri(entry)
                val pfd = try { contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                if (pfd == null) {
                    android.app.AlertDialog.Builder(this@loadModel)
                        .setTitle("⚠️ Model Erişilemiyor")
                        .setMessage("\"$modelName\" dosyasına erişim izni yok veya dosya taşınmış.\n\nBu genellikle yedekten geri yüklenen modellerde olur. Modeli listeden kaldırıp tekrar ekleyin.")
                        .setPositiveButton("Listeden Kaldır") { _, _ ->
                            val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
                            val models = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableSet()
                            models.remove(entry)
                            prefs.edit().putStringSet("saved_models", models).apply()
                            Toast.makeText(this@loadModel, "\"$modelName\" listeden kaldırıldı.", Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton("İptal", null).show()
                    return@launch
                }
                pfd.close()

                val cacheFile = getCacheFileForModel(modelName)
                val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@loadModel, uri)
                val originalSize = docFile?.length() ?: 0L

                if (cacheFile.exists() && originalSize > 0 && cacheFile.length() == originalSize) {
                    MainActivity.log("Maya", "Model önbellekte mevcut, kopyalama atlandı: ${cacheFile.name}")
                    pathToLoad = cacheFile.absolutePath
                } else {
                    if (cacheFile.exists()) cacheFile.delete()
                    progressDialog = android.app.ProgressDialog(this@loadModel).apply {
                        setTitle("Model hazırlanıyor"); setMessage("$modelName kopyalanıyor...")
                        isIndeterminate = false; max = 100
                        setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                        setCancelable(false); show()
                    }
                    MainActivity.log("Maya", "Kopyalama başlıyor: $modelName")
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            var copiedBytes = 0L
                            FileOutputStream(cacheFile).use { output ->
                                val buf = ByteArray(8 * 1024 * 1024)
                                var n: Int
                                while (input.read(buf).also { n = it } != -1) {
                                    output.write(buf, 0, n); copiedBytes += n
                                    if (originalSize > 0) {
                                        val progress = (copiedBytes * 100 / originalSize).toInt()
                                        withContext(Dispatchers.Main) { progressDialog?.progress = progress }
                                    }
                                }
                            }
                        } ?: throw Exception("Dosya açılamadı: $uri")
                    }
                    progressDialog?.dismiss(); progressDialog = null
                    MainActivity.log("Maya", "Kopyalama tamamlandı: ${cacheFile.length()} bytes")
                    pathToLoad = cacheFile.absolutePath
                }
            } else {
                pathToLoad = entry
            }

            Toast.makeText(this@loadModel, "Model yükleniyor...", Toast.LENGTH_SHORT).show()
            engine.loadModel(pathToLoad)
            loadedModelPath = entry

            if (systemPrompt.isNotEmpty() && selectedTemplate == 0) {
                try {
                    engine.setSystemPrompt(applyPersona(systemPrompt))
                    MainActivity.log("Maya", "Sistem promptu gönderildi")
                } catch (e: Exception) {
                    MainActivity.log("Maya", "setSystemPrompt hatası: ${e.message}")
                }
            }

            MainActivity.log("Maya", "Model yüklendi: $modelName template=$selectedTemplate flashAttnMode=$flashAttnMode")
            updateFabIcon(); updateActiveModelSubtitle()
            getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit()
                .putString("last_loaded_model", entry).apply()
            Toast.makeText(this@loadModel, "$modelName yüklendi", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            progressDialog?.dismiss(); progressDialog = null
            Toast.makeText(this@loadModel, "Model yüklenemedi: ${e.message}", Toast.LENGTH_LONG).show()
            MainActivity.log("Maya", "Model yükleme hatası: ${e.message}")
        }
    }
}

internal fun MainActivity.cleanupMissingModels() {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val models = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableSet()
    val valid = models.filter { entry ->
        if (MainActivity.isUriEntry(entry)) true else File(entry).exists()
    }.toMutableSet()
    if (valid.size != models.size) prefs.edit().putStringSet("saved_models", valid).apply()
}

// ── Model listesi diyaloğu ────────────────────────────────────────────────────

internal fun MainActivity.showModelPickerDialog() {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val savedModels = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableList()
    val options = savedModels.map { entry ->
        val name = MainActivity.entryDisplayName(entry)
        if (entry == loadedModelPath) "✓ $name" else name
    }.toMutableList()
    options.add("+ Yeni model ekle")
    android.app.AlertDialog.Builder(this).setTitle("Model Seç")
        .setItems(options.toTypedArray()) { _, which ->
            if (which == options.size - 1) showAddModelDialog()
            else showModelActionDialog(savedModels[which])
        }.show()
}

internal fun MainActivity.showModelActionDialog(entry: String) {
    android.app.AlertDialog.Builder(this).setTitle(MainActivity.entryDisplayName(entry))
        .setItems(arrayOf("Yükle", "Kaldır")) { _, which ->
            when (which) { 0 -> showTemplatePickerDialog(entry); 1 -> confirmRemoveModel(entry) }
        }.setNegativeButton("İptal", null).show()
}

internal fun MainActivity.confirmRemoveModel(entry: String) {
    val name = MainActivity.entryDisplayName(entry)
    android.app.AlertDialog.Builder(this).setTitle("Modeli Kaldır")
        .setMessage("\"$name\" listeden ve önbellekten kaldırılsın mı?")
        .setPositiveButton("Kaldır") { _, _ ->
            if (loadedModelPath == entry) {
                Toast.makeText(this, "Önce başka bir model yükleyin.", Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
            val models = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableSet()
            models.remove(entry)
            prefs.edit().putStringSet("saved_models", models).apply()
            if (MainActivity.isUriEntry(entry)) {
                val cacheFile = getCacheFileForModel(name)
                if (cacheFile.exists()) { cacheFile.delete(); MainActivity.log("Maya", "Model dosyası silindi: ${cacheFile.name}") }
            } else {
                val file = File(entry)
                if (file.exists() && file.absolutePath.startsWith(filesDir.absolutePath)) file.delete()
            }
            Toast.makeText(this, "\"$name\" kaldırıldı", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("İptal", null).show()
}

internal fun MainActivity.showAddModelDialog() {
    filePickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    })
}

internal fun MainActivity.showModelInfoDialog() {
    if (loadedModelPath == null) {
        Toast.makeText(this, "Henüz model yüklenmedi", Toast.LENGTH_SHORT).show()
        return
    }
    lifecycleScope.launch(Dispatchers.IO) {
        val dbMessages = db.chatDao().getMessages(currentConversationId)
        val tpsList = dbMessages.mapNotNull { if (it.role == "assistant") it.tps else null }
        val avgTps = if (tpsList.isNotEmpty()) tpsList.average() else null

        withContext(Dispatchers.Main) {
            val modelName = MainActivity.entryDisplayName(loadedModelPath!!)
            val templateNames = arrayOf("Otomatik (GGUF'tan)", "Aya / Command-R", "ChatML", "Gemma", "Llama 3", "Granite", "Özel Şablon")
            val templateStr = templateNames.getOrElse(selectedTemplate) { "Bilinmiyor" }
            val flashStr = when (flashAttnMode) {
                0    -> "Kapalı"
                2    -> "Açık (zorla)"
                else -> "Otomatik"
            }
            val mmapStr  = if (useMmap) "Açık" else "Kapalı"
            val mlockStr = if (useMlock) "Açık" else "Kapalı"
            val tpsStr = if (avgTps != null) "%.2f t/s".format(avgTps) else "—"
            val msgCount = dbMessages.count { it.role == "assistant" }
            val activeChar = characters.find { it.id == activeCharacterId }
            val impl = engine as? InferenceEngineImpl
            val visionStr = if (impl?.isMmprojLoaded == true) "Yüklü ✅" else "Yüklü değil"

            val info = buildString {
                appendLine("🤖  $modelName")
                appendLine()
                appendLine("Şablon:           $templateStr")
                appendLine("Context Window:   $contextSize token")
                appendLine("Generated Tokens: $predictLength token")
                appendLine("Bypass Context:   ${if (bypassContextLength) "Açık ✅" else "Kapalı"}")
                appendLine("Flash Attention:  $flashStr")
                appendLine("mmap:             $mmapStr")
                appendLine("mlock:            $mlockStr")
                appendLine("Vision (mmproj):  $visionStr")
                appendLine()
                if (activeChar != null) {
                    appendLine("Aktif Karakter:   ${activeChar.emoji} ${activeChar.name}")
                }
                appendLine()
                appendLine("Bu sohbet:")
                appendLine("  Yanıt sayısı:   $msgCount")
                appendLine("  Ort. hız:       $tpsStr")
            }

            val dp = resources.displayMetrics.density
            val tv = android.widget.TextView(this@showModelInfoDialog).apply {
                text = info; textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(0xFFE0E0E0.toInt())
                val pad = (16 * dp).toInt()
                setPadding(pad, pad, pad, pad)
            }

            android.app.AlertDialog.Builder(this@showModelInfoDialog)
                .setTitle("ℹ️ Model Bilgisi")
                .setView(tv)
                .setPositiveButton("Tamam", null)
                .setNeutralButton("📷 Mmproj Yükle") { _, _ -> pickMmprojFile() }
                .show()
        }
    }
}

// ── v4.8: Vision (görüntü) desteği ───────────────────────────────────────────

internal fun MainActivity.setupImageAttach() {
    btnAttachImage.setOnClickListener {
        if (loadedModelPath == null) {
            Toast.makeText(this, "Önce bir model yükleyin", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val impl = engine as? InferenceEngineImpl
        if (impl != null && !impl.isMmprojLoaded) {
            android.app.AlertDialog.Builder(this)
                .setTitle("🖼️ Görüntü Desteği")
                .setMessage("Görüntü gönderebilmek için bir mmproj (vision projector) dosyası gereklidir.\n\nÖrnek:\n• gemma-3-4b-it-mmproj-f16.gguf\n• mmproj-model-f16.gguf\n\nMmproj dosyasını seçmek ister misiniz?")
                .setPositiveButton("Mmproj Seç") { _, _ -> pickMmprojFile() }
                .setNegativeButton("İptal", null)
                .show()
            return@setOnClickListener
        }
        openImagePicker()
    }

    btnRemoveImage.setOnClickListener {
        clearSelectedImage()
    }
}

internal fun MainActivity.pickMmprojFile() {
    mmprojPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}

internal fun MainActivity.openImagePicker() {
    imagePickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}

internal fun MainActivity.loadMmprojFromUri(uri: Uri) {
    lifecycleScope.launch {
        var progressDialog: android.app.ProgressDialog? = null
        try {
            val fileName = uri.lastPathSegment?.substringAfterLast("/")
                ?: "mmproj_${System.currentTimeMillis()}.gguf"
            val cacheDir = getModelsDir()
            val cacheFile = File(cacheDir, "mmproj_$fileName")

            val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(this@loadMmprojFromUri, uri)
            val originalSize = docFile?.length() ?: 0L

            if (!cacheFile.exists() || (originalSize > 0 && cacheFile.length() != originalSize)) {
                progressDialog = android.app.ProgressDialog(this@loadMmprojFromUri).apply {
                    setTitle("Mmproj hazırlanıyor")
                    setMessage("$fileName kopyalanıyor...")
                    isIndeterminate = false; max = 100
                    setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                    setCancelable(false); show()
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        var copied = 0L
                        FileOutputStream(cacheFile).use { out ->
                            val buf = ByteArray(4 * 1024 * 1024)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n); copied += n
                                if (originalSize > 0) {
                                    val pct = (copied * 100 / originalSize).toInt()
                                    withContext(Dispatchers.Main) { progressDialog?.progress = pct }
                                }
                            }
                        }
                    } ?: throw Exception("Dosya açılamadı")
                }
                progressDialog?.dismiss(); progressDialog = null
            }

            val impl = engine as? InferenceEngineImpl
            val ok = impl?.loadMmprojModel(cacheFile.absolutePath) ?: false
            if (ok) {
                loadedMmprojPath = cacheFile.absolutePath
                MainActivity.log("Maya", "Mmproj yüklendi: ${cacheFile.name}")
                Toast.makeText(this@loadMmprojFromUri, "✅ Mmproj yüklendi: $fileName", Toast.LENGTH_SHORT).show()
                openImagePicker()
            } else {
                Toast.makeText(this@loadMmprojFromUri, "❌ Mmproj yüklenemedi. Bu dosya desteklenen bir vision projector mı?", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            progressDialog?.dismiss()
            Toast.makeText(this@loadMmprojFromUri, "Mmproj hatası: ${e.message}", Toast.LENGTH_LONG).show()
            MainActivity.log("Maya", "Mmproj yükleme hatası: ${e.message}")
        }
    }
}

internal fun MainActivity.handleSelectedImage(uri: Uri) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val cacheDir = externalCacheDir ?: cacheDir
            val imgFile = File(cacheDir, "maya_img_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(imgFile).use { out -> input.copyTo(out) }
            } ?: throw Exception("Görüntü okunamadı")

            withContext(Dispatchers.Main) {
                selectedImagePath = imgFile.absolutePath
                val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                if (bmp != null) {
                    imagePreviewView.setImageBitmap(bmp)
                } else {
                    imagePreviewView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
                imagePreviewLabel.text = "🖼️ ${imgFile.name}"
                imagePreviewContainer.visibility = View.VISIBLE
                MainActivity.log("Maya", "Görüntü seçildi: ${imgFile.absolutePath}")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@handleSelectedImage, "Görüntü seçilemedi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.clearSelectedImage() {
    selectedImagePath?.let { path ->
        try { File(path).delete() } catch (_: Exception) {}
    }
    selectedImagePath = null
    imagePreviewContainer.visibility = View.GONE
    imagePreviewView.setImageDrawable(null)
}
