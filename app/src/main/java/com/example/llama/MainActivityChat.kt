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

// \u2500\u2500 Sohbet y\u00f6netimi \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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

// \u2500\u2500 Mesaj g\u00f6nderme \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

internal fun MainActivity.sendMessage() {
    val text = messageInput.text.toString().trim()
    if (text.isEmpty() && selectedImagePath == null) return
    val msgText = text.ifEmpty { "Bu g\u00f6rseli a\u00e7\u0131kla." }
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

    // \u2500\u2500 v5.5: URL okuma \u2014 web aramas\u0131ndan ba\u011f\u0131ms\u0131z, her zaman otomatik \u2500\u2500\u2500\u2500\u2500\u2500
    val urlsInMessage = extractUrlsFromMessage(msgText, limit = 3)
    if (urlsInMessage.isNotEmpty() && imgPath == null) {
        lifecycleScope.launch {
            val savedTitle = supportActionBar?.title?.toString() ?: ""
            supportActionBar?.title = "\ud83d\udd17 Sayfa okunuyor\u2026"

            val pageContent = withContext(Dispatchers.IO) { fetchUrlsFromMessage(msgText) }

            supportActionBar?.title = savedTitle

            if (pageContent.isNotEmpty()) {
                val annotated = buildString {
                    appendLine("A\u015fa\u011f\u0131da kullan\u0131c\u0131n\u0131n payla\u015ft\u0131\u011f\u0131 URL(ler)den \u00e7ekilen sayfa i\u00e7erikleri yer almaktad\u0131r.")
                    appendLine("Bu i\u00e7eri\u011fi kullanarak kullan\u0131c\u0131n\u0131n iste\u011fini yerine getir.")
                    appendLine()
                    appendLine(pageContent)
                    appendLine()
                    append("Kullan\u0131c\u0131 mesaj\u0131: $msgText")
                }
                val modified = currentMessages.toMutableList()
                val lastIdx = modified.indexOfLast { it.isUser }
                if (lastIdx >= 0) {
                    val updated = modified[lastIdx].copy(annotatedContent = annotated)
                    modified[lastIdx] = updated
                    currentMessages[lastIdx] = updated
                }
                MainActivity.log("URLFetch", "URL i\u00e7eri\u011fi annotatedContent'e yaz\u0131ld\u0131: ${pageContent.length} karakter")
                continueWithWebSearch(msgText, imgPath, currentMessages.toList())
            } else {
                MainActivity.log("URLFetch", "URL fetch sonu\u00e7 vermedi, normal ak\u0131\u015fa devam ediliyor")
                continueWithWebSearch(msgText, imgPath, currentMessages.toList())
            }
        }
        return
    }

    continueWithWebSearch(msgText, imgPath, currentMessages.toList())
}

/**
 * URL fetch sonras\u0131nda (veya URL yoksa do\u011frudan) web aramas\u0131 ak\u0131\u015f\u0131n\u0131 y\u00fcr\u00fct\u00fcr.
 */
private fun MainActivity.continueWithWebSearch(
    msgText: String,
    imgPath: String?,
    messagesSnapshot: List<ChatMessage>
) {
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

                supportActionBar?.title = "\ud83d\udd0d Aran\u0131yor: ${searchQuery.take(30)}\u2026"
                val searchResults = withContext(Dispatchers.IO) { performWebSearch(searchQuery) }
                supportActionBar?.title = savedTitle

                val messagesToSend = if (searchResults.isNotEmpty()) {
                    lastWebSearchResults = searchResults
                    lastWebSearchQuery   = searchQuery

                    val motorName = when (webSearchEngine) {
                        "brave"   -> "Brave Search"
                        "searxng" -> "SearXNG ($searxngUrl)"
                        else      -> "DuckDuckGo"
                    }
                    MainActivity.log("WebArama", "=== SON ARAMA SONU\u00c7LARI ===")
                    MainActivity.log("WebArama", "Motor: $motorName | Sorgu: \"$searchQuery\" | Kaynak mesaj: \"${msgText.take(60)}\"")
                    MainActivity.log("WebArama", "---")
                    searchResults.lines().forEach { line -> MainActivity.log("WebArama", line) }
                    MainActivity.log("WebArama", "--- Toplam: ${searchResults.length
