package com.essentialapp.sam

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/*
 * Cervelli in formato GGUF (1.14): quelli di llama.cpp, LM Studio e Ollama (Qwen, Llama, Mistral, Phi, DeepSeek,
 * Gemma 3, SmolLM, Granite…). Il motore è llama.cpp già compilato per Android (dal pacchetto open source llama.rn,
 * licenza MIT), chiamato con JNA come LiteRT-LM.
 *
 * Per non riscrivere tutto il cervello di SAM, [GgufLiteRt] offre le stesse funzioni di LiteRT-LM ([LiteRtLmApi]) ma
 * le fa con llama.cpp: conversazioni, istruzioni ed esempi, risposte a pezzi, interruzioni. Così memoria, regole,
 * ripartenze, file e autoapprendimento funzionano uguali con tutti e due i motori.
 */

// ------------------------------------------------------------------ strutture di llama.h (llama.rn 0.11.5)
@Structure.FieldOrder("devices", "tensor_buft_overrides", "n_gpu_layers", "split_mode", "main_gpu", "tensor_split",
    "progress_callback", "progress_callback_user_data", "kv_overrides", "vocab_only", "use_mmap", "use_direct_io",
    "use_mlock", "check_tensors", "use_extra_bufts", "no_host", "no_alloc")
open class LlamaModelParams : Structure() {
    @JvmField var devices: Pointer? = null
    @JvmField var tensor_buft_overrides: Pointer? = null
    @JvmField var n_gpu_layers: Int = 0
    @JvmField var split_mode: Int = 0
    @JvmField var main_gpu: Int = 0
    @JvmField var tensor_split: Pointer? = null
    @JvmField var progress_callback: Pointer? = null
    @JvmField var progress_callback_user_data: Pointer? = null
    @JvmField var kv_overrides: Pointer? = null
    @JvmField var vocab_only: Byte = 0
    @JvmField var use_mmap: Byte = 0
    @JvmField var use_direct_io: Byte = 0
    @JvmField var use_mlock: Byte = 0
    @JvmField var check_tensors: Byte = 0
    @JvmField var use_extra_bufts: Byte = 0
    @JvmField var no_host: Byte = 0
    @JvmField var no_alloc: Byte = 0
}
class LlamaModelParamsV : LlamaModelParams(), Structure.ByValue

@Structure.FieldOrder("n_ctx", "n_batch", "n_ubatch", "n_seq_max", "n_threads", "n_threads_batch", "rope_scaling_type",
    "pooling_type", "attention_type", "flash_attn_type", "rope_freq_base", "rope_freq_scale", "yarn_ext_factor",
    "yarn_attn_factor", "yarn_beta_fast", "yarn_beta_slow", "yarn_orig_ctx", "defrag_thold", "cb_eval", "cb_eval_user_data",
    "type_k", "type_v", "abort_callback", "abort_callback_data", "embeddings", "offload_kqv", "no_perf", "op_offload",
    "swa_full", "kv_unified", "samplers", "n_samplers")
open class LlamaContextParams : Structure() {
    @JvmField var n_ctx: Int = 0
    @JvmField var n_batch: Int = 0
    @JvmField var n_ubatch: Int = 0
    @JvmField var n_seq_max: Int = 0
    @JvmField var n_threads: Int = 0
    @JvmField var n_threads_batch: Int = 0
    @JvmField var rope_scaling_type: Int = 0
    @JvmField var pooling_type: Int = 0
    @JvmField var attention_type: Int = 0
    @JvmField var flash_attn_type: Int = 0
    @JvmField var rope_freq_base: Float = 0f
    @JvmField var rope_freq_scale: Float = 0f
    @JvmField var yarn_ext_factor: Float = 0f
    @JvmField var yarn_attn_factor: Float = 0f
    @JvmField var yarn_beta_fast: Float = 0f
    @JvmField var yarn_beta_slow: Float = 0f
    @JvmField var yarn_orig_ctx: Int = 0
    @JvmField var defrag_thold: Float = 0f
    @JvmField var cb_eval: Pointer? = null
    @JvmField var cb_eval_user_data: Pointer? = null
    @JvmField var type_k: Int = 0
    @JvmField var type_v: Int = 0
    @JvmField var abort_callback: Pointer? = null
    @JvmField var abort_callback_data: Pointer? = null
    @JvmField var embeddings: Byte = 0
    @JvmField var offload_kqv: Byte = 0
    @JvmField var no_perf: Byte = 0
    @JvmField var op_offload: Byte = 0
    @JvmField var swa_full: Byte = 0
    @JvmField var kv_unified: Byte = 0
    @JvmField var samplers: Pointer? = null
    @JvmField var n_samplers: Long = 0
}
class LlamaContextParamsV : LlamaContextParams(), Structure.ByValue

