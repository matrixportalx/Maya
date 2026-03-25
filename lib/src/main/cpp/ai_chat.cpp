#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

// ── v4.8 Multimodal: yeni mtmd API (llava.h/clip.h kaldırıldı) ─────────────
#include "mtmd.h"
#include "mtmd-helper.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 2048;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.8f;
constexpr float DEFAULT_SAMPLER_TOP_P   = 0.95f;
constexpr int   DEFAULT_SAMPLER_TOP_K   = 40;

static int   g_context_size = DEFAULT_CONTEXT_SIZE;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;

// ── Multimodal global state (mtmd) ────────────────────────────────────────────
static mtmd_context * g_mtmd_ctx = nullptr;

// ── Sohbet durumu ─────────────────────────────────────────────────────────────
static int  current_position         = 0;
static int  system_prompt_position   = 0;
static int  stop_generation_position = 0;
static std::string         cached_token_chars;
static std::ostringstream  assistant_ss;
// Görüntü embed edildikten sonra true olur; processUserPrompt marker'ı mesaja ekler.
static bool g_image_just_embedded = false;
// Qwen3 vb. Jinja şablonu generation prompt'una "<think>" eklediğinde Kotlin'e enjekte edilir.
static std::string g_response_prefix;

static const char *ROLE_SYSTEM    = "system";
static const char *ROLE_USER      = "user";
static const char *ROLE_ASSISTANT = "assistant";

// ─────────────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    llama_log_set(aichat_android_log_callback, nullptr);
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path,
                                                       jboolean use_mmap, jboolean use_mlock) {
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap  = (bool) use_mmap;
    model_params.use_mlock = (bool) use_mlock;
    LOGi("load(): use_mmap=%d use_mlock=%d", (int)use_mmap, (int)use_mlock);

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_loadFromFd(JNIEnv *env, jobject, jint fd, jstring jmodel_name) {
    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);

    const auto *model_name = env->GetStringUTFChars(jmodel_name, 0);
    LOGd("%s: Loading model via fd %d (%s) from %s", __func__, fd, model_name, fd_path);
    env->ReleaseStringUTFChars(jmodel_name, model_name);

    llama_model_params model_params = llama_model_default_params();
    auto *model = llama_model_load_from_file(fd_path, model_params);
    if (!model) {
        LOGe("%s: Failed for fd path: %s", __func__, fd_path);
        return 1;
    }
    g_model = model;
    return 0;
}

