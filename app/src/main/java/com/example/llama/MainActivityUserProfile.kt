package tr.maya

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// ── Kullanıcı Profilleri: Yönetim Listesi ─────────────────────────────────────

internal fun MainActivity.showUserProfileManagerDialog() {
    val dp = resources.displayMetrics.density
    val isDark = MessageAdapter.isDarkTheme(this)

    val scroll = ScrollView(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
    }
    scroll.addView(container)

    val listContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    container.addView(listContainer)

    fun refresh() {
        val profiles = UserProfile.loadAll(this)
        listContainer.removeAllViews()

        if (profiles.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "Henüz profil yok. Aşağıdan ekleyin."
                textSize = 12f; alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
            })
        }

        profiles.forEach { profile ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dp).toInt() }
                background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(if (isDark) 0xFF20202E.toInt() else 0xFFF0F0FA.toInt())
                }
                setPadding((10 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
                isClickable = true; isFocusable = true
            }

            val avatarImg = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
                    .apply { marginEnd = (10 * dp).toInt() }
                visibility = android.view.View.GONE
            }
            val avatarEmoji = TextView(this).apply {
                text = "👤"; textSize = 20f; gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
                    .apply { marginEnd = (10 * dp).toInt() }
            }
            row.addView(avatarImg); row.addView(avatarEmoji)

            val avatarUriLocal = profile.avatarUri
            if (avatarUriLocal != null) {
                val bmp = try {
                    if (avatarUriLocal.startsWith("file:"))
                        BitmapFactory.decodeFile(avatarUriLocal.removePrefix("file:"))
                            ?.let { roundBitmap(it, (40 * dp).toInt()) }
                    else
                        loadRoundedBitmap(Uri.parse(avatarUriLocal), (40 * dp).toInt())
                } catch (_: Exception) { null }
                if (bmp != null) {
                    avatarImg.setImageBitmap(bmp)
                    avatarImg.visibility = android.view.View.VISIBLE
                    avatarEmoji.visibility = android.view.View.GONE
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = profile.name; textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            if (profile.bio.isNotBlank()) {
                textCol.addView(TextView(this).apply {
                    text = profile.bio; textSize = 11f; alpha = 0.65f
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            row.addView(textCol)

            val deleteBtn = TextView(this).apply {
                text = "🗑"; textSize = 16f
                setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                isClickable = true; isFocusable = true
            }
            row.addView(deleteBtn)

            row.setOnClickListener { showUserProfileEditDialog(profile) { refresh() } }
            deleteBtn.setOnClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Profili Sil")
                    .setMessage("\"${profile.name}\" silinsin mi?")
                    .setPositiveButton("Sil") { _, _ ->
                        val updated = UserProfile.loadAll(this).filter { it.id != profile.id }
                        UserProfile.saveAll(this, updated)
                        refresh()
                    }
                    .setNegativeButton("İptal", null).show()
            }

            listContainer.addView(row)
        }
    }
    refresh()

    val addBtn = android.widget.Button(this).apply {
        text = "➕ Yeni Profil"; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() }
        background = GradientDrawable().apply {
            cornerRadius = 8 * dp
            setColor(if (isDark) 0xFF1A3A5C.toInt() else 0xFFDDEEFF.toInt())
        }
        setTextColor(if (isDark) 0xFF88AAFF.toInt() else 0xFF2244AA.toInt())
    }
    addBtn.setOnClickListener { showUserProfileEditDialog(null) { refresh() } }
    container.addView(addBtn)

    container.addView(TextView(this).apply {
        text = "Bu profiller ileride karakterlere atanabilecek (yakında). Şimdilik burada oluşturup düzenleyebilirsiniz."
        textSize = 11f; alpha = 0.55f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() }
    })

    android.app.AlertDialog.Builder(this)
        .setTitle("👥 Kullanıcı Profilleri")
        .setView(scroll)
        .setPositiveButton("Kapat", null)
        .show()
}

// ── Kullanıcı Profili: Düzenleme Diyaloğu ─────────────────────────────────────