@Structure.FieldOrder("no_perf")
open class LlamaSamplerChainParams : Structure() { @JvmField var no_perf: Byte = 0 }
class LlamaSamplerChainParamsV : LlamaSamplerChainParams(), Structure.ByValue

@Structure.FieldOrder("n_tokens", "token", "embd", "pos", "n_seq_id", "seq_id", "logits")
open class LlamaBatch : Structure() {
    @JvmField var n_tokens: Int = 0
    @JvmField var token: Pointer? = null
    @JvmField var embd: Pointer? = null
    @JvmField var pos: Pointer? = null
    @JvmField var n_seq_id: Pointer? = null
    @JvmField var seq_id: Pointer? = null
    @JvmField var logits: Pointer? = null
}
class LlamaBatchV : LlamaBatch(), Structure.ByValue

/** void (*)(enum lm_ggml_log_level, const char *, void *) */
interface LlamaLogCallback : Callback { fun invoke(level: Int, text: Pointer?, userData: Pointer?) }
/** bool (*)(void *): true = ferma il calcolo */
interface LlamaAbortCallback : Callback { fun invoke(data: Pointer?): Byte }

/** Le funzioni di llama.cpp che servono a SAM. I bool di C si passano e si leggono come byte. */
interface LlamaApi : Library {
    fun llama_backend_init()
    fun llama_log_set(cb: LlamaLogCallback?, userData: Pointer?)
    fun llama_model_default_params(): LlamaModelParamsV
    fun llama_context_default_params(): LlamaContextParamsV
    fun llama_sampler_chain_default_params(): LlamaSamplerChainParamsV
    fun llama_model_load_from_file(path: String, params: LlamaModelParamsV): Pointer?
    fun llama_model_free(model: Pointer)
    fun llama_init_from_model(model: Pointer, params: LlamaContextParamsV): Pointer?
    fun llama_free(ctx: Pointer)
    fun llama_n_ctx(ctx: Pointer): Int
    fun llama_model_get_vocab(model: Pointer): Pointer?
    fun llama_model_chat_template(model: Pointer, name: String?): String?
    fun llama_chat_apply_template(tmpl: String?, chat: Pointer, nMsg: Long, addAss: Byte, buf: Pointer?, length: Int): Int
    fun llama_tokenize(vocab: Pointer, text: ByteArray, textLen: Int, tokens: IntArray?, nMax: Int, addSpecial: Byte, parseSpecial: Byte): Int
    fun llama_token_to_piece(vocab: Pointer, token: Int, buf: ByteArray, length: Int, lstrip: Int, special: Byte): Int
    fun llama_vocab_is_eog(vocab: Pointer, token: Int): Byte
    fun llama_batch_get_one(tokens: Pointer, n: Int): LlamaBatchV
    fun llama_decode(ctx: Pointer, batch: LlamaBatchV): Int
    fun llama_get_memory(ctx: Pointer): Pointer?
    fun llama_memory_clear(mem: Pointer, data: Byte)
    fun llama_memory_seq_rm(mem: Pointer, seq: Int, p0: Int, p1: Int): Byte
    fun llama_set_abort_callback(ctx: Pointer, cb: LlamaAbortCallback?, data: Pointer?)
    fun llama_sampler_chain_init(params: LlamaSamplerChainParamsV): Pointer?
    fun llama_sampler_chain_add(chain: Pointer, smpl: Pointer)
    fun llama_sampler_init_top_k(k: Int): Pointer
    fun llama_sampler_init_top_p(p: Float, minKeep: Long): Pointer
    fun llama_sampler_init_min_p(p: Float, minKeep: Long): Pointer
    fun llama_sampler_init_temp(t: Float): Pointer
    fun llama_sampler_init_dist(seed: Int): Pointer
    fun llama_sampler_init_penalties(lastN: Int, repeat: Float, freq: Float, present: Float): Pointer
    fun llama_sampler_sample(smpl: Pointer, ctx: Pointer, idx: Int): Int
    fun llama_sampler_free(smpl: Pointer)
}

// ------------------------------------------------------------------ lettura dei metadati GGUF (Kotlin puro)
/**
 * I dati scritti all'inizio di un file GGUF: architettura, nome, contesto, modello di chat. Legge solo l'intestazione
 * (i pesi no), quindi è veloce anche su file di diversi GB. Kotlin puro: si collauda senza telefono.
 */
