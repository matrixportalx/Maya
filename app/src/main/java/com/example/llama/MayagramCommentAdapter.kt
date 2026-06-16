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

class MayagramCommentAdapter : RecyclerView.Adapter<MayagramCommentAdapter.VH>() {

    private val items = mutableListOf<MayagramComment>()

    fun submitList(list: List<MayagramComment>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun addComment(comment: MayagramComment) {
        items.add(comment)
        notifyItemInserted(items.size - 1)
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

        holder.tvName.text = "${c.authorEmoji} ${c.authorName}"
        holder.tvContent.text = c.content
        holder.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(c.timestamp))

        if (c.authorAvatarUri != null) {
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
        val ivAvatar: ImageView = v.findViewById(R.id.mgc_avatar_image)
        val tvEmoji: TextView   = v.findViewById(R.id.mgc_avatar_emoji)
        val tvName: TextView    = v.findViewById(R.id.mgc_author_name)
        val tvContent: TextView = v.findViewById(R.id.mgc_content)
        val tvTime: TextView    = v.findViewById(R.id.mgc_time)
    }
}
