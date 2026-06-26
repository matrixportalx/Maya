package tr.maya

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Spannable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.LruCache
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
import io.noties.markwon.ext.tables.TablePlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sohbet mesajı veri sınıfı.
 */
data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val tokensPerSecond: Float? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val annotatedContent: String? = null
)

class MessageAdapter(
    private val onCopy: (String) -> Unit,
    private val onEdit: (Int, String) -> Unit,
    private val onRegenerate: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private var markwon: Markwon? = null

    private val expandedPositions = mutableSetOf<Int>()

    // Karakter/kullanıcı görüntüleme bilgileri
    var charName: String  = "Asistan"
    var charEmoji: String = "🤖"
    var charAvatarUri: String? = null   // v6.0
    var userName: String  = "Kullanıcı"
    var userEmoji: String = "👤"
    var userAvatarUri: String? = null   // v6.0

    // ── Bitmap önbelleği ──────────────────────────────────────────────────────
    private val bitmapCache: LruCache<String, Bitmap> by lazy {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        LruCache(maxMemory / 8)
    }

    companion object {
        private const val VIEW_TYPE_USER      = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun estimateTokens(text: String): Int = maxOf(1, text.length / 4)

        private fun makeAvatarBackground(color: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        fun resolveColor(context: Context, colorAttr: Int): Int {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(colorAttr, typedValue, true)
            return typedValue.data
        }

        fun isDarkTheme(context: Context): Boolean {
            val uiMode = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        /**
         * URI'den yuvarlak Bitmap yükler (main thread'de çağrılmamalı; önbellekle kullanın).
         */
        fun loadRoundedBitmapFromUri(context: Context, uri: Uri, sizePx: Int): Bitmap? {
            return try {
                val input = context.contentResolver.openInputStream(uri) ?: return null
                val raw = BitmapFactory.decodeStream(input)
                input.close()
                raw ?: return null

                val size = raw.width.coerceAtMost(raw.height)
                val x = (raw.width - size) / 2
                val y = (raw.height - size) / 2
                val cropped = Bitmap.createBitmap(raw, x, y, size, size)
                val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)

                val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.drawOval(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(scaled, 0f, 0f, paint)
                output
            } catch (_: Exception) { null }
        }
    }

    // ── Avatar yükleme yardımcısı ─────────────────────────────────────────────

    /**
     * Verilen ImageView'a avatar URI veya emoji gösterir.
     * Bitmap önbellekten gelir; ilk yüklemede arka planda çekilir.
     */
    private fun bindAvatar(
        context: Context,
        imageView: android.widget.ImageView,
        textView: TextView,
        avatarUri: String?,
        emoji: String,
        fallbackColor: Int,
        sizePx: Int
    ) {
        if (avatarUri == "drawable:maya_default_avatar") {
            imageView.setImageResource(R.drawable.maya_default_avatar)
            imageView.visibility = View.VISIBLE
            textView.visibility = View.GONE
            return
        }
        if (avatarUri != null) {
            val cached = bitmapCache.get(avatarUri)
            if (cached != null) {
                imageView.setImageBitmap(cached)
                imageView.visibility = View.VISIBLE
                textView.visibility = View.GONE
            } else {
                // Arka planda yükle
                imageView.setImageDrawable(null)
                imageView.visibility = View.GONE
                textView.text = emoji
                textView.visibility = View.VISIBLE

                val uriRef = avatarUri
                Thread {
                    val bmp = loadRoundedBitmapFromUri(context, Uri.parse(uriRef), sizePx)
                    if (bmp != null) {
                        bitmapCache.put(uriRef, bmp)
                        (context as? android.app.Activity)?.runOnUiThread {
                            // Hâlâ aynı URI mi kontrol et (view recycled olabilir)
                            imageView.setImageBitmap(bmp)
                            imageView.visibility = View.VISIBLE
                            textView.visibility = View.GONE
                        }
                    }
                }.start()
            }
        } else {
            imageView.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = emoji
            textView.background = makeAvatarBackground(fallbackColor)
        }
    }

    // --- Thinking block ayrıştırma ---

    private data class ParsedMessage(
        val thinkContent: String?,
        val visibleContent: String
    )

    private fun parseThinking(raw: String): ParsedMessage {

        val g4WithMarkerRegex = Regex(
            """<\|channel>thought\n(.*?)<channel\|>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        g4WithMarkerRegex.find(raw)?.let { m ->
            val think = m.groupValues[1].trim()
            val visible = raw.removeRange(m.range).trim()
            return ParsedMessage(think.ifEmpty { null }, visible)
        }

        val g4NoMarkerRegex = Regex(
            """^thought\n(.*?)<channel\|>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        g4NoMarkerRegex.find(raw)?.let { m ->
            val think = m.groupValues[1].trim()
            val visible = raw.removeRange(m.range).trim()
            return ParsedMessage(think.ifEmpty { null }, visible)
        }

        val g4GenericRegex = Regex(
            """<\|channel>(.*?)<channel\|>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        g4GenericRegex.find(raw)?.let { m ->
            val think = m.groupValues[1].removePrefix("thought\n").trim()
            val visible = raw.removeRange(m.range).trim()
            return ParsedMessage(think.ifEmpty { null }, visible)
        }

        val g4OpenWithMarkerIdx = raw.indexOf("<|channel>thought\n")
        if (g4OpenWithMarkerIdx != -1) {
            val after = g4OpenWithMarkerIdx + "<|channel>thought\n".length
            val think = raw.substring(after).trim()
            val visible = raw.substring(0, g4OpenWithMarkerIdx).trim()
            return ParsedMessage("$think▌", visible)
        }

        if (raw.startsWith("thought\n")) {
            val think = raw.removePrefix("thought\n").trim()
            return ParsedMessage("$think▌", "")
        }

        val g4OpenIdx = raw.indexOf("<|channel>")
        if (g4OpenIdx != -1) {
            val after = g4OpenIdx + "<|channel>".length
            val think = raw.substring(after).removePrefix("thought\n").trim()
            val visible = raw.substring(0, g4OpenIdx).trim()
            return ParsedMessage("$think▌", visible)
        }

        val thinkCompleteRegex = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)
        thinkCompleteRegex.find(raw)?.let { m ->
            val think = m.groupValues[1].trim()
            val visible = raw.removeRange(m.range).trim()
            return ParsedMessage(think.ifEmpty { null }, visible)
        }

        val thinkOpenIdx = raw.indexOf("<think>")
        if (thinkOpenIdx != -1) {
            val think = raw.substring(thinkOpenIdx + 7).trim()
            val visible = raw.substring(0, thinkOpenIdx).trim()
            return ParsedMessage("$think▌", visible)
        }

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

    private fun buildMarkwon(context: Context): Markwon {
        val isDark = isDarkTheme(context)
        val linkColor = ContextCompat.getColor(
            context,
            if (isDark) R.color.link_color_dark else R.color.link_color_light
        )
        return Markwon.builder(context)
            .usePlugin(io.noties.markwon.core.CorePlugin.create())
            .usePlugin(TablePlugin.create(context))
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
        val dp = context.resources.displayMetrics.density
        val avatarSizePx = (44 * dp).toInt()

                if (holder is UserViewHolder) {
            // ── Avatar ────────────────────────────────────────────────────────
            val fallbackUserColor = ContextCompat.getColor(context, R.color.light_avatar_user)
            bindAvatar(
                context = context,
                imageView = holder.msgAvatarImage,
                textView = holder.msgAvatar,
                avatarUri = userAvatarUri,
                emoji = userEmoji,
                        fallbackColor = fallbackUserColor,
                sizePx = avatarSizePx
            )

            holder.msgSenderName.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isDark) R.color.dark_sender_user else R.color.light_sender_user
                )
            )
            holder.msgSenderName.text = userName
            holder.msgTimestamp.text = timeStr
            holder.msgIndex.text = "#$position"

            val displayText = if (message.annotatedContent != null)
                "🌐 ${message.content}"
            else
                message.content
            holder.msgContent.text = displayText

            // ── v4.8: Kullanıcının gönderdiği görsel ───────────────────────────
            val imgPath = message.imagePath
            if (imgPath != null && File(imgPath).exists()) {
                holder.msgUserImage.visibility = View.VISIBLE
                val cached = bitmapCache.get(imgPath)
                if (cached != null) {
                    holder.msgUserImage.setImageBitmap(cached)        } else {
            holder.msgUserImage.setImageDrawable(null)
            Thread {
                val bmp = BitmapFactory.decodeFile(imgPath)
                if (bmp != null) {
                    bitmapCache.put(imgPath, bmp)
                    (context as? android.app.Activity)?.runOnUiThread {
                        holder.msgUserImage.setImageBitmap(bmp)
                    }
                }
            }.start()
        }
        holder.msgUserImage.setOnClickListener {
            showImagePreviewDialog(context, imgPath)
        }
    } else {
        holder.msgUserImage.visibility = View.GONE
        holder.msgUserImage.setImageDrawable(null)
    }

    holder.itemView.findViewById<Button>(R.id.btn_copy).setOnClickListener {
        onCopy(message.content)
    }
    holder.itemView.findViewById<Button>(R.id.btn_edit).setOnClickListener {
        onEdit(position, message.content)
    }

} else if (holder is AssistantViewHolder) {
            val parsed = parseThinking(message.content)

            // ── Avatar ────────────────────────────────────────────────────────
            val fallbackAssistantColor = ContextCompat.getColor(context, R.color.light_avatar_assistant)
            bindAvatar(
                context = context,
                imageView = holder.msgAvatarImage,
                textView = holder.msgAvatar,
                avatarUri = charAvatarUri,
                emoji = charEmoji,
                fallbackColor = fallbackAssistantColor,
                sizePx = avatarSizePx
            )

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

            val currentMarkwon = markwon ?: buildMarkwon(context).also { markwon = it }
            currentMarkwon.setMarkdown(textView, displayText2)
            textView.setTextIsSelectable(true)
            addCodeCopySpans(textView, displayText2)
            textView.movementMethod = LinkMovementMethod.getInstance()

            // --- t/s + token ---
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

    // ── ViewHolder'lar ────────────────────────────────────────────────────────

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val msgAvatarImage: android.widget.ImageView = view.findViewById(R.id.msg_avatar_image)
        val msgAvatar: TextView      = view.findViewById(R.id.msg_avatar)
        val msgSenderName: TextView  = view.findViewById(R.id.msg_sender_name)
        val msgTimestamp: TextView   = view.findViewById(R.id.msg_timestamp)
        val msgIndex: TextView       = view.findViewById(R.id.msg_index)
        val msgContent: TextView     = view.findViewById(R.id.msg_content)
        val msgUserImage: android.widget.ImageView = view.findViewById(R.id.msg_user_image)
    }

    class AssistantViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val msgAvatarImage: android.widget.ImageView = view.findViewById(R.id.msg_avatar_image)
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
