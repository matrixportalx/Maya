package tr.maya

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.InferenceEngine
import com.arm.aichat.internal.InferenceEngineImpl
import tr.maya.data.AppDatabase
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

// v4.1 - UB düzeltmesi, StringBuilder, UI throttle, t/s kalıcı kayıt
// v4.5 - Karakter sistemi: drawer'da karakter yönetimi, SharedPreferences tabanlı
// v4.8 - Multimodal (vision) desteği: görüntü seçme, mmproj yükleme, görüntülü sohbet
// v5.1 - İnternet araması: DuckDuckGo, Brave Search API ve SearXNG desteği
// v5.2 - Günlük rapor: arka planda LLM özetleme, WorkManager zamanlayıcı
// v5.4 - MainActivity refaktörü: 9 dosyaya bölündü (extension functions)
// v5.5 - URL okuma: mesajdaki URL'leri otomatik çekip modele iletme
// v5.8 - Tema desteği: Karanlık / Aydınlık / Sistem seçeneği
// v5.9 - Uygulama içi güncelleme: GitHub Releases API
// v6.0 - Avatar desteği: karakter ve kullanıcı için özel fotoğraf
// v6.1 - Dream API entegrasyonu: LocalDream SSE görüntü üretimi
// v6.2 - Mayagram: Maya karakterlerinin sosyal medya akışı
// v6.3 - Tavern karakter kartı içe/dışa aktarma (.png)

// ── Karakter veri sınıfı ──────────────────────────────────────────────────────
data class MayaCharacter(
    val id: String,
    val name: String,       // {{char}} için
    val userName: String,   // {{user}} için
    val emoji: String,
    val systemPrompt: String,        // Geriye dönük uyumluluk — yeni karakterlerde description+personality+scenario'dan türetilir
    val avatarUri: String? = null,   // v6.0: Karakter avatar URI (kalıcı izinli)
    val scenario: String = "",       // v6.3: Tavern "scenario" alanı — kullanıcıyla ilişki/bağlam
    val firstMessage: String = "",   // v6.3: Tavern "first_mes" alanı — henüz otomatik kullanılmıyor
    val description: String = "",    // v6.4: Tavern "description" alanı — bio/görünüm
    val personality: String = ""     // v6.4: Tavern "personality" alanı — kişilik özeti
)

// ── Özel şablon veri sınıfı ──────────────────────────────────────────────────
data class MayaTemplate(
    val id:               String,
    val name:             String,
    val bosToken:         String,
    val sysPrefix:        String,
    val sysSuffix:        String,
    val inputPrefix:      String,
    val inputSuffix:      String,
    val outputPrefix:     String,
    val outputSuffix:     String,
    val lastOutputPrefix: String,
    val stopSeq:          String
)

class MainActivity : AppCompatActivity() {

    companion object {
        private val logBuffer = ArrayDeque<String>(200)
        var loggingEnabled: Boolean = false
        getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("logging_enabled", loggingEnabled).apply()

        /**
         * v6.2: MayagramActivity'nin LLM + Dream API fonksiyonlarına erişebilmesi için
         * mevcut MainActivity örneğine global referans.
         * onResume'da set edilir, onDestroy'da temizlenir.
         */
        @Volatile
        var currentInstance: MainActivity? = null
            private set

        fun log(tag: String, msg: String) {
            if (!loggingEnabled) return
            val entry = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} [$tag] $msg"
            android.util.Log.d(tag, msg)
            synchronized(logBuffer) {
                if (logBuffer.size >= 200) logBuffer.removeFirst()
                logBuffer.addLast(entry)
            }
        }

        fun getLogs(): String = synchronized(logBuffer) { logBuffer.joinToString("\n") }
        fun clearLogs() = synchronized(logBuffer) { logBuffer.clear() }

