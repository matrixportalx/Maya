package tr.maya

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.FileOutputStream
import android.app.Dialog
import android.widget.ImageButton

class MayagramActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var feedAdapter: MayagramFeedAdapter
    private lateinit var rvFeed: RecyclerView
    private lateinit var fabNewPost: FloatingActionButton
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressStatus: TextView

    // v6.5: Kullanıcı gönderisi için galeriden seçilen görüntü URI'si (diyalog açıkken geçici tutulur)
    private var pendingUserPostImageUri: Uri? = null
    private var pendingUserPostImageCallback: ((Uri) -> Unit)? = null

    private val userPostImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingUserPostImageUri = uri
                pendingUserPostImageCallback?.invoke(uri)
            }
        }
    }

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

        fabNewPost.setOnClickListener { showNewPostChoiceDialog() }

        observeFeed()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Feed gözlem ───────────────────────────────────────────────────────────

    /**
     * v6.10: Postlar değiştiğinde (yeni paylaşım, silme vb.) her post için karakter
     * beğenilerini de DB'den çekip adapter'a iletir. Post sayısı genelde küçük olduğundan
     * (onlarca-yüzlerce) her post için ayrı sorgu performans sorunu yaratmaz.
     */
    private fun observeFeed() {
        lifecycleScope.launch {
            db.mayagramDao().getAllPosts().collectLatest { posts ->
                val likesByPostId = withContext(Dispatchers.IO) {
                    posts.associate { post -> post.id to db.mayagramDao().getCharacterLikesForPost(post.id) }
                }
                feedAdapter.submitList(posts, likesByPostId)
                tvEmpty.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
                rvFeed.visibility  = if (posts.isEmpty()) View.GONE   else View.VISIBLE
            }
        }
    }

    // ── v6.5: Yeni gönderi — karakter mi kullanıcı mı seçimi ─────────────────

    private fun showNewPostChoiceDialog() {
        AlertDialog.Builder(this)
            .setTitle("✨ Yeni Gönderi")
            .setItems(arrayOf("🎭 Bir karakter paylaşsın", "👤 Kendim paylaşacağım")) { _, which ->
                when (which) {
                    0 -> showNewCharacterPostDialog()
                    1 -> showNewUserPostDialog()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ── Karakter gönderisi diyaloğu (eski showNewPostDialog) ─────────────────

    private fun showNewCharacterPostDialog() {
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
            .setTitle("🎭 Karakter Gönderisi")
            .setView(scroll)
            .setPositiveButton("Oluştur") { _, _ ->
                val topic = topicEdit.text.toString().trim().ifEmpty { null }
                startCharacterPostGeneration(selectedChar, topic, main)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ── v6.5: Kullanıcı gönderisi diyaloğu ────────────────────────────────────

    private fun showNewUserPostDialog() {
        val dp = resources.displayMetrics.density
        val main = MainActivity.currentInstance

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (16*dp).toInt())
        }
        scroll.addView(layout)

        layout.addView(TextView(this).apply {
            text = "Ne paylaşmak istiyorsun?"; textSize = 13f; alpha = 0.7f
        })
        val captionEdit = EditText(this).apply {
            hint = "Gönderi metnini yaz…"
            minLines = 2; maxLines = 6; isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4*dp).toInt(); bottomMargin = (12*dp).toInt() }
        }
        layout.addView(captionEdit)

        // ── Görüntü seçeneği ──────────────────────────────────────────────────
        layout.addView(TextView(this).apply {
            text = "Görüntü"; textSize = 13f; alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4*dp).toInt() }
        })

        val imageModeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbNoImage  = RadioButton(this).apply { text = "📝 Görüntüsüz paylaş"; id = View.generateViewId(); isChecked = true }
        val rbGallery  = RadioButton(this).apply { text = "📷 Galeriden seç"; id = View.generateViewId() }
        val dreamEnabled = getSharedPreferences("llama_prefs", MODE_PRIVATE).getBoolean("dream_api_enabled", false)
        val rbAi       = RadioButton(this).apply {
            text = if (dreamEnabled) "✨ AI ile oluştur (metninden otomatik)" else "✨ AI ile oluştur (Dream API kapalı)"
            id = View.generateViewId()
            isEnabled = dreamEnabled
        }
        imageModeGroup.addView(rbNoImage); imageModeGroup.addView(rbGallery); imageModeGroup.addView(rbAi)
        layout.addView(imageModeGroup)

        // Galeri önizleme
        val previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (160 * dp).toInt()
            ).apply { topMargin = (8*dp).toInt() }
            visibility = View.GONE
            setBackgroundColor(0xFF222222.toInt())
        }
        layout.addView(previewImage)

        var selectedGalleryUri: Uri? = null

        rbGallery.setOnClickListener {
            pendingUserPostImageCallback = { uri ->
                selectedGalleryUri = uri
                previewImage.visibility = View.VISIBLE
                contentResolver.openInputStream(uri)?.use { input ->
                    previewImage.setImageBitmap(BitmapFactory.decodeStream(input))
                }
            }
            userPostImagePickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
        rbNoImage.setOnClickListener { previewImage.visibility = View.GONE }
        rbAi.setOnClickListener { previewImage.visibility = View.GONE }

        AlertDialog.Builder(this)
            .setTitle("👤 Kendi Gönderin")
            .setView(scroll)
            .setPositiveButton("Paylaş") { _, _ ->
                val caption = captionEdit.text.toString().trim()
                if (caption.isEmpty() && selectedGalleryUri == null) {
                    Toast.makeText(this, "Bir metin yazın veya görüntü seçin", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (rbAi.isChecked && main == null) {
                    Toast.makeText(this, "AI görüntü için önce Maya'dan model yükleyin", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                progressBar.visibility = View.VISIBLE
                tvProgressStatus.visibility = View.VISIBLE
                fabNewPost.isEnabled = false

                // Galeri görüntüsünü kalıcı bir konuma kopyala (uri izinleri kapanabilir)
                lifecycleScope.launch {
                    val copiedGalleryPath = selectedGalleryUri?.let { uri ->
                        withContext(Dispatchers.IO) { copyGalleryImageToMayagramDir(uri) }
                    }

                    main?.generateUserPost(
                        captionText      = caption,
                        galleryImagePath = copiedGalleryPath,
                        useAiImage       = rbAi.isChecked,
                        onProgress       = { status -> runOnUiThread { tvProgressStatus.text = status } },
                        onDone           = { post ->
                            runOnUiThread {
                                hideProgress()
                                Toast.makeText(this@MayagramActivity, "✅ Gönderin paylaşıldı!", Toast.LENGTH_SHORT).show()
                                scheduleAutoComments(post)
                            }
                        },
                        onError          = { msg ->
                            runOnUiThread {
                                hideProgress()
                                Toast.makeText(this@MayagramActivity, "❌ $msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) ?: run {
                        // main == null ama AI istenmedi — direkt kaydet (model gerektirmiyor)
                        if (!rbAi.isChecked) {
                            val fallbackMain = MainActivity.currentInstance
                            if (fallbackMain != null) return@launch
                            hideProgress()
                            Toast.makeText(this@MayagramActivity, "❌ Maya ana ekranı kapalı, lütfen önce açın", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private suspend fun copyGalleryImageToMayagramDir(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(getExternalFilesDir(null), "Mayagram").also { it.mkdirs() }
            val file = File(dir, "userpost_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            } ?: return@withContext null
            file.absolutePath
        } catch (e: Exception) {
            MainActivity.log("Mayagram", "Galeri görüntüsü kopyalanamadı: ${e.message}")
            null
        }
    }

    // ── Post üretimi (karakter) ────────────────────────────────────────────────

    if (main.loadedModelPath == null) {
            if (main.autoLoadMode == "on_action") {
                progressBar.visibility      = View.VISIBLE
                tvProgressStatus.visibility = View.VISIBLE
                tvProgressStatus.text       = "⏳ Model yükleniyor…"
                fabNewPost.isEnabled        = false
                main.triggerAutoLoadModel {
                    startCharacterPostGeneration(character, topic, main)
                }
            } else {
                Toast.makeText(this, "⚠️ Model yüklü değil — önce Maya'dan model yükleyin", Toast.LENGTH_LONG).show()
            }
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
                    scheduleAutoComments(post)
                }
            },
            onError = { msg ->
                runOnUiThread {
                    hideProgress()
                    Toast.makeText(this, "❌ $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hideProgress() {
        progressBar.visibility      = View.GONE
        tvProgressStatus.visibility = View.GONE
        fabNewPost.isEnabled        = true
    }

    /**
     * Gönderi yayınlandıktan sonra diğer karakterlerden otomatik yorum VE beğeni tetikler
     * (post sahibi hariç tüm karakterler arasından).
     *
     * v6.10: Beğeniler yorumlardan bağımsızdır — LLM çağrısı gerektirmediğinden hemen
     * tetiklenir, model meşgul olsa bile çalışır. Yorumlayan karakterlerle beğenen
     * karakterler kesişebilir (biri hem yorum hem beğeni bırakabilir, gerçek sosyal medya
     * davranışına benzer şekilde) — bu kasıtlıdır, ayrı havuzlardan seçilirler.
     */
    private fun scheduleAutoComments(post: MayagramPost) {
        val main = MainActivity.currentInstance ?: return
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val allChars = loadCharacters(prefs.getString("characters_json", null))
        if (allChars.isEmpty()) return

        val otherChars = allChars.filter { it.id != post.characterId }
        if (otherChars.isEmpty()) return

        // ── Beğeniler: yorumdan bağımsız, anında tetiklenir ──────────────────
        main.triggerAutoLikes(post, otherChars)

        // ── Yorumlar: LLM gerektirir, model meşgulse atlanabilir ─────────────
        val commenters = otherChars.shuffled().take(2)

        lifecycleScope.launch {
            commenters.forEach { commenter ->
                kotlinx.coroutines.delay(1200)
                main.generateCharacterComment(post, commenter, parentCommentId = null) {
                    MainActivity.log("Mayagram", "${commenter.name} yorum yaptı")
                }
            }
        }
    }

    // Yorum içindeki @ işaretli karakterleri bulur
    private fun extractMentionedCharacters(text: String, allCharacters: List<MayaCharacter>): List<MayaCharacter> {
        val mentioned = mutableListOf<MayaCharacter>()
        val words = text.split(" ")
        for (word in words) {
            if (word.startsWith("@")) {
                val name = word.removePrefix("@").trim().replace(Regex("[^a-zA-ZğüşıöçĞÜŞİÖÇ]"), "")
                val found = allCharacters.find { it.name.equals(name, ignoreCase = true) }
                if (found != null) mentioned.add(found)
            }
        }
        return mentioned.distinctBy { it.id }
    }

    /**
     * Kullanıcı (veya bir karakter) posta DİREKT yorum yazdığında (parentCommentId == null),
     * post sahibi karakter o yoruma otomatik yanıt verir. Yanıt parentCommentId = yeni
     * yorumun id'si ile kaydedilir — böylece UI'da doğru thread oluşur.
     * @ ile etiketlenen karakterler de aynı şekilde, o yoruma yanıt verir.
     */
    private fun triggerOwnerReplyToComment(post: MayagramPost, newComment: MayagramComment) {
        if (!newComment.authorIsUser && newComment.authorId == post.characterId) {
            // Post sahibi kendi gönderisine kendi yorumuna yanıt vermesin
            return
        }
        val main = MainActivity.currentInstance ?: return
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val allChars = loadCharacters(prefs.getString("characters_json", null))
        if (allChars.isEmpty()) return

        val postOwner = allChars.find { it.id == post.characterId }
        if (postOwner == null) {
            MainActivity.log("Mayagram", "⚠️ Gönderi sahibi bulunamadı! ID: ${post.characterId}")
            return
        }

        val mentioned = extractMentionedCharacters(newComment.content, allChars)
            .filter { it.id != post.characterId }

        val responders = (listOf(postOwner) + mentioned)
            .distinctBy { it.id }
            .filter { it.id != newComment.authorId }

        if (responders.isEmpty()) return

        MainActivity.log("Mayagram", "📢 '${newComment.content.take(30)}' yorumuna yanıt verecekler: ${responders.joinToString { it.name }}")

        lifecycleScope.launch {
            for ((index, character) in responders.withIndex()) {
                kotlinx.coroutines.delay(1200 + (index * 900L))
                main.generateCharacterComment(
                    post = post,
                    commenter = character,
                    parentCommentId = newComment.id
                ) { reply ->
                    MainActivity.log("Mayagram", "💬 ${character.name} → ${newComment.authorName}'e yanıt verdi: ${reply.content}")
                }
            }
        }
    }

    /**
     * v6.5: Bir yoruma YANIT yazıldığında (parentCommentId dolu) çağrılır.
     * Yanıtlanan yorumu yazan KARAKTER ise (kullanıcı değilse), o karaktere
     * zincirleme bir yanıt ürettirir — sınırsız derinlik, her seferinde bir önceki yazar cevap verir.
     */
    /**
 * Bir yoruma YANIT yazıldığında çağrılır. Yanıtlanan yorumu yazan KARAKTER ise
 * (kullanıcı değilse), o karaktere zincirleme bir yanıt ürettirir.
 * [newComment] az önce eklenen yorumdur (örn. kullanıcının "Nereye gittin?" sorusu) —
 * karakterin yanıtı BUNA bağlanır, eski parent yoruma değil.
 */
    private fun triggerChainReply(post: MayagramPost, parentComment: MayagramComment, newComment: MayagramComment) {
        if (parentComment.authorIsUser) {
        // Kullanıcının yorumuna otomatik karakter yanıtı tetiklenmez — kullanıcı ne zaman isterse kendi yazar.
            return
        }
        val main = MainActivity.currentInstance ?: return
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val allChars = loadCharacters(prefs.getString("characters_json", null))
        val commenter = allChars.find { it.id == parentComment.authorId } ?: return

        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)
            main.generateCharacterComment(post, commenter, parentCommentId = newComment.id) { reply ->
                MainActivity.log("Mayagram", "🔁 ${commenter.name} zincirleme yanıt verdi: ${reply.content}")
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

        // v6.5: "şuna yanıt veriyorsun" göstergesi — reply moduna geçince görünür
        var replyTarget: MayagramComment? = null
        val replyBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding((10*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
            setBackgroundColor(0x22E8724A)
        }
        val replyBannerText = TextView(this).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val replyBannerCancel = TextView(this).apply {
            text = "✕"; textSize = 14f; setPadding((8*dp).toInt(), 0, (4*dp).toInt(), 0)
            isClickable = true; isFocusable = true
        }
        replyBanner.addView(replyBannerText)
        replyBanner.addView(replyBannerCancel)
        outer.addView(replyBanner)

        val commentAdapter = MayagramCommentAdapter(
            onReply = { comment ->
                replyTarget = comment
                replyBanner.visibility = View.VISIBLE
                replyBannerText.text = "↩ ${comment.authorName}'e yanıtlıyorsun"
            }
        )
        replyBannerCancel.setOnClickListener {
            replyTarget = null
            replyBanner.visibility = View.GONE
        }

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
            val currentReplyTarget = replyTarget

            val comment = MayagramComment(
                postId          = post.id,
                authorId        = "user",
                authorName      = userName,
                authorEmoji     = "👤",
                authorAvatarUri = userAvatar,
                content         = text,
                parentCommentId = currentReplyTarget?.id,
                authorIsUser    = true
            )
            lifecycleScope.launch(Dispatchers.IO) {
                db.mayagramDao().insertComment(comment)
                withContext(Dispatchers.Main) {
                    commentAdapter.addComment(comment)
                    commentEdit.text.clear()
                    rvComments.scrollToPosition(commentAdapter.itemCount - 1)
                    replyTarget = null
                    replyBanner.visibility = View.GONE

                    if (currentReplyTarget != null) {
                        // Bir yoruma yanıt yazıldı — o yorumun sahibine, YENİ yoruma bağlı zincirleme yanıt tetikle
                        triggerChainReply(post, currentReplyTarget, comment)
                    } else {
                        // Post'a direkt yorum — post sahibi (ve @etiketlenenler) BU YORUMA yanıt verir
                        triggerOwnerReplyToComment(post, comment)
                    }
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
                    db.mayagramDao().deleteCharacterLikesForPost(post.id)
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
                    avatarUri    = obj.optString("avatar_uri", "").ifEmpty { null },
                    description  = obj.optString("description", ""),
                    personality  = obj.optString("personality", ""),
                    scenario     = obj.optString("scenario", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}