// ── Mmproj (vision projector) yükleme / boşaltma — mtmd API ──────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeLoadMmproj(JNIEnv *env, jobject, jstring jpath) {
    if (!g_model) {
        LOGe("nativeLoadMmproj: LLM modeli henüz yüklenmedi!");
        return 2;
    }

    const auto *path = env->GetStringUTFChars(jpath, nullptr);
    LOGi("nativeLoadMmproj: loading from %s", path);

    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }

    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                          (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));

    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu       = false;
    mparams.print_timings = false;
    mparams.n_threads     = n_threads;

    auto *ctx = mtmd_init_from_file(path, g_model, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!ctx) {
        LOGe("nativeLoadMmproj: mtmd_init_from_file başarısız");
        return 1;
    }
    g_mtmd_ctx = ctx;
    LOGi("nativeLoadMmproj: mmproj yüklendi. vision=%d audio=%d",
         (int)mtmd_support_vision(g_mtmd_ctx),
         (int)mtmd_support_audio(g_mtmd_ctx));
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeUnloadMmproj(JNIEnv * /*env*/, jobject /*unused*/) {
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
        LOGi("nativeUnloadMmproj: mmproj kaldırıldı");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeIsMmprojLoaded(JNIEnv * /*env*/, jobject /*unused*/) {
    return (g_mtmd_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// ── Görüntü yerleştirme (image embed) — mtmd API ─────────────────────────────

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeProcessImageEmbed(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jimagePath
) {
    if (!g_mtmd_ctx) {
        LOGe("nativeProcessImageEmbed: mmproj yüklü değil");
        return 1;
    }
    if (!g_context) {
        LOGe("nativeProcessImageEmbed: LLM context hazır değil");
        return 2;
    }

    const auto *image_path = env->GetStringUTFChars(jimagePath, nullptr);
    LOGi("nativeProcessImageEmbed: %s", image_path);

    mtmd_bitmap * bmp = mtmd_helper_bitmap_init_from_file(g_mtmd_ctx, image_path);
    env->ReleaseStringUTFChars(jimagePath, image_path);

    if (!bmp) {
        LOGe("nativeProcessImageEmbed: mtmd_helper_bitmap_init_from_file başarısız");
        return 3;
    }

    const char * marker = mtmd_default_marker();
    mtmd_input_text text_input;
    text_input.text          = marker;
    text_input.add_special   = false;
    text_input.parse_special = true;

    const mtmd_bitmap * bitmaps[1] = { bmp };

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    int32_t res = mtmd_tokenize(g_mtmd_ctx, chunks, &text_input, bitmaps, 1);

    mtmd_bitmap_free(bmp);

    if (res != 0) {
        LOGe("nativeProcessImageEmbed: mtmd_tokenize başarısız, res=%d", res);
        mtmd_input_chunks_free(chunks);
        return 4;
    }

    llama_pos new_n_past = 0;
    int eval_res = mtmd_helper_eval_chunks(
        g_mtmd_ctx,
        g_context,
        chunks,
        (llama_pos) current_position,
        0,
        BATCH_SIZE,
        false,
        &new_n_past
    );

    mtmd_input_chunks_free(chunks);

    if (eval_res != 0) {
        LOGe("nativeProcessImageEmbed: mtmd_helper_eval_chunks başarısız, res=%d", eval_res);
        return 5;
    }

    current_position = (int) new_n_past;
    g_image_just_embedded = true;
    LOGi("nativeProcessImageEmbed: görüntü gömüldü, new_position=%d", current_position);
    return 0;
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Flash Attention mod değerini llama_flash_attn_type enum'una çevirir.
 *
 * Kotlin tarafından gelen int değeri (flashAttnMode):
 *   0 → LLAMA_FLASH_ATTN_TYPE_DISABLED  (Kapalı — hiçbir zaman kullanma)
 *   1 → LLAMA_FLASH_ATTN_TYPE_AUTO      (Otomatik — model destekliyorsa aç, varsayılan)
 *   2 → LLAMA_FLASH_ATTN_TYPE_ENABLED   (Açık — model desteklemese de zorla aç)
 *
 * Bilinmeyen değer güvenli varsayılan olan AUTO'ya düşer.
 */
static llama_flash_attn_type flash_attn_mode_to_type(int mode) {
    switch (mode) {
        case 0:  return LLAMA_FLASH_ATTN_TYPE_DISABLED;
        case 2:  return LLAMA_FLASH_ATTN_TYPE_ENABLED;
        default: return LLAMA_FLASH_ATTN_TYPE_AUTO;  // 1 = Otomatik
    }
}

/**
 * llama_context oluşturur.
 * flash_attn_mode: 0=Kapalı, 1=Otomatik (varsayılan), 2=Açık
 */
static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE,
                                   const int flash_attn_mode = 1 /* AUTO */) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx           = n_ctx;
    ctx_params.flash_attn_type = flash_attn_mode_to_type(flash_attn_mode);
    ctx_params.n_batch         = BATCH_SIZE;
    ctx_params.n_ubatch        = BATCH_SIZE;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    const char* fa_names[] = {"Kapalı", "Otomatik", "Açık"};
    const char* fa_name = (flash_attn_mode >= 0 && flash_attn_mode <= 2)
                          ? fa_names[flash_attn_mode] : "Otomatik";
    LOGi("%s: flash_attn_mode=%d (%s)", __func__, flash_attn_mode, fa_name);

    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp, float top_p, int top_k) {
    common_params_sampling sparams;
    sparams.temp           = temp;
    sparams.top_p          = top_p;
    sparams.top_k          = top_k;
    sparams.penalty_repeat = 1.1f;
    sparams.penalty_last_n = 64;
    LOGi("Sampler: temp=%.2f top_p=%.2f top_k=%d repeat_penalty=%.2f",
         temp, top_p, top_k, sparams.penalty_repeat);
    return common_sampler_init(g_model, sparams);
}

/**
 * Model context'ini hazırlar.
 * flash_attn_mode: 0=Kapalı, 1=Otomatik, 2=Açık
 * Önceki sürümde jboolean olan parametre artık jint.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(
        JNIEnv * /*env*/,
        jobject /*unused*/,
        jint n_ctx,
        jfloat temperature,
        jfloat top_p,
        jint top_k,
        jint flash_attn_mode
) {
    g_context_size = n_ctx;
    LOGi("prepare(): context_size=%d temp=%.2f top_p=%.2f top_k=%d flash_attn_mode=%d",
         g_context_size, temperature, top_p, top_k, (int)flash_attn_mode);

    auto *context = init_context(g_model, g_context_size, (int)flash_attn_mode);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(temperature, top_p, top_k);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    // Benchmark için AUTO modunda context oluştur
    auto *context = init_context(g_model, pp, 1 /* AUTO */);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }
            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = (double) (t_pp_end - t_pp_start) / 1e6;
        const auto t_tg = (double) (t_tg_end - t_tg_start) / 1e6;

        const auto speed_pp = (double) pp / t_pp;
        const auto speed_tg = (double) tg / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    pp_avg /= (double) nr;
    tg_avg /= (double) nr;

    if (nr > 1) {
        pp_std = sqrt(pp_std / (double) nr - pp_avg * pp_avg);
        tg_std = sqrt(tg_std / (double) nr - tg_avg * tg_avg);
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    std::ostringstream result;
    result << std::setprecision(2);
    result << "| model | size | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << llama_model_size(g_model) << "B | " << get_backend() << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << llama_model_size(g_model) << "B | " << get_backend() << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";

    llama_free(context);

    return env->NewStringUTF(result.str().c_str());
}

// ── Sohbet yardımcıları ───────────────────────────────────────────────────────

static std::string chat_add_and_format(const char *role, const std::string &content) {
    common_chat_msg message;
    message.role    = role;
    message.content = content;

    common_chat_templates_inputs inputs;
    inputs.messages.push_back(message);
    inputs.add_generation_prompt = (std::string(role) == ROLE_USER);

    try {
        auto result = common_chat_templates_apply(g_chat_templates.get(), inputs);
        LOGd("formatted (%s): '%s'", role, result.prompt.c_str());
        return result.prompt;
    } catch (const std::exception &e) {
        // Qwen3.5 gibi modellerin Jinja şablonu raise_exception() içerebilir.
        // Bu durumda şablonu atla ve ham içeriği döndür — uygulama çökmez.
        LOGw("chat_add_and_format (%s): Jinja exception yakalandı, ham içerik kullanılıyor. Hata: %s", role, e.what());
        return content;
    } catch (...) {
        LOGw("chat_add_and_format (%s): Bilinmeyen exception yakalandı, ham içerik kullanılıyor.", role);
        return content;
    }
}

static void reset_long_term_states() {
    current_position = 0;
    system_prompt_position = 0;
    stop_generation_position = 0;
}

static void reset_short_term_states() {
    cached_token_chars.clear();
    assistant_ss.str("");
    g_response_prefix.clear();
    g_image_just_embedded = false;
}

static void shift_context() {
    LOGi("%s: Shifting context. Current pos: %d, system_prompt_pos: %d",
         __func__, current_position, system_prompt_position);

    auto *memory = llama_get_memory(g_context);
    if (!memory) { LOGe("%s: llama_get_memory() returned null!", __func__); return; }

    const int keep_tokens = system_prompt_position;
    const int n_discard   = (current_position - keep_tokens) / 2;

    LOGi("%s: Discarding %d tokens (keeping first %d system tokens)", __func__, n_discard, keep_tokens);

    llama_memory_seq_rm(memory, 0, keep_tokens, keep_tokens + n_discard);
    llama_memory_seq_add(memory, 0, keep_tokens + n_discard, current_position, -n_discard);

    current_position -= n_discard;
    stop_generation_position = std::max(stop_generation_position - n_discard, current_position + 1);

    LOGi("%s: Context shifted. New pos: %d", __func__, current_position);
    cached_token_chars.clear();
    assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);

    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        if (start_pos + i + cur_batch_size >= g_context_size - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    reset_long_term_states();
    reset_short_term_states();

    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, formatted_system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               /* add_special= */ false,
                                               /* parse_special= */ true);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    const int max_batch_size = g_context_size - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    reset_short_term_states();

    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Görüntü embed edildiyse marker'ı şablona göre doğru yere ekle.
    // Kotlin buildVisionPrompt() sadece tek turn gönderir (tam geçmiş değil),
    // bu sayede görüntü tokenları ile metin prompt'u sıralı ve tutarlı olur.
    if (g_image_just_embedded) {
        const char* marker_cstr = mtmd_default_marker();
        const std::string marker_str = std::string(marker_cstr) + "\n";
        bool inserted = false;

        // Şablona göre ilk user turn pattern'ını bul ve marker'ı hemen arkasına ekle.
        const std::vector<std::string> user_patterns = {
            "<start_of_turn>user\n",                          // Gemma (template=3)
            "<|im_start|>user\n",                             // ChatML (template=2)
            "<|start_header_id|>user<|end_header_id|>\n\n",  // Llama3 (template=4)
            "<|USER_TOKEN|>"                                   // Aya/Command-R (template=1)
        };
        for (const auto& pattern : user_patterns) {
            const auto pos = formatted_user_prompt.find(pattern);
            if (pos != std::string::npos) {
                formatted_user_prompt.insert(pos + pattern.size(), marker_str);
                inserted = true;
                LOGi("processUserPrompt: image marker inserted after '%s'", pattern.c_str());
                break;
            }
        }
        if (!inserted) {
            // Template=0: düz metin, Jinja şablonu halleder, marker başa eklenir.
            formatted_user_prompt = marker_str + formatted_user_prompt;
            LOGi("processUserPrompt: image marker prepended (template=0)");
        }
        g_image_just_embedded = false;
    }

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        // DÜZELTME: user_prompt (serbest bırakılmış pointer) yerine
        // formatted_user_prompt (kopyalanmış std::string) kullanılıyor.
        formatted_user_prompt = chat_add_and_format(ROLE_USER, formatted_user_prompt);

        // Jinja şablonu (Qwen3 vb.) generation prompt'una "<think>" ekleyebilir.
        // Bu durumda model <think> tag'i olmadan başlar; Kotlin parseThinking bunu yakalayamaz.
        // Çözüm: şablon sonucunun <think> ile bitip bitmediğini kontrol et, bittiyse
        // g_response_prefix'e "<think>" kaydet; ilk token'da Kotlin'e enjekte edilir.
        const std::string think_tag = "<think>";
        const std::string think_tag_nl = "<think>\n";
        const auto& fp = formatted_user_prompt;
        if (fp.size() >= think_tag_nl.size() &&
            fp.compare(fp.size() - think_tag_nl.size(), think_tag_nl.size(), think_tag_nl) == 0) {
            g_response_prefix = think_tag;
            LOGi("processUserPrompt: <think> prefix detected in template, will inject.");
        } else if (fp.size() >= think_tag.size() &&
                   fp.compare(fp.size() - think_tag.size(), think_tag.size(), think_tag) == 0) {
            g_response_prefix = think_tag;
            LOGi("processUserPrompt: <think> prefix detected in template, will inject.");
        }
    }

    auto user_tokens = common_tokenize(g_context, formatted_user_prompt,
                                       /* add_special= */ false,
                                       /* parse_special= */ true);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = g_context_size - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed! context_size=%d current_pos=%d tokens=%d",
             __func__, g_context_size, current_position, user_prompt_size);
        return 2;
    }

    current_position += user_prompt_size;
    stop_generation_position = current_position + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

