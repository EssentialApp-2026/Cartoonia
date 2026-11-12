package com.essentialapp.sam

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * L'interprete TFLite (LiteRT) che c'è già dentro la libreria di LiteRT-LM: serve a far girare modelli .tflite qualsiasi,
 * come i tre pezzi di Stable Diffusion per disegnare. Funzioni dell'API in C, chiamate con JNA.
 */
interface TfLiteApi : Library {
    fun TfLiteModelCreateFromFile(path: String): Pointer?
    fun TfLiteModelDelete(model: Pointer)
    fun TfLiteInterpreterOptionsCreate(): Pointer?
    fun TfLiteInterpreterOptionsDelete(o: Pointer)
    fun TfLiteInterpreterOptionsSetNumThreads(o: Pointer, n: Int)
    fun TfLiteInterpreterCreate(model: Pointer, options: Pointer?): Pointer?
    fun TfLiteInterpreterDelete(i: Pointer)
    fun TfLiteInterpreterAllocateTensors(i: Pointer): Int
    fun TfLiteInterpreterResizeInputTensor(i: Pointer, index: Int, dims: IntArray, n: Int): Int
    fun TfLiteInterpreterGetInputTensorCount(i: Pointer): Int
    fun TfLiteInterpreterGetInputTensor(i: Pointer, index: Int): Pointer?
    fun TfLiteInterpreterGetOutputTensorCount(i: Pointer): Int
    fun TfLiteInterpreterGetOutputTensor(i: Pointer, index: Int): Pointer?
    fun TfLiteInterpreterInvoke(i: Pointer): Int
    fun TfLiteTensorType(t: Pointer): Int
    fun TfLiteTensorNumDims(t: Pointer): Int
    fun TfLiteTensorDim(t: Pointer, i: Int): Int
    fun TfLiteTensorByteSize(t: Pointer): Long
    fun TfLiteTensorName(t: Pointer): String?
    fun TfLiteTensorCopyFromBuffer(t: Pointer, buf: Pointer, size: Long): Int
    fun TfLiteTensorCopyToBuffer(t: Pointer, buf: Pointer, size: Long): Int
}

/** Un modello .tflite caricato. Non è thread-safe: un'esecuzione alla volta. */
class TfModel(lib: String, path: String, threads: Int) : AutoCloseable {
    class Tensor(val name: String, val type: Int, val dims: IntArray) {
        val count: Int get() = dims.fold(1) { a, b -> a * b }
        override fun toString() = "$name${dims.contentToString()}:${typeName(type)}"
    }

