package com.essentialapp.sam

import java.io.InputStream
import java.util.zip.GZIPInputStream

/*
 * Disegnare (1.14): Stable Diffusion 1.4 sul telefono, nei tre pezzi TFLite di KerasCV (text encoder, diffusion model,
 * decoder, convertiti da Sayak Paul: huggingface.co/sayakpaul/kerascv_sd_tflite, licenza CreativeML OpenRAIL-M), fatti
 * girare dall'interprete TFLite che c'è già dentro la libreria di LiteRT-LM. Qui la parte senza Android: il tokenizzatore
 * di CLIP, il calendario del rumore (DDIM, come KerasCV), i pixel, le frasi. Kotlin puro: si collauda senza telefono.
 */

/**
 * Il tokenizzatore di CLIP (lo stesso di OpenAI e di KerasCV): il testo diventa 77 numeri. Il vocabolario
 * ("bpe_simple_vocab_16e6.txt.gz", 49 408 pezzi di parola) sta negli assets dell'app.
 */
class ClipTokenizer(vocabGz: InputStream) {
    private val byteEncoder: Map<Int, Char>
    private val ranks: Map<Pair<String, String>, Int>
    private val encoder: Map<String, Int>
    private val cache = HashMap<String, List<String>>()

    init {
        // bytes_to_unicode di GPT-2/CLIP: ogni byte ha un carattere "stampabile"
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code); bs.addAll('¡'.code..'¬'.code); bs.addAll('®'.code..'ÿ'.code)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        byteEncoder = bs.indices.associate { bs[it] to cs[it].toChar() }
        // 2.1: la build Gradle scompatta da sola gli assets .gz (e toglie l'estensione): si accettano tutti e due
        val buffered = java.io.BufferedInputStream(vocabGz)
        buffered.mark(2)
        val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val lines = (if (gz) GZIPInputStream(buffered) else buffered).bufferedReader(Charsets.UTF_8).readLines()
        val merges = lines.subList(1, 49152 - 256 - 2 + 1).map { val p = it.split(' '); p[0] to p[1] }
        ranks = merges.withIndex().associate { it.value to it.index }
        val vocab = ArrayList<String>()
        val base = bs.map { byteEncoder[it]!!.toString() }
        vocab.addAll(base); vocab.addAll(base.map { "$it</w>" })
        for ((a, b) in merges) vocab.add(a + b)
        vocab.add(START_TEXT); vocab.add(END_TEXT)
        encoder = vocab.withIndex().associate { it.value to it.index }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word: MutableList<String> = token.dropLast(1).map { it.toString() }.toMutableList().also { it.add(token.last() + "</w>") }
        if (word.size == 1) return listOf(token + "</w>")
        while (true) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val r = ranks[word[i] to word[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = word[i] to word[i + 1] }
            }
            val (first, second) = best ?: break
            val out = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) { out.add(first + second); i += 2 }
                else { out.add(word[i]); i++ }
            }
            word = out
            if (word.size == 1) break
        }
        cache[token] = word
        return word
    }

    /** I numeri dei pezzi di parola (senza inizio e fine). */
    fun encode(text: String): List<Int> {
        val clean = text.replace(Regex("\\s+"), " ").trim().lowercase()
        val out = ArrayList<Int>()
        for (m in PATTERN.findAll(clean)) {
            val tok = m.value.toByteArray(Charsets.UTF_8).joinToString("") { byteEncoder[it.toInt() and 0xff]!!.toString() }
            for (p in bpe(tok)) encoder[p]?.let { out.add(it) }
        }
        return out
    }

    /** 77 numeri: inizio, la frase (tagliata se troppo lunga), fine, e poi "fine" fino in fondo (come KerasCV). */
    fun tokens77(text: String): IntArray {
        val body = encode(text).take(MAX_TOKENS - 2)
        val t = IntArray(MAX_TOKENS) { END }
        t[0] = START
        for ((i, v) in body.withIndex()) t[i + 1] = v
        t[body.size + 1] = END
        return t
    }

    companion object {
        const val START = 49406
        const val END = 49407
        const val MAX_TOKENS = 77
        const val START_TEXT = "<|startoftext|>"
        const val END_TEXT = "<|endoftext|>"
        private val PATTERN = Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            RegexOption.IGNORE_CASE)

        /** Il "testo vuoto" per la guida senza descrizione: inizio e poi tutto "fine". */
        fun unconditional(): IntArray = IntArray(MAX_TOKENS) { if (it == 0) START else END }

        fun positions(): IntArray = IntArray(MAX_TOKENS) { it }
    }
}