// ── Bypass Context Length — yardımcı JNI fonksiyonlar ────────────────────────

/**
 * Mevcut KV cache kullanımını (token sayısı olarak current_position) döner.
 * Kotlin tarafı bu değeri okuyarak bypass kararı verir.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeGetContextUsage(
        JNIEnv * /*env*/, jobject /*unused*/) {
    return (jint) current_position;
}

/**
 * KV cache'i tamamen sıfırlar ve tüm pozisyon sayaçlarını sıfıra çeker.
 * Bypass modunda her turda yeni encode işleminden önce çağrılır.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeResetContext(
        JNIEnv * /*env*/, jobject /*unused*/) {
    if (g_context) {
        auto *memory = llama_get_memory(g_context);
        if (memory) llama_memory_clear(memory, true);
    }
    reset_long_term_states();
    reset_short_term_states();
    LOGi("nativeResetContext: KV cache temizlendi, pozisyonlar sıfırlandı");
}

/**
 * Önceden tam olarak formatlanmış bir promptu (bypass modu) KV cache'e encode eder.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeEncodeBypassPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jtext,
        jint    n_predict
) {
    reset_short_term_states();

    const auto *text_cstr = env->GetStringUTFChars(jtext, nullptr);
    std::string formatted(text_cstr);
    env->ReleaseStringUTFChars(jtext, text_cstr);

    // Bypass prompt <think> suffix tespiti
    {
        const std::string think_tag    = "<think>";
        const std::string think_tag_nl = "<think>\n";
        if (formatted.size() >= think_tag_nl.size() &&
            formatted.compare(formatted.size() - think_tag_nl.size(),
                              think_tag_nl.size(), think_tag_nl) == 0) {
            g_response_prefix = think_tag;
            LOGi("nativeEncodeBypassPrompt: <think> prefix tespit edildi, enjekte edilecek.");
        } else if (formatted.size() >= think_tag.size() &&
                   formatted.compare(formatted.size() - think_tag.size(),
                                     think_tag.size(), think_tag) == 0) {
            g_response_prefix = think_tag;
            LOGi("nativeEncodeBypassPrompt: <think> prefix tespit edildi, enjekte edilecek.");
        }
    }

    auto tokens = common_tokenize(g_context, formatted,
                                  /* add_special= */ false,
                                  /* parse_special= */ true);

    LOGi("nativeEncodeBypassPrompt: %d token, n_predict=%d", (int)tokens.size(), n_predict);

    // İkinci katman güvence: context dolmaması için token sayısını kontrol et.
    const int max_tokens = g_context_size - OVERFLOW_HEADROOM - n_predict - 64;
    if (max_tokens > 0 && (int)tokens.size() > max_tokens) {
        tokens.erase(tokens.begin(), tokens.begin() + ((int)tokens.size() - max_tokens));
        LOGw("nativeEncodeBypassPrompt: %d token'a kırpıldı (max=%d)", (int)tokens.size(), max_tokens);
    }

    // Pozisyon 0'dan başlayarak decode et (KV cache zaten sıfırlandı).
    if (decode_tokens_in_batches(g_context, g_batch, tokens, 0, /* compute_last_logit= */ true)) {
        LOGe("nativeEncodeBypassPrompt: decode_tokens_in_batches başarısız");
        return 1;
    }

    current_position         = (int) tokens.size();
    system_prompt_position   = 0;  // Bypass modunda ayrı sistem promptu takibi yok
    stop_generation_position = current_position + n_predict;

    LOGi("nativeEncodeBypassPrompt: encode tamamlandı — pos=%d stop=%d",
         current_position, stop_generation_position);
    return 0;
}

