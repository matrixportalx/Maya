package tr.maya

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tr.maya.data.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

// ── UI kurulumu ───────────────────────────────────────────────────────────────

internal fun MainActivity.bindViews() {
    drawerLayout    = findViewById(R.id.drawer_layout)
    toolbar         = findViewById(R.id.toolbar)
    messagesRv      = findViewById(R.id.messages)
    messageInput    = findViewById(R.id.message)
    fab             = findViewById(R.id.send)
    conversationsRv = findViewById(R.id.conversations_list)
    btnNewChat      = findViewById(R.id.btn_new_chat)
    charactersRv    = findViewById(R.id.characters_list)
    btnAddCharacter = findViewById(R.id.btn_add_character)
    imagePreviewContainer = findViewById(R.id.image_preview_container)
    imagePreviewView      = findViewById(R.id.image_preview)
    imagePreviewLabel     = findViewById(R.id.image_preview_label)
    btnRemoveImage        = findViewById(R.id.btn_remove_image)
    btnAttachImage        = findViewById(R.id.btn_attach_image)
    btnWebSearch          = findViewById(R.id.btn_web_search)
}

internal fun MainActivity.setupToolbar() {
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
}

internal fun MainActivity.setupDrawer() {
    val toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.drawer_open, R.string.drawer_close)
    drawerLayout.addDrawerListener(toggle)
    toggle.syncState()
    btnNewChat.setOnClickListener {
        lifecycleScope.launch { createNewConversation(); drawerLayout.closeDrawers() }
    }
}

internal fun MainActivity.setupMessageList() {
    messageAdapter = MessageAdapter(
        onCopy = { msg ->
            val clip = ClipData.newPlainText("mesaj", msg)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(this, "Panoya kopyalandı", Toast.LENGTH_SHORT).show()
        },
        onEdit = { position, content -> showEditMessageDialog(position, content) },
        onRegenerate = { _ -> regenerateLastResponse() }
    )
    messagesRv.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
    messagesRv.adapter = messageAdapter
    val activeChar = characters.find { it.id == activeCharacterId }
    messageAdapter.charName  = activeChar?.name  ?: charName
    messageAdapter.charEmoji = activeChar?.emoji ?: "🤖"
    messageAdapter.userName  = activeChar?.userName ?: userName
    messageAdapter.userEmoji = "👤"

    messagesRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            when (newState) {
                RecyclerView.SCROLL_STATE_DRAGGING -> {
                    userIsScrolling = true
                    scrollPending = false
                    autoScroll = false
                }
                RecyclerView.SCROLL_STATE_IDLE -> {
                    if (userIsScrolling && !recyclerView.canScrollVertically(1)) {
                        autoScroll = true
                    }
                    userIsScrolling = false
                }
            }
        }
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { }
    })
}

internal fun MainActivity.setupConversationList() {
    conversationAdapter = ConversationAdapter(
        onSelect = { conv ->
            lifecycleScope.launch { switchConversation(conv.id); drawerLayout.closeDrawers() }
        },
        onDelete = { conv -> confirmDeleteConversation(conv) },
        onRename = { conv -> showRenameConversationDialog(conv) }
    )
    conversationsRv.layoutManager = LinearLayoutManager(this)
    conversationsRv.adapter = conversationAdapter
}

internal fun MainActivity.setupFab() {
    updateFabIcon()
    fab.setOnClickListener {
        when {
            isGenerating -> stopGeneration()
            loadedModelPath == null -> showModelPickerDialog()
            else -> sendMessage()
        }
    }
}

internal fun MainActivity.updateFabIcon() {
    fab.setImageResource(when {
        isGenerating -> android.R.drawable.ic_media_pause
        loadedModelPath == null -> android.R.drawable.ic_menu_add
        else -> android.R.drawable.ic_menu_send
    })
}

internal fun MainActivity.setupInput() {
    messageInput.setOnEditorActionListener { _, _, _ ->
        if (!isGenerating && loadedModelPath != null) sendMessage()
        true
    }
}

internal fun MainActivity.observeConversations() {
    lifecycleScope.launch {
        db.chatDao().getAllConversations().collectLatest { list ->
            conversationAdapter.activeId = currentConversationId
            conversationAdapter.submitList(list)
        }
    }
}

internal fun MainActivity.updateToolbarTitle(title: String) { supportActionBar?.title = title }