/** Il calendario del rumore di Stable Diffusion e il passo di DDIM, come in KerasCV (generate_image). */
object Diffusion {
    const val LATENT = 64          // 512 pixel / 8
    const val CHANNELS = 4
    const val TEMB = 320
    const val SIZE = LATENT * LATENT * CHANNELS

    /** alphas_cumprod: 1000 valori, beta "scaled linear" da 0,00085 a 0,012. */
    val alphasCumprod: DoubleArray by lazy {
        val a = DoubleArray(1000)
        val s0 = Math.sqrt(0.00085); val s1 = Math.sqrt(0.012)
        var prod = 1.0
        for (i in 0 until 1000) {
            val b = s0 + (s1 - s0) * i / 999.0
            prod *= 1.0 - b * b
            a[i] = prod
        }
        a
    }

    /** I tempi del rumore per [steps] passi: 1, 1+k, 1+2k… (k = 1000/steps), come KerasCV. */
    fun timesteps(steps: Int): IntArray {
        val k = maxOf(1, 1000 / steps)
        return (1 until 1000 step k).toList().toIntArray()
    }

    /** Il tempo [t] come vettore di 320 numeri (coseni e seni a frequenze diverse). */
    fun timestepEmbedding(t: Int): FloatArray {
        val half = TEMB / 2
        val out = FloatArray(TEMB)
        for (i in 0 until half) {
            val freq = Math.exp(-Math.log(10000.0) * i / half)
            val arg = t * freq
            out[i] = Math.cos(arg).toFloat()
            out[i + half] = Math.sin(arg).toFloat()
        }
        return out
    }

    /** Rumore iniziale (gaussiano), lo stesso per lo stesso [seed]. */
    fun noise(seed: Long): FloatArray {
        val r = java.util.Random(seed)
        return FloatArray(SIZE) { r.nextGaussian().toFloat() }
    }

    /** La guida: senza descrizione + [scale] × (con descrizione − senza). */
    fun guide(uncond: FloatArray, cond: FloatArray, scale: Float): FloatArray =
        FloatArray(cond.size) { uncond[it] + scale * (cond[it] - uncond[it]) }

    /** Un passo di DDIM: dal rumore previsto [eps] al latente del passo prima. */
    fun ddimStep(latent: FloatArray, eps: FloatArray, aT: Double, aPrev: Double): FloatArray {
        val sa = Math.sqrt(aT); val s1a = Math.sqrt(1 - aT)
        val sp = Math.sqrt(aPrev); val s1p = Math.sqrt(1 - aPrev)
        return FloatArray(latent.size) {
            val x0 = (latent[it] - s1a * eps[it]) / sa
            (eps[it] * s1p + sp * x0).toFloat()
        }
    }

    /**
     * L'immagine: un passo alla volta dal rumore al latente pulito. [unet] (latente, tempo, contesto) → rumore previsto,
     * [both] (latente, tempo) → (senza descrizione, con descrizione) se il modello ne fa due insieme (più veloce).
     * [onStep] (passo fatto, passi totali): false = fermati. null = fermato.
     */
    fun sample(steps: Int, seed: Long, scale: Float,
               eps: (latent: FloatArray, temb: FloatArray) -> Pair<FloatArray, FloatArray>,
               onStep: (Int, Int) -> Boolean): FloatArray? {
        val ts = timesteps(steps)
        val alphas = ts.map { alphasCumprod[it] }
        val prev = listOf(1.0) + alphas.dropLast(1)
        var latent = noise(seed)
        var done = 0
        for (i in ts.indices.reversed()) {
            val (u, c) = eps(latent, timestepEmbedding(ts[i]))
            val e = guide(u, c, scale)
            latent = ddimStep(latent, e, alphas[i], prev[i])
            done++
            if (!onStep(done, ts.size)) return null
        }
        return latent
    }

