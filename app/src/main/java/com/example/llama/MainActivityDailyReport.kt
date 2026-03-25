package tr.maya

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import tr.maya.data.DbMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ── Günlük rapor kontrolü ─────────────────────────────────────────────────────

internal fun MainActivity.checkAndShowPendingDailyReport(intent: android.content.Intent? = null) {
    val prefs = getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE)

    val fromIntent = intent?.getBooleanExtra("daily_report_ready", false) == true
    val fromFlag   = prefs.getBoolean("report_needs_display", false)
    if (!fromIntent && !fromFlag) return

    prefs.edit().putBoolean("report_needs_display", false).apply()
    intent?.removeExtra("daily_report_ready")

    val pid = intent?.getStringExtra("report_profile_id")
        ?: prefs.getString("last_profile_id", null)
        ?: "default"

    val finalSummary  = prefs.getString(DailyReportWorker.keySummary(pid), null)
        ?: prefs.getString("final_summary", null)
    val pendingReport = prefs.getString(DailyReportWorker.keyPending(pid), null)
        ?: prefs.getString("pending_report", null)
    val reportDate    = prefs.getString(DailyReportWorker.keyDate(pid), null)
        ?: prefs.getString("report_date", "") ?: ""

    val profileName = reportProfiles.find { it.id == pid }?.name ?: "Günlük Rapor"

    when {
        !finalSummary.isNullOrEmpty()  -> showSummaryDialog(finalSummary, reportDate, pid, profileName)
        !pendingReport.isNullOrEmpty() -> showRawReportDialog(pendingReport, reportDate, pid, profileName)
    }
}

