#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "LocaleAI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static std::mutex g_model_mutex;
static std::atomic<bool> g_cancel{false};

// Usate solo quando il GGUF non ha un chat template riconosciuto.
static const char *kFallbackStops[] = {"\nUtente:", "\nUser:", "\nTu:"};

static void llama_log_to_android(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", text);
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    llama_log_set(llama_log_to_android, nullptr);
    llama_backend_init();
    return JNI_VERSION_1_6;
}

static std::string from_java_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    // GetStringUTFChars restituisce Modified UTF-8: per emoji e caratteri
    // fuori dal BMP convertiamo esplicitamente da UTF-16 a UTF-8 standard.
    const jsize len = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};

    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len &&
            chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

static void throw_java(JNIEnv *env, const char *class_name, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(class_name);
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

/*
 * Invia i byte del token come byte[], senza NewStringUTF: i token possono
 * contenere UTF-8 incompleto. Restituisce false se la callback ha lanciato.
 */
static bool send_bytes(JNIEnv *env, jobject callback, jmethodID method,
                       const char *data, size_t length) {
    if (length == 0) return true;

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(length));
    if (bytes == nullptr) {
        env->ExceptionClear();
        return false;
    }
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length),
                            reinterpret_cast<const jbyte *>(data));
    if (!env->ExceptionCheck()) {
        env->CallVoidMethod(callback, method, bytes);
    }
    env->DeleteLocalRef(bytes);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

static void free_model_locked() {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_it_localeai_NativeLlm_loadModel(
    JNIEnv *env, jobject, jstring java_path, jint context_size, jint threads) {

    std::lock_guard<std::mutex> lock(g_model_mutex);
    free_model_locked();
    g_cancel.store(false);

    const std::string path = from_java_string(env, java_path);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr) {
        LOGE("Caricamento GGUF fallito: %s", path.c_str());
        return JNI_FALSE;
    }

    const int n_threads = std::max(1, static_cast<int>(threads));
    const uint32_t n_ctx = static_cast<uint32_t>(std::max(512, static_cast<int>(context_size)));

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = n_ctx;
    context_params.n_batch = n_ctx;     // tutto il prompt in un solo batch logico
    context_params.n_ubatch = 512;
    context_params.n_threads = n_threads;
    context_params.n_threads_batch = n_threads;

    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        LOGE("Creazione del contesto fallita");
        return JNI_FALSE;
    }

    LOGI("Modello caricato: ctx=%u threads=%d", n_ctx, n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_it_localeai_NativeLlm_requestStop(JNIEnv *, jobject) {
    // Non acquisisce g_model_mutex: la generazione lo tiene mentre
    // controlla questa flag atomica.
    g_cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_it_localeai_NativeLlm_unload(JNIEnv *, jobject) {
    g_cancel.store(true);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    free_model_locked();
}

// Formato di riserva quando il modello non ha un template riconosciuto.
static std::string fallback_prompt(const std::vector<std::string> &roles,
                                   const std::vector<std::string> &contents,
                                   size_t first) {
    std::string out;
    if (!roles.empty() && roles[0] == "system") out += contents[0] + "\n\n";
    for (size_t i = first; i < roles.size(); ++i) {
        if (roles[i] == "system") continue;
        out += (roles[i] == "user" ? "Utente: " : "Assistente: ");
        out += contents[i];
        out += "\n";
    }
    out += "Assistente:";
    return out;
}

// Applica il chat template del GGUF. Restituisce false se non supportato.
static bool templated_prompt(const char *tmpl,
                             const std::vector<std::string> &roles,
                             const std::vector<std::string> &contents,
                             size_t first, std::string &out) {
    if (tmpl == nullptr) return false;

    std::vector<llama_chat_message> chat;
    if (!roles.empty() && roles[0] == "system") {
        chat.push_back({roles[0].c_str(), contents[0].c_str()});
    }
    for (size_t i = first; i < roles.size(); ++i) {
        if (roles[i] == "system") continue;
        chat.push_back({roles[i].c_str(), contents[i].c_str()});
    }

    std::vector<char> buf(8192);
    int32_t n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true,
                                          buf.data(), static_cast<int32_t>(buf.size()));
    if (n < 0) return false;
    if (static_cast<size_t>(n) > buf.size()) {
        buf.resize(static_cast<size_t>(n));
        n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true,
                                      buf.data(), static_cast<int32_t>(buf.size()));
        if (n < 0) return false;
    }
    out.assign(buf.data(), static_cast<size_t>(n));
    return true;
}