class GgufInfo(val version: Int, val meta: Map<String, Any?>) {
    val arch: String? get() = meta["general.architecture"] as? String
    val name: String? get() = meta["general.name"] as? String
    val type: String? get() = meta["general.type"] as? String
    /** quanti token di contesto ha visto in addestramento ("qwen2.context_length") */
    val contextLength: Int get() = (meta["${arch}.context_length"] as? Number)?.toInt() ?: 0
    val chatTemplate: String? get() = meta["tokenizer.chat_template"] as? String
    /** il pezzo "vista" di un modello (mmproj, architettura clip): da solo non conversa */
    val isProjector: Boolean get() = arch == "clip" || type == "mmproj" || meta.keys.any { it.startsWith("clip.") }
    /** un modello che crea immagini o video (Wan, Flux, Stable Diffusion…) o il suo codificatore del testo (T5): non conversa */
    val isGenerator: Boolean get() = !isProjector && (arch?.lowercase()?.let { it in GENERATORS } ?: true)
    /** un file diviso in pezzi ("-00001-of-00003"): llama.cpp li vuole tutti insieme */
    val splitCount: Int get() = (meta["split.count"] as? Number)?.toInt() ?: 0
    /** Qwen3 ragiona prima di rispondere (<think>): a voce sarebbe troppo lento, gli si chiede di non farlo */
    val thinks: Boolean get() = arch == "qwen3" || arch == "qwen3moe" || (chatTemplate?.contains("enable_thinking") == true)

    companion object {
        val GENERATORS = setOf("wan", "flux", "sd1", "sd2", "sdxl", "sd3", "t5", "t5encoder", "umt5", "hunyuan_video", "hyvid", "ltxv",
            "cosmos", "lumina2", "hidream", "qwen_image", "chroma", "aura", "pixart", "unet", "vae")
        val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

        fun isGguf(f: File): Boolean = try {
            RandomAccessFile(f, "r").use { r -> val b = ByteArray(4); r.readFully(b); b.contentEquals(MAGIC) }
        } catch (e: Exception) { false }

        /** Legge l'intestazione. Gli array lunghi (i 150 000 pezzi di parola del vocabolario) si saltano. */
        fun read(f: File): GgufInfo {
            RandomAccessFile(f, "r").use { raf ->
                val inp = java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(raf.fd), 1 shl 16))
                fun u32(): Long = Integer.reverseBytes(inp.readInt()).toLong() and 0xffffffffL
                fun i32(): Int = Integer.reverseBytes(inp.readInt())
                fun u64(): Long = java.lang.Long.reverseBytes(inp.readLong())
                fun str(): String {
                    val n = u64()
                    if (n < 0 || n > 50_000_000) throw IllegalArgumentException("intestazione GGUF rovinata")
                    val b = ByteArray(n.toInt()); inp.readFully(b); return String(b, Charsets.UTF_8)
                }
                val magic = ByteArray(4); inp.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException("non è un file GGUF")
                val version = i32()
                if (version < 2) throw IllegalArgumentException("GGUF troppo vecchio (versione $version)")
                u64() // tensori
                val nKv = u64()
                if (nKv < 0 || nKv > 100_000) throw IllegalArgumentException("intestazione GGUF rovinata")
                fun skip(n: Long) { var left = n; while (left > 0) { val s = inp.skip(left); if (s <= 0) { inp.readByte(); left-- } else left -= s } }
                fun value(type: Int): Any? = when (type) {
                    0 -> inp.readUnsignedByte(); 1 -> inp.readByte().toInt()
                    2 -> java.lang.Short.reverseBytes(inp.readShort()).toInt() and 0xffff
                    3 -> java.lang.Short.reverseBytes(inp.readShort()).toInt()
                    4 -> u32(); 5 -> i32()
                    6 -> java.lang.Float.intBitsToFloat(Integer.reverseBytes(inp.readInt()))
                    7 -> inp.readByte().toInt() != 0
                    8 -> str()
                    9 -> {
                        val t = i32(); val n = u64()
                        val size = when (t) { 0, 1, 7 -> 1L; 2, 3 -> 2L; 4, 5, 6 -> 4L; 10, 11, 12 -> 8L; else -> -1L }
                        if (size > 0) { skip(size * n); null }
                        else { for (i in 0 until n) value(t); null }   // array di stringhe: si legge e si butta
                    }
                    10 -> u64(); 11 -> u64()
                    12 -> java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(inp.readLong()))
                    else -> throw IllegalArgumentException("tipo GGUF sconosciuto $type")
                }
                val meta = LinkedHashMap<String, Any?>()
                for (i in 0 until nKv) {
                    val key = str()
                    val t = i32()
                    meta[key] = value(t)
                }
                return GgufInfo(version, meta)
            }
        }
    }
}

/** Testo UTF-8 che arriva a pezzi di byte: si consegna solo quando i caratteri sono completi (le accentate sono 2 byte). */
class Utf8Joiner {
    private val pending = ByteArrayOutputStream()
    fun add(b: ByteArray, n: Int = b.size): String {
        pending.write(b, 0, n)
        val all = pending.toByteArray()
        val ok = completeLength(all)
        pending.reset()
        if (ok < all.size) pending.write(all, ok, all.size - ok)
        return String(all, 0, ok, Charsets.UTF_8)
    }
    fun rest(): String { val s = String(pending.toByteArray(), Charsets.UTF_8); pending.reset(); return s }