    /**
     * I pixel (RGB, 0–255) dall'uscita del decoder: se è tra -1 e 1 (KerasCV) si porta a 0–255, se è già 0–255 si tiene.
     */
    fun toRgb(out: FloatArray): ByteArray {
        var mx = Float.NEGATIVE_INFINITY
        for (v in out) if (v > mx) mx = v
        val unit = mx <= 2f
        return ByteArray(out.size) {
            val v = if (unit) (out[it] + 1f) / 2f * 255f else out[it]
            v.coerceIn(0f, 255f).toInt().toByte()
        }
    }

    /** Quanto manca, a parole ("circa 3 minuti", "meno di un minuto"). */
    fun eta(msLeft: Long): String = when {
        msLeft < 60_000 -> "meno di un minuto"
        msLeft < 90_000 -> "circa un minuto"
        else -> "circa ${(msLeft + 30_000) / 60_000} minuti"
    }
}

/** Le frasi per disegnare. */
object DrawCommands {
    private fun ci(p: String) = Lang.rx(p)
    private const val FILL = "(?:(?:sam|ehi|ok|allora|senti|per favore|perfavore|dai|adesso|ora)[,]?\\s+)*(?:(?:mi |ti )?(?:puoi |potresti |riesci a |vorrei che )?)?"
    private val DRAW = ci(
        "^$FILL(?:disegna|disegnami|disegnare|disegnarmi|dipingi|dipingimi|dipingere|crea|creami|creare|crearmi|genera|generami|generare|generarmi|" +
        "fammi|fai|farmi|fare|realizza|realizzami|illustra|illustrami)\\s+" +
        "(?:(?:un'|una |un |l'|la |il |uno )?(?:immagine|immaginetta|foto|fotografia|disegno|disegnino|dipinto|quadro|illustrazione|ritratto|" +
        "sfondo|logo|icona|vignetta|figura)(?: (?:realistic[ao]|fotorealistic[ao]|bella|bello|ad alta qualità|in alta qualità|di alta qualità))?\\s+" +
        "(?:di|del|della|dello|dei|delle|degli|con|che|su|sul|sulla|raffigurante|che mostra|che rappresenta|dove)\\s+|" +
        "(?=\\S))(.+)$"
    )
    private val DRAW_VERB_ONLY = ci("^$FILL(?:disegna|disegnami|disegnare|disegnarmi|dipingi|dipingimi|dipingere|illustrami|illustrare)\\b")
    private val DOWNLOAD = ci(
        "^$FILL(?:scarica|scaricami|scaricare|installa|installami|prendi)\\s+(?:il |i )?(?:modell[oi]|cervell[oi]|programma)\\s+" +
        "(?:per (?:disegnare|le immagini|fare (?:le )?immagini|creare (?:le )?immagini)|delle immagini|dei disegni|di stable diffusion)$|" +
        "^$FILL(?:scarica|installa)\\s+stable diffusion$"
    )
    private val CAN_DRAW = ci("^(?:sai|riesci a|puoi|sei capace di)?\\s*(?:disegnare|fare (?:le |delle )?immagini|creare (?:le |delle )?immagini|generare (?:le |delle )?immagini)\\??$")
    // i video (1.16): "fammi un video di un gatto che vola" → disegno animato (zoom e panoramica) salvato in MP4
    private val VIDEO = ci(
        "^$FILL(?:fammi|fai|farmi|fare|crea|creami|creare|crearmi|genera|generami|generare|generarmi|realizza|realizzami|realizzare|prepara|preparami|voglio)\\s+" +
        "(?:un |il |uno |una |un'|l')?(?:video|videoclip|video clip|filmato|filmino|cortometraggio|animazione|clip|gif)(?: animat[oa]| breve| corto)?\\s+" +
        "(?:di|del|della|dello|dei|delle|degli|con|su|sul|sulla|sullo|sui|che mostra|che mostri|dove|raffigurante|in cui)\\s+(.+)$"
    )
    private val CAN_VIDEO = ci("^(?:sai|riesci a|puoi|sei capace di)?\\s*(?:fare|creare|generare|realizzare) (?:i |dei |un )?(?:video|filmati|animazioni)\\??$")
    private val QUALITY = ci("\\b(?:alta qualità|qualità alta|con calma|fatt[oa] bene|dettagliat[oa]|più bell[oa]|massima qualità)\\b")
    private val FAST = ci("\\b(?:veloce|velocemente|in fretta|rapid[oa]|bozza|schizzo)\\b")
    private val NOT_DRAW = ci("^(?:fammi|fai|crea|creami|genera|generami)\\s+(?:un |una |il |la )?(?:file|documento|pdf|programma|codice|script|" +
        "promemoria|sveglia|timer|nota|lista|elenco|foto(?:grafia)? (?:con|dalla) (?:la )?fotocamera|una foto$|la foto$|una chiamata|un messaggio)\\b|" +
        "^(?:fammi|fai) (?:vedere|sapere|sentire|ridere|compagnia|una domanda|un riassunto|una sorpresa)\\b")