        fun isUriEntry(entry: String) = entry.startsWith("uri:")
        fun entryToUri(entry: String): Uri = Uri.parse(entry.removePrefix("uri:"))
        fun entryDisplayName(entry: String): String {
            return if (isUriEntry(entry)) {
                entry.removePrefix("uri:").substringAfterLast("%2F").substringAfterLast("/")
                    .let { if (it.isBlank()) entry.substringAfterLast("/") else it }
            } else {
                entry.substringAfterLast("/")
            }
        }

        // ── Tema sabitleri ──────────────────────────────────────────────────────
        const val THEME_SYSTEM = 0
        const val THEME_DARK   = 1
        const val THEME_LIGHT  = 2

        // ── Varsayılan Maya avatarı (gömülü drawable) için özel işaretçi ────────
        // avatarUri alanında normal galeri URI'leri "content://..." şeklindeyken,
        // bu özel string drawable/maya_default_avatar.png'yi işaret eder.
        const val DEFAULT_AVATAR_MARKER = "drawable:maya_default_avatar"
        fun applyThemeMode(mode: Int) {
            val nightMode = when (mode) {
                THEME_DARK  -> AppCompatDelegate.MODE_NIGHT_YES
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else        -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    internal lateinit var drawerLayout: DrawerLayout
    internal var selectedTemplate: Int = 0
    internal lateinit var toolbar: Toolbar
    internal lateinit var messagesRv: RecyclerView
    internal lateinit var messageInput: android.widget.EditText
    internal lateinit var fab: FloatingActionButton
    internal lateinit var conversationsRv: RecyclerView
    internal lateinit var btnNewChat: android.widget.Button
    internal lateinit var charactersRv: RecyclerView
    internal lateinit var btnAddCharacter: android.widget.Button

    // v4.8: Görüntü UI bileşenleri
    internal lateinit var imagePreviewContainer: android.widget.LinearLayout
    internal lateinit var imagePreviewView: android.widget.ImageView
    internal lateinit var imagePreviewLabel: android.widget.TextView
    internal lateinit var btnRemoveImage: android.widget.Button
    internal lateinit var btnAttachImage: android.widget.Button

    internal lateinit var messageAdapter: MessageAdapter
    internal lateinit var conversationAdapter: ConversationAdapter
    internal lateinit var characterAdapter: CharacterAdapter
    internal lateinit var db: AppDatabase
    internal lateinit var engine: InferenceEngine

    internal var currentConversationId: String = ""
    internal var loadedModelPath: String? = null
    internal var isGenerating = false
    internal var generationJob: Job? = null

    internal var autoScroll = true
    internal var isAutoScrolling = false
    internal var scrollPending = false
    internal var userIsScrolling = false

    internal var contextSize: Int = 2048
    internal var predictLength: Int = 512
    internal var systemPrompt: String = ""
    internal var temperature: Float = 0.8f
    internal var topP: Float = 0.95f
    internal var topK: Int = 40
    internal var noThinking: Boolean = false
    internal var autoLoadLastModel: Boolean = false
    internal var flashAttnMode: Int = 1
    internal var useMmap: Boolean = true
    internal var useMlock: Boolean = false
    internal var bypassContextLength: Boolean = false

    // ── v5.8: Tema ayarı ──────────────────────────────────────────────────────
    internal var appThemeMode: Int = THEME_SYSTEM

    // ── v5.1: İnternet araması ────────────────────────────────────────────────
    internal var webSearchMode: String = "off"
    internal val webSearchEnabled: Boolean get() = webSearchMode != "off"
    internal var webSearchQueryMode: String = "smart"
    internal var webSearchTriggers: MutableList<String> = mutableListOf()
    internal var webSearchEngine: String = "duckduckgo"
    internal var braveApiKey: String = ""
    internal var searxngUrl: String = "https://searx.be"
    internal var webSearchResultCount: Int = 5
    internal lateinit var btnWebSearch: android.widget.Button
    internal var lastWebSearchResults: String = ""
    internal var lastWebSearchQuery: String = ""
    internal var webPageFetchEnabled: Boolean = false

    // ── v5.5: URL okuma ───────────────────────────────────────────────────────
    internal var urlFetchEnabled: Boolean = true
    internal var urlFetchCharLimit: Int = 5000

    // ── v5.2: Günlük Rapor ───────────────────────────────────────────────────
    internal var reportProfiles = mutableListOf<ReportProfile>()

    // ── Özel şablon sistemi ───────────────────────────────────────────────────
    internal var customTemplates: MutableList<MayaTemplate> = mutableListOf()
    internal var selectedCustomTemplateId: String? = null

    internal fun activeCustomTemplate(): MayaTemplate? =
        customTemplates.find { it.id == selectedCustomTemplateId }
            ?: customTemplates.firstOrNull()

    // Persona
    internal var charName: String = "Asistan"
    internal var userName: String = "Kullanıcı"

    // ── Karakter sistemi ──────────────────────────────────────────────────────
    internal var characters: MutableList<MayaCharacter> = mutableListOf()
    internal var activeCharacterId: String? = null

    // ── v6.0: Kullanıcı avatarı ───────────────────────────────────────────────
    internal var userAvatarUri: String? = null

    internal val currentMessages = mutableListOf<ChatMessage>()

    internal var generationService: MayaForegroundService? = null
    internal var isAppInForeground = true
    internal var tokenUpdateCounter = 0

    internal val skipAutoTitleConvIds: MutableSet<String> = mutableSetOf()

    // ── v4.8: Vision (multimodal) state ──────────────────────────────────────
    internal var selectedImagePath: String? = null
    internal var loadedMmprojPath: String? = null

    // ── v5.9: Bekleyen güncelleme bilgisi ─────────────────────────────────────
    internal var pendingUpdateInfo: AppUpdater.UpdateInfo? = null

    // ── v6.0: Karakter avatar seçici için bekleyen karakter ID ───────────────
    internal var pendingAvatarCharacterId: String? = null

    // ── v6.1: Dream API ───────────────────────────────────────────────────────
    internal var dreamApiEnabled: Boolean = false
    internal var dreamApiUrl: String = "http://127.0.0.1:8081"
    internal var dreamSize: Int = 512
    internal var dreamSteps: Int = 20
    internal var dreamCfg: Float = 7.0f
    internal var dreamSeed: Long = -1L
    internal var dreamUseOpenCl: Boolean = false
    internal var dreamDefaultNegativePrompt: String = ""

    // ── v6.3: Tavern kartı dışa aktarma — kaydet diyaloğu sonucu beklenen bayt dizisi ──
    internal var pendingTavernExportBytes: ByteArray? = null

    internal val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MayaForegroundService.LocalBinder
            generationService = binder.getService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            generationService = null
        }
    }