    companion object {
        /** Quanti byte dall'inizio formano caratteri completi. */
        fun completeLength(b: ByteArray): Int {
            var i = b.size - 1
            var back = 0
            while (i >= 0 && back < 4) {
                val c = b[i].toInt() and 0xff
                if (c and 0xC0 != 0x80) {   // primo byte di un carattere
                    val need = when { c < 0x80 -> 1; c shr 5 == 0x6 -> 2; c shr 4 == 0xE -> 3; c shr 3 == 0x1E -> 4; else -> 1 }
                    return if (b.size - i >= need) b.size else i
                }
                i--; back++
            }
            return b.size
        }
    }
}

// ------------------------------------------------------------------ LiteRT-LM fatto con llama.cpp
/**
 * Le funzioni di [LiteRtLmApi] fatte con llama.cpp: [Llm] non si accorge della differenza. Gli "oggetti" (impostazioni,
 * motore, conversazione, pezzo di risposta) sono numeri che si passano avanti e indietro come [Pointer], e qui
 * corrispondono a oggetti Kotlin.
 */
class GgufLiteRt(libPath: String) : LiteRtLmApi {
    private val ll: LlamaApi = apiFactory?.invoke(libPath)
        ?: Native.load(libPath, LlamaApi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    private val ids = AtomicLong(0x5A000)
    private val objs = ConcurrentHashMap<Long, Any>()
    private fun handle(o: Any): Pointer { val id = ids.incrementAndGet() * 16; objs[id] = o; return Pointer(id) }
    private inline fun <reified T> obj(p: Pointer?): T? = p?.let { objs[Pointer.nativeValue(it)] as? T }
    private fun drop(p: Pointer) { objs.remove(Pointer.nativeValue(p)) }

    // i log di llama.cpp: zitti (tenuti in vita: una callback raccolta dal GC fa cadere l'app)
    private val quiet = object : LlamaLogCallback { override fun invoke(level: Int, text: Pointer?, userData: Pointer?) {} }

    init {
        Lang.utf8Native()
        ll.llama_log_set(quiet, null)
        ll.llama_backend_init()
    }

    private class Settings(val model: String) { var threads = 4; var maxTokens = 0 }
    private inner class Engine(val model: Pointer, val ctx: Pointer, val vocab: Pointer, val info: GgufInfo?, val nCtx: Int) {
        val template: String? = try { ll.llama_model_chat_template(model, null) } catch (t: Throwable) { null }
        /** i token già letti dal motore (la conversazione in corso): la parte uguale non si rilegge */
        var kv: IntArray = IntArray(0)
        @Volatile var cancel = false
        @Volatile var busy = false
        val abort = object : LlamaAbortCallback { override fun invoke(data: Pointer?): Byte = if (cancel) 1 else 0 }
        init { ll.llama_set_abort_callback(ctx, abort, null) }
    }
    private class Sampling(var topK: Int = 40, var topP: Float = 0.95f, var temp: Float = 0.7f, var seed: Int = 0)
    private class Session { var sampling = Sampling(); var maxOut = 220 }
    private class ConvConfig { var system: String? = null; var examples: List<Pair<String, String>> = emptyList(); var session: Session? = null }
    private class Msg(val role: String, val text: String)
    private inner class Conv(val engine: Engine, val cfg: ConvConfig) {
        val history = ArrayList<Msg>()
        @Volatile var active: Thread? = null
    }
    private class Opt { var maxOut = 0; var presence = 0f }
    private class Chunk(val text: String?, val final: Boolean, val error: String?)

    // ---- registro e file
    override fun litert_lm_set_min_log_level(level: Int) {}

    override fun litert_lm_loaded_file_create(path: String): Pointer? {
        val info = try { GgufInfo.read(File(path)) } catch (e: Exception) { return null }
        return handle(info)
    }
    override fun litert_lm_loaded_file_delete(f: Pointer) = drop(f)
    /** 1 = modello per conversare; il pezzo della vista (mmproj) da solo no */
    override fun litert_lm_loaded_file_model_type(f: Pointer): Int = if (obj<GgufInfo>(f)?.isProjector == true) 0 else 1
    override fun litert_lm_loaded_file_max_context_tokens(f: Pointer): Int = obj<GgufInfo>(f)?.contextLength?.takeIf { it > 0 } ?: 4096
    override fun litert_lm_loaded_file_is_dynamic_context(f: Pointer): Byte = 1
    override fun litert_lm_loaded_file_supports_thinking(f: Pointer): Byte = if (obj<GgufInfo>(f)?.thinks == true) 1 else 0
    override fun litert_lm_loaded_file_min_runtime_version(f: Pointer): String? = null
    override fun litert_lm_loaded_file_supports_input_modality(f: Pointer, modality: Int): Byte = 0
    override fun litert_lm_loaded_file_modality_supported_backends(f: Pointer, modality: Int, out: IntArray?, max: Int): Int = 0

    // ---- motore
    override fun litert_lm_engine_settings_create(modelPath: String, backend: String, visionBackend: String?, audioBackend: String?): Pointer? =
        handle(Settings(modelPath))
    override fun litert_lm_engine_settings_delete(settings: Pointer) = drop(settings)
    override fun litert_lm_engine_settings_set_num_threads(settings: Pointer, n: Int) { obj<Settings>(settings)?.threads = n }
    override fun litert_lm_engine_settings_set_cache_dir(settings: Pointer, dir: String) {}
    override fun litert_lm_engine_settings_set_max_num_tokens(settings: Pointer, n: Int) { obj<Settings>(settings)?.maxTokens = n }
    override fun litert_lm_engine_settings_set_max_num_images(settings: Pointer, n: Int) {}

    override fun litert_lm_engine_create(settings: Pointer): Pointer? {
        val s = obj<Settings>(settings) ?: return null
        val info = try { GgufInfo.read(File(s.model)) } catch (e: Exception) { null }
        if (info?.isProjector == true || info?.isGenerator == true) return null
        val mp = ll.llama_model_default_params()
        mp.n_gpu_layers = 0          // sul processore: su questi telefoni la scheda grafica non aiuta llama.cpp
        mp.use_mmap = 1
        val model = ll.llama_model_load_from_file(s.model, mp) ?: return null
        val cp = ll.llama_context_default_params()
        val trained = info?.contextLength ?: 0
        cp.n_ctx = when {
            s.maxTokens > 0 -> s.maxTokens
            trained > 0 -> minOf(trained, Llm.CONTEXT_TOKENS)
            else -> Llm.CONTEXT_TOKENS
        }
        cp.n_batch = 512; cp.n_ubatch = 512
        cp.n_threads = s.threads.coerceIn(1, 8); cp.n_threads_batch = cp.n_threads
        val ctx = ll.llama_init_from_model(model, cp)
        if (ctx == null) { ll.llama_model_free(model); return null }
        val vocab = ll.llama_model_get_vocab(model) ?: run { ll.llama_free(ctx); ll.llama_model_free(model); return null }
        return handle(Engine(model, ctx, vocab, info, ll.llama_n_ctx(ctx)))
    }

    override fun litert_lm_engine_delete(engine: Pointer) {
        val e = obj<Engine>(engine) ?: return
        e.cancel = true
        var waited = 0
        while (e.busy && waited < 5000) { Thread.sleep(20); waited += 20 }
        drop(engine)
        try { ll.llama_free(e.ctx) } catch (t: Throwable) {}
        try { ll.llama_model_free(e.model) } catch (t: Throwable) {}
    }

    // ---- campionamento, sessione, conversazione
    override fun litert_lm_sampler_params_create(type: Int): Pointer? = handle(Sampling())
    override fun litert_lm_sampler_params_delete(p: Pointer) {}   // la sessione la tiene: si libera con lei
    override fun litert_lm_sampler_params_set_top_k(p: Pointer, k: Int) { obj<Sampling>(p)?.topK = k }
    override fun litert_lm_sampler_params_set_top_p(p: Pointer, v: Float) { obj<Sampling>(p)?.topP = v }
    override fun litert_lm_sampler_params_set_temperature(p: Pointer, v: Float) { obj<Sampling>(p)?.temp = v }
    override fun litert_lm_sampler_params_set_seed(p: Pointer, seed: Int) { obj<Sampling>(p)?.seed = seed }

    override fun litert_lm_session_config_create(): Pointer? = handle(Session())
    override fun litert_lm_session_config_delete(c: Pointer) {}
    override fun litert_lm_session_config_set_max_output_tokens(c: Pointer, n: Int) { obj<Session>(c)?.maxOut = n }
    override fun litert_lm_session_config_set_sampler_params(c: Pointer, p: Pointer) {
        val s = obj<Session>(c) ?: return
        obj<Sampling>(p)?.let { s.sampling = Sampling(it.topK, it.topP, it.temp, it.seed) }
        drop(p)
    }

    override fun litert_lm_conversation_config_create(): Pointer? = handle(ConvConfig())
    override fun litert_lm_conversation_config_delete(c: Pointer) = drop(c)
    override fun litert_lm_conversation_config_set_session_config(c: Pointer, s: Pointer) { obj<ConvConfig>(c)?.session = obj<Session>(s); drop(s) }
    override fun litert_lm_conversation_config_set_system_message(c: Pointer, text: String) { obj<ConvConfig>(c)?.system = text }
    override fun litert_lm_conversation_config_set_messages(c: Pointer, json: String) {
        val list = try { Json.parse(json) as? List<*> } catch (e: Exception) { null } ?: return
        fun textOf(c: Any?): String = when (c) {
            is String -> c
            is List<*> -> c.joinToString("") { p -> ((p as? Map<*, *>)?.takeIf { it["type"] == null || it["type"] == "text" }?.get("text") as? String) ?: "" }
            else -> ""
        }
        val msgs = list.mapNotNull { m -> (m as? Map<*, *>)?.let { (it["role"] as? String) to textOf(it["content"]) } }
        // a coppie: domanda dell'utente, risposta dell'assistente
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i + 1 < msgs.size) {
            if (msgs[i].first == "user" && msgs[i + 1].first == "assistant") { out.add(msgs[i].second to msgs[i + 1].second); i += 2 } else i++
        }
        obj<ConvConfig>(c)?.examples = out
    }
    override fun litert_lm_conversation_config_set_thinking_config(c: Pointer, t: Pointer) {}
    override fun litert_lm_thinking_config_create(): Pointer? = handle(Any())
    override fun litert_lm_thinking_config_delete(t: Pointer) = drop(t)
    override fun litert_lm_thinking_config_set_enable_thinking(t: Pointer, enable: Byte) {}

