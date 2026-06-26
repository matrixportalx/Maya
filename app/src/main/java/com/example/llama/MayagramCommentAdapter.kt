package tr.maya

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v6.5: [onReply] — kullanıcı bir yorumun "↩ Yanıtla" butonuna bastığında çağrılır.
 * Parametre: yanıtlanacak yorum (parentCommentId olarak kullanılacak).
 *
 * Thread/grup görünümü: yorumlar DB'den timestamp sırasıyla gelir ama burada
 * ağaç sırasına (kök yorum + tüm yanıt zinciri art arda) yeniden diziliyor.
 * Böylece "X'e verilen yanıtlar alt alta" görünümü elde edilir.
 */
class MayagramCommentAdapter(
    private val onReply: (MayagramComment) -> Unit = {}
) : RecyclerView.Adapter<MayagramCommentAdapter.VH>() {

    private val items = mutableListOf<MayagramComment>()
    // commentId -> authorName eşlemesi, "kime yanıt veriyor" etiketini göstermek için
    private val authorNameById = mutableMapOf<String, String>()
    // commentId -> derinlik (0 = kök yorum, 1+ = yanıt zinciri derinliği)
    private val depthById = mutableMapOf<String, Int>()

    /** Flat (timestamp sıralı) DB listesini ağaç sırasına çevirir. */
    private fun toThreadOrder(flat: List<MayagramComment>): List<MayagramComment> {
        val byParent = flat.groupBy { it.parentCommentId }
        val roots = (byParent[null] ?: emptyList()).sortedBy { it.timestamp }
        val result = mutableListOf<MayagramComment>()

        fun appendWithReplies(comment: MayagramComment, depth: Int) {
            result.add(comment)
            depthById[comment.id] = depth
            val replies = (byParent[comment.id] ?: emptyList()).sortedBy { it.timestamp }
            replies.forEach { appendWithReplies(it, depth + 1) }
        }

        roots.forEach { appendWithReplies(it, 0) }

        // Güvenlik: parent'ı listede bulunamayan (silinmiş/kayıp) yorumlar varsa en sona ekle
        val placed = result.map { it.id }.toSet()
        flat.filter { it.id !in placed }.forEach {
            result.add(it)
            depthById[it.id] = 0
        }

        return result
    }

    fun submitList(list: List<MayagramComment>) {
        depthById.clear()
        val ordered = toThreadOrder(list)
        items.clear()
        items.addAll(ordered)
        authorNameById.clear()
        ordered.forEach { authorNameById[it.id] = it.authorName }
        notifyDataSetChanged()
    }

    fun addComment(comment: MayagramComment) {
        // Yeni yorumu mevcut listeye ekleyip tüm listeyi yeniden ağaç sırasına diz.
        // Tek bir yorum eklerken sıralama az sayıda yorumda hızlıdır, performans sorunu olmaz.
        val newFlat = items + comment
        authorNameById[comment.id] = comment.authorName
        submitList(newFlat)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mayagram_comment, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density

        holder.tvName.text = c.authorName
        holder.tvContent.text = c.content
        holder.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(c.timestamp))

        // v6.5: Reply girintisi — derinliğe göre kademeli kaydır (her seviye 20dp)
        val depth = depthById[c.id] ?: 0
        holder.replyIndentSpace.layoutParams = holder.replyIndentSpace.layoutParams.apply {
            width = (depth * 20 * dp).toInt()
        }

        // v6.5: "↩ @kime yanıt veriliyor" etiketi
        val parentAuthorName = c.parentCommentId?.let { authorNameById[it] }
        if (parentAuthorName != null) {
            holder.tvReplyTo.visibility = View.VISIBLE
            holder.tvReplyTo.text = "↩ @$parentAuthorName'e yanıt"
        } else {
            holder.tvReplyTo.visibility = View.GONE
        }

        holder.btnReply.setOnClickListener { onReply(c) }

        if (c.authorAvatarUri == "drawable:maya_default_avatar") {
            holder.ivAvatar.setImageResource(R.drawable.maya_default_avatar)
            holder.ivAvatar.visibility = View.VISIBLE
            holder.tvEmoji.visibility = View.GONE
        } else if (c.authorAvatarUri != null) {
            val sizePx = (32 * dp).toInt()
            holder.ivAvatar.visibility = View.VISIBLE
            holder.tvEmoji.visibility = View.GONE
            Thread {
                try {
                    val input = ctx.contentResolver.openInputStream(Uri.parse(c.authorAvatarUri)) ?: return@Thread
                    val raw = BitmapFactory.decodeStream(input); input.close()
                    raw ?: return@Thread
                    val size = raw.width.coerceAtMost(raw.height)
                    val cropped = Bitmap.createBitmap(raw, (raw.width-size)/2, (raw.height-size)/2, size, size)
                    val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)
                    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(output); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    canvas.drawOval(RectF(0f,0f,sizePx.toFloat(),sizePx.toFloat()), paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(scaled, 0f, 0f, paint)
                    (ctx as? android.app.Activity)?.runOnUiThread { holder.ivAvatar.setImageBitmap(output) }
                } catch (_: Exception) {}
            }.start()
        } else {
            holder.ivAvatar.visibility = View.GONE
            holder.tvEmoji.visibility = View.VISIBLE
            holder.tvEmoji.text = c.authorEmoji
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val replyIndentSpace: View = v.findViewById(R.id.mgc_reply_indent_space)
        val ivAvatar: ImageView = v.findViewById(R.id.mgc_avatar_image)
        val tvEmoji: TextView   = v.findViewById(R.id.mgc_avatar_emoji)
        val tvName: TextView    = v.findViewById(R.id.mgc_author_name)
        val tvReplyTo: TextView = v.findViewById(R.id.mgc_reply_to)
        val tvContent: TextView = v.findViewById(R.id.mgc_content)
        val tvTime: TextView    = v.findViewById(R.id.mgc_time)
        val btnReply: TextView  = v.findViewById(R.id.mgc_btn_reply)
    }
}
