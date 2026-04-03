package tr.maya

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.internal.InferenceEngineImpl
import tr.maya.data.Conversation
import tr.maya.data.DbMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ── Sohbet yönetimi ───────────────────────────────────────────────────────────

internal suspend fun MainActivity.ensureActiveConversation() {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val savedId = prefs.getString("active_conversation_id", null)
    currentConversationId = if (savedId != null && conversationExists(savedId)) savedId
                            else createNewConversation()
    loadMessagesForCurrent()
}

internal suspend fun MainActivity.conversationExists(id: String): Boolean = withContext(Dispatchers.IO) {
    try { db.chatDao().conversationExistsById(id) > 0 } catch (e: Exception) { false }
}

internal suspend fun MainActivity.createNewConversation(): String = withContext(Dispatchers.IO) {
    val id = UUID.randomUUID().toString()
    db.chatDao().insertConversation(Conversation(id = id, title = "Yeni Sohbet"))
    withContext(Dispatchers.Main) {
        currentConversationId = id
        saveActiveId(id)
        currentMessages.clear()
        messageAdapter.submitList(emptyList())
        conversationAdapter.activeId = id
        conversationAdapter.notifyDataSetChanged()
        updateToolbarTitle("Yeni Sohbet")
    }
    id
}

internal suspend fun MainActivity.switchConversation(id: String) {
    if (id == currentConversationId) return
    currentConversationId = id; saveActiveId(id)
    loadMessagesForCurrent()
    withContext(Dispatchers.Main) {
        conversationAdapter.activeId = id
        conversationAdapter.notifyDataSetChanged()
    }
}

internal suspend fun MainActivity.loadMessagesForCurrent() = withContext(Dispatchers.IO) {
    val dbMessages = db.chatDao().getMessages(currentConversationId)
    val chatMessages = dbMessages.map {
        ChatMessage(
            content = it.content,
            isUser = it.role == "user",
            tokensPerSecond = it.tps,
            timestamp = it.timestamp,
            imagePath = it.imagePath
        )
    }
    withContext(Dispatchers.Main) {
        currentMessages.clear()
        currentMessages.addAll(chatMessages)
        messageAdapter.submitList(currentMessages.toList())
        if (currentMessages.isNotEmpty()) {
            isAutoScrolling = true
            messagesRv.scrollToPosition(currentMessages.size - 1)
            messagesRv.post { isAutoScrolling = false }
        }
        updateToolbarTitle(if (chatMessages.isNotEmpty()) chatMessages.first().content.take(30) else "Yeni Sohbet")
    }
}

internal fun MainActivity.saveActiveId(id: String) {
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit().putString("active_conversation_id", id).apply()
}

internal fun MainActivity.clearCurrentChat() {
    lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            db.chatDao().deleteMessages(currentConversationId)
            db.chatDao().updateConversationTitle(currentConversationId, "Yeni Sohbet", System.currentTimeMillis())
        }
        currentMessages.clear()
        messageAdapter.submitList(emptyList())
        updateToolbarTitle("Yeni Sohbet")
    }
}

// ── Mesaj gönderme ────────────────────────────────────────────────────────────

