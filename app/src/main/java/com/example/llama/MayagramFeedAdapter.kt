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
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MayagramFeedAdapter(
    private val onLike: (MayagramPost) -> Unit,
    private val onComment: (MayagramPost) -> Unit,
    private val onDelete: (MayagramPost) -> Unit,
    private val onImageClick: (String) -> Unit
) : RecyclerView.Adapter<MayagramFeedAdapter.PostVH>() {

    private val posts = mutableListOf<MayagramPost>()
    // v6.10: postId -> o postu beğenen karakterler (kullanıcı beğenisi dahil değil — o likeCount/isLikedByUser'da)
    private val likesByPostId = mutableMapOf<String, List<MayagramPostLike>>()

    private val bitmapCache: LruCache<String, Bitmap> by lazy {
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        LruCache(maxMem / 6)
    }

    /**
     * v6.10: [likes] haritası verilirse her post için "Maya ve 2 kişi beğendi" satırı gösterilir.
     * Geriye dönük uyumluluk için parametre opsiyonel — verilmezse boş kabul edilir.
     */
    fun submitList(newPosts: List<MayagramPost>, likes: Map<String, List<MayagramPostLike>> = emptyMap()) {
        posts.clear()
        posts.addAll(newPosts)
        likesByPostId.clear()
        likesByPostId.putAll(likes)
        notifyDataSetChanged()
    }

    override fun getItemCount() = posts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mayagram_post, parent, false)
        return PostVH(view)
    }

    override fun onBindViewHolder(holder: PostVH, position: Int) {
        val post = posts[position]
        val ctx = holder.itemView.context
        val dp = ctx.resources.displayMetrics.density

        // ── Header: Avatar + İsim + Zaman ─────────────────────────────────────
        val timeStr = formatRelativeTime(post.timestamp)
        holder.tvName.text = post.characterName
        holder.tvTime.text = timeStr
        bindAvatar(ctx, holder.ivAvatar, holder.tvAvatarEmoji,
            post.characterAvatarUri, post.characterEmoji, (40 * dp).toInt())

        // ── Görüntü ───────────────────────────────────────────────────────────
        if (post.imagePath != null && File(post.imagePath).exists()) {
            holder.ivPostImage.visibility = View.VISIBLE
            val cached = bitmapCache.get(post.imagePath)
            if (cached != null) {
                holder.ivPostImage.setImageBitmap(cached)
            } else {
                holder.ivPostImage.setImageDrawable(null)
                Thread {
                    val bmp = BitmapFactory.decodeFile(post.imagePath)
                    if (bmp != null) {
                        bitmapCache.put(post.imagePath, bmp)
                        (ctx as? android.app.Activity)?.runOnUiThread {
                            holder.ivPostImage.setImageBitmap(bmp)
                        }
                    }
                }.start()
            }
            holder.ivPostImage.setOnClickListener { onImageClick(post.imagePath) }
        } else {
            holder.ivPostImage.visibility = View.GONE
        }

        // ── Caption ───────────────────────────────────────────────────────────
        holder.tvCaption.text = post.caption

        // ── v6.10: "Maya ve 2 kişi beğendi" satırı ────────────────────────────
        val charLikes = likesByPostId[post.id].orEmpty()
        holder.tvLikedBy.text = buildLikedByText(charLikes, post.isLikedByUser)
        holder.tvLikedBy.visibility = if (holder.tvLikedBy.text.isNotEmpty()) View.VISIBLE else View.GONE

        // ── Like butonu — toplam sayı: kullanıcı beğenisi + karakter beğenileri ─
        val totalLikes = post.likeCount + charLikes.size
        holder.btnLike.text = if (post.isLikedByUser) "❤️ $totalLikes" else "🤍 $totalLikes"
        holder.btnLike.setOnClickListener { onLike(post) }

        // ── Yorum butonu ──────────────────────────────────────────────────────
        holder.btnComment.setOnClickListener { onComment(post) }

        // ── Sil butonu ────────────────────────────────────────────────────────
        holder.btnDelete.setOnClickListener { onDelete(post) }
    }

    /**
     * v6.10: "Maya ve 2 kişi beğendi" / "Maya, Aria ve 1 kişi beğendi" / "Sen beğendin" gibi
     * Instagram tarzı özet metni üretir. Hem karakter beğenileri hem kullanıcının kendi
     * beğenisi (varsa "Sen" olarak) birleştirilir.
     */
    private fun buildLikedByText(charLikes: List<MayagramPostLike>, likedByUser: Boolean): String {
        val names = mutableListOf<String>()
        if (likedByUser) names.add("Sen")
        names.addAll(charLikes.map { it.characterName })

        if (names.isEmpty()) return ""

        return when (names.size) {
            1 -> "❤️ ${names[0]} beğendi"
            2 -> "❤️ ${names[0]} ve ${names[1]} beğendi"
            else -> "❤️ ${names[0]}, ${names[1]} ve ${names.size - 2} kişi daha beğendi"
        }
    }

    // ── Avatar yardımcısı ─────────────────────────────────────────────────────
    private fun bindAvatar(
        ctx: Context,
        imageView: ImageView,
        textView: TextView,
        avatarUri: String?,
        emoji: String,
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
                imageView.setImageDrawable(null)
                imageView.visibility = View.GONE
                textView.text = emoji
                textView.visibility = View.VISIBLE
                Thread {
                    val bmp = loadRoundedBitmapStatic(ctx, Uri.parse(avatarUri), sizePx)
                    if (bmp != null) {
                        bitmapCache.put(avatarUri, bmp)
                        (ctx as? android.app.Activity)?.runOnUiThread {
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
        }
    }

    private fun loadRoundedBitmapStatic(ctx: Context, uri: Uri, sizePx: Int): Bitmap? {
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            val raw = BitmapFactory.decodeStream(input)
            input.close()
            raw ?: return null
            val size = raw.width.coerceAtMost(raw.height)
            val x = (raw.width - size) / 2; val y = (raw.height - size) / 2
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

    private fun formatRelativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000L              -> "Az önce"
            diff < 3_600_000L           -> "${diff / 60_000} dk önce"
            diff < 86_400_000L          -> "${diff / 3_600_000} saat önce"
            diff < 7 * 86_400_000L      -> "${diff / 86_400_000} gün önce"
            else -> SimpleDateFormat("d MMM", Locale("tr")).format(Date(timestamp))
        }
    }

    inner class PostVH(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView   = view.findViewById(R.id.mg_avatar_image)
        val tvAvatarEmoji: TextView = view.findViewById(R.id.mg_avatar_emoji)
        val tvName: TextView      = view.findViewById(R.id.mg_char_name)
        val tvTime: TextView      = view.findViewById(R.id.mg_time)
        val ivPostImage: ImageView = view.findViewById(R.id.mg_post_image)
        val tvCaption: TextView   = view.findViewById(R.id.mg_caption)
        val tvLikedBy: TextView   = view.findViewById(R.id.mg_liked_by)
        val btnLike: TextView     = view.findViewById(R.id.mg_btn_like)
        val btnComment: TextView  = view.findViewById(R.id.mg_btn_comment)
        val btnDelete: TextView   = view.findViewById(R.id.mg_btn_delete)
    }
}