static bool tokenize(const llama_vocab *vocab, const std::string &text,
                     std::vector<llama_token> &tokens) {
    const int32_t needed = -llama_tokenize(vocab, text.c_str(),
                                           static_cast<int32_t>(text.size()),
                                           nullptr, 0, true, true);
    if (needed <= 0) return false;
    tokens.resize(static_cast<size_t>(needed));
    const int32_t actual = llama_tokenize(vocab, text.c_str(),
                                          static_cast<int32_t>(text.size()),
                                          tokens.data(), needed, true, true);
    if (actual < 0) return false;
    tokens.resize(static_cast<size_t>(actual));
    return true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_it_localeai_NativeLlm_generate(
    JNIEnv *env, jobject, jobjectArray java_roles, jobjectArray java_contents,
    jint max_tokens, jobject callback) {

    std::lock_guard<std::mutex> lock(g_model_mutex);

    if (g_model == nullptr || g_context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Carica prima un modello GGUF.");
        return nullptr;
    }
    if (java_roles == nullptr || java_contents == nullptr || callback == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "Parametri mancanti.");
        return nullptr;
    }

    // Lo stop viene azzerato all'avvio: una richiesta arrivata prima
    // dell'inizio della generazione è considerata già superata.
    g_cancel.store(false);

    const jsize count = std::min(env->GetArrayLength(java_roles),
                                 env->GetArrayLength(java_contents));
    std::vector<std::string> roles, contents;
    for (jsize i = 0; i < count; ++i) {
        auto r = static_cast<jstring>(env->GetObjectArrayElement(java_roles, i));
        auto c = static_cast<jstring>(env->GetObjectArrayElement(java_contents, i));
        roles.push_back(from_java_string(env, r));
        contents.push_back(from_java_string(env, c));
        if (r) env->DeleteLocalRef(r);
        if (c) env->DeleteLocalRef(c);
    }
    if (roles.empty()) {
        throw_java(env, "java/lang/IllegalArgumentException", "Conversazione vuota.");
        return nullptr;
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_bytes = env->GetMethodID(callback_class, "onTokenBytes", "([B)V");
    env->DeleteLocalRef(callback_class);
    if (env->ExceptionCheck() || on_bytes == nullptr) {
        env->ExceptionClear();
        throw_java(env, "java/lang/RuntimeException", "Callback token non disponibile.");
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    const int n_ctx = static_cast<int>(llama_n_ctx(g_context));
    const int n_predict = std::max(1, std::min(static_cast<int>(max_tokens), n_ctx / 2));

    // Costruzione del prompt: se è troppo lungo per il contesto si scartano
    // i messaggi più vecchi (il messaggio di sistema resta sempre).
    size_t first = (roles[0] == "system") ? 1 : 0;
    std::vector<llama_token> tokens;
    bool use_fallback_stops = false;
    bool fits = false;

    while (first < roles.size()) {
        std::string prompt;
        use_fallback_stops = !templated_prompt(tmpl, roles, contents, first, prompt);
        if (use_fallback_stops) prompt = fallback_prompt(roles, contents, first);

        if (!tokenize(vocab, prompt, tokens)) {
            throw_java(env, "java/lang/RuntimeException", "Tokenizzazione del prompt non riuscita.");
            return nullptr;
        }
        if (static_cast<int>(tokens.size()) + n_predict < n_ctx) {
            fits = true;
            break;
        }
        // Scarta il messaggio più vecchio e riparti da un messaggio utente.
        ++first;
        while (first < roles.size() && roles[first] != "user") ++first;
    }

    if (!fits) {
        throw_java(env, "java/lang/RuntimeException",
                   "Messaggio troppo lungo per il contesto del modello.");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(g_context), true);

    // Valutazione del prompt a blocchi di n_batch.
    const int n_batch = static_cast<int>(llama_n_batch(g_context));
    const int n_prompt = static_cast<int>(tokens.size());
    llama_batch batch = llama_batch_init(std::max(n_batch, 1), 0, 1);

    for (int start = 0; start < n_prompt; start += n_batch) {
        const int n = std::min(n_batch, n_prompt - start);
        batch.n_tokens = n;
        for (int j = 0; j < n; ++j) {
            batch.token[j] = tokens[start + j];
            batch.pos[j] = start + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (start + j == n_prompt - 1);
        }
        if (llama_decode(g_context, batch) != 0) {
            llama_batch_free(batch);
            throw_java(env, "java/lang/RuntimeException",
                       "Valutazione del prompt fallita (memoria insufficiente?).");
            return nullptr;
        }
        if (g_cancel.load()) break;
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Con il formato di riserva si trattengono gli ultimi byte finché non è
    // certo che non siano l'inizio di "\nUtente:" (che non deve comparire).
    size_t hold = 0;
    if (use_fallback_stops) {
        for (const char *s : kFallbackStops) hold = std::max(hold, std::strlen(s));
        hold -= 1;
    }

    std::string generated;
    size_t sent = 0;
    bool callback_ok = true;
    int position = n_prompt;
    std::vector<char> piece(256);

    for (int i = 0; i < n_predict && !g_cancel.load() && callback_ok; ++i) {
        if (position >= n_ctx - 1) break;

        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        int32_t len = llama_token_to_piece(vocab, token, piece.data(),
                                           static_cast<int32_t>(piece.size()), 0, false);
        if (len < 0) {
            piece.resize(static_cast<size_t>(-len));
            len = llama_token_to_piece(vocab, token, piece.data(),
                                       static_cast<int32_t>(piece.size()), 0, false);
        }

        bool stop_found = false;
        if (len > 0) {
            generated.append(piece.data(), static_cast<size_t>(len));

            size_t limit = generated.size();
            if (use_fallback_stops) {
                size_t stop_at = std::string::npos;
                for (const char *s : kFallbackStops) {
                    const size_t p = generated.find(s, sent > hold ? sent - hold : 0);
                    if (p != std::string::npos) stop_at = std::min(stop_at, p);
                }
                if (stop_at != std::string::npos) {
                    limit = stop_at;
                    stop_found = true;
                } else {
                    limit = generated.size() > hold ? generated.size() - hold : 0;
                }
            }
            if (limit > sent) {
                callback_ok = send_bytes(env, callback, on_bytes,
                                         generated.data() + sent, limit - sent);
                sent = limit;
            }
        }
        if (stop_found) {
            sent = generated.size();   // il resto non va mostrato
            break;
        }

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = position++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;

        if (llama_decode(g_context, batch) != 0) {
            LOGE("llama_decode fallito durante la generazione");
            break;
        }
    }

    // Byte trattenuti ancora da mostrare.
    if (callback_ok && sent < generated.size()) {
        send_bytes(env, callback, on_bytes, generated.data() + sent, generated.size() - sent);
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    return env->NewStringUTF(generated.empty() ? "" : "ok");
}
