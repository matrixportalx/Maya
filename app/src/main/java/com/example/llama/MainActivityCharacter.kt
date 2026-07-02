package tr.maya

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.internal.InferenceEngineImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// ── Karakter kurulumu (drawer) ────────────────────────────────────────────────

internal fun MainActivity.setupCharacters() {
    characterAdapter = CharacterAdapter(
        onSelect = { char ->
            setActiveCharacter(char)
            drawerLayout.closeDrawers()
            Toast.makeText(this, "🎭 ${char.name} aktif", Toast.LENGTH_SHORT).show()
        },
        onLongClick = { char -> showCharacterOptionsDialog(char) }
    )
    charactersRv.layoutManager = LinearLayoutManager(this)
    charactersRv.adapter = characterAdapter
    characterAdapter.activeId = activeCharacterId
    characterAdapter.submitList(characters.toList())

    btnAddCharacter.setOnClickListener { showAddCharacterChoiceDialog() }
}

/**
 * "+ Ekle" butonuna basıldığında: manuel oluştur ya da Tavern kartı içe aktar seçimi.
 */
internal fun MainActivity.showAddCharacterChoiceDialog() {
    android.app.AlertDialog.Builder(this)
        .setTitle("Yeni Karakter")
        .setItems(arrayOf("✏️ Manuel Oluştur", "🃏 Tavern Kartı İçe Aktar (.png)")) { _, which ->
            when (which) {
                0 -> showCharacterEditDialog(null)
                1 -> pickTavernCardFile()
            }
        }
        .setNegativeButton("İptal", null).show()
}

internal fun MainActivity.showCharacterOptionsDialog(char: MayaCharacter) {
    val options = mutableListOf("✏️ Düzenle", "🃏 Tavern Kartı Olarak Dışa Aktar")
    if (characters.size > 1) options.add("🗑️ Sil")
    android.app.AlertDialog.Builder(this)
        .setTitle("${char.emoji} ${char.name}")
        .setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "✏️ Düzenle" -> showCharacterEditDialog(char)
                "🃏 Tavern Kartı Olarak Dışa Aktar" -> exportCharacterAsTavernCard(char)
                "🗑️ Sil"    -> confirmDeleteCharacter(char)
            }
        }
        .setNegativeButton("İptal", null).show()
}