internal fun MainActivity.sendMessage() {
    val text = messageInput.text.toString().trim()
    if (text.isEmpty() && selectedImagePath == null) return
    val msgText = text.ifEmpty { "Bu görseli açıkla." }
    messageInput.text.clear()

    val imgPath = selectedImagePath
    val userMsg = ChatMessage(content = msgText, isUser = true, timestamp = System.currentTimeMillis(), imagePath = imgPath)
    currentMessages.add(userMsg)
    messageAdapter.submitList(currentMessages.toList())
    autoScroll = true
    isAutoScrolling = true
    messagesRv.scrollToPosition(currentMessages.size - 1)
    messagesRv.post { isAutoScrolling = false }

    val convId = currentConversationId
    val msgId  = UUID.randomUUID().toString()
    lifecycleScope.launch(Dispatchers.IO) {
        db.chatDao().insertMessage(DbMessage(
            msgId, convId, "user", msgText,
            System.currentTimeMillis(), null, imgPath
        ))
        if (currentMessages.size == 1) {
            db.chatDao().updateConversationTitle(convId, msgText.take(40), System.currentTimeMillis())
        } else {
            db.chatDao().touchConversation(convId, System.currentTimeMillis())
        }
    }

    if (imgPath != null) {
        selectedImagePath = null
        imagePreviewContainer.visibility = android.view.View.GONE
        imagePreviewView.setImageDrawable(null)
    }

    // ── v5.5: URL okuma — web aramasından bağımsız, her zaman otomatik ──────
    // Mesajda URL varsa, URL fetch açıksa ve görsel mesaj değilse çek
    val urlsInMessage = extractUrlsFromMessage(msgText, limit = 3)
    if (urlsInMessage.isNotEmpty() && imgPath == null) {
        lifecycleScope.launch {
            val savedTitle = supportActionBar?.title?.toString() ?: ""
            supportActionBar?.title = "🔗 Sayfa okunuyor…"

            val pageContent = withContext(Dispatchers.IO) { fetchUrlsFromMessage(msgText) }

            supportActionBar?.title = savedTitle

            if (pageContent.isNotEmpty()) {
                // URL içeriğini annotatedContent'e ekle; web araması da varsa üstüne bindirme yapılır
                val annotated = buildString {
                    appendLine("Aşağıda kullanıcının paylaştığı URL(ler)den çekilen sayfa içerikleri yer almaktadır.")
                    appendLine("Bu içeriği kullanarak kullanıcının isteğini yerine getir.")
                    appendLine()
                    appendLine(pageContent)
                    appendLine()
                    append("Kullanıcı mesajı: $msgText")
                }
                val modified = currentMessages.toMutableList()
                val lastIdx = modified.indexOfLast { it.isUser }
                if (lastIdx >= 0) {
                    val updated = modified[lastIdx].copy(annotatedContent = annotated)
                    modified[lastIdx] = updated
                    currentMessages[lastIdx] = updated
                }
                MainActivity.log("URLFetch", "URL içeriği annotatedContent'e yazıldı: ${pageContent.length} karakter")

                // URL fetch bitti, şimdi web araması da varsa üzerine ekle; yoksa direkt gönder
                continueWithWebSearch(msgText, imgPath, currentMessages.toList())
            } else {
                MainActivity.log("URLFetch", "URL fetch sonuç vermedi, normal akışa devam ediliyor")
                continueWithWebSearch(msgText, imgPath, currentMessages.toList())
            }
        }
        return
    }

    // URL yoksa doğrudan web araması akışına geç
    continueWithWebSearch(msgText, imgPath, currentMessages.toList())
}

/**
 * URL fetch sonrasında (veya URL yoksa doğrudan) web araması akışını yürütür.
 * Web araması kapalıysa veya tetiklenmiyorsa sendMessageContent'i çağırır.
 */
