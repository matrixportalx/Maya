package tr.maya

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sohbet mesajı veri sınıfı.
 *
 * [content]           → Kullanıcıya gösterilen kısa/orijinal metin
 * [annotatedContent]  → Web araması sonucu oluşturulan uzun bağlamlı metin;
 *                        sadece modele gönderilir, UI'da gösterilmez.
 *                        null ise buildFormattedPrompt normal [content] kullanır.
 * [imagePath]         → Multimodal görselin dosya yolu (null = görsel yok)
 */
data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val tokensPerSecond: Float? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    /**
     * Web araması yapıldığında oluşturulan prompt — sadece model bağlamında kullanılır.
     * UI'da her zaman [content] (kısa orijinal) gösterilir; [annotatedContent] gizlenir.
     * Bypass Context Length re-encode'u da bu alanı kullanır → sohbet tutarlı kalır.
     */
    val annotatedContent: String? = null
)

class MessageAdapter(
    private val onCopy: (String) -> Unit,
    private val onEdit: (Int, String) -> Unit,
    private val onRegenerate: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private var markwon: Markwon? = null

    // Hangi pozisyonlardaki thinking bloğu açık — notifyItemChanged sonrası da korunur
    private val expandedPositions = mutableSetOf<Int>()

    // Karakter/kullanıcı görüntüleme bilgileri — MainActivity tarafından set edilir
    var charName: String  = "Asistan"
    var charEmoji: String = "🤖"
    var userName: String  = "Kullanıcı"
    var userEmoji: String = "👤"

    companion object {
        private const val VIEW_TYPE_USER      = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        // Kaba token tahmini: Türkçe/İngilizce karışık metinler için ~4 karakter/token
        fun estimateTokens(text: String): Int = maxOf(1, text.length / 4)

        // Emoji avatar için yuvarlak arka plan
        private fun makeAvatarBackground(color: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        /**
         * Temadan renk attribute'u çöz.
         * colorAttr: örn. android.R.attr.colorBackground
         */
        fun resolveColor(context: Context, colorAttr: Int): Int {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(colorAttr, typedValue, true)
            return typedValue.data
        }

        /**
         * Mevcut tema karanlık mı kontrol et.
         * Configuration.uiMode & UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
         */
        fun isDarkTheme(context: Context): Boolean {
            val uiMode = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    // --- Thinking block ayrıştırma ---

    private data class ParsedMessage(
        val thinkContent: String?,
        val visibleContent: String
    )

    /**
     * Hem Gemma 3 / Qwen3 (<think>...</think>) hem de
     * Gemma 4 (<|channel>thought\n...<channel|>) formatlarını destekler.
     *
     * Gemma 4 formatı (tamamlanmış):
     *   <|channel>thought\n[İç muhakeme]<channel|>[Nihai yanıt]
     *
     * Gemma 4 formatı (akış sırasında — henüz kapanmamış):
     *   <|channel>thought\n[İç muhakeme devam ediyor...
     *
     * Qwen3 / Gemma 3 formatı (tamamlanmış):
     *   <think>[İç muhakeme]</think>[Nihai yanıt]
     *
     * Qwen3 / Gemma 3 formatı (akış sırasında):
     *   <think>[İç muhakeme devam ediyor...
     */
    private fun parseThinking(raw: String): ParsedMessage {

        // ── 1. Gemma 4: <|channel>thought\n...<channel|> ─────────────────────
        // Tamamlanmış blok
        val gemma4CompleteRegex = Regex(
            """<\|channel>thought\n(.*?)<channel\|>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val gemma4Match = gemma4CompleteRegex.find(raw)
        if (gemma4Match != null) {
            val thinkContent = gemma4Match.groupValues[1].trim()
            val visible = raw.removeRange(gemma4Match.range).trim()
            return ParsedMessage(thinkContent.ifEmpty { null }, visible)
        }

        // Açık (akış sırasında kapanmamış) Gemma 4 bloğu
        val gemma4OpenIdx = raw.indexOf("<|channel>thought\n")
        if (gemma4OpenIdx != -1) {
            val afterMarker = gemma4OpenIdx + "<|channel>thought\n".length
            val thinkContent = raw.substring(afterMarker).trim()
            val visible = raw.substring(0, gemma4OpenIdx).trim()
            return ParsedMessage("$thinkContent▌", visible)
        }

        // Gemma 4 alternatif açılış (thought\n olmadan — sadece <|channel>)
        val gemma4AltCompleteRegex = Regex(
            """<\|channel>(.*?)<channel\|>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val gemma4AltMatch = gemma4AltCompleteRegex.find(raw)
        if (gemma4AltMatch != null) {
            val inner = gemma4AltMatch.groupValues[1]
            // "thought\n" prefix'i varsa çıkar
            val thinkContent = inner.removePrefix("thought\n").trim()
            val visible = raw.removeRange(gemma4AltMatch.range).trim()
            return ParsedMessage(thinkContent.ifEmpty { null }, visible)
        }

        val gemma4AltOpenIdx = raw.indexOf("<|channel>")
        if (gemma4AltOpenIdx != -1) {
            val afterMarker = gemma4AltOpenIdx + "<|channel>".length
            val inner = raw.substring(afterMarker)
            val thinkContent = inner.removePrefix("thought\n").trim()
            val visible = raw.substring(0, gemma4AltOpenIdx).trim()
            return ParsedMessage("$thinkContent▌", visible)
        }

        // ── 2. Gemma 3 / Qwen3: <think>...</think> ───────────────────────────
        val completeRegex = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)
        val completeMatch = completeRegex.find(raw)
        if (completeMatch != null) {
            val thinkContent = completeMatch.groupValues[1].trim()
            val visible = raw.removeRange(completeMatch.range).trim()
            return ParsedMessage(thinkContent.ifEmpty { null }, visible)
        }

        // Açık (akış sırasında kapanmamış) <think> bloğu
        val openIdx = raw.indexOf("<think>")
        if (openIdx != -1) {
            val thinkContent = raw.substring(openIdx + 7).trim()
            val visible = raw.substring(0, openIdx).trim()
            return ParsedMessage("$thinkContent▌", visible)
        }

        // ── 3. Thinking bloğu yok ─────────────────────────────────────────────
        return ParsedMessage(null, raw)
    }

    // --- Kod bloğu kopyalama ---
    private fun addCodeCopySpans(textView: TextView, markdown: String) {
        val spannable = textView.text as? Spannable ?: return
        val renderedText = spannable.toString()

        val fencedPattern = Regex("```(?:[^\\n]*)\\n([\\s\\S]*?)```")
        var searchFrom = 0

        for (match in fencedPattern.findAll(markdown)) {
            val code = match.groupValues[1].trimEnd('\n')
            if (code.isBlank()) continue

            val idx = renderedText.indexOf(code, searchFrom)
            if (idx < 0) continue

            val end = idx + code.length
            searchFrom = end
            val codeToCopy = code

            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val ctx = widget.context
                    val clip = ClipData.newPlainText("kod", codeToCopy)
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(clip)
                    Toast.makeText(ctx, "Kod kopyalandı ✓", Toast.LENGTH_SHORT).show()
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                }
            }, idx, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // --- Tema uyumlu Markwon oluşturucu ---
    private fun buildMarkwon(context: Context): Markwon {
        val isDark = isDarkTheme(context)
        val linkColor = ContextCompat.getColor(
            context,
            if (isDark) R.color.link_color_dark else R.color.link_color_light
        )
        return Markwon.builder(context)
            .usePlugin(io.noties.markwon.core.CorePlugin.create())
            .usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(linkColor)
                }
            })
            .build()
    }

    // --- Adapter ---

    fun submitList(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        expandedPositions.clear()
        notifyDataSetChanged()
    }

    var isStreaming: Boolean = false
    var markdownThisUpdate: Boolean = false

    fun updateLastAssistantMessage(text: String, tps: Float? = null): Int {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            val existing = messages[messages.size - 1]
            messages[messages.size - 1] = ChatMessage(
                content = text, isUser = false, tokensPerSecond = tps,
                timestamp = existing.timestamp
            )
            notifyItemChanged(messages.size - 1)
        } else {
            messages.add(ChatMessage(content = text, isUser = false, tokensPerSecond = tps))
            notifyItemInserted(messages.size - 1)
        }
        return messages.size - 1
    }

    override fun getItemViewType(position: Int) =
        if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        // Markwon'u tema uyumlu şekilde oluştur — ilk ViewHolder oluşturulduğunda
        if (markwon == null) markwon = buildMarkwon(parent.context)
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER)
            UserViewHolder(inflater.inflate(R.layout.item_message_user, parent, false))
        else
            AssistantViewHolder(inflater.inflate(R.layout.item_message_assistant, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val timeStr = TIME_FMT.format(Date(message.timestamp))
        val context = holder.itemView.context
        val isDark = isDarkTheme(context)

        if (holder is UserViewHolder) {
            // Avatar — tema uyumlu renk
            holder.msgAvatar.text = userEmoji
            val avatarUserColor = ContextCompat.getColor(
                context,
                if (isDark) R.color.light_avatar_user else R.color.light_avatar_user
            )
            holder.msgAvatar.background = makeAvatarBackground(avatarUserColor)

            // İsim rengi — tema uyumlu
            holder.msgSenderName.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isDark) R.color.dark_sender_user else R.color.light_sender_user
                )
            )
            holder.msgSenderName.text = userName
            holder.msgTimestamp.text = timeStr
            holder.msgIndex.text = "#$position"

            // İçerik
            val displayText = if (message.annotatedContent != null)
                "🌐 ${message.content}"
            else
                message.content
            holder.msgContent.text = displayText

            // Butonlar
            holder.itemView.findViewById<Button>(R.id.btn_copy).setOnClickListener {
                onCopy(message.content)
            }
            holder.itemView.findViewById<Button>(R.id.btn_edit).setOnClickListener {
                onEdit(position, message.content)
            }

        } else if (holder is AssistantViewHolder) {
            val parsed = parseThinking(message.content)

            // Avatar — tema uyumlu renk
            holder.msgAvatar.text = charEmoji
            val avatarAssistantColor = ContextCompat.getColor(
                context,
                if (isDark) R.color.light_avatar_assistant else R.color.light_avatar_assistant
            )
            holder.msgAvatar.background = makeAvatarBackground(avatarAssistantColor)

            // İsim rengi — tema uyumlu
            holder.msgSenderName.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isDark) R.color.dark_sender_assistant else R.color.light_sender_assistant
                )
            )
            holder.msgSenderName.text = charName
            holder.msgTimestamp.text = timeStr
            holder.msgIndex.text = "#$position"

            // --- Thinking card ---
            if (parsed.thinkContent != null) {
                holder.thinkingSection.visibility = View.VISIBLE
                holder.thinkingContent.text = parsed.thinkContent

                val isExpanded = expandedPositions.contains(position)
                holder.thinkingContent.visibility = if (isExpanded) View.VISIBLE else View.GONE
                holder.thinkingChevron.text = if (isExpanded) "▴" else "▾"

                holder.thinkingHeader.setOnClickListener {
                    val nowExpanded = expandedPositions.contains(position)
                    if (nowExpanded) {
                        expandedPositions.remove(position)
                        holder.thinkingContent.visibility = View.GONE
                        holder.thinkingChevron.text = "▾"
                    } else {
                        expandedPositions.add(position)
                        holder.thinkingContent.visibility = View.VISIBLE
                        holder.thinkingChevron.text = "▴"
                    }
                }
            } else {
                holder.thinkingSection.visibility = View.GONE
            }

            // --- Asıl mesaj ---
            val textView = holder.itemView.findViewById<TextView>(R.id.msg_content)
            val displayText2 = parsed.visibleContent.ifEmpty {
                if (parsed.thinkContent != null) "" else "…"
            }

            // Tema değiştiğinde Markwon'u yeniden oluştur (link rengi için)
            val currentMarkwon = markwon ?: buildMarkwon(context).also { markwon = it }

            // 1) Markdown render
            currentMarkwon.setMarkdown(textView, displayText2)

            // 2) Metin seçilebilir
            textView.setTextIsSelectable(true)

            // 3) Kod bloklarına kopyalama span'ları ekle
            addCodeCopySpans(textView, displayText2)

            // 4) LinkMovementMethod
            textView.movementMethod = LinkMovementMethod.getInstance()

            // --- t/s + token uzunluğu göstergesi ---
            val tps = message.tokensPerSecond
            val tokenEst = estimateTokens(parsed.visibleContent)
            val visibleLen = parsed.visibleContent.length

            if (tps != null && tps > 0f) {
                holder.txtTps.visibility = View.VISIBLE
                holder.txtTps.text = "%.2f t/s  •  ~%d token".format(tps, tokenEst)
            } else if (visibleLen > 0) {
                holder.txtTps.visibility = View.VISIBLE
                holder.txtTps.text = "~$tokenEst token"
            } else {
                holder.txtTps.visibility = View.GONE
            }

            holder.itemView.findViewById<Button>(R.id.btn_copy).setOnClickListener {
                onCopy(parsed.visibleContent.ifEmpty { message.content })
            }
            holder.itemView.findViewById<Button>(R.id.btn_regenerate).setOnClickListener {
                onRegenerate(position)
            }
        }
    }

    override fun getItemCount() = messages.size

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val msgAvatar: TextView      = view.findViewById(R.id.msg_avatar)
        val msgSenderName: TextView  = view.findViewById(R.id.msg_sender_name)
        val msgTimestamp: TextView   = view.findViewById(R.id.msg_timestamp)
        val msgIndex: TextView       = view.findViewById(R.id.msg_index)
        val msgContent: TextView     = view.findViewById(R.id.msg_content)
    }

    class AssistantViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val msgAvatar: TextView      = view.findViewById(R.id.msg_avatar)
        val msgSenderName: TextView  = view.findViewById(R.id.msg_sender_name)
        val msgTimestamp: TextView   = view.findViewById(R.id.msg_timestamp)
        val msgIndex: TextView       = view.findViewById(R.id.msg_index)
        val thinkingSection: LinearLayout = view.findViewById(R.id.thinking_section)
        val thinkingHeader: LinearLayout  = view.findViewById(R.id.thinking_header)
        val thinkingContent: TextView     = view.findViewById(R.id.thinking_content)
        val thinkingChevron: TextView     = view.findViewById(R.id.thinking_chevron)
        val txtTps: TextView              = view.findViewById(R.id.txt_tps)
    }
}