internal fun MainActivity.showCharacterEditDialog(existing: MayaCharacter?) {
    val dp = resources.displayMetrics.density
    val isNew = existing == null

    val scrollView = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (8*dp).toInt())
    }
    scrollView.addView(layout)

    fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8*dp).toInt(); bottomMargin = (2*dp).toInt() }
    }
    fun field(hint: String, value: String = "", multiLine: Boolean = false) = android.widget.EditText(this).apply {
        this.hint = hint; setText(value)
        if (multiLine) { minLines = 3; maxLines = 8; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // ── Avatar önizleme + seçim butonu ───────────────────────────────────────
    var currentAvatarUri: String? = existing?.avatarUri

    val avatarRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8*dp).toInt() }
    }

    val avatarPreview = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(
            (72*dp).toInt(), (72*dp).toInt()
        ).apply { marginEnd = (12*dp).toInt() }
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFF2E2E4A.toInt())
        }
        clipToOutline = true
    }

    // Avatar yükle
    fun loadAvatarPreview(uriStr: String?) {
        if (uriStr == MainActivity.DEFAULT_AVATAR_MARKER) {
            avatarPreview.setImageResource(R.drawable.maya_default_avatar)
            return
        }
        if (uriStr != null) {
            try {
                // İçe aktarılan tavern avatarları VEYA AI ile üretilen avatarlar (cacheDir/filesDir dosya yolu) "file:" işaretçisiyle başlar
                val bmp = if (uriStr.startsWith("file:")) {
                    val path = uriStr.removePrefix("file:")
                    BitmapFactory.decodeFile(path)?.let { raw -> roundBitmap(raw, (72 * dp).toInt()) }
                } else {
                    loadRoundedBitmap(Uri.parse(uriStr), (72 * dp).toInt())
                }
                if (bmp != null) {
                    avatarPreview.setImageBitmap(bmp)
                    return
                }
            } catch (_: Exception) {}
        }
        // Varsayılan emoji göster
        avatarPreview.setImageDrawable(null)
    }
    loadAvatarPreview(currentAvatarUri)

    // ── v6.6: Avatar önizlemesine dokununca tam ekran göster ─────────────────
    // tempAvatarUri henüz tanımlanmadığı için (aşağıda) buradaki listener bir
    // lambda referansı üzerinden en güncel değeri okur — Kotlin closure'ı
    // değişkenin kendisini değil son değerini yakalar, bu yüzden sorun olmaz.
    avatarPreview.isClickable = true
    avatarPreview.isFocusable = true

    val avatarBtnCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    val selectAvatarBtn = android.widget.Button(this).apply {
        text = "📷 Fotoğraf Seç"
        isAllCaps = false
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    // ── v6.6: AI ile avatar oluştur butonu ───────────────────────────────────
    val aiAvatarBtn = android.widget.Button(this).apply {
        text = "✨ AI ile Oluştur"
        isAllCaps = false
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (4*dp).toInt() }
    }

    val removeAvatarBtn = android.widget.Button(this).apply {
        text = "✕ Kaldır"
        isAllCaps = false
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (4*dp).toInt() }
        visibility = if (currentAvatarUri != null) android.view.View.VISIBLE else android.view.View.GONE
    }

    avatarBtnCol.addView(selectAvatarBtn)
    avatarBtnCol.addView(aiAvatarBtn)
    avatarBtnCol.addView(removeAvatarBtn)
    avatarRow.addView(avatarPreview)
    avatarRow.addView(avatarBtnCol)

    // AI üretim durum etiketi (buton altında, ihtiyaç olunca görünür)
    val aiStatusLabel = TextView(this).apply {
        textSize = 11f; alpha = 0.7f
        visibility = android.view.View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (4*dp).toInt() }
    }

    // Geçici seçim diyalog için callback
    var tempAvatarUri: String? = currentAvatarUri

    // ── v6.6: Avatar önizlemesine dokununca güncel avatarı tam ekran göster ──
    avatarPreview.setOnClickListener {
        val uriToShow = tempAvatarUri
        if (uriToShow == null) {
            Toast.makeText(this, "Henüz bir avatar seçilmedi", Toast.LENGTH_SHORT).show()
        } else {
            showAvatarFullscreen(uriToShow)
        }
    }

    selectAvatarBtn.setOnClickListener {
        // Doğrudan sistem galeri intent'i aç (diyalog açık olduğu için launcher yerine)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        // pendingAvatarCharacterId'ye özel bir işaretçi koy
        pendingAvatarCharacterId = existing?.id ?: "__new__"
        // Diyalog ID'sini geçici sakla — callback avatarPreview'ı güncellesin
        pendingAvatarDialogCallback = { uriStr ->
            tempAvatarUri = uriStr
            loadAvatarPreview(uriStr)
            removeAvatarBtn.visibility = android.view.View.VISIBLE
        }
        characterAvatarPickerLauncher.launch(intent)
    }

    removeAvatarBtn.setOnClickListener {
        tempAvatarUri = null
        avatarPreview.setImageDrawable(null)
        removeAvatarBtn.visibility = android.view.View.GONE
    }

    layout.addView(label("Avatar Fotoğrafı"))
    layout.addView(avatarRow)
    layout.addView(aiStatusLabel)

    layout.addView(label("Emoji (Avatar yoksa gösterilir)"))
    val emojiField = field("🃏", existing?.emoji ?: "🃏")
    layout.addView(emojiField)

    layout.addView(label("Karakter adı ({{char}})"))
    val nameField = field("Maya", existing?.name ?: "Maya")
    layout.addView(nameField)

    layout.addView(label("Kullanıcı adı ({{user}})"))
    val userNameField = field("Kullanıcı", existing?.userName ?: "")
    layout.addView(userNameField)

    layout.addView(label("Açıklama / Bio (görünüm, geçmiş, görev)"))
    val descriptionField = field("Karakterin görünümünü, geçmişini tanımlayın...", existing?.description ?: existing?.systemPrompt ?: "", multiLine = true)
    layout.addView(descriptionField)

    layout.addView(label("Kişilik (isteğe bağlı)"))
    val personalityField = field("Karakterin kişilik özelliklerini tanımlayın...", existing?.personality ?: "", multiLine = true)
    layout.addView(personalityField)

    layout.addView(label("Senaryo (kullanıcıyla ilişki/bağlam — isteğe bağlı)"))
    val scenarioField = field("Örn: Sen ve {{user}} eski arkadaşsınız...", existing?.scenario ?: "", multiLine = true)
    layout.addView(scenarioField)

    layout.addView(label("İlk mesaj (isteğe bağlı)"))
    val firstMessageField = field("Karakterin sohbet başında söyleyeceği ilk söz...", existing?.firstMessage ?: "", multiLine = true)
    layout.addView(firstMessageField)

    // ── v6.8: Otomatik Mayagram paylaşımı switch'i ───────────────────────────
    val autoPostRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12*dp).toInt() }
    }
    val autoPostLabel = TextView(this).apply {
        text = "🔁 Otomatik Mayagram paylaşımına dahil et"
        textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    @Suppress("DEPRECATION")
    val autoPostSwitch = android.widget.Switch(this).apply {
        isChecked = existing?.autoPostEnabled ?: false
    }
    autoPostRow.addView(autoPostLabel); autoPostRow.addView(autoPostSwitch)
    layout.addView(autoPostRow)
    layout.addView(TextView(this).apply {
        text = "Açıksa, Ayarlar'da belirlenen aralıkta bu karakter rastgele otomatik gönderi paylaşabilir."
        textSize = 11f; alpha = 0.55f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (4*dp).toInt() }
    })

    // ── v6.6: AI avatar oluşturma butonu davranışı ───────────────────────────
    // NOT: Bu listener tüm alanlar (description/personality/emoji vb.) layout'a
    // eklendikten SONRA tanımlanıyor — çünkü tıklandığında o anki metin kutusu
    // içeriklerini (henüz kaydedilmemiş haliyle) okuyup prompt'a katmamız gerekiyor.
    aiAvatarBtn.setOnClickListener {
        if (loadedModelPath == null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("⚠️ Model Gerekli")
                .setMessage("AI ile avatar oluşturmak için önce bir model yüklemeniz gerekir.")
                .setPositiveButton("Tamam", null).show()
            return@setOnClickListener
        }
        if (!dreamApiEnabled) {
            android.app.AlertDialog.Builder(this)
                .setTitle("⚠️ Dream API Kapalı")
                .setMessage("AI ile avatar oluşturmak için Dream API'yi Ayarlar'dan etkinleştirin ve Local Dream uygulamasının çalıştığından emin olun.")
                .setPositiveButton("Tamam", null).show()
            return@setOnClickListener
        }

        val tempCharForPrompt = MayaCharacter(
            id = existing?.id ?: "__new__",
            name = nameField.text.toString().trim().ifEmpty { "Maya" },
            userName = userNameField.text.toString().trim().ifEmpty { "Kullanıcı" },
            emoji = emojiField.text.toString().trim().ifEmpty { "🃏" },
            systemPrompt = existing?.systemPrompt ?: "",
            description = descriptionField.text.toString().trim(),
            personality = personalityField.text.toString().trim(),
            scenario = scenarioField.text.toString().trim()
        )

        if (tempCharForPrompt.description.isBlank() && tempCharForPrompt.personality.isBlank()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("⚠️ Açıklama Gerekli")
                .setMessage("AI'nın bir görsel tarif edebilmesi için önce \"Açıklama / Bio\" veya \"Kişilik\" alanını doldurun.")
                .setPositiveButton("Tamam", null).show()
            return@setOnClickListener
        }

        aiAvatarBtn.isEnabled = false
        selectAvatarBtn.isEnabled = false
        aiStatusLabel.visibility = android.view.View.VISIBLE
        aiStatusLabel.text = "🧠 Görsel tarif oluşturuluyor…"

        generateAvatarForCharacter(
            character = tempCharForPrompt,
            onProgress = { status ->
                aiStatusLabel.text = status
            },
            onDone = { avatarFile ->
                tempAvatarUri = "file:${avatarFile.absolutePath}"
                loadAvatarPreview(tempAvatarUri)
                removeAvatarBtn.visibility = android.view.View.VISIBLE
                aiStatusLabel.text = "✅ Avatar oluşturuldu"
                aiAvatarBtn.isEnabled = true
                selectAvatarBtn.isEnabled = true
            },
            onError = { msg ->
                aiStatusLabel.text = "❌ $msg"
                aiAvatarBtn.isEnabled = true
                selectAvatarBtn.isEnabled = true
            }
        )
    }

    android.app.AlertDialog.Builder(this)
        .setTitle(if (isNew) "➕ Yeni Karakter" else "✏️ Karakteri Düzenle")
        .setView(scrollView)
        .setPositiveButton("Kaydet") { _, _ ->
            val emoji   = emojiField.text.toString().trim().ifEmpty { "👩‍🦰" }
            val name    = nameField.text.toString().trim().ifEmpty { "Maya" }
            val uName   = userNameField.text.toString().trim().ifEmpty { "Kullanıcı" }
            val description = descriptionField.text.toString().trim().ifEmpty {
                "{{date}} {{time}}. Senin adın {{char}}. Sen yararlı, zeki ve eğlenceli bir yapay zeka asistansın ve {{user}}'ın sadık bir dostusun."
            }
            val personality = personalityField.text.toString().trim()
            val scenario = scenarioField.text.toString().trim()
            val firstMsg = firstMessageField.text.toString().trim()

            val char = MayaCharacter(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name, userName = uName, emoji = emoji,
                systemPrompt = existing?.systemPrompt ?: "",
                avatarUri = tempAvatarUri ?: MainActivity.DEFAULT_AVATAR_MARKER,
                scenario = scenario,
                firstMessage = firstMsg,
                description = description,
                personality = personality,
                autoPostEnabled = autoPostSwitch.isChecked
            )
            if (isNew) {
                characters.add(char)
            } else {
                val idx = characters.indexOfFirst { it.id == char.id }
                if (idx >= 0) characters[idx] = char
            }
            saveCharactersToPrefs(characters)
            characterAdapter.submitList(characters.toList())
            if (char.id == activeCharacterId) setActiveCharacter(char)
            pendingAvatarDialogCallback = null
        }
        .setNegativeButton("İptal") { _, _ ->
            pendingAvatarDialogCallback = null
        }
        .show()
}

