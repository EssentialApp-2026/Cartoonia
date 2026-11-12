package com.essentialapp.sam

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Collegamento JNA all'API C ufficiale di Vosk (libvosk.so), firme da vosk_api.h. */
interface VoskApi : Library {
    fun vosk_set_log_level(level: Int)
    fun vosk_model_new(modelPath: String): Pointer?
    fun vosk_model_free(model: Pointer)
    fun vosk_recognizer_new(model: Pointer, sampleRate: Float): Pointer?
    /** riconoscitore limitato a una lista di frasi (JSON), es. ["sam", "[unk]"] */
    fun vosk_recognizer_new_grm(model: Pointer, sampleRate: Float, grammar: String): Pointer?
    /** 1 = fine frase (leggi result), 0 = continua, -1 = errore */
    fun vosk_recognizer_accept_waveform(recognizer: Pointer, data: ByteArray, length: Int): Int
    fun vosk_recognizer_result(recognizer: Pointer): String?
    fun vosk_recognizer_partial_result(recognizer: Pointer): String?
    fun vosk_recognizer_final_result(recognizer: Pointer): String?
    fun vosk_recognizer_reset(recognizer: Pointer)
    fun vosk_recognizer_free(recognizer: Pointer)
    /** 1 = nei risultati includi le singole parole con la confidenza */
    fun vosk_recognizer_set_words(recognizer: Pointer, words: Int)

    // riconoscimento di chi parla: modello delle voci (x-vector), uguale per tutte le lingue
    fun vosk_spk_model_new(modelPath: String): Pointer?
    fun vosk_spk_model_free(model: Pointer)
    /** con il modello delle voci, ogni risultato contiene anche "spk" (l'impronta della voce) e "spk_frames" */
    fun vosk_recognizer_set_spk_model(recognizer: Pointer, spkModel: Pointer)
}

/**
 * Modello vocale offline caricato una sola volta e condiviso dai riconoscitori.
 * [libPath] = percorso assoluto di libvosk.so, [modelDir] = cartella del modello,
 * [spkDir] = cartella del modello delle voci (facoltativo: serve a riconoscere chi parla).
 */
class VoskEngine(libPath: String, modelDir: String, spkDir: String? = null) {

    private val api: VoskApi = run {
        Lang.utf8Native()
        Native.load(libPath, VoskApi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }
    private val model: Pointer
    private var spk: Pointer? = null

    init {
        api.vosk_set_log_level(-1)
        model = api.vosk_model_new(modelDir) ?: throw IllegalStateException("modello vocale non valido: $modelDir")
        if (spkDir != null) spk = try { api.vosk_spk_model_new(spkDir) } catch (t: Throwable) { null }
    }

    /** true se il modello delle voci è caricato (SAM può riconoscere chi parla). */
    val knowsVoices: Boolean get() = spk != null

    /** Riconoscitore completo (tutto il vocabolario italiano), con l'impronta della voce se c'è il modello delle voci. */
    fun recognizer(sampleRate: Float = 16000f): VoskRecognizer {
        val r = api.vosk_recognizer_new(model, sampleRate) ?: throw IllegalStateException("riconoscitore non creato")
        spk?.let { try { api.vosk_recognizer_set_spk_model(r, it) } catch (t: Throwable) {} }
        return VoskRecognizer(api, r)
    }

    /** Riconoscitore limitato a una lista di frasi (sconsigliato per "sam": forza la parola su tutto). */
    fun grammarRecognizer(phrases: List<String>, sampleRate: Float = 16000f): VoskRecognizer {
        val grammar = phrases.joinToString(",", "[", "]") { Json.quote(it) }
        return VoskRecognizer(api, api.vosk_recognizer_new_grm(model, sampleRate, grammar)
            ?: throw IllegalStateException("riconoscitore a grammatica non creato"))
    }

    fun close() {
        try { api.vosk_model_free(model) } catch (t: Throwable) {}
        spk?.let { try { api.vosk_spk_model_free(it) } catch (t: Throwable) {} }
        spk = null
    }
}

/** Un riconoscitore. Da usare da un solo thread (quello del microfono). */
class VoskRecognizer internal constructor(private val api: VoskApi, private val rec: Pointer) {

    /** Ritorna true quando una frase è finita (poi leggi [result]). */
    fun accept(pcm16: ByteArray, length: Int): Boolean =
        api.vosk_recognizer_accept_waveform(rec, pcm16, length) == 1

    fun result(): String = field(api.vosk_recognizer_result(rec), "text")

    /** Frase finita: il testo e l'impronta della voce di chi l'ha detta (null senza modello delle voci). */
    fun resultWithVoice(): Pair<String, VoiceSample?> = withVoice(api.vosk_recognizer_result(rec))
    fun finalWithVoice(): Pair<String, VoiceSample?> = withVoice(api.vosk_recognizer_final_result(rec))

    private fun withVoice(json: String?): Pair<String, VoiceSample?> {
        if (json.isNullOrBlank()) return "" to null
        return try {
            val m = Json.parse(json) as? Map<*, *> ?: return "" to null
            val text = (m["text"] as? String)?.trim() ?: ""
            val v = (m["spk"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
            val frames = (m["spk_frames"] as? Number)?.toInt() ?: 0
            text to (if (v != null && v.size >= 16) VoiceSample(v.toFloatArray(), frames) else null)
        } catch (e: Exception) { "" to null }
    }

    /** Attiva le parole con confidenza nei risultati (vedi [resultWords]). */
    fun enableWords() = api.vosk_recognizer_set_words(rec, 1)

    /** Frase finita come lista (parola, confidenza 0..1). Richiede [enableWords]. */
    fun resultWords(): List<Pair<String, Double>> = words(api.vosk_recognizer_result(rec))
    fun finalWords(): List<Pair<String, Double>> = words(api.vosk_recognizer_final_result(rec))

    private fun words(json: String?): List<Pair<String, Double>> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val list = (Json.parse(json) as? Map<*, *>)?.get("result") as? List<*> ?: return emptyList()
            list.mapNotNull { w ->
                val m = w as? Map<*, *> ?: return@mapNotNull null
                val word = m["word"] as? String ?: return@mapNotNull null
                val conf = (m["conf"] as? Number)?.toDouble() ?: 0.0
                word to conf
            }
        } catch (e: Exception) { emptyList() }
    }
    fun partial(): String = field(api.vosk_recognizer_partial_result(rec), "partial")
    fun finalResult(): String = field(api.vosk_recognizer_final_result(rec), "text")
    fun reset() = api.vosk_recognizer_reset(rec)
    fun close() { try { api.vosk_recognizer_free(rec) } catch (t: Throwable) {} }

    private fun field(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return try { ((Json.parse(json) as? Map<*, *>)?.get(key) as? String)?.trim() ?: "" } catch (e: Exception) { "" }
    }
}
