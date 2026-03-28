package tr.maya

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tr.maya.data.DbMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Arama sonucu veri sınıfı ──────────────────────────────────────────────────

data class SearchResult(
    val message: DbMessage,
    val conversationTitle: String,
    val query: String            // highlight için
)

// ── Arama sonuçları adapter ───────────────────────────────────────────────────

class SearchResultAdapter(
    private val onResultClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private val items = mutableListOf<SearchResult>()
    var globalScope: Boolean = false   // true = tüm sohbetler, false = aktif sohbet

    fun submitList(list: List<SearchResult>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val convRow: LinearLayout   = view.findViewById(R.id.search_result_conv_row)
        val convTitle: TextView     = view.findViewById(R.id.search_result_conv_title)
        val timeText: TextView      = view.findViewById(R.id.search_result_time)
        val snippet: TextView       = view.findViewById(R.id.search_result_snippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val result = items[position]
        val msg    = result.message

        // Sohbet başlığı satırı — sadece global aramada göster
        if (globalScope) {
            holder.convRow.visibility = View.VISIBLE
            holder.convTitle.text     = result.conversationTitle
            holder.timeText.text      = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                .format(Date(msg.timestamp))
        } else {
            holder.convRow.visibility = View.GONE
        }

        // Mesaj snippet — eşleşen kelimeyi highlight'la
        holder.snippet.text = buildHighlightedSnippet(
            fullText = msg.content,
            query    = result.query,
            context  = holder.itemView.context
        )

        holder.itemView.setOnClickListener { onResultClick(result) }
    }

    override fun getItemCount() = items.size

    // ── Highlight + snippet yardımcısı ────────────────────────────────────────

    private fun buildHighlightedSnippet(
        fullText: String,
        query: String,
        context: Context
    ): SpannableString {
        // <think> bloğunu temizle
        val cleaned = fullText
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .trim()

        // Eşleşen konumu bul ve etrafından ~120 karakter al
        val lowerText  = cleaned.lowercase(Locale("tr"))
        val lowerQuery = query.lowercase(Locale("tr"))
        val matchIdx   = lowerText.indexOf(lowerQuery)

        val snippet = if (matchIdx >= 0) {
            val start  = (matchIdx - 60).coerceAtLeast(0)
            val end    = (matchIdx + query.length + 100).coerceAtMost(cleaned.length)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < cleaned.length) "…" else ""
            "$prefix${cleaned.substring(start, end)}$suffix"
        } else {
            cleaned.take(180)
        }

        val spannable = SpannableString(snippet)

        // Highlight — tüm eşleşmeleri renklendir
        var searchFrom = 0
        val lowerSnippet = snippet.lowercase(Locale("tr"))
        while (true) {
            val idx = lowerSnippet.indexOf(lowerQuery, searchFrom)
            if (idx < 0) break
            val highlightColor = ContextCompat.getColor(context, R.color.search_highlight)
            spannable.setSpan(
                BackgroundColorSpan(highlightColor),
                idx, idx + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                idx, idx + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            searchFrom = idx + query.length
        }
        return spannable
    }
}

// ── MainActivity extension: arama özelliği ────────────────────────────────────

internal fun MainActivity.setupSearchMenuItem() {
    // Toolbar'daki büyüteç menü öğesi onOptionsItemSelected'dan showSearchOverlay'i çağırır.
    // Buraya ek kurulum gerekmez; menü item'ı main_menu.xml'e ekleniyor.
}

/**
 * Arama overlay'ini göster.
 * Tam ekran bir dialog olarak açılır; kendi toolbar'ı vardır.
 */
internal fun MainActivity.showSearchOverlay() {
    val dp = resources.displayMetrics.density

    // Overlay view'ını inflate et
    val overlayView = layoutInflater.inflate(R.layout.activity_search, null)

    val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
        .setView(overlayView)
        .create()
    dialog.window?.apply {
        setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
        setWindowAnimations(android.R.style.Animation_Dialog)
    }

    // View referansları
    val btnBack         = overlayView.findViewById<ImageButton>(R.id.btn_search_back)
    val searchInput     = overlayView.findViewById<EditText>(R.id.search_input)
    val btnScope        = overlayView.findViewById<TextView>(R.id.btn_search_scope)
    val resultCountTv   = overlayView.findViewById<TextView>(R.id.search_result_count)
    val resultsRv       = overlayView.findViewById<RecyclerView>(R.id.search_results_rv)
    val emptyTv         = overlayView.findViewById<TextView>(R.id.search_empty)

    // Adapter
    val adapter = SearchResultAdapter { result ->
        dialog.dismiss()
        navigateToSearchResult(result)
    }
    resultsRv.layoutManager = LinearLayoutManager(this)
    resultsRv.adapter = adapter

    // Scope state: false = bu sohbet, true = tüm sohbetler
    var isGlobalScope = true

    fun updateScopeButton() {
        btnScope.text = if (isGlobalScope) "Tüm sohbetler" else "Bu sohbet"
        adapter.globalScope = isGlobalScope
    }
    updateScopeButton()

    // Arama debounce job'ı
    var searchJob: Job? = null

    fun performSearch(query: String) {
        if (query.length < 2) {
            resultsRv.visibility   = View.GONE
            emptyTv.visibility     = View.GONE
            resultCountTv.visibility = View.GONE
            adapter.submitList(emptyList())
            return
        }

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(300)   // debounce

            val results = withContext(Dispatchers.IO) {
                if (isGlobalScope) {
                    searchAllConversations(query)
                } else {
                    searchCurrentConversation(query)
                }
            }

            // UI güncelle
            if (results.isEmpty()) {
                resultsRv.visibility     = View.GONE
                emptyTv.visibility       = View.VISIBLE
                resultCountTv.visibility = View.GONE
            } else {
                resultsRv.visibility     = View.VISIBLE
                emptyTv.visibility       = View.GONE
                resultCountTv.visibility = View.VISIBLE
                resultCountTv.text       = "${results.size} sonuç"
                adapter.submitList(results)
            }
        }
    }

    // Listener'lar
    btnBack.setOnClickListener { dialog.dismiss() }

    btnScope.setOnClickListener {
        isGlobalScope = !isGlobalScope
        updateScopeButton()
        performSearch(searchInput.text.toString().trim())
    }

    searchInput.addTextChangedListener { editable ->
        performSearch(editable.toString().trim())
    }

    searchInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
            performSearch(searchInput.text.toString().trim())
            true
        } else false
    }

    dialog.show()

    // Klavyeyi otomatik aç
    searchInput.requestFocus()
    searchInput.postDelayed({
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }, 200)
}

