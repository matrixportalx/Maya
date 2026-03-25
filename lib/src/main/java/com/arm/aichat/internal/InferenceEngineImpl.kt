package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.internal.InferenceEngineImpl.Companion.getInstance
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        // ── Flash Attention mod sabitleri ─────────────────────────────────────
        /** Flash Attention kapalı — hiçbir zaman kullanma */
        const val FLASH_ATTN_DISABLED = 0
        /** Flash Attention otomatik — model destekliyorsa açar (varsayılan, llama.cpp -fa auto) */
        const val FLASH_ATTN_AUTO     = 1
        /** Flash Attention açık — desteklemese bile zorla */
        const val FLASH_ATTN_ENABLED  = 2

        @Volatile
        private var instance: InferenceEngineImpl? = null

        fun getInstance(context: Context): InferenceEngineImpl =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    InferenceEngineImpl(nativeLibDir).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }

    private var cfgContextSize:   Int     = 2048
    private var cfgTemperature:   Float   = 0.8f
    private var cfgTopP:          Float   = 0.95f
    private var cfgTopK:          Int     = 40
    /**
     * Flash Attention modu:
     *   FLASH_ATTN_DISABLED (0) → Kapalı
     *   FLASH_ATTN_AUTO     (1) → Otomatik (varsayılan — llama.cpp -fa auto ile aynı)
     *   FLASH_ATTN_ENABLED  (2) → Açık (zorla)
     */
    private var cfgFlashAttnMode: Int     = FLASH_ATTN_AUTO
    private var cfgUseMmap:       Boolean = true
    private var cfgUseMlock:      Boolean = false

    /**
     * Çalışma zamanı ayarlarını günceller.
     *
     * [flashAttnMode]:
     *   [FLASH_ATTN_DISABLED] (0) → Kapalı
     *   [FLASH_ATTN_AUTO]     (1) → Otomatik (varsayılan)
     *   [FLASH_ATTN_ENABLED]  (2) → Açık (zorla)
     */
    fun applySettings(
        contextSize:   Int,
        temperature:   Float,
        topP:          Float,
        topK:          Int,
        flashAttnMode: Int     = FLASH_ATTN_AUTO,
        useMmap:       Boolean = true,
        useMlock:      Boolean = false
    ) {
        cfgContextSize   = contextSize
        cfgTemperature   = temperature
        cfgTopP          = topP
        cfgTopK          = topK
        cfgFlashAttnMode = flashAttnMode.coerceIn(FLASH_ATTN_DISABLED, FLASH_ATTN_ENABLED)
        cfgUseMmap       = useMmap
        cfgUseMlock      = useMlock

        val modeNames = arrayOf("Kapalı", "Otomatik", "Açık")
        val modeName  = modeNames.getOrElse(cfgFlashAttnMode) { "Otomatik" }
        Log.i(TAG, "Settings applied: ctx=$contextSize temp=$temperature topP=$topP topK=$topK " +
            "flashAttn=$modeName($cfgFlashAttnMode) mmap=$useMmap mlock=$useMlock")
    }

    // ── JNI: temel fonksiyonlar ───────────────────────────────────────────────
    @FastNative private external fun init(nativeLibDir: String)
    @FastNative private external fun load(modelPath: String, useMmap: Boolean, useMlock: Boolean): Int
    @FastNative private external fun loadFromFd(fd: Int, modelName: String): Int
    /**
     * [flashAttnMode]: 0=Kapalı, 1=Otomatik, 2=Açık
     * C++ tarafı jint olarak alır ve LLAMA_FLASH_ATTN_TYPE_* enum'una dönüştürür.
     */
    @FastNative private external fun prepare(
        contextSize:   Int,
        temperature:   Float,
        topP:          Float,
        topK:          Int,
        flashAttnMode: Int
    ): Int
    @FastNative private external fun systemInfo(): String
    @FastNative private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String
    @FastNative private external fun processSystemPrompt(systemPrompt: String): Int
    @FastNative private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    @FastNative private external fun generateNextToken(): String?
    @FastNative private external fun unload()
    @FastNative private external fun shutdown()

    // ── JNI: multimodal (vision) fonksiyonlar ────────────────────────────────
    @FastNative private external fun nativeLoadMmproj(path: String): Int
    @FastNative private external fun nativeUnloadMmproj()
    @FastNative private external fun nativeIsMmprojLoaded(): Boolean
    @FastNative private external fun nativeProcessImageEmbed(imagePath: String): Int

    // ── JNI: Bypass Context Length ────────────────────────────────────────────
    @FastNative private external fun nativeGetContextUsage(): Int
    @FastNative private external fun nativeResetContext()
    @FastNative private external fun nativeEncodeBypassPrompt(text: String, nPredict: Int): Int

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false
    @Volatile private var _cancelGeneration = false

    /** Mmproj yüklü mü — MainActivity bu değeri okur */
    @Volatile var isMmprojLoaded: Boolean = false
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("ai-chat")
                init(nativeLibDir)
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
        }
    }

    override suspend fun loadModel(pathToModel: String) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                load(pathToModel, cfgUseMmap, cfgUseMlock).let {
                    if (it != 0) throw UnsupportedArchitectureException()
                }
                prepare(cfgContextSize, cfgTemperature, cfgTopP, cfgTopK, cfgFlashAttnMode).let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                Log.i(TAG, "Model loaded!")
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    suspend fun loadModelFromFd(fd: Int, modelName: String) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                Log.i(TAG, "Loading model via fd=$fd name=$modelName")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                loadFromFd(fd, modelName).let {
                    if (it != 0) throw UnsupportedArchitectureException()
                }
                prepare(cfgContextSize, cfgTemperature, cfgTopP, cfgTopK, cfgFlashAttnMode).let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                Log.i(TAG, "Model loaded via fd!")
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model via fd: ${e.message}", e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    // ── Mmproj yükleme / boşaltma ─────────────────────────────────────────────

    suspend fun loadMmprojModel(path: String): Boolean = withContext(llamaDispatcher) {
        Log.i(TAG, "loadMmprojModel: $path")
        File(path).let {
            if (!it.exists() || !it.canRead()) {
                Log.e(TAG, "loadMmprojModel: file not accessible: $path")
                return@withContext false
            }
        }
        val result = nativeLoadMmproj(path)
        isMmprojLoaded = (result == 0)
        if (isMmprojLoaded) Log.i(TAG, "loadMmprojModel: success")
        else Log.e(TAG, "loadMmprojModel: nativeLoadMmproj returned $result")
        isMmprojLoaded
    }

    fun unloadMmprojSync() {
        runBlocking(llamaDispatcher) {
            nativeUnloadMmproj()
            isMmprojLoaded = false
            Log.i(TAG, "unloadMmproj: done")
        }
    }

    // ── Sistem promptu ────────────────────────────────────────────────────────

    override suspend fun setSystemPrompt(prompt: String) =
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(prompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "System prompt processed! Awaiting user prompt...")
            _state.value = InferenceEngine.State.ModelReady
        }

    // ── Metin üretimi (görüntüsüz) ────────────────────────────────────────────

    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            Log.i(TAG, "Sending user prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            processUserPrompt(message, predictLength).let { result ->
                if (result != 0) {
                    Log.e(TAG, "Failed to process user prompt: $result")
                    return@flow
                }
            }

            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { token ->
                    if (token.isNotEmpty()) emit(token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    // ── Görüntülü metin üretimi ───────────────────────────────────────────────

    fun sendUserPromptWithImage(
        message: String,
        imagePath: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        require(imagePath.isNotEmpty()) { "Image path cannot be empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }
        check(isMmprojLoaded) {
            "Mmproj (vision projector) modeli yüklü değil! Model bilgisi menüsünden mmproj seçin."
        }

        try {
            Log.i(TAG, "sendUserPromptWithImage: embedding image from $imagePath")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            val embedResult = nativeProcessImageEmbed(imagePath)
            if (embedResult != 0) {
                Log.e(TAG, "Image embed failed with code $embedResult")
                _state.value = InferenceEngine.State.ModelReady
                return@flow
            }

            processUserPrompt(message, predictLength).let { result ->
                if (result != 0) {
                    Log.e(TAG, "Failed to process user prompt after image embed: $result")
                    _state.value = InferenceEngine.State.ModelReady
                    return@flow
                }
            }

            Log.i(TAG, "Image + user prompt processed. Generating response...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { token ->
                    if (token.isNotEmpty()) emit(token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Image generation aborted per request.")
            } else {
                Log.i(TAG, "Image generation complete.")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Image generation flow cancelled.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during image generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    // ── Bypass Context Length ─────────────────────────────────────────────────

    fun getContextUsage(): Int = nativeGetContextUsage()

    fun sendBypassPrompt(
        fullFormattedText: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(fullFormattedText.isNotEmpty()) { "Bypass prompt boş olamaz!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Bypass prompt reddedildi: ${_state.value.javaClass.simpleName}"
        }

        try {
            Log.i(TAG, "Bypass: context sıfırlanıyor ve yeniden encode ediliyor " +
                    "(${fullFormattedText.length} karakter)...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            nativeResetContext()

            nativeEncodeBypassPrompt(fullFormattedText, predictLength).let { result ->
                if (result != 0) {
                    Log.e(TAG, "nativeEncodeBypassPrompt başarısız: $result")
                    _state.value = InferenceEngine.State.ModelReady
                    return@flow
                }
            }

            Log.i(TAG, "Bypass: encode tamamlandı, üretim başlıyor...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { token ->
                    if (token.isNotEmpty()) emit(token)
                } ?: break
            }
            if (_cancelGeneration) Log.i(TAG, "Bypass üretimi kullanıcı tarafından durduruldu.")
            else Log.i(TAG, "Bypass üretimi tamamlandı.")
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Bypass flow koleksiyonu iptal edildi.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Bypass üretiminde hata!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    // ── Benchmark ─────────────────────────────────────────────────────────────

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    // ── Temizlik ──────────────────────────────────────────────────────────────

    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }
                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }
                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
