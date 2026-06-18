package tr.maya

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.maya.data.AppDatabase
import java.io.File
import java.io.FileOutputStream

// ── Mayagram aktivitesi başlatıcı ─────────────────────────────────────────────

internal fun MainActivity.openMayagram() {
    val intent = Intent(this, MayagramActivity::class.java)
    startActivity(intent)
}

// ── Post üretimi: LLM + Dream API pipeline ────────────────────────────────────

internal fun MainActivity.generateMayagramPost(
    character: MayaCharacter,
    topic: String? = null,
    onProgress: (String) -> Unit,
    onDone: (MayagramPost) -> Unit,
    onError: (String) -> Unit
) {
    if (loadedModelPath == null) {
        onError("Önce bir model yükleyin")
        return
    }

    lifecycleScope.launch {
        try {
            onProgress("✍️ ${character.name} yazıyor…")

            val topicLine = if (!topic.isNullOrBlank()) "Konu: $topic\n" else ""

            // Her seferinde farklı içerik için rastgele bir ruh hali ekle
            val moods = listOf(
                "Bugün özellikle neşeli ve enerjik hissediyorsun.",
                "Bugün biraz düşünceli ve derin bir ruh halindeysin.",
                "Bugün meraklı ve keşfetmeye açık hissediyorsun.",
                "Bugün nostaljik ve duygusal bir ruh halindeysin.",
                "Bugün heyecanlı, yeni bir şeyler paylaşmak istiyorsun."
            )
            val randomMood = moods.random()

            val systemInstr = buildString {
                appendLine("Sen ${character.name} karakterisin. ${character.systemPrompt}")
                appendLine()
                appendLine(randomMood)
                if (topicLine.isNotBlank()) appendLine(topicLine)
                appendLine()
                appendLine("Bir sosyal medya gönderisi hazırla. TAM OLARAK şu formatta yanıt ver (başka hiçbir şey yazma, açıklama yapma):")
                appendLine()
                appendLine("CAPTION: <gönderi metni, emoji ve hashtag dahil, max 3 cümle>")
                append("IMAGE_PROMPT: <İngilizce, görüntü üretim modeli için ayrıntılı sahne tarifi, max 20 kelime>")
            }

            val fullPrompt = buildMayagramPrompt(systemInstr)
            val sb = StringBuilder()

            try {
                engine.sendUserPrompt(fullPrompt, predictLength = 250).collect { token ->
                    sb.append(token)
                }
            } catch (e: Exception) {
                onError("LLM hatası: ${e.message}")
                return@launch
            }

            val raw = extractVisibleContent(sb.toString())
            MainActivity.log("Mayagram", "Ham yanıt (temizlenmiş): $raw")

            val caption     = extractMayagramLine(raw, "CAPTION:")
            val imagePrompt = extractMayagramLine(raw, "IMAGE_PROMPT:")

            // CAPTION bulunamadıysa tüm metni caption olarak kullan (fallback)
            val finalCaption = when {
                caption.isNotBlank() -> caption
                raw.trim().isNotBlank() -> raw.lines()
                    .filter { it.isNotBlank() && !it.startsWith("IMAGE_PROMPT") }
                    .take(3).joinToString(" ").trim()
                else -> {
                    onError("Karakter yanıt üretemedi")
                    return@launch
                }
            }

            // ── Dream API ─────────────────────────────────────────────────────
            var imagePath: String? = null
            var usedPrompt: String? = null

            if (dreamApiEnabled && imagePrompt.isNotBlank()) {
                onProgress("🎨 Görüntü oluşturuluyor…")
                usedPrompt = imagePrompt

                var dreamBitmap: Bitmap? = null
                val dreamReq = DreamRequest(
                    prompt         = imagePrompt,
                    negativePrompt = dreamDefaultNegativePrompt,
                    size           = dreamSize,
                    steps          = dreamSteps,
                    cfg            = dreamCfg,
                    seed           = if (dreamSeed < 0) System.currentTimeMillis() % 100000L else dreamSeed,
                    useOpenCl      = dreamUseOpenCl
                )

                performDreamRequest(dreamApiUrl, dreamReq) { event ->
                    when (event) {
                        is DreamEvent.Progress -> onProgress("🎨 ${event.step}/${event.totalSteps} adım…")
                        is DreamEvent.Complete -> dreamBitmap = event.bitmap
                        is DreamEvent.Error    -> MainActivity.log("Mayagram", "Dream hata: ${event.message}")
                    }
                }

                if (dreamBitmap != null) {
                    imagePath = saveMayagramImage(dreamBitmap!!)
                }
            }

            // ── DB kaydet ─────────────────────────────────────────────────────
            onProgress("💾 Kaydediliyor…")

            val post = MayagramPost(
                characterId        = character.id,
                characterName      = character.name,
                characterEmoji     = character.emoji,
                characterAvatarUri = character.avatarUri,
                caption            = finalCaption,
                imagePath          = imagePath,
                dreamPrompt        = usedPrompt,
                timestamp          = System.currentTimeMillis()
            )

            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@generateMayagramPost)
                    .mayagramDao().insertPost(post)
            }

            onDone(post)

        } catch (e: Exception) {
            onError("Hata: ${e.message}")
        }
    }
}