    override fun litert_lm_conversation_optional_args_create(): Pointer? = handle(Opt())
    override fun litert_lm_conversation_optional_args_delete(o: Pointer) = drop(o)
    override fun litert_lm_conversation_optional_args_set_repetition_penalty_config(o: Pointer, c: Pointer) {
        obj<Opt>(o)?.presence = obj<FloatArray>(c)?.get(0) ?: 0f
    }
    override fun litert_lm_conversation_optional_args_set_no_repeat_ngram_config(o: Pointer, c: Pointer) {}
    override fun litert_lm_conversation_optional_args_set_max_output_tokens(o: Pointer, n: Int) { obj<Opt>(o)?.maxOut = n }
    override fun litert_lm_repetition_penalty_config_create(): Pointer? = handle(FloatArray(1))
    override fun litert_lm_repetition_penalty_config_delete(c: Pointer) = drop(c)
    override fun litert_lm_repetition_penalty_config_set_repetition_penalty(c: Pointer, v: Float) {}
    override fun litert_lm_repetition_penalty_config_set_presence_penalty(c: Pointer, v: Float) { obj<FloatArray>(c)?.set(0, v) }
    override fun litert_lm_repetition_penalty_config_set_frequency_penalty(c: Pointer, v: Float) {}
    override fun litert_lm_repetition_penalty_config_set_window_size(c: Pointer, n: Int) {}
    override fun litert_lm_no_repeat_ngram_config_create(): Pointer? = handle(Any())
    override fun litert_lm_no_repeat_ngram_config_delete(c: Pointer) = drop(c)
    override fun litert_lm_no_repeat_ngram_config_set_no_repeat_ngram_size(c: Pointer, n: Int) {}
    override fun litert_lm_no_repeat_ngram_config_set_window_size(c: Pointer, n: Int) {}