internal fun MainActivity.addSummaryToConversation(summary: String, date: String, pid: String, profileName: String) {
    val prefs = getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .remove(DailyReportWorker.keySummary(pid))
        .remove(DailyReportWorker.keyPending(pid))
        .remove("final_summary").remove("pending_report")
        .apply()
    val reportTitle = "📰 $profileName — $date"
    val msg = ChatMessage(
        content   = "📰 **$profileName — $date**\n\n$summary",
        isUser    = false,
        timestamp = System.currentTimeMillis()
    )
    currentMessages.add(msg)
    messageAdapter.submitList(currentMessages.toList())
    autoScroll = true
    messagesRv.post { messagesRv.scrollToPosition(currentMessages.size - 1) }
    val isNewConv = currentMessages.size == 1
    val convId = currentConversationId
    lifecycleScope.launch(Dispatchers.IO) {
        db.chatDao().insertMessage(DbMessage(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = "assistant",
            content = msg.content,
            timestamp = msg.timestamp,
            tps = null
        ))
        if (isNewConv) {
            db.chatDao().updateConversationTitle(convId, reportTitle, System.currentTimeMillis())
            withContext(Dispatchers.Main) {
                updateToolbarTitle(reportTitle)
                skipAutoTitleConvIds.add(convId)
            }
        }
    }
    Toast.makeText(this, "📰 Rapor sohbete eklendi", Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.showSummaryDialog(summary: String, date: String, pid: String, profileName: String) {
    val preview = if (summary.length > 300) summary.take(300) + "…" else summary
    val rawData = getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(DailyReportWorker.keyPending(pid), null)
    android.app.AlertDialog.Builder(this)
        .setTitle("📰 $profileName Hazır")
        .setMessage("$date\n\n$preview")
        .setPositiveButton("Sohbete Ekle") { _, _ -> addSummaryToConversation(summary, date, pid, profileName) }
        .setNeutralButton("Ham Veriyi Gör") { _, _ ->
            android.app.AlertDialog.Builder(this).setTitle("Ham Arama Sonuçları")
                .setMessage(rawData ?: "Ham veri bulunamadı.")
                .setPositiveButton("Tamam", null).show()
        }
        .setNegativeButton("Kapat") { _, _ ->
            getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(DailyReportWorker.keySummary(pid)).apply()
        }
        .show()
}

internal fun MainActivity.showRawReportDialog(rawData: String, date: String, pid: String, profileName: String) {
    android.app.AlertDialog.Builder(this)
        .setTitle("📰 $profileName — Özet Bekleniyor")
        .setMessage("Rapor oluşturulurken model meşguldü.\n$date\n\nŞimdi özetlemek ister misiniz?")
        .setPositiveButton("Şimdi Özetle") { _, _ ->
            getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(DailyReportWorker.keyPending(pid)).apply()
            sendDailyReportToModel(rawData, date, profileName)
        }
        .setNeutralButton("Ham Sonuçları Gör") { _, _ ->
            android.app.AlertDialog.Builder(this).setTitle("Ham Arama Sonuçları")
                .setMessage(rawData).setPositiveButton("Tamam", null).show()
        }
        .setNegativeButton("Kapat") { _, _ ->
            getSharedPreferences(DailyReportWorker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(DailyReportWorker.keyPending(pid)).apply()
        }
        .show()
}

internal fun MainActivity.sendDailyReportToModel(rawData: String, date: String, profileName: String = "Rapor") {
    if (loadedModelPath == null) { Toast.makeText(this, "Önce bir model yükleyin", Toast.LENGTH_SHORT).show(); return }
    val visibleMsg = ChatMessage(
        content = "📰 $profileName — $date raporunu Türkçe özetle.",
        isUser = true,
        timestamp = System.currentTimeMillis()
    )
    currentMessages.add(visibleMsg)
    messageAdapter.submitList(currentMessages.toList())
    autoScroll = true
    val trimmedData = rawData.take(15000)
    val hiddenMsg = visibleMsg.copy(
        content = "Aşağıdaki arama sonuçlarını kısa, anlaşılır, madde madde Türkçe olarak özetle. " +
                  "Sadece özeti yaz, başka bir şey ekleme.\n\n=== HABERLER ===\n$trimmedData\n=== SON ==="
    )
    val messagesForModel = currentMessages.dropLast(1) + hiddenMsg
    sendMessageContent(messagesForModel)
}

// ── Rapor profili diyaloğu ────────────────────────────────────────────────────

internal fun MainActivity.showProfileEditDialog(profile: ReportProfile?, onSaved: () -> Unit) {
    val dp = resources.displayMetrics.density
    val ctx = this
    val isNew = profile == null

    val scroll = android.widget.ScrollView(ctx)
    val layout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (16*dp).toInt())
    }
    scroll.addView(layout)

    fun label(text: String) = TextView(ctx).apply {
        this.text = text; textSize = 12f; alpha = 0.75f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8*dp).toInt(); bottomMargin = (2*dp).toInt() }
    }
    fun styledEdit(hint: String, value: String, numeric: Boolean = false, wide: Boolean = true) = android.widget.EditText(ctx).apply {
        setText(value); this.hint = hint
        if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        gravity = if (numeric) android.view.Gravity.CENTER else android.view.Gravity.START
        setTextColor(0xFFE0E0E0.toInt()); setHintTextColor(0xFF666666.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 8*dp; setColor(0xFF1E1E1E.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            if (wide) LinearLayout.LayoutParams.MATCH_PARENT else (64*dp).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8*dp).toInt() }
    }

    layout.addView(label("Profil Adı"))
    val nameEdit = styledEdit("örn: Teknoloji, Finans, Sabah Haberleri", profile?.name ?: "")
    layout.addView(nameEdit)

    layout.addView(label("Saat  :  Dakika"))
    val timeRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8*dp).toInt() }
    }
    val hourEdit   = styledEdit("8",  (profile?.hour   ?: 8).toString(),   numeric = true, wide = false)
    val colonLabel = TextView(ctx).apply { text = " : "; textSize = 18f; setTextColor(0xFFE0E0E0.toInt()) }
    val minuteEdit = styledEdit("00", "%02d".format(profile?.minute ?: 0), numeric = true, wide = false)
    timeRow.addView(hourEdit); timeRow.addView(colonLabel); timeRow.addView(minuteEdit)
    layout.addView(timeRow)
    layout.addView(TextView(ctx).apply {
        text = "Test için birkaç dakika ilerisi: alarm tam saatinde tetiklenir."; textSize = 11f; alpha = 0.55f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (10*dp).toInt() }
    })

    layout.addView(label("Konular / RSS Kaynakları (her satır ayrı konu)"))
    val topicsEdit = android.widget.EditText(ctx).apply {
        setText(profile?.topics?.joinToString("\n") ?: "")
        hint = "Türkiye haberleri\ndolar kuru\nhttps://feeds.feedburner.com/TechCrunch"
        minLines = 4; maxLines = 8; isSingleLine = false
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setTextColor(0xFFE0E0E0.toInt()); setHintTextColor(0xFF666666.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 8*dp; setColor(0xFF1E1E1E.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    layout.addView(topicsEdit)
    layout.addView(TextView(ctx).apply {
        text = "https:// ile başlayanlar RSS feed, diğerleri web araması olarak işlenir."; textSize = 11f; alpha = 0.55f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12*dp).toInt() }
    })

    layout.addView(label("Özet Prompt'u (isteğe bağlı)"))
    val promptEdit = android.widget.EditText(ctx).apply {
        setText(profile?.summaryPrompt ?: "")
        hint = "Boş bırakırsanız varsayılan prompt kullanılır.\nÖrnek: Aşağıdaki finans haberlerini Türkçe özetle. Her madde: şirket adı ve tek cümle."
        minLines = 3; maxLines = 6; isSingleLine = false
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setTextColor(0xFFE0E0E0.toInt()); setHintTextColor(0xFF666666.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 8*dp; setColor(0xFF1E1E1E.toInt()); setStroke((1*dp).toInt(), 0xFF555577.toInt())
        }
        setPadding((10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (4*dp).toInt() }
    }
    layout.addView(promptEdit)
    layout.addView(TextView(ctx).apply {
        text = "Haber verileri otomatik olarak sonuna eklenir, tekrar yazmanıza gerek yok."; textSize = 11f; alpha = 0.55f
    })

    val builder = android.app.AlertDialog.Builder(ctx)
        .setTitle(if (isNew) "Yeni Rapor Profili" else "Profili Düzenle")
        .setView(scroll)
        .setPositiveButton("Kaydet") { _, _ ->
            val name   = nameEdit.text.toString().trim().ifEmpty { "Rapor" }
            val hour   = hourEdit.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 8
            val minute = minuteEdit.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
            val topics = topicsEdit.text.toString()
                .split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
            val summaryPrompt = promptEdit.text.toString().trim()
            val saved = if (isNew)
                ReportProfile(name = name, hour = hour, minute = minute, topics = topics, summaryPrompt = summaryPrompt)
            else
                profile!!.copy(name = name, hour = hour, minute = minute, topics = topics, summaryPrompt = summaryPrompt)
            val existingIdx = reportProfiles.indexOfFirst { it.id == saved.id }
            if (existingIdx >= 0) reportProfiles[existingIdx] = saved else reportProfiles.add(saved)
            ReportProfile.saveAll(this, reportProfiles)
            if (saved.enabled) DailyReportScheduler.schedule(this, saved)
            onSaved()
            Toast.makeText(this, "'$name' kaydedildi — $hour:%02d".format(minute), Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("İptal", null)

    if (!isNew && profile != null) {
        builder.setNeutralButton("🗑 Sil") { _, _ ->
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Profili Sil")
                .setMessage("'${profile.name}' silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    DailyReportScheduler.cancel(this, profile)
                    reportProfiles.removeIf { it.id == profile.id }
                    ReportProfile.saveAll(this, reportProfiles)
                    onSaved()
                }
                .setNegativeButton("İptal", null).show()
        }
    }

    builder.show()
}