private fun MainActivity.continueWithWebSearch(
    msgText: String,
    imgPath: String?,
    messagesSnapshot: List<ChatMessage>
) {
    // v5.4: Akıllı web araması — mod ve tetikleyici mantığı
    if (webSearchMode != "off" && msgText.isNotEmpty() && imgPath == null) {
        val shouldSearch = when (webSearchMode) {
            "trigger" -> containsTrigger(msgText)
            "always"  -> true
            else      -> false
        }

        if (shouldSearch) {
            lifecycleScope.launch {
                val savedTitle = supportActionBar?.title?.toString() ?: ""
                val searchQuery = when (webSearchQueryMode) {
                    "smart"  -> extractSmartQuery(msgText)
                    else     -> extractSimpleQuery(msgText)
                }
                MainActivity.log("Maya", "Arama sorgusu (mod=$webSearchQueryMode): \"$searchQuery\"")

                supportActionBar?.title = "🔍 Aranıyor: ${searchQuery.take(30)}…"
                val searchResults = withContext(Dispatchers.IO) { performWebSearch(searchQuery) }
                supportActionBar?.title = savedTitle

                val messagesToSend: List<ChatMessage>
                val searchResultsNotEmpty = searchResults.isNotEmpty()
                
                if (searchResultsNotEmpty) {
                    lastWebSearchResults = searchResults
                    lastWebSearchQuery   = searchQuery
    
                    val motorName = when (webSearchEngine) {
                        "brave"   -> "Brave Search"
                        "searxng" -> "SearXNG ($searxngUrl)"
                        else      -> "DuckDuckGo"
                    }
                    MainActivity.log("WebArama", "=== SON ARAMA SONUÇLARI ===")
                    MainActivity.log("WebArama", "Motor: $motorName | Sorgu: \"$searchQuery\" | Kaynak mesaj: \"${msgText.take(60)}\"")
                    MainActivity.log("WebArama", "---")
                    searchResults.lines().forEach { line -> MainActivity.log("WebArama", line) }
                    MainActivity.log("WebArama", "--- Toplam: ${searchResults.length} karakter ---")

                    val today = java.text.SimpleDateFormat("d MMMM yyyy, EEEE", java.util.Locale("tr")).format(java.util.Date())

                    // Web arama sonuçlarını mevcut annotatedContent'in üzerine ekle
                    val modified = currentMessages.toMutableList()
                    val lastIdx = modified.indexOfLast { it.isUser }
                    if (lastIdx >= 0) {
                        val existing = modified[lastIdx]
                        val existingAnnotated = existing.annotatedContent ?: ""
                        val annotated = buildString {
                            appendLine("Bugünün tarihi: $today")
                            appendLine()
                            appendLine("\"$searchQuery\" sorgusu için gerçek zamanlı web arama sonuçları:")
                            appendLine("Bu bilgiler GERÇEK ve GÜNCEL. Eğitim verilerindeki eski bilgileri değil,")
                            appendLine("aşağıdaki arama sonuçlarını kullanarak yanıt ver.")
                            appendLine()
                            appendLine("=== GÜNCEL WEB ARAMA SONUÇLARI ===")
                            append(searchResults)
                            appendLine()
                            appendLine("=== ARAMA SONUÇLARI SONU ===")
                            appendLine()
                            // Eğer URL içeriği zaten varsa, onu da koru
                            if (existingAnnotated.isNotBlank()) {
                                appendLine()
                                appendLine(existingAnnotated)
                            } else {
                                append("Kullanıcı mesajı: $msgText")
                            }
                        }
                        val updated = existing.copy(annotatedContent = annotated)
                        modified[lastIdx] = updated
                        currentMessages[lastIdx] = updated
                    }
                    MainActivity.log("Maya", "Web arama tamamlandı: ${searchResults.length} karakter, modele iletildi")
                    modified.toList()
                } else {
                    MainActivity.log("Maya", "Web arama sonuç döndürmedi (motor: $webSearchEngine, sorgu: \"$searchQuery\")")
                    currentMessages.toList()
                }
                sendMessageContent(messagesToSend)
            }
        } else {
            sendMessageContent(currentMessages.toList())
        }
    } else {
        sendMessageContent(currentMessages.toList())
    }
}

internal fun MainActivity.stopGeneration() {
    generationJob?.cancel(); generationJob = null
    isGenerating = false; updateFabIcon()
    generationService?.onGenerationCancelled(); generationService = null
    try { unbindService(serviceConnection) } catch (_: Exception) {}
}

// ── Mesaj düzenleme / yeniden oluştur ────────────────────────────────────────