    override fun litert_lm_conversation_create(engine: Pointer, config: Pointer): Pointer? {
        val e = obj<Engine>(engine) ?: return null
        val cfg = obj<ConvConfig>(config) ?: return null
        val copy = ConvConfig().also { it.system = cfg.system; it.examples = cfg.examples; it.session = cfg.session ?: Session() }
        return handle(Conv(e, copy))
    }

    override fun litert_lm_conversation_delete(conv: Pointer) {
        val c = obj<Conv>(conv) ?: return
        if (c.engine.busy) { c.engine.cancel = true; var w = 0; while (c.engine.busy && w < 5000) { Thread.sleep(20); w += 20 } }
        drop(conv)
    }

    override fun litert_lm_conversation_cancel_process(conv: Pointer) { obj<Conv>(conv)?.engine?.cancel = true }

    override fun litert_lm_conversation_render_preface_to_string(conv: Pointer): String? {
        val c = obj<Conv>(conv) ?: return null
        return render(c.engine, messages(c, null), addAssistant = false)
    }

    override fun litert_lm_conversation_get_token_count(conv: Pointer): Int = obj<Conv>(conv)?.engine?.kv?.size ?: 0

    // ---- risposte a pezzi
    override fun litert_lm_stream_chunk_get_text(chunk: Pointer): String? = obj<Chunk>(chunk)?.text?.let { "{\"role\":\"assistant\",\"content\":${Json.quote(it)}}" }
    override fun litert_lm_stream_chunk_is_final(chunk: Pointer): Byte = if (obj<Chunk>(chunk)?.final == true) 1 else 0
    override fun litert_lm_stream_chunk_get_error(chunk: Pointer): String? = obj<Chunk>(chunk)?.error

    private fun emit(cb: StreamCallback, ch: Chunk) {
        val p = handle(ch)
        try { cb.invoke(null, p) } finally { drop(p) }
    }

