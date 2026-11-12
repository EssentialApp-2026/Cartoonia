package com.essentialapp.sam

/**
 * SAM che agisce da solo (1.20): il cervello, mentre risponde, può chiedere di FARE una cosa scrivendo in fondo
 * «AZIONE: <comando come lo diresti tu>» (es. «AZIONE: metti una sveglia alle 7»). SAM toglie quella riga da quello che
 * dice e mostra, poi esegue il comando con il cervello dei comandi di sempre ([Brain]): così il cervello IA usa tutte le
 * funzioni e le skill che SAM sa già fare, senza inventarne. Kotlin puro.
 */
object Agent {
    /** il cervello può chiedere al massimo questo numero di azioni per risposta */
    const val MAX_ACTIONS = 2

    fun prompt(device: String = "telefono"): String =
        " Non devi solo chiacchierare: puoi anche FARE le cose. Se chi ti parla ti chiede di fare qualcosa, o se fare una cosa è " +
        "chiaramente il modo giusto di aiutarlo, rispondi in breve e poi scrivi in fondo, su una riga a parte, «AZIONE:» seguito dal " +
        "comando in italiano, come lo direbbe lui. Esempi: «AZIONE: metti una sveglia alle 7», «AZIONE: imposta un timer di 10 minuti», " +
        "«AZIONE: cerca su internet il prezzo del rame», «AZIONE: ricordati che il dentista è martedì alle 16», «AZIONE: apri WhatsApp», " +
        "«AZIONE: che tempo fa domani a Napoli», «AZIONE: traduci in inglese: buongiorno a tutti», «AZIONE: correggi questo testo: …», " +
        "«AZIONE: riassumi: …», «AZIONE: scrivimi un programma in Python che …», «AZIONE: fammi una presentazione sul mare», " +
        "«AZIONE: disegna un gatto», «AZIONE: mostrami le foto di ieri», «AZIONE: scatta una foto», «AZIONE: registra un video di 10 secondi», " +
        "«AZIONE: fammi un video con le foto di sabato», «AZIONE: leggimi la chat». Usa solo comandi così, al massimo due, e solo quando servono davvero: " +
        "mai per domande normali, e non dire che l'hai già fatto (lo fa l'app sul $device dopo la tua risposta)."

    private val LINE = Regex("(?im)^\\W{0,3}azion[ei]\\s*:\\s*(.+?)\\s*$")
    private val INLINE = Regex("(?i)(?:^|[.!?…]\\s+)\\W{0,3}azion[ei]\\s*:")

    /** I comandi chiesti dal cervello, nell'ordine (senza doppioni, al massimo [MAX_ACTIONS]). */
    fun actions(raw: String): List<String> {
        val t = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        val out = LinkedHashSet<String>()
        for (m in LINE.findAll(t)) {
            val c = m.groupValues[1].trim().trim('«', '»', '"', '*', '`', '.', ' ')
            if (c.length in 3..400) out.add(c)
        }
        return out.take(MAX_ACTIONS)
    }

    /** Il testo senza le righe «AZIONE: …» (per lo schermo e per la voce). */
    fun strip(raw: String): String {
        if (!raw.contains("azion", ignoreCase = true)) return raw
        var t = LINE.replace(raw, "")
        // anche se l'ha scritto in mezzo a una riga: da lì in poi non si dice
        INLINE.find(t)?.let { t = t.substring(0, it.range.first + (if (it.value.firstOrNull()?.let { c -> c in ".!?…" } == true) 1 else 0)) }
        return if (t == raw) raw else t.trimEnd()
    }

    /** true se il comando, rielaborato da [Brain], ha prodotto qualcosa da fare (non un'altra domanda al cervello IA). */
    fun isAction(o: Outcome): Boolean = o !is Outcome.NeedLlm && o !is Outcome.Chat && o !is Outcome.Quit && o !is Outcome.NewConversation &&
        !(o is Outcome.Speak && o.text.startsWith("Non ho capito"))
}