internal fun MainActivity.updateActiveModelSubtitle() {
    val modelName = loadedModelPath?.let { MainActivity.entryDisplayName(it) } ?: "Model yüklü değil"
    val activeChar = characters.find { it.id == activeCharacterId }
    val charStr = if (activeChar != null) "${activeChar.emoji} ${activeChar.name}  •  " else ""
    val impl = engine as? com.arm.aichat.internal.InferenceEngineImpl
    val visionStr = if (impl?.isMmprojLoaded == true) "  📷" else ""
    val searchStr = when (webSearchMode) {
        "trigger" -> "  🔍"
        "always"  -> "  🌐"
        else      -> ""
    }
    supportActionBar?.subtitle = "$charStr$modelName$visionStr$searchStr"
}

// ── Güncelleme kontrolü ───────────────────────────────────────────────────────

/**
 * Uygulama açılışında sessizce güncelleme kontrolü yapar.
 * Güncelleme varsa toolbar başlığına "🆕" rozeti ekler ve
 * menüde "Güncelleme mevcut" olarak gösterir.
 */
internal fun MainActivity.checkForUpdateSilently() {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)

    // Günde en fazla bir kez kontrol et (pil/veri tasarrufu)
    val lastCheck = prefs.getLong("last_update_check", 0L)
    val now       = System.currentTimeMillis()
    if (now - lastCheck < 24 * 60 * 60 * 1000L) return   // 24 saat geçmedi

    prefs.edit().putLong("last_update_check", now).apply()

    lifecycleScope.launch {
        AppUpdater.checkForUpdate(
            context        = this@checkForUpdateSilently,
            currentVersion = android.os.Build.VERSION.SDK_INT.let {
                // BuildConfig yerine versionName doğrudan prefs'e yazılır ilk açılışta
                prefs.getString("app_version_name", getVersionName()) ?: getVersionName()
            }
        ) { info ->
            if (info != null) {
                pendingUpdateInfo = info
                // Toolbar'a küçük rozet ekle
                supportActionBar?.title = "Maya 🆕"
                invalidateOptionsMenu()  // menüyü güncelle
                log("Updater", "Güncelleme mevcut: ${info.versionName}")
            }
        }
    }
}

/**
 * Menüden veya ayarlardan "Güncelleme Kontrol Et" seçildiğinde.
 * Her zaman kontrol eder (24 saat sınırını atlar).
 */
internal fun MainActivity.checkForUpdateNow() {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    lifecycleScope.launch {
        supportActionBar?.subtitle = "🔍 Güncelleme kontrol ediliyor…"
        AppUpdater.checkForUpdate(
            context        = this@checkForUpdateNow,
            currentVersion = prefs.getString("app_version_name", getVersionName()) ?: getVersionName()
        ) { info ->
            updateActiveModelSubtitle()   // subtitle'ı geri yükle
            if (info != null) {
                pendingUpdateInfo = info
                invalidateOptionsMenu()
                showUpdateDialog(info)
            } else {
                Toast.makeText(
                    this@checkForUpdateNow,
                    "✅ Uygulama güncel (${getVersionName()})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/**
 * Güncelleme diyaloğunu gösterir.
 * Bekleyen güncelleme varsa (pendingUpdateInfo != null) bilgi gösterir,
 * kullanıcı onaylarsa indirme başlar.
 */
internal fun MainActivity.showUpdateDialog(info: AppUpdater.UpdateInfo) {
    val sizeStr = AppUpdater.formatSize(info.apkSize)
    val msg = buildString {
        appendLine("Yeni sürüm: ${info.versionName}$sizeStr")
        appendLine("Mevcut sürüm: ${getVersionName()}")
        if (info.releaseNotes.isNotEmpty()) {
            appendLine()
            appendLine("📝 Sürüm notları:")
            appendLine(info.releaseNotes)
        }
    }
    android.app.AlertDialog.Builder(this)
        .setTitle("🆕 Güncelleme Mevcut")
        .setMessage(msg)
        .setPositiveButton("İndir ve Kur") { _, _ ->
            AppUpdater.downloadAndInstall(this, info)
        }
        .setNegativeButton("Sonra", null)
        .show()
}

/** PackageManager'dan mevcut versionName'i okur */
internal fun MainActivity.getVersionName(): String {
    return try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) { "1.0.0" }
}

internal fun MainActivity.showRenameConversationDialog(conv: Conversation) {
    val input = EditText(this).apply {
        setText(conv.title); selectAll(); setPadding(48, 24, 48, 24)
    }
    android.app.AlertDialog.Builder(this)
        .setTitle("Sohbeti Yeniden Adlandır").setView(input)
        .setPositiveButton("Kaydet") { _, _ ->
            val newTitle = input.text.toString().trim()
            if (newTitle.isNotEmpty() && newTitle != conv.title) {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.chatDao().updateConversationTitle(conv.id, newTitle, System.currentTimeMillis())
                    if (conv.id == currentConversationId) {
                        withContext(Dispatchers.Main) { updateToolbarTitle(newTitle) }
                    }
                }
                skipAutoTitleConvIds.add(conv.id)
                getSharedPreferences("llama_prefs", Context.MODE_PRIVATE).edit()
                    .putStringSet("skip_auto_title_convs", skipAutoTitleConvIds.toSet()).apply()
            }
        }
        .setNegativeButton("İptal", null).show()
}

internal fun MainActivity.confirmDeleteConversation(conv: Conversation) {
    android.app.AlertDialog.Builder(this).setTitle("Sohbeti Sil")
        .setMessage("\"${conv.title}\" silinsin mi?")
        .setPositiveButton("Sil") { _, _ ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    db.chatDao().deleteMessages(conv.id)
                    db.chatDao().deleteConversation(conv.id)
                }
                if (conv.id == currentConversationId) createNewConversation()
            }
        }
        .setNegativeButton("İptal", null).show()
}

// ── Sohbeti dışa aktar ────────────────────────────────────────────────────────

internal fun MainActivity.exportChat() {
    if (currentMessages.isEmpty()) {
        Toast.makeText(this, "Dışa aktarılacak mesaj yok", Toast.LENGTH_SHORT).show()
        return
    }
    val formats = arrayOf("Düz Metin (.txt)", "Markdown (.md)")
    android.app.AlertDialog.Builder(this).setTitle("📤 Dışa Aktarma Formatı")
        .setItems(formats) { _, which ->
            val isMarkdown = which == 1
            val ext = if (isMarkdown) "md" else "txt"
            val mimeType = if (isMarkdown) "text/markdown" else "text/plain"
            val convTitle = supportActionBar?.title?.toString()
                ?.replace(Regex("[^\\w\\s-]"), "")?.trim() ?: "sohbet"
            val fileName = "maya_${convTitle.take(30)}_${System.currentTimeMillis()}.$ext"
            pendingExportCallback = { uri -> performExportToUri(uri, isMarkdown) }
            exportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, fileName)
            })
        }
        .setNegativeButton("İptal", null).show()
}

