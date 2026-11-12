package com.essentialapp.sam

/**
 * Come parla e come pensa SAM: velocità e tono della voce, quale voce del telefono, fantasia del cervello.
 * Kotlin puro (si collauda senza telefono): le scelte le salva e le applica il servizio vocale.
 */
object Tuning {
    const val RATE_MIN = 0.6f
    const val RATE_MAX = 1.6f
    const val PITCH_MIN = 0.7f
    const val PITCH_MAX = 1.4f
    /** la velocità e il tono normali (prima della 1.10 la velocità era 1,05: un filo di fretta) */
    const val NORMAL = 1.0f
    private const val STEP = 0.1f

    /** Un passo di velocità o di tono: [step] +1 o -1; 0 = normale. Arrotondato al decimo, dentro i limiti. */
    fun stepped(cur: Float, step: Int, min: Float, max: Float): Float =
        if (step == 0) NORMAL else (Math.round((cur + STEP * step) * 10f) / 10f).coerceIn(min, max)

    /**
     * La voce dopo [current] nell'elenco [names] (dalla migliore), o quella prima con [step] -1, girando in tondo.
     * Se [current] non è nell'elenco si parte dalla prima. null = nessuna voce.
     */
    fun nextVoice(names: List<String>, current: String?, step: Int): String? {
        if (names.isEmpty()) return null
        val i = names.indexOf(current)
        if (i < 0) return names[0]
        return names[Math.floorMod(i + (if (step < 0) -1 else 1), names.size)]
    }

    /** La velocità a parole ("normale", "un po' più lenta"…), per dirla e per lo schermo. */
    fun rateWords(r: Float): String = when {
        r < 0.75f -> "molto lenta"
        r < 0.95f -> "più lenta del solito"
        r <= 1.05f -> "normale"
        r < 1.3f -> "più veloce del solito"
        else -> "molto veloce"
    }

    /** Il tono a parole. */
    fun pitchWords(p: Float): String = when {
        p < 0.85f -> "molto grave"
        p < 0.95f -> "più grave del solito"
        p <= 1.05f -> "normale"
        p < 1.2f -> "più acuto del solito"
        else -> "molto acuto"
    }

    /** La fantasia del cervello: -1 preciso, 0 normale, 1 fantasioso (oltre non si va). */
    fun creativity(cur: Int, step: Int): Int = if (step == 0) 0 else (cur + step).coerceIn(-1, 1)

    fun creativityWords(c: Int): String = when {
        c < 0 -> "preciso: invento meno e sto più sui fatti"
        c > 0 -> "fantasioso: le risposte saranno più varie e creative"
        else -> "normale"
    }

    /** Cosa dice SAM dopo aver cambiato la fantasia (da [old] a [now], con il passo chiesto [step]). */
    fun creativityReply(old: Int, now: Int, step: Int): String = when {
        step == 0 && old == 0 -> "La mia fantasia è già normale."
        step == 0 -> "Ok, torno alla fantasia normale."
        now == old && step > 0 -> "Sono già al massimo della fantasia: di più divento poco affidabile."
        now == old -> "Sono già al massimo della precisione."
        now > old && now > 0 -> "Ok, da ora uso più fantasia: le risposte saranno più varie e creative. Se esagero, di': sii più preciso."
        now > old -> "Ok, torno alla fantasia normale."
        now < 0 -> "Ok, da ora sono più preciso: invento meno e sto più sui fatti."
        else -> "Ok, torno alla fantasia normale."
    }
}