internal fun MainActivity.showEditMessageDialog(position: Int, currentContent: String) {
    if (isGenerating) { Toast.makeText(this, "Yanıt üretilirken düzenleme yapılamaz", Toast.LENGTH_SHORT).show(); return }
    val input = android.widget.EditText(this).apply {
        setText(currentContent); setSelection(currentContent.length); setPadding(48, 24, 48, 24)
    }
    android.app.AlertDialog.Builder(this).setTitle("Mesajı Düzenle").setView(input)
        .setPositiveButton("Gönder") { _, _ ->
            val newText = input.text.toString().trim()
            if (newText.isNotEmpty() && newText != currentContent) editAndResend(position, newText)
        }.setNegativeButton("İptal", null).show()
}

internal fun MainActivity.editAndResend(position: Int, newContent: String) {
    val convId = currentConversationId
    while (currentMessages.size > position) currentMessages.removeAt(currentMessages.size - 1)
    currentMessages.add(ChatMessage(content = newContent, isUser = true, timestamp = System.currentTimeMillis()))
    messageAdapter.submitList(currentMessages.toList())
    autoScroll = true
    lifecycleScope.launch(Dispatchers.IO) {
        db.chatDao().deleteMessages(convId)
        currentMessages.forEachIndexed { idx, msg ->
            db.chatDao().insertMessage(DbMessage(
                id = UUID.randomUUID().toString(), conversationId = convId,
                role = if (msg.isUser) "user" else "assistant", content = msg.content,
                timestamp = System.currentTimeMillis() + idx, tps = msg.tokensPerSecond,
                imagePath = msg.imagePath
            ))
        }
    }
    sendMessageContent(currentMessages.toList())
}

internal fun MainActivity.regenerateLastResponse() {
    if (isGenerating) { Toast.makeText(this, "Yanıt üretilirken yeniden oluşturulamaz", Toast.LENGTH_SHORT).show(); return }
    if (loadedModelPath == null) { Toast.makeText(this, "Önce bir model yükleyin", Toast.LENGTH_SHORT).show(); return }
    if (currentMessages.isNotEmpty() && !currentMessages.last().isUser) currentMessages.removeAt(currentMessages.size - 1)
    if (currentMessages.isEmpty() || !currentMessages.last().isUser) return
    messageAdapter.submitList(currentMessages.toList())
    autoScroll = true
    val convId = currentConversationId
    lifecycleScope.launch(Dispatchers.IO) {
        val dbMessages = db.chatDao().getMessages(convId)
        val lastAssistant = dbMessages.lastOrNull { it.role == "assistant" }
        lastAssistant?.let { msg ->
            db.chatDao().deleteMessages(convId)
            dbMessages.filter { it.id != msg.id }.forEach { db.chatDao().insertMessage(it) }
        }
    }
    sendMessageContent(currentMessages.toList())
}

// ── Bypass Context Length yardımcıları ───────────────────────────────────────

internal fun MainActivity.estimateTokenCount(text: String): Int =
    (text.length / 3.5).toInt() + 8

internal fun MainActivity.truncateMessagesForBypass(messages: List<ChatMessage>): List<ChatMessage> {
    val sysTokens  = estimateTokenCount(applyPersona(systemPrompt)) + 30
    val reserved   = sysTokens + predictLength + 256
    val available  = contextSize - reserved

    if (available <= 50) {
        return messages.filter { it.isUser }.takeLast(1)
    }

    val result     = mutableListOf<ChatMessage>()
    var usedTokens = 0

    for (msg in messages.reversed()) {
        val msgTokens = estimateTokenCount(msg.content) + 20
        if (usedTokens + msgTokens > available && result.isNotEmpty()) break
        result.add(0, msg)
        usedTokens += msgTokens
    }

    while (result.isNotEmpty() && !result.first().isUser) result.removeFirst()

    if (result.isEmpty()) {
        messages.lastOrNull { it.isUser }?.let { result.add(it) }
    }

    return result
}