// ── Karakter yorumu üret ──────────────────────────────────────────────────────

internal fun MainActivity.generateCharacterComment(
    post: MayagramPost,
    commenter: MayaCharacter,
    onDone: (MayagramComment) -> Unit
) {
    if (loadedModelPath == null) return

    lifecycleScope.launch {
        try {
            val prompt = buildMayagramCommentPrompt(post, commenter)
            val sb = StringBuilder()
            engine.sendUserPrompt(prompt, predictLength = 100).collect { sb.append(it) }

            val text = extractVisibleContent(sb.toString())
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 2 }
                .filterNot { it.startsWith("CAPTION:") || it.startsWith("IMAGE_PROMPT:") }
                .firstOrNull() ?: return@launch

            val comment = MayagramComment(
                postId          = post.id,
                authorId        = commenter.id,
                authorName      = commenter.name,
                authorEmoji     = commenter.emoji,
                authorAvatarUri = commenter.avatarUri,
                content         = text
            )
            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@generateCharacterComment)
                    .mayagramDao().insertComment(comment)
            }
            onDone(comment)
        } catch (e: Exception) {
            MainActivity.log("Mayagram", "Yorum üretme hatası: ${e.message}")
        }
    }
}

// ── Prompt oluşturucular ──────────────────────────────────────────────────────

/**
 * Gemma 4 (template=7): thinking TAMAMEN KAPALI.
 * <|channel> token'ı hiç eklenmez — sadece kısa metin çıktısı istiyoruz.
 * System turn da yok, doğrudan user→model.
 */
private fun MainActivity.buildMayagramPrompt(instruction: String): String {
    return when (selectedTemplate) {
        0    -> instruction
        1    -> "<BOS_TOKEN><|START_OF_TURN_TOKEN|><|USER_TOKEN|>$instruction<|END_OF_TURN_TOKEN|><|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>"
        2    -> "<|im_start|>user\n$instruction<|im_end|>\n<|im_start|>assistant\n"
        3    -> "<bos><start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"
        4    -> "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$instruction<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        5    -> "<|start_of_role|>user<|end_of_role|>$instruction<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>"
        7    -> "<bos><|turn>user\n$instruction<turn|>\n<|turn>model\n"
        else -> instruction
    }
}

private fun MainActivity.buildMayagramCommentPrompt(
    post: MayagramPost,
    commenter: MayaCharacter
): String {
    val instruction = buildString {
        appendLine("Sen ${commenter.name} karakterisin. ${commenter.systemPrompt}")
        appendLine()
        appendLine("${post.characterName} şunu paylaştı:")
        appendLine("\"${post.caption}\"")
        appendLine()
        append("Bu gönderiye karakterine uygun, kısa ve samimi bir yorum yaz (tek cümle, emoji kullanabilirsin). Sadece yorum metnini yaz, başka hiçbir şey ekleme:")
    }
    return buildMayagramPrompt(instruction)
}

// ── Thinking / token temizleme ────────────────────────────────────────────────