// Diyalog içindeki avatar seçimi için callback (launcher → diyalog köprüsü)
internal var pendingAvatarDialogCallback: ((String) -> Unit)? = null

internal fun MainActivity.confirmDeleteCharacter(char: MayaCharacter) {
    android.app.AlertDialog.Builder(this)
        .setTitle("Karakteri Sil")
        .setMessage("\"${char.emoji} ${char.name}\" silinsin mi?")
        .setPositiveButton("Sil") { _, _ ->
            characters.removeAll { it.id == char.id }
            saveCharactersToPrefs(characters)
            if (char.id == activeCharacterId && characters.isNotEmpty()) {
                setActiveCharacter(characters.first())
            }
            characterAdapter.submitList(characters.toList())
            Toast.makeText(this, "\"${char.name}\" silindi", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("İptal", null).show()
}

// ── v6.7: Avatar tam ekran önizleme + galeriye kaydetme ──────────────────────

/**
 * Karakter düzenleme diyaloğundaki yuvarlak avatar önizlemesine dokununca çağrılır.
 * [avatarUriStr] şu formatlardan biri olabilir:
 *   "drawable:maya_default_avatar" → gömülü Maya fotoğrafı
 *   "content://..."                → galeriden seçilmiş fotoğraf
 *   "file:/data/.../xyz.png"       → tavern kartı veya AI üretimli avatar
 *
 * Mayagram'daki ZoomableImageView + dialog_fullscreen_image.xml yeniden kullanılır,
 * ancak Paylaş butonu gizlenir (avatar paylaşmak anlamsız) — sadece Kapat ve
 * Galeriye Kaydet kalır.
 */
internal fun MainActivity.showAvatarFullscreen(avatarUriStr: String) {
    val bmp: Bitmap? = try {
        when {
            avatarUriStr == MainActivity.DEFAULT_AVATAR_MARKER ->
                BitmapFactory.decodeResource(resources, R.drawable.maya_default_avatar)
            avatarUriStr.startsWith("file:") ->
                BitmapFactory.decodeFile(avatarUriStr.removePrefix("file:"))
            else ->
                contentResolver.openInputStream(Uri.parse(avatarUriStr))?.use { BitmapFactory.decodeStream(it) }
        }
    } catch (e: Exception) { null }

    if (bmp == null) {
        Toast.makeText(this, "Görüntü yüklenemedi", Toast.LENGTH_SHORT).show()
        return
    }

    val view = layoutInflater.inflate(R.layout.dialog_fullscreen_image, null)
    val imageView = view.findViewById<ZoomableImageView>(R.id.fs_image_view)
    val btnShare = view.findViewById<android.widget.ImageButton>(R.id.btn_fs_share)
    val btnSave  = view.findViewById<android.widget.ImageButton>(R.id.btn_fs_save)
    val btnClose = view.findViewById<android.widget.ImageButton>(R.id.btn_fs_close)

    imageView.setImageBitmap(bmp)
    // Avatar paylaşmak anlamsız — bu buton karakter avatar önizlemesinde gizlenir.
    btnShare.visibility = android.view.View.GONE

    val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.setContentView(view)
    dialog.window?.setLayout(
        android.view.WindowManager.LayoutParams.MATCH_PARENT,
        android.view.WindowManager.LayoutParams.MATCH_PARENT
    )

    btnSave.setOnClickListener { saveBitmapToGallery(bmp, "maya_avatar") }
    btnClose.setOnClickListener { dialog.dismiss() }

    dialog.show()
}

/**
 * Verilen Bitmap'i doğrudan galeriye PNG olarak kaydeder.
 * [fileNamePrefix] örn. "maya_avatar" → "maya_avatar_<timestamp>.png"
 * RELATIVE_PATH "Pictures/Maya" — Mayagram görüntüleriyle aynı klasörü kullanmaz,
 * çünkü bunlar gönderi değil, kullanıcının kendi karakter avatarlarıdır.
 */
internal fun MainActivity.saveBitmapToGallery(bitmap: Bitmap, fileNamePrefix: String) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "${fileNamePrefix}_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Maya")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveBitmapToGallery,
                        "✅ Galeriye kaydedildi (Pictures/Maya)", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@saveBitmapToGallery, "Kaydetme başarısız", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@saveBitmapToGallery, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// ── v6.7: Kullanılmayan AI avatar dosyalarını temizle ─────────────────────────

/**
 * ai_avatars/ dizinindeki dosyalar arasında, characters_json içinde HİÇBİR
 * karakterin avatar_uri'si olarak referans verilmeyenleri siler.
 *
 * Bu, kullanıcının "✨ AI ile Oluştur" ile birden fazla deneme yapıp beğenmediği
 * sonuçları "İptal" ile kapattığı durumlarda diskte biriken sahipsiz dosyaları
 * temizler. Uygulama açılışında sessizce çalışır (cleanupMissingModels gibi) —
 * kullanıcıya herhangi bir onay sorusu sormaz, sadece gerçekten kullanılmayan
 * dosyaları siler.
 *
 * NOT: Şu an düzenlenmekte olan (henüz "Kaydet"e basılmamış) bir diyalogdaki
 * geçici AI avatar, bu fonksiyon çağrıldığında diyalog kapalıysa (örn. sadece
 * uygulama başlangıcında çalıştığı için) risk oluşturmaz — diyalog açıkken bu
 * fonksiyon tetiklenmez.
 */
internal fun MainActivity.cleanupUnusedAiAvatars() {
    try {
        val dir = getAiAvatarsDir()
        val files = dir.listFiles() ?: return
        if (files.isEmpty()) return

        val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        val charactersJson = prefs.getString("characters_json", null) ?: "[]"
        val usedPaths = mutableSetOf<String>()
        try {
            val arr = JSONArray(charactersJson)
            for (i in 0 until arr.length()) {
                val avatarUri = arr.getJSONObject(i).optString("avatar_uri", "")
                if (avatarUri.startsWith("file:")) {
                    usedPaths.add(avatarUri.removePrefix("file:"))
                }
            }
        } catch (_: Exception) { /* JSON bozuksa hiçbir şey silme — güvenli taraf */ return }

        var deletedCount = 0
        files.forEach { file ->
            if (file.absolutePath !in usedPaths) {
                if (file.delete()) deletedCount++
            }
        }
        if (deletedCount > 0) {
            MainActivity.log("Maya", "cleanupUnusedAiAvatars: $deletedCount sahipsiz AI avatar silindi")
        }
    } catch (e: Exception) {
        MainActivity.log("Maya", "cleanupUnusedAiAvatars hatası: ${e.message}")
    }
}

// ── v6.6: AI ile karakter avatarı üretimi ─────────────────────────────────────

/**
 * Karakterin description/personality/scenario alanlarından İngilizce bir Dream API
 * görsel prompt'u üretir (model context'ini bozmadan sendBypassPrompt ile), ardından
 * Dream API'yi çağırıp sonucu [getAiAvatarsDir] dizinine PNG olarak kaydeder.
 *
 * NOT: Bu fonksiyon yalnızca ÜRETİLEN dosyayı [onDone] ile döner — karakteri kaydetmez.
 * Kaydetme işlemi diyalog "Kaydet" butonuna basıldığında zaten yapılan akışla olur.
 */
internal fun MainActivity.generateAvatarForCharacter(
    character: MayaCharacter,
    onProgress: (String) -> Unit,
    onDone: (File) -> Unit,
    onError: (String) -> Unit
) {
    lifecycleScope.launch {
        try {
            onProgress("🧠 Görsel tarif oluşturuluyor…")

            val bioText = listOf(character.description, character.personality, character.scenario)
                .filter { it.isNotBlank() }
                .joinToString(". ")
                .ifBlank { "a person" }

            val instruction = buildString {
                appendLine("Aşağıdaki karakter tanımına uygun, İngilizce ve görüntü üretim modeli için ayrıntılı bir PORTRE (yüz/üst beden) tarifi yaz.")
                appendLine("SADECE İngilizce sahne tarifini yaz, başka hiçbir şey ekleme. Maksimum 25 kelime.")
                appendLine("Tarif fotogerçekçi bir portre fotoğrafı betimlemeli (örn: yaş, saç, göz rengi, ifade, kıyafet, arka plan).")
                appendLine()
                appendLine("Karakter adı: ${character.name}")
                appendLine("Karakter tanımı: \"$bioText\"")
            }

            val fullPrompt = buildAvatarPromptTemplate(instruction)
            val sb = StringBuilder()
            try {
                val impl = engine as? InferenceEngineImpl
                val tokenFlow = if (impl != null) {
                    impl.sendBypassPrompt(fullPrompt, 80)
                } else {
                    engine.sendUserPrompt(fullPrompt, predictLength = 80)
                }
                tokenFlow.collect { token -> sb.append(token) }
            } catch (e: Exception) {
                onError("LLM hatası: ${e.message}")
                return@launch
            }

            val imagePrompt = extractAvatarPromptText(sb.toString())
                .ifBlank { "professional portrait photo of $bioText" }

            MainActivity.log("AvatarAI", "Üretilen prompt: $imagePrompt")

            if (!dreamApiEnabled) {
                onError("Dream API kapalı")
                return@launch
            }

            onProgress("🎨 Görüntü oluşturuluyor…")

            var dreamBitmap: Bitmap? = null
            var dreamError: String? = null
            val dreamReq = DreamRequest(
                prompt         = imagePrompt,
                negativePrompt = dreamDefaultNegativePrompt,
                size           = dreamSize,
                steps          = dreamSteps,
                cfg            = dreamCfg,
                seed           = if (dreamSeed < 0) System.currentTimeMillis() % 100000L else dreamSeed,
                useOpenCl      = dreamUseOpenCl
            )

            performDreamRequest(dreamApiUrl, dreamReq) { event ->
                when (event) {
                    is DreamEvent.Progress -> onProgress("🎨 ${event.step}/${event.totalSteps} adım…")
                    is DreamEvent.Complete -> dreamBitmap = event.bitmap
                    is DreamEvent.Error    -> dreamError = event.message
                }
            }

            val bmp = dreamBitmap
            if (bmp == null) {
                onError(dreamError ?: "Görüntü oluşturulamadı")
                return@launch
            }

            onProgress("💾 Kaydediliyor…")
            val avatarFile = withContext(Dispatchers.IO) { saveAiAvatarBitmap(bmp) }
            onDone(avatarFile)

        } catch (e: Exception) {
            onError("Hata: ${e.message}")
        }
    }
}

/**
 * [generateAvatarForCharacter] içinde üretilen prompt metnini Dream API çağrısı
 * ÖLÇMEDEN doğrudan dışarıdan test edebilmek için ayrı tutulan, şablonsuz
 * (template-bağımsız) prompt sarmalayıcısı.
 *
 * Gemma 4 (template=7) için thinking KAPALI tutulur — sadece kısa metin isteniyor.
 */
private fun MainActivity.buildAvatarPromptTemplate(instruction: String): String {
    return when (selectedTemplate) {
        0    -> instruction
        1    -> "<BOS_TOKEN><|START_OF_TURN_TOKEN|><|USER_TOKEN|>$instruction<|END_OF_TURN_TOKEN|><|START_OF_TURN_TOKEN|><|CHATBOT_TOKEN|>"
        2    -> "<|im_start|>user\n$instruction<|im_end|>\n<|im_start|>assistant\n"
        3    -> "<bos><start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"
        4    -> "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n$instruction<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        5    -> "<|start_of_role|>user<|end_of_role|>$instruction<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>"
        7    -> "<bos><|turn>user\n$instruction<turn|>\n<|turn>model\n"
        else -> instruction
    }
}

/**
 * Model çıktısından thinking bloklarını ve format tokenlarını temizleyip
 * yalnızca görsel tarif metnini döner. MainActivityMayagram.kt'deki
 * extractVisibleContent ile aynı mantık — burada bağımsız tutuldu çünkü
 * o fonksiyon private (dosya-dışı erişilemez).
 */
private fun extractAvatarPromptText(raw: String): String {
    var text = raw

    text = text.replace(
        Regex("""<\|channel>.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), ""
    )
    text = text.replace(
        Regex("""<think>.*?</think>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), ""
    )
    val thinkIdx = text.indexOf("<think>")
    if (thinkIdx != -1) text = text.substring(0, thinkIdx)
    val channelIdx = text.indexOf("<|channel>")
    if (channelIdx != -1) text = text.substring(0, channelIdx)

    text = text
        .replace("<|END_OF_TURN_TOKEN|>", "")
        .replace("<|START_OF_TURN_TOKEN|>", "")
        .replace("<|USER_TOKEN|>", "")
        .replace("<|CHATBOT_TOKEN|>", "")
        .replace("<|im_end|>", "")
        .replace("<|eot_id|>", "")
        .replace("<end_of_turn>", "")
        .replace("<start_of_turn>", "")
        .replace("<turn|>", "")
        .replace("<|turn>", "")
        .replace("<|channel>", "")
        .replace("<channel|>", "")
        .replace("<|end_of_text|>", "")
        .replace("<|endoftext|>", "")
        .replace(Regex("(?i)^(image[_ ]?prompt|caption|prompt)\\s*:\\s*"), "")
        .replace("\"", "")
        .trim()

    return text
}

/** AI üretimli karakter avatarlarının kalıcı olarak saklandığı dizin. */
internal fun MainActivity.getAiAvatarsDir(): File {
    val dir = File(filesDir, "ai_avatars")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun MainActivity.saveAiAvatarBitmap(bitmap: Bitmap): File {
    val dir = getAiAvatarsDir()
    val file = File(dir, "avatar_${UUID.randomUUID()}.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return file
}

// ── Avatar işleme ─────────────────────────────────────────────────────────────

/**
 * Karakter avatar URI seçildiğinde çağrılır.
 * URI'ye kalıcı okuma izni alır, avatarları klasöründe kopyalar.
 */
internal fun MainActivity.handleCharacterAvatarSelected(uri: Uri, charId: String?) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {}

    val uriStr = uri.toString()

    // Eğer bir diyalog callback'i bekliyorsa ona ilet
    pendingAvatarDialogCallback?.invoke(uriStr)

    // Eğer doğrudan mevcut bir karakter için seçildiyse güncelle
    if (charId != null && charId != "__new__") {
        val idx = characters.indexOfFirst { it.id == charId }
        if (idx >= 0) {
            characters[idx] = characters[idx].copy(avatarUri = uriStr)
            saveCharactersToPrefs(characters)
            characterAdapter.submitList(characters.toList())
            if (charId == activeCharacterId) {
                messageAdapter.charAvatarUri = uriStr
                messageAdapter.notifyDataSetChanged()
            }
        }
    }
}

/**
 * Kullanıcı avatar URI seçildiğinde çağrılır.
 */
internal fun MainActivity.handleUserAvatarSelected(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {}

    userAvatarUri = uri.toString()
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        .edit().putString("user_avatar_uri", userAvatarUri).apply()

    messageAdapter.userAvatarUri = userAvatarUri
    messageAdapter.notifyDataSetChanged()

    Toast.makeText(this, "✅ Kullanıcı fotoğrafı güncellendi", Toast.LENGTH_SHORT).show()
}

/**
 * URI'den yuvarlak Bitmap yükler.
 */
internal fun MainActivity.loadRoundedBitmap(uri: Uri, sizePx: Int): Bitmap? {
    return try {
        val input = contentResolver.openInputStream(uri) ?: return null
        val raw = BitmapFactory.decodeStream(input)
        input.close()
        raw ?: return null
        roundBitmap(raw, sizePx)
    } catch (_: Exception) { null }
}

/**
 * Halihazırda elde edilmiş bir Bitmap'i yuvarlak hale getirir (URI gerektirmez).
 * Tavern kartı içe aktarımında doğrudan decode edilen bitmap'ler için kullanılır.
 */
internal fun roundBitmap(raw: Bitmap, sizePx: Int): Bitmap {
    val size = raw.width.coerceAtMost(raw.height)
    val x = (raw.width - size) / 2
    val y = (raw.height - size) / 2
    val cropped = Bitmap.createBitmap(raw, x, y, size, size)
    val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)

    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    canvas.drawOval(
        android.graphics.RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
        paint
    )
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(scaled, 0f, 0f, paint)
    return output
}

// ── Karakter yardımcıları ─────────────────────────────────────────────────────

internal fun MainActivity.loadCharactersFromPrefs(): List<MayaCharacter> {
    val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    val json = prefs.getString("characters_json", null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val legacySystemPrompt = obj.optString("system_prompt", "")
            // Eski karakterlerde description boşsa ama eski systemPrompt doluysa,
            // description'a otomatik taşı (veri kaybı olmaz, kullanıcı görüp düzenleyebilir).
            val description = obj.optString("description", "").ifEmpty {
                if (obj.optString("personality", "").isEmpty()) legacySystemPrompt else ""
            }
            MayaCharacter(
                id = obj.getString("id"),
                name = obj.getString("name"),
                userName = obj.optString("user_name", "Kullanıcı"),
                emoji = obj.optString("emoji", "🃏"),
                systemPrompt = legacySystemPrompt,
                avatarUri = obj.optString("avatar_uri", "").ifEmpty { null },
                scenario = obj.optString("scenario", ""),
                firstMessage = obj.optString("first_message", ""),
                description = description,
                personality = obj.optString("personality", ""),
                autoPostEnabled = obj.optBoolean("auto_post_enabled", false),
                userProfileId = obj.optString("user_profile_id", "").ifEmpty { null }
            )
        }
    } catch (e: Exception) { emptyList() }
}