// ── Arama sorguları ───────────────────────────────────────────────────────────

private suspend fun MainActivity.searchCurrentConversation(query: String): List<SearchResult> {
    val messages = db.chatDao().searchInConversation(currentConversationId, query)
    val convTitle = db.chatDao().getConversationTitle(currentConversationId) ?: "Sohbet"
    return messages.map { msg ->
        SearchResult(message = msg, conversationTitle = convTitle, query = query)
    }
}

private suspend fun MainActivity.searchAllConversations(query: String): List<SearchResult> {
    val messages = db.chatDao().searchAllConversations(query)
    if (messages.isEmpty()) return emptyList()

    // Sohbet başlıklarını grupla (tek tek sorgu atmamak için cache)
    val titleCache = mutableMapOf<String, String>()
    return messages.map { msg ->
        val title = titleCache.getOrPut(msg.conversationId) {
            db.chatDao().getConversationTitle(msg.conversationId) ?: "Sohbet"
        }
        SearchResult(message = msg, conversationTitle = title, query = query)
    }
}

// ── Sonuca gitme ──────────────────────────────────────────────────────────────

private fun MainActivity.navigateToSearchResult(result: SearchResult) {
    val targetConvId = result.message.conversationId

    lifecycleScope.launch {
        // Farklı sohbetteyse önce oraya geç
        if (targetConvId != currentConversationId) {
            switchConversation(targetConvId)
            // Mesajların yüklenmesini bekle
            kotlinx.coroutines.delay(300)
        }

        // Mesajı listede bul ve scroll et
        val targetTimestamp = result.message.timestamp
        val idx = currentMessages.indexOfFirst { it.timestamp == targetTimestamp }

        if (idx >= 0) {
            messagesRv.scrollToPosition(idx)
            // Kısa süre sonra item'ı flash'la
            kotlinx.coroutines.delay(200)
            flashMessageAtPosition(idx)
        } else {
            Toast.makeText(this@navigateToSearchResult, "Mesaja gidilemedi", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Belirtilen pozisyondaki mesajı kısa süre highlight yaparak kullanıcının dikkatini çeker.
 */
private fun MainActivity.flashMessageAtPosition(position: Int) {
    val layoutManager = messagesRv.layoutManager as? LinearLayoutManager ?: return
    val view = layoutManager.findViewByPosition(position) ?: return

    val originalAlpha = view.alpha
    view.animate()
        .alpha(0.3f)
        .setDuration(150)
        .withEndAction {
            view.animate()
                .alpha(originalAlpha)
                .setDuration(150)
                .withEndAction {
                    view.animate().alpha(0.3f).setDuration(150)
                        .withEndAction {
                            view.animate().alpha(originalAlpha).setDuration(200).start()
                        }.start()
                }.start()
        }.start()
}
