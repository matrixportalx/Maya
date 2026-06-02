package tr.maya

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CharacterAdapter(
    private val onSelect: (MayaCharacter) -> Unit,
    private val onLongClick: (MayaCharacter) -> Unit
) : RecyclerView.Adapter<CharacterAdapter.VH>() {

    var activeId: String? = null
    private val items = mutableListOf<MayaCharacter>()

    // Küçük avatar önbelleği (drawer için 32dp boyutu)
    private val thumbCache: LruCache<String, Bitmap> by lazy {
        LruCache(20) // en fazla 20 avatar önbellekte
    }

    fun submitList(list: List<MayaCharacter>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatarImage: ImageView  = v.findViewById(R.id.char_avatar_image)
        val emoji: TextView         = v.findViewById(R.id.char_emoji)
        val name: TextView          = v.findViewById(R.id.char_name)
        val userName: TextView      = v.findViewById(R.id.char_user_name)
        val activeIndicator: TextView = v.findViewById(R.id.char_active_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_character, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val char = items[position]
        holder.name.text = char.name
        holder.userName.text = "👤 ${char.userName}"
        holder.activeIndicator.visibility = if (char.id == activeId) View.VISIBLE else View.GONE

        val context = holder.itemView.context
        val dp = context.resources.displayMetrics.density
        val sizePx = (32 * dp).toInt()

        if (char.avatarUri != null) {
            // Önbellekten dene
            val cached = thumbCache.get(char.avatarUri)
            if (cached != null) {
                holder.avatarImage.setImageBitmap(cached)
                holder.avatarImage.visibility = View.VISIBLE
                holder.emoji.visibility = View.GONE
            } else {
                // Arka planda yükle
                holder.avatarImage.setImageDrawable(null)
                holder.avatarImage.visibility = View.GONE
                holder.emoji.text = char.emoji
                holder.emoji.visibility = View.VISIBLE

                val uriStr = char.avatarUri
                Thread {
                    val bmp = loadRoundedBitmap(context, Uri.parse(uriStr), sizePx)
                    if (bmp != null) {
                        thumbCache.put(uriStr, bmp)
                        (context as? android.app.Activity)?.runOnUiThread {
                            holder.avatarImage.setImageBitmap(bmp)
                            holder.avatarImage.visibility = View.VISIBLE
                            holder.emoji.visibility = View.GONE
                        }
                    }
                }.start()
            }
        } else {
            holder.avatarImage.visibility = View.GONE
            holder.emoji.visibility = View.VISIBLE
            holder.emoji.text = char.emoji
        }

        holder.itemView.setOnClickListener { onSelect(char) }
        holder.itemView.setOnLongClickListener { onLongClick(char); true }
    }

    override fun getItemCount() = items.size

    private fun loadRoundedBitmap(context: android.content.Context, uri: Uri, sizePx: Int): Bitmap? {
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
