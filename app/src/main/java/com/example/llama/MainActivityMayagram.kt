package tr.maya

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.maya.data.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// ── Mayagram aktivitesi başlatıcı ─────────────────────────────────────────────

internal fun MainActivity.openMayagram() {
    val intent = Intent(this, MayagramActivity::class.java)
    startActivity(intent)
}

// ── Post üretimi: LLM + Dream API pipeline ────────────────────────────────────

/**
 * Seçili karakterden bir Mayagram gönderisi üretir.
 * 1. LLM → caption + image prompt üretir
 * 2. Dream API → görüntü üretir (dreamApiEnabled ise)
 * 3. DB'ye kaydeder
 */
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
            // ── Adım 1: LLM ile caption + görüntü promptu üret ────────────────
            onProgress("✍️ ${character.name} yazıyor…")

            val topicLine = if (!topic.isNullOrBlank()) "Konu: $topic\n" else ""
            val systemInstr = """
Sen ${character.name} karakterisin. ${character.systemPrompt}

Bir sosyal medya gönderisi hazırla. Şu formatta yanıt ver (başka hiçbir şey yazma):

CAPTION: <gönderi metni, emoji ve hashtag dahil, max 3 cümle>
IMAGE_PROMPT: <İngilizce, görüntü üretim modeli için ayrıntılı sahne tarifi, max 20 kelime>
""".trimIndent()

            val fullPrompt = buildMayagramPrompt(topicLine + systemInstr)
            val sb = StringBuilder()

            val flow = try {
                engine.sendUserPrompt(fullPrompt, predictLength = 200)
            } catch (e: Exception) {
                onError("LLM hatası: ${e.message}")
                return@launch
            }

            flow.collect { token -> sb.append(token) }

            val raw = cleanMayagramResponse(sb.toString())
            val caption = extractLine(raw, "CAPTION:")
            val imagePrompt = extractLine(raw, "IMAGE_PROMPT:")

            if (caption.isBlank()) {
                onError("Karakter yanıt üretemedi")
                return@launch
            }

            // ── Adım 2: Görüntü üret (Dream API açıksa) ──────────────────────
            var imagePath: String? = null
            var usedPrompt: String? = null

            if (dreamApiEnabled && imagePrompt.isNotBlank()) {
                onProgress("🎨 Görüntü oluşturuluyor…")
                usedPrompt = imagePrompt

                var dreamBitmap: Bitmap? = null
                val dreamReq = DreamRequest(
                    prompt = imagePrompt,
                    negativePrompt = dreamDefaultNegativePrompt,
                    size = dreamSize,
                    steps = dreamSteps,
                    cfg = dreamCfg,
                    seed = if (dreamSeed < 0) System.currentTimeMillis() % 100000L else dreamSeed,
                    useOpenCl = dreamUseOpenCl
                )

                performDreamRequest(dreamApiUrl, dreamReq) { event ->
                    when (event) {
                        is DreamEvent.Progress ->
                            onProgress("🎨 ${event.step}/${event.totalSteps} adım…")
                        is DreamEvent.Complete ->
                            dreamBitmap = event.bitmap
                        is DreamEvent.Error ->
                            MainActivity.log("Mayagram", "Dream hata: ${event.message}")
                    }
                }

                if (dreamBitmap != null) {
                    imagePath = saveMayagramImage(dreamBitmap!!)
                }
            }

            // ── Adım 3: DB'ye kaydet ──────────────────────────────────────────
            onProgress("💾 Kaydediliyor…")

            val post = MayagramPost(
                characterId = character.id,
                characterName = character.name,
                characterEmoji = character.emoji,
                characterAvatarUri = character.avatarUri,
                caption = caption,
                imagePath = imagePath,
                dreamPrompt = usedPrompt,
                timestamp = System.currentTimeMillis()
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

/**
 * Verilen posta bir karakter yorumu LLM ile üretir ve DB'ye kaydeder.
 */
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
            engine.sendUserPrompt(prompt, predictLength = 80).collect { sb.append(it) }

            val text = cleanMayagramResponse(sb.toString())
                .lines()
                .firstOrNull { it.trim().length > 3 }
                ?.trim() ?: return@launch

            val comment = MayagramComment(
                postId = post.id,
                authorId = commenter.id,
                authorName = commenter.name,
                authorEmoji = commenter.emoji,
                authorAvatarUri = commenter.avatarUri,
                content = text
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

// ── Prompt yardımcıları ───────────────────────────────────────────────────────

private fun MainActivity.buildMayagramPrompt(instruction: String): String {
    return when (selectedTemplate) {
        0 -> instruction
        1 -> "<BOS_TOKEN><|START_OF_TURN_TOKEN|><|USER_TOKEN|>$instruction<|END_OF_TURN_TOKEN|><|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>"
        2 -> "<|im_start|>user\n$instruction<|im_end|>\n<|im_start|>assistant\n"
        3 -> "<bos><start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"
        4 -> "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$instruction<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        5 -> "<|start_of_role|>user<|end_of_role|>$instruction<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>"
        7 -> "<bos><|turn>user\n$instruction<turn|>\n<|turn>model\n"
        else -> instruction
    }
}

private fun MainActivity.buildMayagramCommentPrompt(post: MayagramPost, commenter: MayaCharacter): String {
    val instruction = """
Sen ${commenter.name} karakterisin. ${commenter.systemPrompt}

${post.characterName} şunu paylaştı: "${post.caption}"

Bu gönderiye kısa, samimi ve karakterine uygun bir yorum yaz (max 1 cümle, emoji kullanabilirsin). Sadece yorumu yaz:
""".trimIndent()
    return buildMayagramPrompt(instruction)
}

private fun extractLine(text: String, prefix: String): String {
    return text.lines()
        .firstOrNull { it.trimStart().startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.trim() ?: ""
}

private fun cleanMayagramResponse(raw: String): String {
    return raw
        .replace("<|END_OF_TURN_TOKEN|>", "")
        .replace("<|im_end|>", "")
        .replace("<|eot_id|>", "")
        .replace("<end_of_turn>", "")
        .replace("<turn|>", "")
        .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<\\|channel>.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "")
        .trim()
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

// ── Görseli paylaş ───────────────────────────────────────────────────────────

internal fun MainActivity.shareMayagramImage(imagePath: String) {
    try {
        val file = File(imagePath)
        if (!file.exists()) { Toast.makeText(this, "Dosya bulunamadı", Toast.LENGTH_SHORT).show(); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.provider", file
        )
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

// ── Görseli galeriye kaydet ───────────────────────────────────────────────────

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
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "mayagram_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mayagram")
            }
            val resolver = contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { inp -> inp.copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveMayagramImageToGallery, "✅ Galeriye kaydedildi (Pictures/Mayagram)", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveMayagramImageToGallery, "Kaydetme başarısız", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@saveMayagramImageToGallery, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