/**
 * Gemma 4 dahil tüm şablonların thinking bloklarını ve format tokenlarını temizler.
 * Sadece görünür içeriği döner.
 *
 * Desteklenen thinking varyantları:
 *   <|channel>thought\n...<channel|>   (Gemma 4 kapalı)
 *   <|channel>...<channel|>            (Gemma 4 generic)
 *   thought\n...                       (Gemma 4 marker eksik, açık kalmış)
 *   <think>...</think>                 (Qwen3 vb. kapalı)
 *   <think>...                         (açık kalmış)
 */
private fun extractVisibleContent(raw: String): String {
    var text = raw

    // Gemma 4: kapalı thinking — <|channel>thought\n...<channel|>
    text = text.replace(
        Regex("""<\|channel>thought\n.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), ""
    )
    // Gemma 4: kapalı thinking — <|channel>...<channel|> (generic)
    text = text.replace(
        Regex("""<\|channel>.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), ""
    )

    // Gemma 4: açık kalmış — "thought\n..." ile başlıyorsa <channel|>'dan sonrasını al
    val trimmed = text.trimStart()
    if (trimmed.startsWith("thought\n") || trimmed.startsWith("<|channel>")) {
        val afterClose = text.substringAfter("<channel|>", "")
        text = afterClose
    }

    // Qwen3 vb.: <think>...</think>
    text = text.replace(
        Regex("""<think>.*?</think>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), ""
    )
    // Açık kalmış <think> — sonrasını at
    val thinkIdx = text.indexOf("<think>")
    if (thinkIdx != -1) {
        text = text.substring(0, thinkIdx)
    }

    // Format tokenları
    text = text
        .replace("<|END_OF_TURN_TOKEN|>", "")
        .replace("<|START_OF_TURN_TOKEN|>", "")
        .replace("<|USER_TOKEN|>", "")
        .replace("<|CHATBOT_TOKEN|>", "")
        .replace("<|im_end|>", "")
        .replace("<|eot_id|>", "")
        .replace("<end_of_turn>", "")
        .replace("<start_of_turn>", "")
        .replace("<turn|>", "")
        .replace("<|turn>", "")
        .replace("<|channel>", "")
        .replace("<channel|>", "")
        .replace("<|end_of_text|>", "")
        .replace("<|endoftext|>", "")

    // {{user}} / {{char}} placeholder çözümle
    text = text
        .replace("{{user}}", userName)
        .replace("{{char}}", charName)

    return text.trim()
}

/**
 * "CAPTION: ..." → "..." şeklinde prefix'i kaldırır.
 */
private fun extractMayagramLine(text: String, prefix: String): String {
    return text.lines()
        .firstOrNull { it.trimStart().startsWith(prefix, ignoreCase = true) }
        ?.replaceFirst(Regex("(?i)^\\s*${Regex.escape(prefix)}\\s*"), "")
        ?.trim() ?: ""
}

// ── Görüntü kaydetme ──────────────────────────────────────────────────────────

private suspend fun MainActivity.saveMayagramImage(bitmap: Bitmap): String =
    withContext(Dispatchers.IO) {
        val dir = File(getExternalFilesDir(null), "Mayagram").also { it.mkdirs() }
        val file = File(dir, "post_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        file.absolutePath
    }

// ── Paylaş / Galeriye kaydet ──────────────────────────────────────────────────

internal fun MainActivity.shareMayagramImage(imagePath: String) {
    try {
        val file = File(imagePath)
        if (!file.exists()) { Toast.makeText(this, "Dosya bulunamadı", Toast.LENGTH_SHORT).show(); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Paylaş"))
    } catch (e: Exception) {
        Toast.makeText(this, "Paylaşılamadı: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.saveMayagramImageToGallery(imagePath: String) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val src = File(imagePath)
            if (!src.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveMayagramImageToGallery, "Dosya bulunamadı", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "mayagram_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mayagram")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveMayagramImageToGallery,
                        "✅ Galeriye kaydedildi (Pictures/Mayagram)", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveMayagramImageToGallery,
                        "Kaydetme başarısız", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@saveMayagramImageToGallery,
                    "Hata: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