    /** "disegna un gatto sulla luna" → Draw("un gatto sulla luna"); "scarica il modello delle immagini" → download. null = altro. */
    fun parse(t: String): Outcome.Draw? {
        val s = t.trim().trimEnd('.', '!', '?')
        if (DOWNLOAD.containsMatchIn(s)) return Outcome.Draw(null, "download")
        if (CAN_DRAW.matches(s)) return Outcome.Draw(null, "can")
        if (CAN_VIDEO.matches(s)) return Outcome.Draw(null, "canvideo")
        VIDEO.find(s)?.let { m ->
            val what = m.groupValues[1].trim()
            if (Remember.words(what).isEmpty()) return null
            val steps = when { QUALITY.containsMatchIn(s) -> 25; FAST.containsMatchIn(s) -> 10; else -> 15 }
            return Outcome.Draw(what, "video", steps)
        }
        if (NOT_DRAW.containsMatchIn(s)) return null
        val m = DRAW.find(s) ?: return null
        val what = m.groupValues[1].trim()
        // "fammi un panino", "crea un gruppo": senza "immagine/disegno" solo i verbi del disegno contano
        val explicit = m.value.length > what.length + 1 && Lang.rx("\\b(?:immagine|immaginetta|foto|fotografia|disegno|disegnino|dipinto|quadro|" +
            "illustrazione|ritratto|sfondo|logo|icona|vignetta|figura)\\b").containsMatchIn(s.substring(0, s.length - what.length))
        if (!explicit && !DRAW_VERB_ONLY.containsMatchIn(s)) return null
        if (Remember.words(what).isEmpty()) return null
        val steps = when { QUALITY.containsMatchIn(s) -> 25; FAST.containsMatchIn(s) -> 10; else -> 15 }
        return Outcome.Draw(what, "draw", steps)
    }

    /** Le istruzioni per tradurre la descrizione in inglese (Stable Diffusion capisce solo l'inglese). */
    fun translatePrompt(): String =
        "Traduci in inglese la descrizione di un'immagine, per un generatore di immagini. Mantieni il significato, aggiungi al massimo " +
        "pochi dettagli visivi utili (luce, stile, qualità). Rispondi solo con la descrizione in inglese, su una riga, massimo 40 parole."

    /** La descrizione in inglese dalla risposta del cervello; null = non usabile (si usa quella in italiano). */
    fun cleanTranslation(raw: String?): String? {
        if (raw == null) return null
        var t = raw.replace(Regex("(?s)<think>.*?</think>"), " ").replace("<think>", " ").replace("</think>", " ")
        t = t.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = t.removePrefix("Prompt:").removePrefix("English:").removePrefix("Traduzione:").trim().trim('"', '«', '»', '*', ' ')
        if (t.length < 3 || t.length > 400) return null
        return t
    }
}