internal fun MainActivity.saveCharactersToPrefs(list: List<MayaCharacter>) {
    val arr = JSONArray()
    list.forEach { char ->
        arr.put(JSONObject().apply {
            put("id", char.id); put("name", char.name)
            put("user_name", char.userName); put("emoji", char.emoji)
            put("system_prompt", char.systemPrompt)
            put("avatar_uri", char.avatarUri ?: "")
            put("scenario", char.scenario)
            put("first_message", char.firstMessage)
            put("description", char.description)
            put("personality", char.personality)
            put("auto_post_enabled", char.autoPostEnabled)
            put("user_profile_id", char.userProfileId ?: "")
        })
    }
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        .edit().putString("characters_json", arr.toString()).apply()
}

/**
 * description + personality + scenario'dan modele gidecek tek sistem promptu metnini oluşturur.
 * Boş alanlar atlanır. Hiçbiri doluysa eski systemPrompt'a (geriye dönük uyumluluk) düşülür.
 */
internal fun buildCharacterSystemPrompt(char: MayaCharacter): String {
    val combined = buildString {
        if (char.description.isNotBlank()) {
            append(char.description.trim())
        }
        if (char.personality.isNotBlank()) {
            if (isNotEmpty()) appendLine(); appendLine()
            append("Kişilik: ")
            append(char.personality.trim())
        }
        if (char.scenario.isNotBlank()) {
            if (isNotEmpty()) appendLine(); appendLine()
            append("Senaryo: ")
            append(char.scenario.trim())
        }
    }.trim()
    return combined.ifEmpty { char.systemPrompt }
}