// ─────────────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    if (current_position >= g_context_size - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    // Thinking prefix enjeksiyonu: şablon <think> ile bittiyse Kotlin'e bildir
    if (!g_response_prefix.empty()) {
        const std::string prefix = g_response_prefix;
        g_response_prefix.clear();
        assistant_ss << prefix;
        LOGi("generateNextToken: injecting response prefix '%s'", prefix.c_str());
        return env->NewStringUTF(prefix.c_str());
    }

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    current_position++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        // NOT: chat_add_and_format(ROLE_ASSISTANT, ...) çağrısı kasıtlı kaldırıldı.
        // Jinja tabanlı şablonlarda (Qwen3, Gemma, ChatML, Llama3 vb.) bu çağrı
        // common_chat_templates_apply içinde çöküşe neden oluyordu.
        // Sohbet geçmişi Kotlin/Room DB tarafında yönetildiğinden bu çağrı zaten gereksizdi.
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    reset_long_term_states();
    reset_short_term_states();

    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);

    g_sampler  = nullptr;
    g_context  = nullptr;
    g_model    = nullptr;
    // Not: mmproj (g_mtmd_ctx) kasıtlı olarak unload'da serbest bırakılmıyor.
    // Aynı mmproj farklı modellerle kullanılabilir. shutdown()'da serbest bırakılır.
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    llama_backend_free();
}
