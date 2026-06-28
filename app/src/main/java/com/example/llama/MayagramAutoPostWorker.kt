package tr.maya

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.arm.aichat.InferenceEngine
import com.arm.aichat.internal.InferenceEngineImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import tr.maya.data.AppDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * Mayagram otomatik (zamanlı) karakter paylaşımını arka planda üretir.
 *
 * DailyReportWorker ile aynı desen: ayrı bir "Mayagram Modeli" kullanır (ayarlanmamışsa
 * son yüklü modele düşer), engine'i meşgul değilse devralır, üretim biter bitmez serbest
 * bırakır — kullanıcı uygulamayı kullanıyorsa onun akışını kesmemeye çalışır (engine
 * meşgulse o turu atlar, bir sonraki alarmda tekrar dener).
 *
 * Akış:
 *   1. autoPostEnabled=true karakterlerden rastgele birini seç (yoksa sessizce çık)
 *   2. Dream API kapalıysa tamamen atla (görselsiz paylaşım yapılmaz — kullanıcı tercihi)
 *   3. Mayagram modelini yükle (MainActivityDailyReport'taki gibi ayrı model desteği)
 *   4. Konusuz, karaktere uygun bir caption + image_prompt ürettir
 *   5. Dream API ile görüntü üret
 *   6. MayagramPost olarak DB'ye kaydet, günlük sayaçı güncelle
 */
class MayagramAutoPostWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG           = "MayagramAutoPostWorker"
        private const val NOTIF_CHANNEL = "maya_mayagram_autopost"
        private const val NOTIF_ID      = 40001

        // Rapor modeliyle aynı desende, ama Mayagram'a özel ayrı anahtarlar
        const val KEY_AUTOPOST_MODEL_ENTRY    = "mayagram_autopost_model_entry"
        const val KEY_AUTOPOST_MODEL_TEMPLATE = "mayagram_autopost_model_template"
        const val KEY_AUTOPOST_MODEL_NO_THINK = "mayagram_autopost_model_no_think"

        /** "Şimdi Test Et" butonundan tetiklendiğinde true geçirilir — enabled/günlük limit kontrollerini atlar. */
        const val INPUT_IS_MANUAL_TEST = "is_manual_test"
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        val isManualTest = inputData.getBoolean(INPUT_IS_MANUAL_TEST, false)

        if (!isManualTest) {
            if (!MayagramAutoPostScheduler.isEnabled(applicationContext)) {
                Log.i(TAG, "Otomatik paylaşım kapalı, iş atlandı")
                return Result.success()
            }
            if (MayagramAutoPostScheduler.isDailyLimitReached(applicationContext)) {
                Log.i(TAG, "Günlük paylaşım limitine ulaşıldı, iş atlandı")
                return Result.success()
            }
        }

        val charactersJson = prefs.getString("characters_json", null)
        val candidates = loadAutoPostCandidates(charactersJson)
        if (candidates.isEmpty()) {
            Log.i(TAG, "autoPostEnabled karakter yok, iş atlandı")
            return Result.success()
        }

        // ── v6.9: Dream API kapalıysa otomatik paylaşım tamamen atlanır ───────
        // (görselsiz paylaşım istenmiyor — kullanıcı tercihi). Model yüklemeden
        // önce kontrol edilir, böylece gereksiz CPU/pil tüketimi önlenir.
        if (!prefs.getBoolean("dream_api_enabled", false)) {
            Log.i(TAG, "Dream API kapalı, otomatik paylaşım bu turda atlandı (görselsiz paylaşım yapılmaz)")
            return Result.success()
        }

        val character = candidates.random()
        Log.i(TAG, "Seçilen karakter: ${character.name}")

        createNotificationChannel()
        setForeground(buildProgressForeground("✍️ ${character.name} hazırlanıyor…"))

        return try {
            val modelEntry = prefs.getString(KEY_AUTOPOST_MODEL_ENTRY, null)
                ?: prefs.getString("last_loaded_model", null)
            if (modelEntry == null) {
                Log.w(TAG, "Kullanılabilir model yok, iş atlandı")
                return Result.success()
            }

            val modelPath = resolveModelCachePath(modelEntry)
            if (modelPath == null) {
                Log.w(TAG, "Model önbellekte yok: $modelEntry")
                return Result.success()
            }

            val templateIndex = prefs.getInt(KEY_AUTOPOST_MODEL_TEMPLATE, -1).let { if (it < 0) null else it }
            val noThink        = prefs.getBoolean(KEY_AUTOPOST_MODEL_NO_THINK, false)

            val eng = InferenceEngineImpl.getInstance(applicationContext)

            var waited = 0
            while (eng.state.value is InferenceEngine.State.Uninitialized ||
                   eng.state.value is InferenceEngine.State.Initializing) {
                if (waited++ > 100) { Log.e(TAG, "Engine init zaman aşımı"); return Result.success() }
                delay(200)
            }

            val st = eng.state.value
            if (st is InferenceEngine.State.Generating ||
                st is InferenceEngine.State.ProcessingUserPrompt ||
                st is InferenceEngine.State.ProcessingSystemPrompt) {
                Log.w(TAG, "Engine meşgul (kullanıcı muhtemelen sohbet ediyor), otomatik paylaşım bu turda atlandı")
                return Result.success()
            }

            if (st is InferenceEngine.State.ModelReady || st is InferenceEngine.State.Error) {
                eng.cleanUp()
                waited = 0
                while (eng.state.value !is InferenceEngine.State.Initialized) {
                    if (waited++ > 50) break; delay(200)
                }
            }

            if (eng.state.value !is InferenceEngine.State.Initialized) {
                Log.e(TAG, "Engine Initialized değil: ${eng.state.value}"); return Result.success()
            }

            eng.applySettings(
                contextSize   = prefs.getInt("mayagram_autopost_context_size", 4096).coerceIn(2048, 16384),
                temperature   = prefs.getFloat("temperature", 0.8f),
                topP          = prefs.getFloat("top_p", 0.95f),
                topK          = prefs.getInt("top_k", 40),
                flashAttnMode = prefs.getInt("flash_attn_mode", 1),
                useMmap       = prefs.getBoolean("use_mmap", true),
                useMlock      = prefs.getBoolean("use_mlock", false)
            )

            eng.loadModel(modelPath)
            waited = 0
            while (eng.state.value !is InferenceEngine.State.ModelReady) {
                if (waited++ > 300) { Log.e(TAG, "Model yükleme zaman aşımı"); return Result.success() }
                delay(200)
            }
            Log.i(TAG, "Model hazır, gönderi üretimi başlıyor")
            setForeground(buildProgressForeground("✍️ ${character.name} yazıyor…"))

            val charPrompt = character.systemPrompt.ifEmpty {
                listOf(character.description, character.personality, character.scenario)
                    .filter { it.isNotBlank() }.joinToString(". ")
            }

            val instruction = buildString {
                appendLine("Sen ${character.name} karakterisin. $charPrompt")
                appendLine()
                appendLine("Kendi karakterine uygun, günlük hayattan kısa bir sosyal medya gönderisi paylaş (belirli bir konu verilmedi, kendin seç).")
                appendLine("TAM OLARAK şu formatta yanıt ver (başka hiçbir şey yazma, açıklama yapma):")
                appendLine()
                appendLine("CAPTION: <gönderi metni, emoji ve hashtag dahil, max 4 cümle>")
                append("IMAGE_PROMPT: <İngilizce, görüntü üretim modeli için ayrıntılı sahne tarifi, max 20 kelime>")
            }

            val fullPrompt = buildAutoPostPromptTemplate(instruction, templateIndex, noThink)

            val sb = StringBuilder()
            val impl = eng as? InferenceEngineImpl
            val tokenFlow = if (impl != null && templateIndex != null) {
                impl.sendBypassPrompt(fullPrompt, 250)
            } else {
                eng.sendUserPrompt(fullPrompt, predictLength = 250)
            }
            tokenFlow.collect { token -> sb.append(token) }

            val raw = extractAutoPostVisibleContent(sb.toString())
            Log.i(TAG, "Ham yanıt (temizlenmiş): $raw")

            val caption     = extractAutoPostLine(raw, "CAPTION:")
            val imagePrompt = extractAutoPostLine(raw, "IMAGE_PROMPT:")

            val finalCaption = when {
                caption.isNotBlank() -> caption
                raw.trim().isNotBlank() -> raw.lines()
                    .filter { it.isNotBlank() && !it.startsWith("IMAGE_PROMPT") }
                    .take(3).joinToString(" ").trim()
                else -> {
                    Log.w(TAG, "Karakter yanıt üretemedi, iş atlandı")
                    return Result.success()
                }
            }

            // ── Dream API (buraya gelindiyse zaten açık olduğu garantili) ──────
            var imagePath: String? = null
            var usedPrompt: String? = null

            if (imagePrompt.isNotBlank()) {
                setForeground(buildProgressForeground("🎨 Görüntü oluşturuluyor…"))
                usedPrompt = imagePrompt

                val dreamApiUrl   = prefs.getString("dream_api_url", "http://127.0.0.1:8081") ?: "http://127.0.0.1:8081"
                val dreamSize     = prefs.getInt("dream_size", 512)
                val dreamSteps    = prefs.getInt("dream_steps", 20)
                val dreamCfg      = prefs.getFloat("dream_cfg", 7.0f)
                val dreamSeed     = prefs.getLong("dream_seed", -1L)
                val dreamOpenCl   = prefs.getBoolean("dream_use_opencl", false)
                val dreamNegative = prefs.getString("dream_negative_prompt", "") ?: ""

                var dreamBitmap: Bitmap? = null
                val dreamReq = DreamRequest(
                    prompt         = imagePrompt,
                    negativePrompt = dreamNegative,
                    size           = dreamSize,
                    steps          = dreamSteps,
                    cfg            = dreamCfg,
                    seed           = if (dreamSeed < 0) System.currentTimeMillis() % 100000L else dreamSeed,
                    useOpenCl      = dreamOpenCl
                )

                try {
                    performDreamRequest(dreamApiUrl, dreamReq) { event ->
                        when (event) {
                            is DreamEvent.Complete -> dreamBitmap = event.bitmap
                            is DreamEvent.Error    -> Log.w(TAG, "Dream hata: ${event.message}")
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Dream API çağrısı başarısız: ${e.message}")
                }

                if (dreamBitmap != null) {
                    imagePath = withContext(Dispatchers.IO) { saveAutoPostImage(dreamBitmap!!) }
                }
            }

            // ── DB kaydet ─────────────────────────────────────────────────────
            val post = MayagramPost(
                characterId        = character.id,
                characterName      = character.name,
                characterEmoji     = character.emoji,
                characterAvatarUri = character.avatarUri,
                caption            = finalCaption,
                imagePath          = imagePath,
                dreamPrompt        = usedPrompt,
                timestamp          = System.currentTimeMillis(),
                authorIsUser       = false
            )
            AppDatabase.getInstance(applicationContext).mayagramDao().insertPost(post)
            Log.i(TAG, "Otomatik gönderi kaydedildi: ${character.name}")

            if (!isManualTest) {
                MayagramAutoPostScheduler.incrementTodayCount(applicationContext)
                Log.i(TAG, "Günlük sayaç güncellendi: ${MayagramAutoPostScheduler.getTodayCount(applicationContext)}/${MayagramAutoPostScheduler.getDailyLimit(applicationContext).let { if (it <= 0) "limitsiz" else it.toString() }}")
            }

            try {
                if (eng.state.value is InferenceEngine.State.ModelReady) {
                    eng.cleanUp(); Log.i(TAG, "Model serbest bırakıldı")
                }
            } catch (e: Exception) { Log.w(TAG, "Temizleme uyarısı: ${e.message}") }

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Otomatik paylaşım hatası: ${e.message}", e)
            Result.failure()
        }
    }

    // ── Karakter listesi (autoPostEnabled filtrelenmiş) ──────────────────────

    private fun loadAutoPostCandidates(json: String?): List<MayaCharacter> {
        json ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                if (!obj.optBoolean("auto_post_enabled", false)) return@mapNotNull null
                val legacySystemPrompt = obj.optString("system_prompt", "")
                val description = obj.optString("description", "").ifEmpty {
                    if (obj.optString("personality", "").isEmpty()) legacySystemPrompt else ""
                }
                MayaCharacter(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    userName = obj.optString("user_name", "Kullanıcı"),
                    emoji = obj.optString("emoji", "🤖"),
                    systemPrompt = legacySystemPrompt,
                    avatarUri = obj.optString("avatar_uri", "").ifEmpty { null },
                    scenario = obj.optString("scenario", ""),
                    firstMessage = obj.optString("first_message", ""),
                    description = description,
                    personality = obj.optString("personality", ""),
                    autoPostEnabled = true
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Karakter listesi parse hatası: ${e.message}")
            emptyList()
        }
    }

    // ── Model dosya yolu çözümleme (DailyReportWorker ile aynı mantık) ───────

    private fun resolveModelCachePath(entry: String): String? {
        return if (entry.startsWith("uri:")) {
            val raw = entry.removePrefix("uri:")
            val fileName = raw.substringAfterLast("%2F").substringAfterLast("/")
                .let { if (it.isBlank()) raw.substringAfterLast("/") else it }
            listOfNotNull(
                applicationContext.getExternalFilesDir("models")?.let { File(it, "model_$fileName") },
                applicationContext.externalCacheDir?.let { File(it, "model_$fileName") },
                File(applicationContext.cacheDir, "model_$fileName")
            ).firstOrNull { it.exists() && it.length() > 0 }?.absolutePath
        } else {
            File(entry).takeIf { it.exists() }?.absolutePath
        }
    }

    // ── Prompt şablonu (MainActivityMayagram.buildMayagramPrompt ile aynı mantık) ──

    private fun buildAutoPostPromptTemplate(instruction: String, templateIndex: Int?, noThink: Boolean): String {
        val noThinkSuffix = if (noThink) "\n/no_think" else ""
        return when (templateIndex) {
            null -> instruction
            0    -> instruction
            1    -> "<BOS_TOKEN><|START_OF_TURN_TOKEN|><|USER_TOKEN|>$instruction$noThinkSuffix<|END_OF_TURN_TOKEN|><|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>"
            2    -> "<|im_start|>user\n$instruction$noThinkSuffix<|im_end|>\n<|im_start|>assistant\n"
            3    -> "<bos><start_of_turn>user\n$instruction$noThinkSuffix<end_of_turn>\n<start_of_turn>model\n"
            4    -> "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$instruction$noThinkSuffix<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            5    -> "<|start_of_role|>user<|end_of_role|>$instruction$noThinkSuffix<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>"
            7    -> "<bos><|turn>user\n$instruction<turn|>\n<|turn>model\n"  // Gemma 4: thinking tamamen kapalı (Mayagram deseni)
            else -> instruction
        }
    }

    // ── Çıktı temizleme (MainActivityMayagram.extractVisibleContent ile aynı mantık) ──

    private fun extractAutoPostVisibleContent(raw: String): String {
        var text = raw
        text = text.replace(Regex("""<\|channel>.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), "")
        text = text.replace(Regex("""<think>.*?</think>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        val thinkIdx = text.indexOf("<think>")
        if (thinkIdx != -1) text = text.substring(0, thinkIdx)

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

        val captionIdx = text.indexOf("CAPTION:", ignoreCase = true)
        val imageIdx   = text.indexOf("IMAGE_PROMPT:", ignoreCase = true)
        val startIdx = when {
            captionIdx >= 0 && imageIdx >= 0 -> minOf(captionIdx, imageIdx)
            captionIdx >= 0 -> captionIdx
            imageIdx   >= 0 -> imageIdx
            else -> -1
        }
        if (startIdx > 0) text = text.substring(startIdx)

        return text.trim()
    }

    private fun extractAutoPostLine(text: String, prefix: String): String {
        return text.lines()
            .firstOrNull { it.trimStart().startsWith(prefix, ignoreCase = true) }
            ?.replaceFirst(Regex("(?i)^\\s*${Regex.escape(prefix)}\\s*"), "")
            ?.trim() ?: ""
    }

    // ── Görüntü kaydetme ──────────────────────────────────────────────────────

    private fun saveAutoPostImage(bitmap: Bitmap): String {
        val dir = File(applicationContext.getExternalFilesDir(null), "Mayagram").also { it.mkdirs() }
        val file = File(dir, "autopost_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }

    // ── Bildirim ──────────────────────────────────────────────────────────────

    private fun buildProgressForeground(status: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📸 Mayagram — Otomatik Paylaşım")
            .setContentText(status)
            .setProgress(0, 0, true)
            .setOngoing(true).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL, "Mayagram Otomatik Paylaşım", NotificationManager.IMPORTANCE_LOW)
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
