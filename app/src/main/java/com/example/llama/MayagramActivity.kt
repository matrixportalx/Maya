package tr.maya

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tr.maya.data.AppDatabase
import java.io.File

class MayagramActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var feedAdapter: MayagramFeedAdapter
    private lateinit var rvFeed: RecyclerView
    private lateinit var fabNewPost: FloatingActionButton
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        // Tema
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val themeMode = prefs.getInt("app_theme_mode", MainActivity.THEME_SYSTEM)
        MainActivity.applyThemeMode(themeMode)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayagram)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.mayagram_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = "Mayagram"
            subtitle = "Maya karakterlerinin akışı"
            setDisplayHomeAsUpEnabled(true)
        }

        db = AppDatabase.getInstance(this)

        rvFeed           = findViewById(R.id.mayagram_feed_rv)
        fabNewPost       = findViewById(R.id.fab_new_mayagram_post)
        tvEmpty          = findViewById(R.id.mayagram_empty_tv)
        progressBar      = findViewById(R.id.mayagram_progress)
        tvProgressStatus = findViewById(R.id.mayagram_progress_status)

        feedAdapter = MayagramFeedAdapter(
            onLike       = { post -> handleLike(post) },
            onComment    = { post -> showCommentDialog(post) },
            onDelete     = { post -> confirmDeletePost(post) },
            onImageClick = { path -> showFullImage(path) }
        )
        rvFeed.layoutManager = LinearLayoutManager(this)
        rvFeed.adapter = feedAdapter

        fabNewPost.setOnClickListener { showNewPostDialog() }

        observeFeed()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Feed gözlem ───────────────────────────────────────────────────────────

    private fun observeFeed() {
        lifecycleScope.launch {
            db.mayagramDao().getAllPosts().collectLatest { posts ->
                feedAdapter.submitList(posts)
                tvEmpty.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
                rvFeed.visibility  = if (posts.isEmpty()) View.GONE   else View.VISIBLE
            }
        }
    }

    // ── Yeni gönderi diyaloğu ─────────────────────────────────────────────────

    private fun showNewPostDialog() {
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val characters = loadCharacters(prefs.getString("characters_json", null))

        if (characters.isEmpty()) {
            Toast.makeText(this,
                "Önce Maya'da karakter oluşturun (Çekmece → + Ekle)",
                Toast.LENGTH_LONG).show()
            return
        }

        val main = MainActivity.currentInstance
        if (main == null) {
            Toast.makeText(this,
                "⚠️ Model yüklü değil — önce Maya ana ekranından bir model yükleyin",
                Toast.LENGTH_LONG).show()
            return
        }

        val dp = resources.displayMetrics.density

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (16*dp).toInt())
        }
        scroll.addView(layout)

        // Karakter seçimi
        layout.addView(TextView(this).apply {
            text = "Kim paylaşsın?"; textSize = 13f; alpha = 0.7f
        })

        var selectedChar = characters.first()
        val charNames = characters.map { "${it.emoji} ${it.name}" }.toTypedArray()

        val charSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MayagramActivity,
                android.R.layout.simple_spinner_dropdown_item,
                charNames
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt(); bottomMargin = (12*dp).toInt() }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    selectedChar = characters[pos]
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        layout.addView(charSpinner)

        // Konu
        layout.addView(TextView(this).apply {
            text = "Konu / ilham (isteğe bağlı)"; textSize = 13f; alpha = 0.7f
        })
        val topicEdit = EditText(this).apply {
            hint = "örn: yemek, doğa, müzik, günlük düşünceler…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt(); bottomMargin = (12*dp).toInt() }
        }
        layout.addView(topicEdit)

        // Dream API durumu
        val dreamEnabled = prefs.getBoolean("dream_api_enabled", false)
        layout.addView(TextView(this).apply {
            text = if (dreamEnabled)
                "🎨 Dream API açık — görüntü de üretilecek"
            else
                "📝 Dream API kapalı — yalnızca metin gönderi\n(Açmak için: Maya Ayarlar → Dream API)"
            textSize = 11f
            setTextColor(if (dreamEnabled) 0xFF55CC77.toInt() else 0xFF999999.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        AlertDialog.Builder(this)
            .setTitle("✨ Yeni Gönderi")
            .setView(scroll)
            .setPositiveButton("Oluştur") { _, _ ->
                val topic = topicEdit.text.toString().trim().ifEmpty { null }
                startPostGeneration(selectedChar, topic, main)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ── Post üretimi ──────────────────────────────────────────────────────────

    private fun startPostGeneration(character: MayaCharacter, topic: String?, main: MainActivity) {
        if (main.loadedModelPath == null) {
            Toast.makeText(this, "⚠️ Model yüklü değil — önce Maya'dan model yükleyin", Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility      = View.VISIBLE
        tvProgressStatus.visibility = View.VISIBLE
        fabNewPost.isEnabled        = false

        main.generateMayagramPost(
            character  = character,
            topic      = topic,
            onProgress = { status ->
                runOnUiThread { tvProgressStatus.text = status }
            },
            onDone = { post ->
                runOnUiThread {
                    hideProgress()
                    Toast.makeText(this, "✅ ${character.name} paylaştı!", Toast.LENGTH_SHORT).show()
                    scheduleAutoComments(post, main)
                }
            },
            onError = { msg ->
                runOnUiThread {
                    hideProgress()
                    Toast.makeText(this, "❌ $msg", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun hideProgress() {
        progressBar.visibility      = View.GONE
        tvProgressStatus.visibility = View.GONE
        fabNewPost.isEnabled        = true
    }

    /** Gönderi yayınlandıktan sonra diğer karakterlerden otomatik yorum */
    private fun scheduleAutoComments(post: MayagramPost, main: MainActivity) {
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val allChars = loadCharacters(prefs.getString("characters_json", null))
        val commenters = allChars
            .filter { it.id != post.characterId }
            .shuffled()
            .take(2)

        lifecycleScope.launch {
            commenters.forEach { commenter ->
                kotlinx.coroutines.delay(1200)
                main.generateCharacterComment(post, commenter) {
                    MainActivity.log("Mayagram", "${commenter.name} yorum yaptı")
                }
            }
        }
    }

    // ── Like ──────────────────────────────────────────────────────────────────

    private fun handleLike(post: MayagramPost) {
        val newLiked = !post.isLikedByUser
        val newCount = post.likeCount + if (newLiked) 1 else -1
        lifecycleScope.launch(Dispatchers.IO) {
            db.mayagramDao().updateLike(post.id, newCount, newLiked)
        }
    }

    // ── Yorumlar ──────────────────────────────────────────────────────────────

    private fun showCommentDialog(post: MayagramPost) {
        val dp = resources.displayMetrics.density

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val commentAdapter = MayagramCommentAdapter()
        val rvComments = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MayagramActivity)
            adapter = commentAdapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (280 * dp).toInt()
            )
        }
        outer.addView(rvComments)

        // Giriş satırı
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((12*dp).toInt(), (8*dp).toInt(), (8*dp).toInt(), (8*dp).toInt())
        }
        val commentEdit = EditText(this).apply {
            hint = "Yorum yaz…"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendBtn = TextView(this).apply {
            text = "➤"; textSize = 20f
            setPadding((12*dp).toInt(), 0, 0, 0)
        }
        inputRow.addView(commentEdit)
        inputRow.addView(sendBtn)
        outer.addView(inputRow)

        lifecycleScope.launch(Dispatchers.IO) {
            val comments = db.mayagramDao().getComments(post.id)
            withContext(Dispatchers.Main) { commentAdapter.submitList(comments) }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("💬 ${post.characterName}'in gönderisi")
            .setView(outer)
            .setNegativeButton("Kapat", null)
            .create()

        sendBtn.setOnClickListener {
            val text = commentEdit.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener

            val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
            val userName     = prefs.getString("user_name", "Kullanıcı") ?: "Kullanıcı"
            val userAvatar   = prefs.getString("user_avatar_uri", null)

            val comment = MayagramComment(
                postId       = post.id,
                authorId     = "user",
                authorName   = userName,
                authorEmoji  = "👤",
                authorAvatarUri = userAvatar,
                content      = text
            )
            lifecycleScope.launch(Dispatchers.IO) {
                db.mayagramDao().insertComment(comment)
                withContext(Dispatchers.Main) {
                    commentAdapter.addComment(comment)
                    commentEdit.text.clear()
                    rvComments.scrollToPosition(commentAdapter.itemCount - 1)
                }
            }
        }

        dialog.show()
    }

    // ── Gönderi silme ─────────────────────────────────────────────────────────

    private fun confirmDeletePost(post: MayagramPost) {
        AlertDialog.Builder(this)
            .setTitle("Gönderiyi Sil")
            .setMessage("\"${post.characterName}\" gönderisi silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.mayagramDao().deleteCommentsForPost(post.id)
                    db.mayagramDao().deletePost(post.id)
                    post.imagePath?.let { File(it).delete() }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ── Tam ekran görüntü ─────────────────────────────────────────────────────

    private fun showFullImage(imagePath: String) {
        val bmp = BitmapFactory.decodeFile(imagePath) ?: run {
            Toast.makeText(this, "Görüntü yüklenemedi", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_fullscreen_image, null)
        val imageView = view.findViewById<ZoomableImageView>(R.id.fs_image_view)
        val btnShare = view.findViewById<ImageButton>(R.id.btn_fs_share)
        val btnSave  = view.findViewById<ImageButton>(R.id.btn_fs_save)
        val btnClose = view.findViewById<ImageButton>(R.id.btn_fs_close)

        imageView.setImageBitmap(bmp)

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(view)
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )

        btnShare.setOnClickListener { shareImage(imagePath) }
        btnSave.setOnClickListener { saveToGallery(imagePath) }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun shareImage(imagePath: String) {
        try {
            val file = File(imagePath)
            if (!file.exists()) { Toast.makeText(this, "Dosya bulunamadı", Toast.LENGTH_SHORT).show(); return }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Görüntüyü Paylaş"))
        } catch (e: Exception) {
            Toast.makeText(this, "Paylaşılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToGallery(imagePath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val src = File(imagePath)
                if (!src.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MayagramActivity, "Dosya bulunamadı", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "mayagram_${System.currentTimeMillis()}.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mayagram")
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MayagramActivity,
                            "✅ Galeriye kaydedildi (Pictures/Mayagram)",
                            Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MayagramActivity, "Kaydetme başarısız", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MayagramActivity, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Yardımcı ─────────────────────────────────────────────────────────────

    private fun loadCharacters(json: String?): List<MayaCharacter> {
        json ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MayaCharacter(
                    id           = obj.getString("id"),
                    name         = obj.getString("name"),
                    userName     = obj.optString("user_name", "Kullanıcı"),
                    emoji        = obj.optString("emoji", "🤖"),
                    systemPrompt = obj.optString("system_prompt", ""),
                    avatarUri    = obj.optString("avatar_uri", "").ifEmpty { null }
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}