internal fun MainActivity.applyActiveCharacterValues() {
    val char = characters.find { it.id == activeCharacterId }
    if (char != null) {
        charName     = char.name
        userName     = char.userName
        systemPrompt = buildCharacterSystemPrompt(char)
    }
}

internal fun MainActivity.setActiveCharacter(char: MayaCharacter) {
    activeCharacterId = char.id
    charName          = char.name
    userName          = char.userName
    systemPrompt      = buildCharacterSystemPrompt(char)
    getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        .edit().putString("active_character_id", char.id).apply()
    saveSettings()
    characterAdapter.activeId = char.id
    characterAdapter.notifyDataSetChanged()
    messageAdapter.charName     = char.name
    messageAdapter.charEmoji    = char.emoji
    messageAdapter.charAvatarUri = char.avatarUri
    messageAdapter.userName     = char.userName
    messageAdapter.notifyDataSetChanged()
    updateActiveModelSubtitle()
    if (loadedModelPath != null && selectedTemplate == 0 && systemPrompt.isNotEmpty()) {
        lifecycleScope.launch {
            try { engine.setSystemPrompt(applyPersona(systemPrompt)) } catch (_: Exception) {}
        }
    }
}

// ── Persona yardımcısı ────────────────────────────────────────────────────────

internal fun MainActivity.applyPersona(text: String): String {
    val now = java.util.Calendar.getInstance()
    val dateStr = String.format("%d %s %d",
        now.get(java.util.Calendar.DAY_OF_MONTH),
        arrayOf("Ocak","Şubat","Mart","Nisan","Mayıs","Haziran",
                 "Temmuz","Ağustos","Eylül","Ekim","Kasım","Aralık")[now.get(java.util.Calendar.MONTH)],
        now.get(java.util.Calendar.YEAR))
    val timeStr = String.format("%02d:%02d",
        now.get(java.util.Calendar.HOUR_OF_DAY),
        now.get(java.util.Calendar.MINUTE))
    return text
        .replace("{{char}}", charName)
        .replace("{{user}}", userName)
        .replace("{{date}}", dateStr)
        .replace("{{time}}", timeStr)
}