internal fun MainActivity.performExportToUri(uri: android.net.Uri, isMarkdown: Boolean) {
    val activity = this
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            val title = withContext(Dispatchers.Main) { supportActionBar?.title?.toString() ?: "Sohbet" }
            val dateStr = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

            if (isMarkdown) {
                sb.appendLine("# $title"); sb.appendLine("*Dışa aktarma tarihi: $dateStr*"); sb.appendLine()
                for (msg in currentMessages) {
                    if (msg.isUser) {
                        sb.appendLine("**Sen:**")
                        if (msg.imagePath != null) sb.appendLine("*[Görüntü eklenmiş]*")
                        sb.appendLine(msg.content)
                    } else {
                        sb.appendLine("**Maya:**")
                        val visible = msg.content.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
                        sb.appendLine(visible.ifEmpty { msg.content })
                        msg.tokensPerSecond?.let { tps -> sb.appendLine(); sb.appendLine("*${String.format("%.2f", tps)} t/s*") }
                    }
                    sb.appendLine(); sb.appendLine("---"); sb.appendLine()
                }
            } else {
                sb.appendLine("=== $title ==="); sb.appendLine("Dışa aktarma tarihi: $dateStr"); sb.appendLine()
                for (msg in currentMessages) {
                    if (msg.isUser) {
                        sb.appendLine("Sen:")
                        if (msg.imagePath != null) sb.appendLine("[Görüntü eklenmiş]")
                        sb.appendLine(msg.content)
                    } else {
                        sb.appendLine("Maya:")
                        val visible = msg.content.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
                        sb.appendLine(visible.ifEmpty { msg.content })
                        msg.tokensPerSecond?.let { tps -> sb.appendLine("[${String.format("%.2f", tps)} t/s]") }
                    }
                    sb.appendLine()
                }
            }

            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            } ?: throw Exception("Dosya yazılamadı")

            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "${currentMessages.size} mesaj dışa aktarıldı", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Dışa aktarma hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// ── Log diyalogları ───────────────────────────────────────────────────────────