internal fun MainActivity.showUserProfileEditDialog(existing: UserProfile?, onSaved: () -> Unit) {
    val dp = resources.displayMetrics.density
    val isNew = existing == null

    val scroll = ScrollView(this)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
    }
    scroll.addView(layout)

    fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt(); bottomMargin = (2 * dp).toInt() }
    }

    var tempAvatarUri: String? = existing?.avatarUri

    val avatarRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8 * dp).toInt() }
    }
    val avatarPreview = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt())
            .apply { marginEnd = (12 * dp).toInt() }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(0xFF2E2E4A.toInt())
        }
        clipToOutline = true
    }

    fun loadAvatarPreview(uriStr: String?) {
        if (uriStr != null) {
            try {
                val bmp = if (uriStr.startsWith("file:")) {
                    BitmapFactory.decodeFile(uriStr.removePrefix("file:"))
                        ?.let { roundBitmap(it, (72 * dp).toInt()) }
                } else {
                    loadRoundedBitmap(Uri.parse(uriStr), (72 * dp).toInt())
                }
                if (bmp != null) { avatarPreview.setImageBitmap(bmp); return }
            } catch (_: Exception) {}
        }
        avatarPreview.setImageDrawable(null)
    }
    loadAvatarPreview(tempAvatarUri)

    val avatarBtnCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val selectAvatarBtn = android.widget.Button(this).apply {
        text = "📷 Fotoğraf Seç"; isAllCaps = false; textSize = 12f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    val removeAvatarBtn = android.widget.Button(this).apply {
        text = "✕ Kaldır"; isAllCaps = false; textSize = 12f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (4 * dp).toInt() }
        visibility = if (tempAvatarUri != null) android.view.View.VISIBLE else android.view.View.GONE
    }
    avatarBtnCol.addView(selectAvatarBtn); avatarBtnCol.addView(removeAvatarBtn)
    avatarRow.addView(avatarPreview); avatarRow.addView(avatarBtnCol)

    selectAvatarBtn.setOnClickListener {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        // Karakter avatar seçici altyapısı yeniden kullanılıyor. "__userprofile__" işaretçisi
        // hiçbir karaktere eşleşmediği için handleCharacterAvatarSelected içindeki karakter
        // güncelleme kısmı sessizce atlanır, sadece aşağıdaki callback çalışır.
        pendingAvatarCharacterId = "__userprofile__"
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

    layout.addView(label("Profil Fotoğrafı"))
    layout.addView(avatarRow)

    layout.addView(label("İsim ({{user}})"))
    val nameField = android.widget.EditText(this).apply {
        setText(existing?.name ?: "")
        hint = "Örn: İş Kimliğim, Gerçek Ben, Takma Ad…"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    layout.addView(nameField)

    layout.addView(label("Bio (isteğe bağlı — karakter senin hakkında bunu bilir)"))
    val bioField = android.widget.EditText(this).apply {
        setText(existing?.bio ?: "")
        hint = "Örn: 28 yaşında, yazılımcı, kedi seviyor…"
        minLines = 2; maxLines = 5; isSingleLine = false
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    layout.addView(bioField)

    val builder = android.app.AlertDialog.Builder(this)
        .setTitle(if (isNew) "➕ Yeni Profil" else "✏️ Profili Düzenle")
        .setView(scroll)
        .setPositiveButton("Kaydet") { _, _ ->
            val name = nameField.text.toString().trim().ifEmpty { "Kullanıcı" }
            val bio  = bioField.text.toString().trim()
            val saved = if (isNew)
                UserProfile(name = name, bio = bio, avatarUri = tempAvatarUri)
            else
                existing!!.copy(name = name, bio = bio, avatarUri = tempAvatarUri)

            val all = UserProfile.loadAll(this)
            val idx = all.indexOfFirst { it.id == saved.id }
            if (idx >= 0) all[idx] = saved else all.add(saved)
            UserProfile.saveAll(this, all)

            pendingAvatarDialogCallback = null
            onSaved()
            Toast.makeText(this, "\"$name\" kaydedildi", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("İptal") { _, _ -> pendingAvatarDialogCallback = null }

    if (!isNew && existing != null) {
        builder.setNeutralButton("🗑 Sil") { _, _ ->
            android.app.AlertDialog.Builder(this)
                .setTitle("Profili Sil")
                .setMessage("\"${existing.name}\" silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    val updated = UserProfile.loadAll(this).filter { it.id != existing.id }
                    UserProfile.saveAll(this, updated)
                    onSaved()
                }
                .setNegativeButton("İptal", null).show()
        }
    }

    builder.show()
}