    private val api: TfLiteApi = Native.load(lib, TfLiteApi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    private val model: Pointer
    private val interp: Pointer
    private var closed = false

    init {
        model = api.TfLiteModelCreateFromFile(path) ?: throw IllegalArgumentException("modello TFLite non leggibile: ${path.substringAfterLast('/')}")
        val opt = api.TfLiteInterpreterOptionsCreate()
        if (opt != null) api.TfLiteInterpreterOptionsSetNumThreads(opt, threads.coerceIn(1, 8))
        val ip = api.TfLiteInterpreterCreate(model, opt)
        if (opt != null) api.TfLiteInterpreterOptionsDelete(opt)
        if (ip == null) { api.TfLiteModelDelete(model); throw IllegalStateException("il motore TFLite non apre ${path.substringAfterLast('/')}") }
        interp = ip
        if (api.TfLiteInterpreterAllocateTensors(interp) != 0) { close(); throw IllegalStateException("memoria insufficiente per ${path.substringAfterLast('/')}") }
    }

    private fun info(t: Pointer) = Tensor(api.TfLiteTensorName(t) ?: "", api.TfLiteTensorType(t),
        IntArray(api.TfLiteTensorNumDims(t)) { api.TfLiteTensorDim(t, it) })

    val inputs: List<Tensor> get() = (0 until api.TfLiteInterpreterGetInputTensorCount(interp)).map { info(api.TfLiteInterpreterGetInputTensor(interp, it)!!) }
    val outputs: List<Tensor> get() = (0 until api.TfLiteInterpreterGetOutputTensorCount(interp)).map { info(api.TfLiteInterpreterGetOutputTensor(interp, it)!!) }

    /** Cambia la forma degli ingressi (per esempio due immagini insieme). false = il modello non lo permette. */
    fun resize(shapes: Map<Int, IntArray>): Boolean {
        for ((i, d) in shapes) if (api.TfLiteInterpreterResizeInputTensor(interp, i, d, d.size) != 0) return false
        return api.TfLiteInterpreterAllocateTensors(interp) == 0
    }

    /** Esegue il modello: [inputs] per indice (FloatArray o IntArray, della misura giusta). Ritorna le uscite in float. */
    fun run(inputs: Map<Int, Any>): List<FloatArray> {
        check(!closed) { "modello chiuso" }
        for ((i, v) in inputs) {
            val t = api.TfLiteInterpreterGetInputTensor(interp, i) ?: throw IllegalStateException("ingresso $i mancante")
            val bytes = api.TfLiteTensorByteSize(t)
            val mem = Memory(bytes)
            when (v) {
                is FloatArray -> { require(v.size * 4L == bytes) { "ingresso $i: ${v.size} numeri invece di ${bytes / 4}" }; mem.write(0, v, 0, v.size) }
                is IntArray -> {
                    when (api.TfLiteTensorType(t)) {
                        TYPE_INT32 -> { require(v.size * 4L == bytes) { "ingresso $i: misura sbagliata" }; mem.write(0, v, 0, v.size) }
                        TYPE_INT64 -> { require(v.size * 8L == bytes) { "ingresso $i: misura sbagliata" }; mem.write(0, LongArray(v.size) { v[it].toLong() }, 0, v.size) }
                        TYPE_FLOAT32 -> { require(v.size * 4L == bytes) { "ingresso $i: misura sbagliata" }; mem.write(0, FloatArray(v.size) { v[it].toFloat() }, 0, v.size) }
                        else -> throw IllegalStateException("ingresso $i di tipo ${typeName(api.TfLiteTensorType(t))}")
                    }
                }
                else -> throw IllegalArgumentException("tipo di ingresso non gestito")
            }
            if (api.TfLiteTensorCopyFromBuffer(t, mem, bytes) != 0) throw IllegalStateException("ingresso $i non copiato")
        }
        if (api.TfLiteInterpreterInvoke(interp) != 0) throw IllegalStateException("il modello TFLite ha dato errore")
        return (0 until api.TfLiteInterpreterGetOutputTensorCount(interp)).map { i ->
            val t = api.TfLiteInterpreterGetOutputTensor(interp, i)!!
            val bytes = api.TfLiteTensorByteSize(t)
            val mem = Memory(bytes)
            if (api.TfLiteTensorCopyToBuffer(t, mem, bytes) != 0) throw IllegalStateException("uscita $i non letta")
            when (api.TfLiteTensorType(t)) {
                TYPE_FLOAT32 -> mem.getFloatArray(0, (bytes / 4).toInt())
                TYPE_UINT8 -> ByteArray(bytes.toInt()).also { mem.read(0, it, 0, it.size) }.let { b -> FloatArray(b.size) { (b[it].toInt() and 0xff).toFloat() } }
                TYPE_INT32 -> mem.getIntArray(0, (bytes / 4).toInt()).let { a -> FloatArray(a.size) { a[it].toFloat() } }
                else -> throw IllegalStateException("uscita $i di tipo ${typeName(api.TfLiteTensorType(t))}")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { api.TfLiteInterpreterDelete(interp) } catch (t: Throwable) {}
        try { api.TfLiteModelDelete(model) } catch (t: Throwable) {}
    }

    companion object {
        const val TYPE_FLOAT32 = 1
        const val TYPE_INT32 = 2
        const val TYPE_UINT8 = 3
        const val TYPE_INT64 = 4
        fun typeName(t: Int) = when (t) { 1 -> "float32"; 2 -> "int32"; 3 -> "uint8"; 4 -> "int64"; 9 -> "int8"; 10 -> "float16"; else -> "tipo $t" }
    }
}

/**
 * Stable Diffusion con i tre pezzi TFLite di KerasCV: [encoder] (testo → contesto 77×768), [unet] (latente, tempo,
 * contesto → rumore), [decoder] (latente → 512×512 RGB). Gli ingressi si riconoscono dal nome e, se non basta, dalla forma.
 * Kotlin + JNA, senza Android: si collauda sul PC con modelli finti della stessa forma.
 */
class StableDiffusion(lib: String, encoderPath: String, unetPath: String, decoderPath: String, threads: Int) : AutoCloseable {
    private val encoder = TfModel(lib, encoderPath, threads)
    private val unet = try { TfModel(lib, unetPath, threads) } catch (t: Throwable) { encoder.close(); throw t }
    private val decoder = try { TfModel(lib, decoderPath, threads) } catch (t: Throwable) { encoder.close(); unet.close(); throw t }
    private val tokIn: Int
    private val posIn: Int
    private val latIn: Int
    private val tembIn: Int
    private val ctxIn: Int
    /** true = l'unet fa "senza descrizione" e "con descrizione" insieme (due alla volta: più veloce) */
    var batched = false; private set

    init {
        try {
            val ei = encoder.inputs
            require(ei.size == 2) { "text encoder con ${ei.size} ingressi (attesi 2)" }
            val p = ei.indexOfFirst { it.name.contains("position", true) }
            posIn = if (p >= 0) p else 1
            tokIn = 1 - posIn
            val ui = unet.inputs
            require(ui.size == 3) { "diffusion model con ${ui.size} ingressi (attesi 3)" }
            fun find(nameHint: String, count: Int) = ui.indexOfFirst { it.name.contains(nameHint, true) }.takeIf { it >= 0 }
                ?: ui.indexOfFirst { it.count == count }
            latIn = find("latent", Diffusion.SIZE)
            tembIn = find("timestep", Diffusion.TEMB)
            ctxIn = find("context", ClipTokenizer.MAX_TOKENS * 768)
            require(setOf(latIn, tembIn, ctxIn).size == 3 && latIn >= 0 && tembIn >= 0 && ctxIn >= 0) { "ingressi del diffusion model non riconosciuti: $ui" }
            // due alla volta (senza e con descrizione): se il modello lo permette, quasi il doppio più veloce
            batched = try {
                unet.resize(mapOf(latIn to intArrayOf(2, Diffusion.LATENT, Diffusion.LATENT, Diffusion.CHANNELS),
                    tembIn to intArrayOf(2, Diffusion.TEMB), ctxIn to intArrayOf(2, ClipTokenizer.MAX_TOKENS, 768)))
            } catch (t: Throwable) { false }
            if (!batched) unet.resize(mapOf(latIn to intArrayOf(1, Diffusion.LATENT, Diffusion.LATENT, Diffusion.CHANNELS),
                tembIn to intArrayOf(1, Diffusion.TEMB), ctxIn to intArrayOf(1, ClipTokenizer.MAX_TOKENS, 768)))
        } catch (t: Throwable) { close(); throw t }
    }

    fun describe(): String = "encoder ${encoder.inputs} → ${encoder.outputs}; unet ${unet.inputs} → ${unet.outputs}" +
        (if (batched) " (due alla volta)" else "") + "; decoder ${decoder.inputs} → ${decoder.outputs}"

    private fun encode(tokens: IntArray): FloatArray = encoder.run(mapOf(tokIn to tokens, posIn to ClipTokenizer.positions()))[0]

    /**
     * Disegna. [tokens] = la descrizione in 77 numeri ([ClipTokenizer.tokens77]). [onStep] (fatti, totali): false = fermati.
     * Ritorna i pixel RGB 512×512 (riga per riga), o null se fermato.
     */
    fun generate(tokens: IntArray, steps: Int, seed: Long, scale: Float = 7.5f, onStep: (Int, Int) -> Boolean): ByteArray? {
        val ctx = encode(tokens)
        val unc = encode(ClipTokenizer.unconditional())
        if (!onStep(0, steps)) return null
        val latent = Diffusion.sample(steps, seed, scale, eps = { lat, temb ->
            if (batched) {
                val out = unet.run(mapOf(latIn to lat + lat, tembIn to temb + temb, ctxIn to unc + ctx))[0]
                out.copyOfRange(0, Diffusion.SIZE) to out.copyOfRange(Diffusion.SIZE, 2 * Diffusion.SIZE)
            } else {
                unet.run(mapOf(latIn to lat, tembIn to temb, ctxIn to unc))[0] to unet.run(mapOf(latIn to lat, tembIn to temb, ctxIn to ctx))[0]
            }
        }, onStep = onStep) ?: return null
        val img = decoder.run(mapOf(0 to latent))[0]
        return Diffusion.toRgb(img)
    }

    override fun close() { encoder.close(); unet.close(); decoder.close() }
}