internal fun MainActivity.buildFormattedPromptFull(messages: List<ChatMessage>): String {
    if (selectedTemplate != 0) {
        return buildFormattedPrompt(messages)
    }
    val sp = applyPersona(systemPrompt)
    val sb = StringBuilder()
    if (sp.isNotEmpty()) {
        sb.append("<|im_start|>system\n$sp<|im_end|>\n")
    }
    for (msg in messages) {
        if (msg.isUser) {
            val rawContent = msg.annotatedContent ?: msg.content
            val body = applyPersona(if (noThinking) "$rawContent/no_think" else rawContent)
            sb.append("<|im_start|>user\n$body<|im_end|>\n")
        } else {
            sb.append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
        }
    }
    sb.append("<|im_start|>assistant\n")
    if (!noThinking) sb.append("<think>\n")
    return sb.toString()
}

// ── Prompt oluşturma ──────────────────────────────────────────────────────────

internal fun MainActivity.modelContent(msg: ChatMessage): String =
    if (msg.isUser) msg.annotatedContent ?: msg.content else msg.content

internal fun MainActivity.buildFormattedPrompt(messages: List<ChatMessage>): String {
    val sp = applyPersona(systemPrompt)
    if (selectedTemplate == 0) {
        val lastUser = messages.lastOrNull { it.isUser } ?: return ""
        val lastUserText = modelContent(lastUser)
        val processed = applyPersona(if (noThinking) "$lastUserText/no_think" else lastUserText)
        return processed
    }
    val sb = StringBuilder()
    when (selectedTemplate) {
        1 -> {
            sb.append("<BOS_TOKEN>")
            if (sp.isNotEmpty()) sb.append("<|START_OF_TURN_TOKEN|><|SYSTEM_TOKEN|>$sp<|END_OF_TURN_TOKEN|>")
            for (msg in messages) {
                if (msg.isUser) {
                    val body = applyPersona(if (noThinking) "${modelContent(msg)}/no_think" else modelContent(msg))
                    sb.append("<|START_OF_TURN_TOKEN|><|USER_TOKEN|>$userName: $body<|END_OF_TURN_TOKEN|>")
                } else sb.append("<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>${msg.content}<|END_OF_TURN_TOKEN|>")
            }
            sb.append("<|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>$charName: ")
        }
        2 -> {
            if (sp.isNotEmpty()) sb.append("<|im_start|>system\n$sp<|im_end|>\n")
            for (msg in messages) {
                if (msg.isUser) {
                    val body = applyPersona(if (noThinking) "${modelContent(msg)}/no_think" else modelContent(msg))
                    sb.append("<|im_start|>user\n$userName: $body<|im_end|>\n")
                } else sb.append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
            }
            sb.append("<|im_start|>assistant\n$charName: ")
        }
        3 -> {
            var systemInjected = false
            for (msg in messages) {
                if (msg.isUser) {
                    val prefix = if (!systemInjected && sp.isNotEmpty()) { systemInjected = true; "$sp\n\n" } else ""
                    val body = applyPersona(modelContent(msg))
                    sb.append("<start_of_turn>user\n$prefix$userName: $body<end_of_turn>\n")
                } else sb.append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
            }
            sb.insert(0, "<bos>")
            sb.append("<start_of_turn>model\n$charName: ")
        }
        4 -> {
            sb.append("<|begin_of_text|>")
            if (sp.isNotEmpty()) sb.append("<|start_header_id|>system<|end_header_id|>\n\n$sp<|eot_id|>")
            for (msg in messages) {
                if (msg.isUser) {
                    val body = applyPersona(modelContent(msg))
                    sb.append("<|start_header_id|>user<|end_header_id|>\n\n$userName: $body<|eot_id|>")
                } else sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n${msg.content}<|eot_id|>")
            }
            sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n$charName: ")
        }
        5 -> {
            if (sp.isNotEmpty()) {
                sb.append("<|start_of_role|>system<|end_of_role|>$sp<|end_of_text|>\n")
            }
            for (msg in messages) {
                if (msg.isUser) {
                    val body = applyPersona(modelContent(msg))
                    sb.append("<|start_of_role|>user<|end_of_role|>$userName: $body<|end_of_text|>\n")
                } else {
                    sb.append("<|start_of_role|>assistant<|end_of_role|>${msg.content}<|end_of_text|>\n")
                }
            }
            sb.append("<|start_of_role|>assistant<|end_of_role|>$charName: ")
        }
        6 -> {
            val t = activeCustomTemplate()
            if (t != null) {
                if (t.bosToken.isNotEmpty()) sb.append(t.bosToken)
                if (sp.isNotEmpty() && t.sysPrefix.isNotEmpty()) {
                    sb.append("${t.sysPrefix}$sp${t.sysSuffix}")
                }
                for (msg in messages) {
                    if (msg.isUser) {
                        val body = applyPersona(modelContent(msg))
                        sb.append("${t.inputPrefix}$userName: $body${t.inputSuffix}")
                    } else {
                        sb.append("${t.outputPrefix}${msg.content}${t.outputSuffix}")
                    }
                }
                sb.append("${t.lastOutputPrefix}$charName: ")
            }
        }
    }
    return sb.toString()
}

