package com.essentialapp.sam

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Estrazione di archivi zip, Kotlin puro (usata per il modello vocale incluso nell'app). */
object Unzip {

    /**
     * Estrae [input] (zip) in [dir] togliendo la prima cartella dell'archivio
     * (es. "vosk-model-small-it-0.22/am/final.mdl" -> "am/final.mdl").
     * [total] = dimensione dello zip, per la percentuale.
     */
    fun stripTop(input: InputStream, total: Long, dir: File, onPct: (Int) -> Unit) {
        dir.mkdirs()
        val counting = Counting(input)
        val root = dir.canonicalFile
        ZipInputStream(counting.buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/')
                if (rel.isNotEmpty()) {
                    val out = File(dir, rel)
                    // sicurezza: niente percorsi che escono dalla cartella
                    if (!out.canonicalPath.startsWith(root.path + File.separator)) throw SecurityException("percorso non valido: ${e.name}")
                    if (e.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                }
                onPct(((counting.count * 100) / total.coerceAtLeast(1)).toInt().coerceIn(0, 100))
                zin.closeEntry(); e = zin.nextEntry
            }
        }
    }

    private class Counting(private val inner: InputStream) : InputStream() {
        var count = 0L; private set
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}

/** Copia controllata di un cervello, Kotlin puro. */
object ModelFile {
    const val MAGIC = "LITERTLM"

    /** I formati di cervello che SAM sa usare, riconosciuti dai primi byte del file (non dal nome). */
    enum class Kind(val ext: String, val label: String) {
        LITERTLM("litertlm", "LiteRT-LM"),
        /** il formato di MediaPipe / Google AI Edge: un archivio zip con il modello TFLite e il vocabolario */
        TASK("task", "MediaPipe"),
        /** il formato di llama.cpp, LM Studio e Ollama */
        GGUF("gguf", "GGUF")
    }

    /** Il formato dai primi 8 byte; null = non è un cervello che SAM sa usare. */
    fun sniff(head: ByteArray, n: Int = head.size): Kind? = when {
        n >= 8 && String(head, 0, 8, Charsets.US_ASCII) == MAGIC -> Kind.LITERTLM
        n >= 4 && head[0] == 'G'.code.toByte() && head[1] == 'G'.code.toByte() && head[2] == 'U'.code.toByte() && head[3] == 'F'.code.toByte() -> Kind.GGUF
        n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4 -> Kind.TASK
        else -> null
    }

    /** true se il nome è di un cervello ([Kind]): .litertlm, .task, .gguf */
    fun isBrainName(name: String): Boolean = Kind.values().any { name.endsWith("." + it.ext, ignoreCase = true) }

    /** Il motivo per cui un file non va bene, detto in base al nome (per i formati più comuni che SAM non usa). */
    fun whyNot(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".safetensors") || n.endsWith(".bin") || n.endsWith(".pt") || n.endsWith(".pth") ->
                "è un modello in formato PyTorch/safetensors: sul telefono serve la versione GGUF (cerca su Hugging Face il nome del modello con «GGUF»)"
            n.endsWith(".onnx") -> "è un modello ONNX: SAM usa i formati GGUF, .litertlm e .task"
            n.endsWith(".zip") -> "è un archivio zip: estrai prima il file del cervello"
            else -> "non è un cervello che SAM sa usare: vanno bene i file .gguf, .litertlm e .task"
        }
    }

    /**
     * Copia [input] in [out] solo se è un cervello che SAM sa usare ([Kind]), e dice quale.
     * [size] = dimensione attesa (o -1 se ignota), per la percentuale. [name] serve solo a spiegare il rifiuto.
     */
    fun copyChecked(input: java.io.InputStream, size: Long, out: File, onPct: (Int) -> Unit, name: String = ""): Kind {
        val head = ByteArray(8)
        var got = 0
        while (got < 8) { val r = input.read(head, got, 8 - got); if (r < 0) break; got += r }
        val kind = sniff(head, got) ?: throw IllegalArgumentException(whyNot(name))
        out.outputStream().use { o ->
            o.write(head)
            val buf = ByteArray(1 shl 20)
            var done = 8L; var r: Int
            while (input.read(buf).also { r = it } >= 0) {
                o.write(buf, 0, r); done += r
                if (size > 0) onPct(((done * 100) / size).toInt().coerceIn(0, 100))
            }
        }
        if (kind == Kind.GGUF) {
            val g = try { GgufInfo.read(out) } catch (e: Exception) { null }
            if (g != null && (g.isGenerator || g.isProjector)) { out.delete(); throw IllegalArgumentException(if (g.isProjector) PROJECTOR else generatorWhy(name, g.arch)) }
        }
        return kind
    }

    const val PROJECTOR = "è solo il pezzo della vista (mmproj) di un cervello: da solo non conversa. Importa il cervello vero (il file .gguf più grande)"

    /** Wan e gli altri modelli che creano video o immagini: non sono cervelli per conversare. */
    fun generatorWhy(name: String, arch: String?): String =
        if (Regex("(?i)wan|t2v|i2v|video").containsMatchIn(name) || arch.equals("wan", true))
            "è un modello che crea video (Wan): sul telefono non ci sta insieme a tutto quello che serve e ci metterebbe ore. Usalo con SAM per PC, in Impostazioni › Modelli › Video"
        else "è un modello che crea immagini o un suo pezzo, non un cervello per conversare: SAM usa i GGUF dei modelli di linguaggio (Gemma, Qwen, Llama…)"
}