    internal val backupSaveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingBackupUri = uri
                pendingBackupCallback?.invoke(uri)
                pendingBackupCallback = null
            }
        }
    }

    internal val backupRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleRestoreFile(uri) }
        }
    }

    internal val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingExportCallback?.invoke(uri)
                pendingExportCallback = null
            }
        }
    }

    internal var pendingBackupUri: Uri? = null
    internal var pendingBackupCallback: ((Uri) -> Unit)? = null
    internal var pendingExportCallback: ((Uri) -> Unit)? = null

    /** Ana model (LLM) dosyası seçici */
    internal val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    log("Maya", "Kalıcı URI izni alınamadı: ${e.message}")
                }

                val entry = "uri:$uri"
                val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
                val models = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableSet()
                models.add(entry)
                prefs.edit().putStringSet("saved_models", models).apply()

                log("Maya", "Model URI ile eklendi: $entry")
                showTemplatePickerDialog(entry)
            }
        }
    }

    /** v4.8: Mmproj dosyası seçici */
    internal val mmprojPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadMmprojFromUri(uri) }
        }
    }

    /** v4.8: Galeri resim seçici (sohbet görseli) */
    internal val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleSelectedImage(uri) }
        }
    }

    /** v6.0: Karakter avatar seçici */
    internal val characterAvatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleCharacterAvatarSelected(uri, pendingAvatarCharacterId)
                pendingAvatarCharacterId = null
            }
        }
    }

    /** v6.0: Kullanıcı avatar seçici */
    internal val userAvatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleUserAvatarSelected(uri)
            }
        }
    }

    /** v6.3: Tavern karakter kartı (.png) seçici — içe aktarma */
    internal val tavernCardPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleTavernCardSelected(uri) }
        }
    }

    /** v6.3: Tavern karakter kartı (.png) kaydetme — dışa aktarma */
    internal val tavernCardSaveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> writeTavernCardToUri(uri) }
        } else {
            pendingTavernExportBytes = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        loggingEnabled = prefs.getBoolean("logging_enabled", false)
        appThemeMode = prefs.getInt("app_theme_mode", THEME_SYSTEM)
        applyThemeMode(appThemeMode)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(this)
        engine = InferenceEngineImpl.getInstance(this)

        // versionName'i prefs'e kaydet (AppUpdater için)
        val currentVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
        prefs.edit().putString("app_version_name", currentVersionName).apply()

        loadSettings()
        loadDreamSettings()           // v6.1: Dream API ayarları
        migrateModelsFromCacheToFilesDir()
        cleanupMissingModels()
        bindViews()
        setupToolbar()
        setupDrawer()
        setupCharacters()
        setupMessageList()
        setupConversationList()
        setupFab()
        setupInput()
        setupImageAttach()
        setupWebSearch()
        observeConversations()

        lifecycleScope.launch { ensureActiveConversation() }

        if (autoLoadLastModel) {
            val lastEntry = prefs.getString("last_loaded_model", null)
            if (lastEntry != null) {
                val savedModels = prefs.getStringSet("saved_models", mutableSetOf())!!
                if (savedModels.contains(lastEntry)) {
                    val modelKey = "template_${entryDisplayName(lastEntry)}"
                    selectedTemplate = prefs.getInt(modelKey, 0)
                    loadModel(lastEntry)
                }
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        // ── v5.9: Sessiz güncelleme kontrolü ─────────────────────────────────
        checkForUpdateSilently()
        updateActiveModelSubtitle()
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        // v6.2: Mayagram için instance referansını kaydet
        currentInstance = this
        checkAndShowPendingDailyReport(intent)
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try { checkAndShowPendingDailyReport(intent) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // v6.2: Instance referansını temizle
        if (currentInstance === this) currentInstance = null
        engine.destroy()
        try { unbindService(serviceConnection) } catch (_: Exception) {}
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        // Bekleyen güncelleme varsa menü öğesini vurgula
        val updateItem = menu.findItem(R.id.action_update)
        if (pendingUpdateInfo != null) {
            updateItem?.title = "🆕 Güncelleme Mevcut! (${pendingUpdateInfo!!.versionName})"
        }

        // v6.1: Dream API etkin değilse menü öğesini gizle
        menu.findItem(R.id.action_dream)?.isVisible = dreamApiEnabled

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search       -> { showSearchOverlay(); true }
            R.id.action_mayagram     -> { openMayagram(); true }          // v6.2
            R.id.action_change_model -> { showModelPickerDialog(); true }
            R.id.action_model_info   -> { showModelInfoDialog(); true }
            R.id.action_export_chat  -> { exportChat(); true }
            R.id.action_clear_chat   -> { clearCurrentChat(); true }
            R.id.action_settings     -> { showSettingsDialog(); true }
            R.id.action_backup       -> { backupChats(); true }
            R.id.action_restore      -> { showRestorePicker(); true }
            R.id.action_update       -> {
                val pending = pendingUpdateInfo
                if (pending != null) showUpdateDialog(pending)
                else checkForUpdateNow()
                true
            }
            R.id.action_logs         -> { showLogsDialog(); true }
            R.id.action_dream        -> { showDreamApiDialog(); true }   // v6.1
            else -> super.onOptionsItemSelected(item)
        }
    }
}
