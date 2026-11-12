package com.essentialapp.sam

/** Impronta della voce di una frase (x-vector del modello delle voci di Vosk) e quanta voce l'ha prodotta. */
class VoiceSample(val vector: FloatArray, val frames: Int)

/**
 * L'impronta della voce del proprietario: la media delle sue frasi ([centroid], normalizzata) e la distanza
 * massima per dire "sei tu" su una frase lunga ([threshold]), calibrata sulla sua voce durante l'iscrizione.
 */
class VoiceProfile(val centroid: FloatArray, val threshold: Float, val samples: Int, val updates: Int = 0) {

    fun toText(): String =
        "versione=1\nsoglia=$threshold\ncampioni=$samples\naggiornamenti=$updates\nimpronta=" + centroid.joinToString(",")

    companion object {
        fun fromText(s: String): VoiceProfile? = try {
            val m = s.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
            val c = m["impronta"]!!.split(',').map { it.trim().toFloat() }.toFloatArray()
            if (c.size < 16) null
            else VoiceProfile(c, m["soglia"]!!.toFloat(), m["campioni"]?.toIntOrNull() ?: 0, m["aggiornamenti"]?.toIntOrNull() ?: 0)
        } catch (e: Exception) { null }
    }
}

/**
 * Riconoscimento di chi parla, tutto sul telefono. Kotlin puro: si collauda senza telefono.
 *
 * Misure fatte con il modello delle voci di Vosk (distanza coseno dall'impronta): stessa voce su frasi
 * lunghe 0,06–0,18 (0,33–0,42 con rumore), su comandi brevi 0,20–0,50; voci diverse 0,41–0,93.
 * Quindi: soglia più larga per le frasi brevi, e una fascia di dubbio in cui SAM chiede di ripetere
 * invece di indovinare. Non è una serratura: una voce molto simile o una registrazione possono ingannarlo.
 */
object VoiceId {
    /** meno di ~0,6 secondi di voce: troppo poco per dire chi parla */
    const val MIN_FRAMES = 60
    /** per imparare la voce servono frasi di almeno ~1,2 secondi */
    const val ENROLL_FRAMES = 120
    /** da qui in su una frase è "lunga" e vale la soglia stretta */
    const val LONG_FRAMES = 200
    /** margine in più per le frasi brevi */
    const val SHORT_EXTRA = 0.14f
    /**
     * Tolleranza in più per tutti (1.10.1): nella vita di tutti i giorni la voce cambia più che durante l'iscrizione
     * (distanza dal telefono, stanza, stanchezza, raffreddore), quindi la soglia imparata viene allargata (con le frasi
     * brevi, [SHORT_EXTRA] compreso, 0,17 in più di prima 0,12). Vale anche per le impronte già salvate. Oltre questo
     * margine fisso, la soglia si adatta alla voce vera di chi parla: vedi [relax].
     */
    const val LOOSEN = 0.03f
    /** la soglia imparata (senza tolleranza) non supera mai questo valore, nemmeno ricalibrandosi */
    const val MAX_BASE = 0.50f
    /** una frase detta dopo aver toccato Parla può allargare la soglia al massimo di tanto per volta */
    const val RELAX_STEP = 0.06f
    /** per ricalibrare servono almeno ~0,8 secondi di voce */
    const val RELAX_FRAMES = 80
    /** oltre questa distanza una frase non somiglia più alla tua voce: non serve a ricalibrare */
    const val RELAX_MAX_DIST = 0.62f
    /** fascia di dubbio oltre la soglia: né sì né no */
    const val BAND = 0.10f
    const val ENROLL_SAMPLES = 6

    enum class Verdict { OWNER, OTHER, UNSURE }

