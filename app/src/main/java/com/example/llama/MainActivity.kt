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

// ── Karakter veri sınıfı ──────────────────────────────────────────────────────
data class MayaCharacter(
    val id: String,
    val name: String,       // {{char}} için
    val userName: String,   // {{user}} için
    val emoji: String,
    val systemPrompt: String
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
        const val THEME_SYSTEM = 0   // Sistem temasını takip et
        const val THEME_DARK   = 1   // Daima karanlık
        const val THEME_LIGHT  = 2   // Daima aydınlık

        /**
         * Seçilen tema modunu AppCompatDelegate'e uygular.
         * Activity yeniden oluşturulmadan hemen etkin olur.
         */
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
    /**
     * Flash Attention modu:
     *   0 → Kapalı
     *   1 → Otomatik (varsayılan — model destekliyorsa açar, llama.cpp -fa auto)
     *   2 → Açık (zorla)
     */
    internal var flashAttnMode: Int = 1  // Varsayılan: Otomatik
    internal var useMmap: Boolean = true
    internal var useMlock: Boolean = false
    internal var bypassContextLength: Boolean = false

    // ── v5.8: Tema ayarı ──────────────────────────────────────────────────────
    /** 0=Sistem, 1=Karanlık, 2=Aydınlık */
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

    internal val currentMessages = mutableListOf<ChatMessage>()

    internal var generationService: MayaForegroundService? = null
    internal var isAppInForeground = true
    internal var tokenUpdateCounter = 0

    internal val skipAutoTitleConvIds: MutableSet<String> = mutableSetOf()

    // ── v4.8: Vision (multimodal) state ──────────────────────────────────────
    internal var selectedImagePath: String? = null
    internal var loadedMmprojPath: String? = null

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

    /** v4.8: Galeri resim seçici */
    internal val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleSelectedImage(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Tema, setContentView'dan ÖNCE uygulanmalı
        val prefs = getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
        appThemeMode = prefs.getInt("app_theme_mode", THEME_SYSTEM)
        applyThemeMode(appThemeMode)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(this)
        engine = InferenceEngineImpl.getInstance(this)

        loadSettings()
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try { checkAndShowPendingDailyReport(intent) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        checkAndShowPendingDailyReport(intent)
    }

    override fun onPause() { super.onPause(); isAppInForeground = false }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
        try { unbindService(serviceConnection) } catch (_: Exception) {}
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_model -> { showModelPickerDialog(); true }
            R.id.action_model_info   -> { showModelInfoDialog(); true }
            R.id.action_export_chat  -> { exportChat(); true }
            R.id.action_clear_chat   -> { clearCurrentChat(); true }
            R.id.action_settings     -> { showSettingsDialog(); true }
            R.id.action_backup       -> { backupChats(); true }
            R.id.action_restore      -> { showRestorePicker(); true }
            R.id.action_logs         -> { showLogsDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