internal fun MainActivity.buildVisionPrompt(userContent: String): String {
    val body = applyPersona(userContent)
    return when (selectedTemplate) {
        0    -> body
        1    -> "<|START_OF_TURN_TOKEN|><|USER_TOKEN|>$userName: $body<|END_OF_TURN_TOKEN|><|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>$charName: "
        2    -> "<|im_start|>user\n$userName: $body<|im_end|>\n<|im_start|>assistant\n$charName: "
        3    -> "<start_of_turn>user\n$userName: $body<end_of_turn>\n<start_of_turn>model\n$charName: "
        4    -> "<|start_header_id|>user<|end_header_id|>\n\n$userName: $body<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n$charName: "
        5    -> "<|start_of_role|>user<|end_of_role|>$userName: $body<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>$charName: "
        6    -> activeCustomTemplate()?.let { t ->
                     val bos = t.bosToken
                     "$bos${t.inputPrefix}$userName: $body${t.inputSuffix}${t.lastOutputPrefix}$charName: "
                 } ?: body
        else -> body
    }
}

// ── Üretim döngüsü ────────────────────────────────────────────────────────────

internal fun MainActivity.sendMessageContent(messages: List<ChatMessage>) {
    if (loadedModelPath == null) { Toast.makeText(this, "Önce bir model yükleyin", Toast.LENGTH_SHORT).show(); return }

    val serviceIntent = Intent(this, MayaForegroundService::class.java)
    startService(serviceIntent)
    bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

    val convId = currentConversationId
    isGenerating = true; tokenUpdateCounter = 0; updateFabIcon()
    val responseBuilder = StringBuilder()
    var tokenCount = 0
    var generationStartTime = 0L

    val lastUserMsg = messages.lastOrNull { it.isUser }
    val pendingImagePath = lastUserMsg?.imagePath

    val formattedText = buildFormattedPrompt(messages)
    MainActivity.log("Maya", "sendMessageContent: template=$selectedTemplate turns=${messages.size} " +
        "noThinking=$noThinking predictLength=$predictLength hasImage=${pendingImagePath != null} " +
        "bypass=$bypassContextLength")

    generationJob = lifecycleScope.launch {
        var waited = 0
        while (generationService == null && waited < 20) { kotlinx.coroutines.delay(50); waited++ }
        generationService?.onGenerationStarted()
        messageAdapter.isStreaming = true

        try {
            val impl = engine as? InferenceEngineImpl

            val tokenFlow = when {
                pendingImagePath != null && impl != null && impl.isMmprojLoaded -> {
                    val visionPrompt = buildVisionPrompt(lastUserMsg?.content ?: "")
                    MainActivity.log("Maya", "Vision modu: görüntü gömülüyor")
                    impl.sendUserPromptWithImage(visionPrompt, pendingImagePath, predictLength)
                }
                bypassContextLength && impl != null -> {
                    val truncated  = truncateMessagesForBypass(messages)
                    val fullPrompt = buildFormattedPromptFull(truncated)
                    val dropped    = messages.size - truncated.size
                    if (dropped > 0) {
                        MainActivity.log("Maya", "Bypass: ${messages.size} mesajdan $dropped tanesi atıldı, " +
                                "${truncated.size} mesaj encode ediliyor (${fullPrompt.length} karakter)")
                    } else {
                        MainActivity.log("Maya", "Bypass: ${truncated.size} mesaj encode ediliyor " +
                                "(${fullPrompt.length} karakter)")
                    }
                    impl.sendBypassPrompt(fullPrompt, predictLength)
                }
                else -> {
                    if (pendingImagePath != null) {
                        MainActivity.log("Maya", "UYARI: Görüntü var ama mmproj yüklü değil, düz metin olarak gönderiliyor")
                    }
                    engine.sendUserPrompt(formattedText, predictLength = predictLength)
                }
            }

            tokenFlow.collect { token ->
                val cleaned = token
                    .replace("<|END_OF_TURN_TOKEN|>", "")
                    .replace("<|START_OF_TURN_TOKEN|>", "")
                    .replace("<|USER_TOKEN|>", "")
                    .replace("<|CHATBOT_TOKEN|>", "")
                    .replace("<|START_RESPONSE|>", "")
                    .replace("<|END_RESPONSE|>", "")
                    .replace("<end_of_turn>", "")
                    .replace("<start_of_turn>", "")
                    .replace("<|eot_id|>", "")
                    .replace("<|im_end|>", "")
                    .replace("<|end_of_text|>", "")
                    .replace("<|start_of_role|>", "")
                    .replace("<|end_of_role|>", "")
                    .let { t ->
                        if (selectedTemplate == 6) {
                            val stop = activeCustomTemplate()?.stopSeq ?: ""
                            if (stop.isNotEmpty()) t.replace(stop, "") else t
                        } else t
                    }
                if (tokenCount == 0) generationStartTime = System.currentTimeMillis()
                responseBuilder.append(cleaned)
                tokenCount++; tokenUpdateCounter++
                if (tokenUpdateCounter % 8 == 0) {
                    val snapshot = stripCharPrefix(responseBuilder.toString())
                    messageAdapter.updateLastAssistantMessage(snapshot)
                    if (autoScroll && !scrollPending) {
                        scrollPending = true
                        messagesRv.post {
                            scrollPending = false
                            if (autoScroll) {
                                isAutoScrolling = true
                                messagesRv.scrollToPosition(messageAdapter.itemCount - 1)
                                messagesRv.post { isAutoScrolling = false }
                            }
                        }
                    }
                }
                if (tokenUpdateCounter % 20 == 0) {
                    generationService?.onTokenUpdate(responseBuilder.toString())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            MainActivity.log("Maya", "Üretim kullanıcı tarafından durduruldu. ${responseBuilder.length} karakter üretildi.")
            throw e
        } catch (e: Exception) {
            val current = responseBuilder.toString()
            messageAdapter.updateLastAssistantMessage(
                if (current.isEmpty()) "[Hata: ${e.message}]" else current
            )
        } finally {
            val fullResponse = stripCharPrefix(responseBuilder.toString())
            val elapsedSec = (System.currentTimeMillis() - generationStartTime) / 1000f
            val tps = if (elapsedSec > 0f && tokenCount > 0) tokenCount / elapsedSec else null

            messageAdapter.isStreaming = false

            if (currentMessages.isNotEmpty() && !currentMessages.last().isUser) {
                currentMessages[currentMessages.size - 1] = ChatMessage(fullResponse, false, tps, System.currentTimeMillis())
            } else {
                currentMessages.add(ChatMessage(fullResponse, false, tps, System.currentTimeMillis()))
            }
            messageAdapter.updateLastAssistantMessage(fullResponse, tps)
            if (fullResponse.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.chatDao().insertMessage(DbMessage(
                        UUID.randomUUID().toString(), convId, "assistant", fullResponse,
                        System.currentTimeMillis(), tps
                    ))
                    db.chatDao().touchConversation(convId, System.currentTimeMillis())
                }
            }

            val userMsgCount = currentMessages.count { it.isUser }
            if (userMsgCount == 1 && convId !in skipAutoTitleConvIds && fullResponse.isNotEmpty() && loadedModelPath != null) {
                val firstUserText = currentMessages.firstOrNull { it.isUser }?.content ?: ""
                if (firstUserText.isNotEmpty()) {
                    skipAutoTitleConvIds.add(convId)
                    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit()
                        .putStringSet("skip_auto_title_convs", skipAutoTitleConvIds.toSet()).apply()
                    generateConversationTitle(convId, firstUserText)
                }
            }
            MainActivity.log("Maya", "Üretim bitti: ${fullResponse.length} karakter")
            isGenerating = false; autoScroll = true; updateFabIcon()
            generationService?.onGenerationFinished(fullResponse, isAppInForeground)
            generationService = null
            try { unbindService(serviceConnection) } catch (_: Exception) {}
        }
    }
}

// ── Otomatik sohbet başlığı ───────────────────────────────────────────────────

internal fun MainActivity.buildTitlePrompt(userText: String): String {
    val instruction = "Bu sohbet için 3-5 kelimelik kısa bir başlık yaz. Sadece başlığı yaz, başka hiçbir şey ekleme:\n\n${userText.take(300)}"
    return when (selectedTemplate) {
        0    -> instruction
        1    -> "<BOS_TOKEN><|START_OF_TURN_TOKEN|><|USER_TOKEN|>$instruction<|END_OF_TURN_TOKEN|><|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>"
        2    -> "<|im_start|>user\n$instruction<|im_end|>\n<|im_start|>assistant\n"
        3    -> "<bos><start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"
        4    -> "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$instruction<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        5    -> "<|start_of_role|>user<|end_of_role|>$instruction<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>"
        6    -> activeCustomTemplate()?.let { t ->
                     "${t.bosToken}${t.inputPrefix}$instruction${t.inputSuffix}${t.lastOutputPrefix}"
                 } ?: instruction
        else -> instruction
    }
}

internal fun MainActivity.generateConversationTitle(convId: String, userText: String) {
    lifecycleScope.launch {
        try {
            val prompt = buildTitlePrompt(userText)
            val sb = StringBuilder()
            engine.sendUserPrompt(prompt, predictLength = 30).collect { token -> sb.append(token) }

            val rawTitle = sb.toString()
                .replace(Regex("<[^>]+>"), "").replace("|im_end|", "").replace("|eot_id|", "")
                .replace("<end_of_turn>", "").replace("<|eot_id|>", "")
                .trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""

            val cleanTitle = rawTitle
                .removePrefix("\"").removeSuffix("\"")
                .removePrefix("'").removeSuffix("'")
                .removePrefix("*").removeSuffix("*")
                .removePrefix("#").trim()

            if (cleanTitle.length in 2..80) {
                withContext(Dispatchers.IO) {
                    db.chatDao().updateConversationTitle(convId, cleanTitle, System.currentTimeMillis())
                }
                if (convId == currentConversationId) updateToolbarTitle(cleanTitle)
            }
        } catch (e: Exception) {
            MainActivity.log("Maya", "Başlık üretimi başarısız: ${e.message}")
        }
    }
}