    /** Tutti i messaggi: istruzioni, esempi, discorso, e la domanda nuova [user] (null = solo la parte iniziale). */
    private fun messages(c: Conv, user: String?): List<Msg> {
        val out = ArrayList<Msg>()
        c.cfg.system?.takeIf { it.isNotBlank() }?.let { out.add(Msg("system", it)) }
        for ((q, a) in c.cfg.examples) { out.add(Msg("user", q)); out.add(Msg("assistant", a)) }
        out.addAll(c.history)
        if (user != null) out.add(Msg("user", user))
        return out
    }

    /** Il testo da dare al modello, col suo modello di chat (se llama.cpp non lo riconosce: quello più comune, ChatML). */
    private fun render(e: Engine, msgs: List<Msg>, addAssistant: Boolean): String {
        fun apply(tmpl: String?): String? {
            val keep = ArrayList<Memory>()
            val arr = Memory(16L * maxOf(1, msgs.size))
            for ((i, m) in msgs.withIndex()) {
                val r = cstr(m.role).also { keep.add(it) }; val t = cstr(m.text).also { keep.add(it) }
                arr.setPointer(16L * i, r); arr.setPointer(16L * i + 8, t)
            }
            var size = 4096 + msgs.sumOf { it.text.length * 4 }
            repeat(2) {
                val buf = Memory(size.toLong())
                val n = ll.llama_chat_apply_template(tmpl, arr, msgs.size.toLong(), if (addAssistant) 1 else 0, buf, size)
                if (n < 0) return null
                if (n <= size) return String(buf.getByteArray(0, n), Charsets.UTF_8)
                size = n + 16
            }
            return null
        }
        return apply(e.template) ?: apply("chatml") ?: msgs.joinToString("\n") { "${it.role}: ${it.text}" } + (if (addAssistant) "\nassistant:" else "")
    }

    private fun cstr(s: String): Memory {
        val b = s.toByteArray(Charsets.UTF_8)
        return Memory(b.size + 1L).also { it.write(0, b, 0, b.size); it.setByte(b.size.toLong(), 0) }
    }

    private fun tokenize(e: Engine, text: String): IntArray {
        val b = text.toByteArray(Charsets.UTF_8)
        var cap = b.size + 16
        var out = IntArray(cap)
        var n = ll.llama_tokenize(e.vocab, b, b.size, out, cap, 1, 1)
        if (n < 0) { cap = -n; out = IntArray(cap); n = ll.llama_tokenize(e.vocab, b, b.size, out, cap, 1, 1) }
        if (n < 0) throw IllegalStateException("testo non convertibile in token")
        return out.copyOf(n)
    }

    /** Fa leggere al motore i token [toks] da [from] in poi (a blocchi). false = interrotto o errore. */
    private fun decode(e: Engine, toks: IntArray, from: Int): Int {
        var i = from
        while (i < toks.size) {
            if (e.cancel) return 2
            val n = minOf(512, toks.size - i)
            val mem = Memory(4L * n)
            for (k in 0 until n) mem.setInt(4L * k, toks[i + k])
            val r = ll.llama_decode(e.ctx, ll.llama_batch_get_one(mem, n))
            if (r != 0) return if (e.cancel || r == 2) 2 else 1
            i += n
        }
        return 0
    }