internal fun MainActivity.showLogsDialog() {
    if (lastWebSearchResults.isNotEmpty()) {
        showLogsDialogWithSearchButton()
        return
    }
    val display = MainActivity.getLogs().ifBlank { "Henüz log yok.\nBir model yükleyip mesaj gönderin." }
    val tv = android.widget.TextView(this).apply {
        text = display; textSize = 10f; setTextIsSelectable(true)
        typeface = android.graphics.Typeface.MONOSPACE
        val pad = (8 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
    }
    val scroll = android.widget.ScrollView(this).apply {
        addView(tv); post { fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
    }
    android.app.AlertDialog.Builder(this).setTitle("🔍 Uygulama Logları").setView(scroll)
        .setPositiveButton("Kapat", null)
        .setNeutralButton("Kopyala") { _, _ ->
            val clip = ClipData.newPlainText("log", display)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(this, "Loglar kopyalandı", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(if (MainActivity.loggingEnabled) "🟢 Loglamayı Kapat" else "🔴 Loglamayı Aç") { _, _ ->
            MainActivity.loggingEnabled = !MainActivity.loggingEnabled
            Toast.makeText(this, if (MainActivity.loggingEnabled) "Loglama açıldı" else "Loglama kapatıldı", Toast.LENGTH_SHORT).show()
        }.show()
}

internal fun MainActivity.showLogsDialogWithSearchButton() {
    val display = MainActivity.getLogs().ifBlank { "Henüz log yok.\nBir model yükleyip mesaj gönderin." }
    val dp = resources.displayMetrics.density
    val outerLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val tv = android.widget.TextView(this).apply {
        text = display; textSize = 10f; setTextIsSelectable(true)
        typeface = android.graphics.Typeface.MONOSPACE
        val pad = (8 * dp).toInt(); setPadding(pad, pad, pad, pad)
    }
    val scroll = android.widget.ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(tv)
        post { fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
    }
    outerLayout.addView(scroll)

    if (lastWebSearchResults.isNotEmpty()) {
        val btnLastSearch = android.widget.Button(this).apply {
            text = "🌐 Son Aramanın Sonuçları: \"${lastWebSearchQuery.take(40)}\""
            isAllCaps = false; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { val m = (8 * dp).toInt(); setMargins(m, 0, m, m) }
        }
        outerLayout.addView(btnLastSearch)

        android.app.AlertDialog.Builder(this).setTitle("🔍 Uygulama Logları").setView(outerLayout)
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Kopyala") { _, _ ->
                val clip = ClipData.newPlainText("log", display)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(this, "Loglar kopyalandı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(if (MainActivity.loggingEnabled) "🟢 Loglamayı Kapat" else "🔴 Loglamayı Aç") { _, _ ->
                MainActivity.loggingEnabled = !MainActivity.loggingEnabled
                Toast.makeText(this, if (MainActivity.loggingEnabled) "Loglama açıldı" else "Loglama kapatıldı", Toast.LENGTH_SHORT).show()
            }
            .also { builder ->
                val d = builder.create(); d.show()
                btnLastSearch.setOnClickListener { d.dismiss(); showLastSearchResultsDialog() }
            }
        return
    }

    android.app.AlertDialog.Builder(this).setTitle("🔍 Uygulama Logları").setView(outerLayout)
        .setPositiveButton("Kapat", null)
        .setNeutralButton("Kopyala") { _, _ ->
            val clip = ClipData.newPlainText("log", display)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(this, "Loglar kopyalandı", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(if (MainActivity.loggingEnabled) "🟢 Loglamayı Kapat" else "🔴 Loglamayı Aç") { _, _ ->
            MainActivity.loggingEnabled = !MainActivity.loggingEnabled
            Toast.makeText(this, if (MainActivity.loggingEnabled) "Loglama açıldı" else "Loglama kapatıldı", Toast.LENGTH_SHORT).show()
        }.show()
}

internal fun MainActivity.showLastSearchResultsDialog() {
    val dp = resources.displayMetrics.density
    val motorName = when (webSearchEngine) {
        "brave"   -> "Brave Search"
        "searxng" -> "SearXNG"
        else      -> "DuckDuckGo"
    }
    val header = "Motor: $motorName\nSorgu: \"$lastWebSearchQuery\"\n" +
        "Sonuç: ${lastWebSearchResults.length} karakter\n\n"
    val tv = android.widget.TextView(this).apply {
        text = header + lastWebSearchResults
        textSize = 11f; setTextIsSelectable(true)
        typeface = android.graphics.Typeface.MONOSPACE
        setTextColor(0xFFE0E0E0.toInt())
        val pad = (12 * dp).toInt(); setPadding(pad, pad, pad, pad)
    }
    val scroll = android.widget.ScrollView(this).apply { addView(tv) }
    android.app.AlertDialog.Builder(this)
        .setTitle("🌐 Son Arama Sonuçları")
        .setView(scroll)
        .setPositiveButton("Kapat", null)
        .setNeutralButton("Kopyala") { _, _ ->
            val clip = ClipData.newPlainText("search", lastWebSearchResults)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(this, "Sonuçlar kopyalandı", Toast.LENGTH_SHORT).show()
        }.show()
}