    /** Le frasi da ripetere per far imparare la voce: varie, lunghe un paio di secondi. */
    val PHRASES = listOf(
        "Oggi è una bella giornata e ho voglia di uscire a fare una passeggiata.",
        "Il mio assistente funziona senza internet e capisce quello che dico.",
        "Domani mattina devo svegliarmi presto per andare al lavoro.",
        "Ricordati di comprare il pane, il latte e la frutta.",
        "Vorrei sapere che tempo farà nel fine settimana.",
        "Metti una sveglia alle sette e mezza e accendi la torcia.",
        "Chiama la mamma e dille che arrivo tra poco.",
        "Scrivi su WhatsApp che stasera andiamo a cena fuori."
    )

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val s = Math.sqrt(n).toFloat()
        return if (s <= 0f) v.copyOf() else FloatArray(v.size) { v[it] / s }
    }

    /** 1 − coseno: 0 = stessa direzione, 1 = niente in comune. */
    fun distance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 2f
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na <= 0.0 || nb <= 0.0) return 2f
        return (1.0 - d / Math.sqrt(na * nb)).toFloat()
    }

    fun centroid(vs: List<FloatArray>): FloatArray {
        val c = FloatArray(vs[0].size)
        for (v in vs) { val n = normalize(v); for (i in c.indices) c[i] += n[i] }
        return normalize(c)
    }

    /**
     * Impara la voce dalle frasi ripetute. Toglie le frasi anomale (un rumore, un'altra persona che parlava),
     * poi calibra la soglia su quanto variano le tue frasi tra loro. null = troppo poche frasi buone,
     * o frasi troppo diverse tra loro (molto rumore o più persone): meglio rifarlo in un posto tranquillo.
     */
    fun enroll(samples: List<VoiceSample>): VoiceProfile? {
        var vs = samples.filter { it.frames >= ENROLL_FRAMES }.map { normalize(it.vector) }
        if (vs.size < 4) return null
        // distanza di ogni frase dalla media delle altre
        fun loo(list: List<FloatArray>) = list.indices.map { i -> distance(list[i], centroid(list.filterIndexed { j, _ -> j != i })) }
        var d = loo(vs)
        val median = d.sorted()[d.size / 2]
        val keep = vs.indices.filter { d[it] <= median + 0.15f }
        if (keep.size < 4) return null
        vs = keep.map { vs[it] }
        d = loo(vs)
        val mean = d.average().toFloat()
        // misurato: una voce sola 0,10–0,17 (0,27 cambiando microfono e rumore), due voci mescolate 0,35–0,38
        if (mean > 0.32f) return null
        val threshold = (mean * 1.6f + 0.12f).coerceIn(0.30f, 0.50f)
        return VoiceProfile(centroid(vs), threshold, vs.size)
    }

    /** Distanza della frase dall'impronta del proprietario. */
    fun distanceTo(p: VoiceProfile, s: VoiceSample): Float = distance(p.centroid, s.vector)

    /** Soglia per una frase: più larga per quelle brevi. */
    fun thresholdFor(p: VoiceProfile, s: VoiceSample): Float =
        p.threshold + LOOSEN + (if (s.frames >= LONG_FRAMES) 0f else SHORT_EXTRA)

    fun verdict(p: VoiceProfile, s: VoiceSample?): Verdict {
        if (s == null || s.frames < MIN_FRAMES) return Verdict.UNSURE
        val d = distanceTo(p, s)
        val t = thresholdFor(p, s)
        return when {
            d <= t -> Verdict.OWNER
            d >= t + BAND -> Verdict.OTHER
            else -> Verdict.UNSURE
        }
    }

    /** Quanto somiglia alla tua voce, in percentuale, per dirlo a voce (100 = uguale). */
    fun similarity(p: VoiceProfile, s: VoiceSample): Int = Math.round((1f - distanceTo(p, s)) * 100f).coerceIn(0, 100)

    /**
     * Migliora con l'uso: una frase lunga sicuramente tua sposta di poco l'impronta verso come parli adesso
     * (raffreddore, microfono, stanza). null = frase non abbastanza sicura per imparare.
     */
    fun adapt(p: VoiceProfile, s: VoiceSample): VoiceProfile? {
        if (s.frames < 150 || distanceTo(p, s) > p.threshold * 0.6f) return null
        val a = 0.05f
        val v = normalize(s.vector)
        val c = FloatArray(p.centroid.size) { (1 - a) * p.centroid[it] + a * v[it] }
        return VoiceProfile(normalize(c), p.threshold, p.samples, p.updates + 1)
    }

    /**
     * Ricalibra sulla voce di tutti i giorni: [s] è una frase che SAM sa essere del proprietario (l'ha detta dopo
     * aver toccato Parla) ma che non ha superato la soglia. Allarga un poco la soglia e sposta l'impronta verso
     * di lei, così dopo qualche comando la voce viene riconosciuta senza toccare. Poco per volta e con un tetto:
     * una frase che non somiglia affatto alla tua voce (la TV accesa) non cambia niente. null = nessuna modifica.
     */
    fun relax(p: VoiceProfile, s: VoiceSample): VoiceProfile? {
        if (s.frames < RELAX_FRAMES) return null
        val d = distanceTo(p, s)
        if (d > RELAX_MAX_DIST || d <= thresholdFor(p, s)) return null
        val extra = LOOSEN + (if (s.frames >= LONG_FRAMES) 0f else SHORT_EXTRA)
        val wanted = (d - extra + 0.03f).coerceAtMost(MAX_BASE)
        val base = minOf(wanted, p.threshold + RELAX_STEP).coerceAtLeast(p.threshold)
        val a = 0.10f
        val v = normalize(s.vector)
        val c = FloatArray(p.centroid.size) { (1 - a) * p.centroid[it] + a * v[it] }
        return VoiceProfile(normalize(c), base, p.samples, p.updates + 1)
    }
}