    override fun litert_lm_conversation_send_message_stream(conv: Pointer, messageJson: String, extraContextJson: String,
                                                             optionalArgs: Pointer?, callback: StreamCallback, userData: Pointer?): Int {
        val c = obj<Conv>(conv) ?: return -1
        val e = c.engine
        if (e.busy) return -2
        val root = try { Json.parse(messageJson) as? Map<*, *> } catch (x: Exception) { null } ?: return -3
        if ((root["content"] as? List<*>)?.any { (it as? Map<*, *>)?.get("type") == "image" } == true) {
            emit(callback, Chunk(null, true, "INVALID_ARGUMENT: questo cervello non vede le immagini")); return 0
        }
        var user = Json.messageText(messageJson)
        // Qwen3 e simili ragionerebbero a lungo prima di rispondere: a voce si chiede di rispondere subito
        if (e.info?.thinks == true && !user.contains("/no_think")) user = "$user /no_think"
        val opt = optionalArgs?.let { obj<Opt>(it) }
        val maxOut = (opt?.maxOut?.takeIf { it > 0 } ?: c.cfg.session?.maxOut ?: 220).coerceIn(1, 4096)
        val presence = opt?.presence ?: 0f
        val samp = c.cfg.session?.sampling ?: Sampling()
        e.cancel = false
        e.busy = true
        val t = Thread({
            var sampler: Pointer? = null
            try {
                val prompt = render(e, messages(c, user), addAssistant = true)
                val toks = tokenize(e, prompt)
                if (toks.size + 8 >= e.nCtx) {
                    // come LiteRT-LM: la memoria del discorso è piena (Llm riparte da una conversazione nuova)
                    e.busy = false
                    emit(callback, Chunk(null, true, "INVALID_ARGUMENT: Input token ids are too long. " +
                        "Exceeding the maximum number of tokens allowed: ${toks.size} >= ${e.nCtx}"))
                    return@Thread
                }
                // la parte uguale a prima resta nel motore: si legge solo quella nuova
                var common = 0
                val old = e.kv
                while (common < old.size && common < toks.size && old[common] == toks[common]) common++
                if (common >= toks.size) common = toks.size - 1
                val mem = ll.llama_get_memory(e.ctx)
                if (mem != null) {
                    val ok = if (common > 0) ll.llama_memory_seq_rm(mem, -1, common, -1).toInt() != 0 else false
                    if (!ok) { ll.llama_memory_clear(mem, 1); common = 0 }
                }
                e.kv = IntArray(0)
                when (decode(e, toks, common)) {
                    2 -> { e.busy = false; emit(callback, Chunk(null, true, "CANCELLED: Task cancelled")); return@Thread }
                    1 -> { e.busy = false; emit(callback, Chunk(null, true, "INTERNAL: il motore GGUF non è riuscito a leggere la domanda")); return@Thread }
                }
                val kv = ArrayList<Int>(toks.size + maxOut); toks.forEach { kv.add(it) }
                sampler = ll.llama_sampler_chain_init(ll.llama_sampler_chain_default_params())
                    ?: throw IllegalStateException("campionatore non creato")
                if (presence > 0f) ll.llama_sampler_chain_add(sampler, ll.llama_sampler_init_penalties(256, 1.0f, 0f, presence))
                if (samp.topK > 0) ll.llama_sampler_chain_add(sampler, ll.llama_sampler_init_top_k(samp.topK))
                if (samp.topP in 0f..0.9999f) ll.llama_sampler_chain_add(sampler, ll.llama_sampler_init_top_p(samp.topP, 1))
                ll.llama_sampler_chain_add(sampler, ll.llama_sampler_init_temp(samp.temp))
                ll.llama_sampler_chain_add(sampler, ll.llama_sampler_init_dist(if (samp.seed != 0) samp.seed else (System.nanoTime() and 0x7fffffff).toInt()))
                val text = StringBuilder()
                val joiner = Utf8Joiner()
                val piece = ByteArray(256)
                var cancelled = false
                var produced = 0
                while (produced < maxOut) {
                    if (e.cancel) { cancelled = true; break }
                    val tok = ll.llama_sampler_sample(sampler, e.ctx, -1)
                    if (ll.llama_vocab_is_eog(e.vocab, tok).toInt() != 0) break
                    val n = ll.llama_token_to_piece(e.vocab, tok, piece, piece.size, 0, 0)
                    if (n > 0) {
                        val s = joiner.add(piece, n)
                        if (s.isNotEmpty()) { text.append(s); emit(callback, Chunk(s, false, null)) }
                    }
                    kv.add(tok); produced++
                    if (kv.size + 1 >= e.nCtx) break       // memoria del discorso piena: la risposta finisce qui
                    val one = Memory(4); one.setInt(0, tok)
                    val r = ll.llama_decode(e.ctx, ll.llama_batch_get_one(one, 1))
                    if (r != 0) { cancelled = e.cancel; break }
                }
                joiner.rest().takeIf { it.isNotEmpty() }?.let { text.append(it); emit(callback, Chunk(it, false, null)) }
                e.kv = kv.toIntArray()
                c.history.add(Msg("user", user))
                c.history.add(Msg("assistant", text.toString().trim()))
                e.busy = false
                if (cancelled) emit(callback, Chunk(null, true, "CANCELLED: Task cancelled"))
                else emit(callback, Chunk(null, true, null))
            } catch (x: Throwable) {
                e.kv = IntArray(0)
                e.busy = false
                emit(callback, Chunk(null, true, "INTERNAL: ${x.message ?: "errore del motore GGUF"}"))
            } finally {
                sampler?.let { try { ll.llama_sampler_free(it) } catch (t: Throwable) {} }
                e.busy = false
            }
        }, "sam-gguf")
        c.active = t
        t.start()
        return 0
    }

    companion object {
        /** SAM per PC: un altro modo di arrivare a llama.cpp (un ponte con funzioni semplici); null = la libreria diretta. */
        @JvmStatic @Volatile var apiFactory: ((String) -> LlamaApi)? = null

        /**
         * Quale versione del motore usare su questo processore: quelle più veloci usano istruzioni che solo i processori
         * più recenti hanno (dotprod, i8mm). Si legge /proc/cpuinfo; se non si sa, quella che va ovunque.
         */
        fun variantFor(cpuinfo: String): String {
            val f = cpuinfo.lines().firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet() ?: emptySet()
            val dot = "asimddp" in f
            val i8mm = "i8mm" in f
            return when {
                dot && i8mm -> "librnllama_v8_2_dotprod_i8mm.so"
                dot -> "librnllama_v8_2_dotprod.so"
                else -> "librnllama_v8.so"
            }
        }
    }
}