internal fun MainActivity.stripCharPrefix(text: String): String {
    val p1 = "$charName: "
    val p2 = "$charName:"
    return when {
        text.startsWith(p1) -> text.removePrefix(p1)
        text.startsWith(p2) -> text.removePrefix(p2).trimStart()
        else -> text
    }
}

// ── Avatar görüntüleme yardımcısı (gömülü varsayılan + galeri URI ortak noktası) ──

/**
 * avatarUri alanını yorumlar:
 *  - null                         → emoji göster (ImageView gizlenir)
 *  - "drawable:maya_default_avatar" → gömülü Maya fotoğrafı
 *  - "content://..."              → galeriden seçilmiş fotoğraf
 *  - "file:/data/.../xyz.png"     → içe aktarılan tavern kartı veya AI üretimli avatar (filesDir dosyası)
 *
 * ImageView ve emoji TextView referansları verilir, uygun olanı gösterir.
 */
internal fun MainActivity.bindCharacterAvatar(
    imageView: android.widget.ImageView,
    textView: TextView,
    avatarUri: String?,
    emoji: String,
    sizePx: Int
) {
    when {
        avatarUri == MainActivity.DEFAULT_AVATAR_MARKER -> {
            imageView.setImageResource(R.drawable.maya_default_avatar)
            imageView.visibility = android.view.View.VISIBLE
            textView.visibility = android.view.View.GONE
        }
        avatarUri != null -> {
            val bmp = try {
                if (avatarUri.startsWith("file:")) {
                    BitmapFactory.decodeFile(avatarUri.removePrefix("file:"))
                        ?.let { roundBitmap(it, sizePx) }
                } else {
                    loadRoundedBitmap(Uri.parse(avatarUri), sizePx)
                }
            } catch (_: Exception) { null }
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
                imageView.visibility = android.view.View.VISIBLE
                textView.visibility = android.view.View.GONE
            } else {
                imageView.visibility = android.view.View.GONE
                textView.visibility = android.view.View.VISIBLE
                textView.text = emoji
            }
        }
        else -> {
            imageView.visibility = android.view.View.GONE
            textView.visibility = android.view.View.VISIBLE
            textView.text = emoji
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// ── Tavern Kartı İçe/Dışa Aktarma ────────────────────────────────────────────
// ════════════════════════════════════════════════════════════════════════════

/** Karakter avatarlarının kalıcı olarak saklandığı dizin (galeri izni gerektirmez). */
internal fun MainActivity.getCharacterAvatarsDir(): File {
    val dir = File(filesDir, "character_avatars")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

/** Sistem dosya seçiciyi açar (.png seçimi için). */
internal fun MainActivity.pickTavernCardFile() {
    tavernCardPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/png"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}

/**
 * Seçilen tavern kartı PNG'sini işler: JSON'u çıkarır, avatarı içe kopyalar,
 * "Review Imported Card" tarzı bir önizleme/düzenleme diyaloğu açar.
 */
internal fun MainActivity.handleTavernCardSelected(uri: Uri) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val pngBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Dosya okunamadı")

            val card = TavernCardParser.readCardFromBytes(pngBytes)

            // Avatarı (PNG'nin kendisi) cacheDir/character_avatars içine kopyala
            val avatarsDir = getCharacterAvatarsDir()
            val avatarFile = File(avatarsDir, "tavern_${UUID.randomUUID()}.png")
            FileOutputStream(avatarFile).use { out -> out.write(pngBytes) }

            withContext(Dispatchers.Main) {
                showTavernImportReviewDialog(card, avatarFile)
            }
        } catch (e: TavernCardParser.InvalidCardException) {
            withContext(Dispatchers.Main) {
                android.app.AlertDialog.Builder(this@handleTavernCardSelected)
                    .setTitle("❌ Kart Okunamadı")
                    .setMessage("${e.message}\n\nBu dosya bir Tavern/SillyTavern karakter kartı (.png) olmayabilir.")
                    .setPositiveButton("Tamam", null).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@handleTavernCardSelected, "İçe aktarma hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Ekran görüntüsündeki "Review Imported Card" akışına benzer önizleme diyaloğu.
 * Kullanıcı isim/sistem promptunu düzenleyip "Oluştur" diyebilir.
 */
internal fun MainActivity.showTavernImportReviewDialog(
    card: TavernCardParser.TavernCardData,
    avatarFile: File
) {
    val dp = resources.displayMetrics.density
    val isDark = MessageAdapter.isDarkTheme(this)

    val scrollView = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20*dp).toInt(), (16*dp).toInt(), (20*dp).toInt(), (8*dp).toInt())
    }
    scrollView.addView(layout)

    // Avatar önizleme
    val avatarPreview = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams((96*dp).toInt(), (96*dp).toInt()).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (12*dp).toInt()
        }
        clipToOutline = true
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFF2E2E4A.toInt())
        }
    }
    val bmp = BitmapFactory.decodeFile(avatarFile.absolutePath)?.let { roundBitmap(it, (96*dp).toInt()) }
    if (bmp != null) avatarPreview.setImageBitmap(bmp)
    val avatarHolder = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        gravity = android.view.Gravity.CENTER
    }
    avatarHolder.addView(avatarPreview)
    layout.addView(avatarHolder)

    fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8*dp).toInt(); bottomMargin = (2*dp).toInt() }
    }
    fun field(value: String, multiLine: Boolean = false) = android.widget.EditText(this).apply {
        setText(value)
        if (multiLine) { minLines = 4; maxLines = 12; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    layout.addView(label("Karakter adı ({{char}})"))
    val nameField = field(card.name)
    layout.addView(nameField)

    layout.addView(label("Kullanıcı adı ({{user}})"))
    val userNameField = field(userName)
    layout.addView(userNameField)

    layout.addView(label("Açıklama / Bio (görünüm, geçmiş, görev)"))
    val descriptionInitial = if (card.mesExample.isNotBlank())
        "${card.description.trim()}\n\nÖrnek konuşma stili:\n${card.mesExample.trim()}".trim()
    else card.description
    val descriptionField = field(descriptionInitial, multiLine = true)
    layout.addView(descriptionField)

    layout.addView(label("Kişilik"))
    val personalityField = field(card.personality, multiLine = true)
    layout.addView(personalityField)

    layout.addView(label("Senaryo (kullanıcıyla ilişki/bağlam)"))
    val scenarioField = field(card.scenario, multiLine = true)
    layout.addView(scenarioField)

    layout.addView(label("İlk mesaj"))
    val firstMessageField = field(card.firstMessage, multiLine = true)
    layout.addView(firstMessageField)

    android.app.AlertDialog.Builder(this)
        .setTitle("🃏 Kartı Gözden Geçir")
        .setView(scrollView)
        .setPositiveButton("Oluştur") { _, _ ->
            val name = nameField.text.toString().trim().ifEmpty { "İçe Aktarılan Karakter" }
            val uName = userNameField.text.toString().trim().ifEmpty { "Kullanıcı" }
            val description = descriptionField.text.toString().trim()
            val personality = personalityField.text.toString().trim()
            val scenario = scenarioField.text.toString().trim()
            val firstMsg = firstMessageField.text.toString().trim()

            val newChar = MayaCharacter(
                id = UUID.randomUUID().toString(),
                name = name,
                userName = uName,
                emoji = "🃏",
                systemPrompt = "",
                avatarUri = "file:${avatarFile.absolutePath}",
                scenario = scenario,
                firstMessage = firstMsg,
                description = description,
                personality = personality
            )
            characters.add(newChar)
            saveCharactersToPrefs(characters)
            characterAdapter.submitList(characters.toList())
            Toast.makeText(this, "✅ \"$name\" içe aktarıldı", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("İptal") { _, _ ->
            // Kullanıcı vazgeçti — kopyalanan avatar dosyasını temizle
            try { avatarFile.delete() } catch (_: Exception) {}
        }
        .show()
}

/**
 * Karakteri Tavern (V2) PNG kartı olarak dışa aktarır.
 * Avatar yoksa Maya'nın varsayılan avatarını kaynak görsel olarak kullanır.
 */
internal fun MainActivity.exportCharacterAsTavernCard(char: MayaCharacter) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val sourcePngBytes: ByteArray = when {
                char.avatarUri == null || char.avatarUri == MainActivity.DEFAULT_AVATAR_MARKER -> {
                    resources.openRawResource(R.drawable.maya_default_avatar).use { it.readBytes() }
                }
                char.avatarUri.startsWith("file:") -> {
                    File(char.avatarUri.removePrefix("file:")).readBytes()
                }
                else -> {
                    // content:// galeri URI'si — PNG'ye dönüştürerek oku (JPEG olabilir)
                    val bmp = contentResolver.openInputStream(Uri.parse(char.avatarUri))?.use {
                        BitmapFactory.decodeStream(it)
                    } ?: throw Exception("Avatar okunamadı")
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    baos.toByteArray()
                }
            }

            val descriptionToExport = char.description.ifBlank { char.systemPrompt }

            val cardBytes = TavernCardParser.buildCardPng(
                sourcePngBytes = sourcePngBytes,
                characterName = char.name,
                userName = char.userName,
                description = descriptionToExport,
                personality = char.personality,
                scenario = char.scenario,
                firstMessage = char.firstMessage
            )

            val safeFileName = "tavern_character_${char.name.replace(Regex("[^\\w-]"), "_")}.png"

            withContext(Dispatchers.Main) {
                pendingTavernExportBytes = cardBytes
                tavernCardSaveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/png"
                    putExtra(Intent.EXTRA_TITLE, safeFileName)
                })
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@exportCharacterAsTavernCard, "Dışa aktarma hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** tavernCardSaveLauncher sonucu çağrılır — hazırlanmış PNG bayt dizisini hedef URI'ye yazar. */
internal fun MainActivity.writeTavernCardToUri(uri: Uri) {
    val bytes = pendingTavernExportBytes ?: return
    pendingTavernExportBytes = null
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: throw Exception("Dosya yazılamadı")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@writeTavernCardToUri, "✅ Karakter kartı dışa aktarıldı", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@writeTavernCardToUri, "Yazma hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
