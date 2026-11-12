package com.essentialapp.sam

/**
 * I cervelli giusti per ogni lavoro (1.15): SAM sceglie da solo quale modello usare in base a cosa deve fare.
 *  - chiacchierare, rispondere, riassumere: il cervello principale (quello che hai scelto, di solito Gemma 4);
 *  - scrivere codice: il cervello per programmare (Qwen2.5 Coder, GGUF) se c'è;
 *  - guardare (fotocamera, foto): un cervello che vede (Gemma 4 con la vista);
 *  - disegnare: Stable Diffusion (a parte, vedi [Painter]).
 * Il cambio è temporaneo: finito il lavoro (o se torni a chiacchierare) si torna al principale. Kotlin puro.
 */
object ModelRouter {
    enum class Task(val label: String) { CHAT("chiacchierare"), CODE("programmare"), VISION("guardare") }

    /** true se il file (dal nome) è un cervello che vede le immagini: i Gemma 4 in formato LiteRT-LM. */
    fun sees(file: String): Boolean {
        val n = file.lowercase()
        return n.endsWith(".litertlm") && (n.contains("gemma-4") || n.contains("gemma4") || n.contains("gemma-3n") || n.contains("gemma3n"))
    }

    /** true se il file è un cervello per programmare. */
    fun codes(file: String): Boolean {
        val n = file.lowercase()
        return n == Brains.QWEN_CODER_3B.file || n.contains("coder") || n.contains("codegemma") || n.contains("starcoder") ||
            n.contains("codellama") || n.contains("deepseek-coder") || n.contains("codestral")
    }

    /**
     * Il cervello da usare per [task] tra quelli sul telefono ([files], nomi dei file), con [main] = quello principale.
     * Ritorna il nome del file, oppure [main] se va bene quello (o se non c'è niente di meglio).
     */
    fun choose(task: Task, main: String?, files: List<String>): String? = when (task) {
        Task.CHAT -> main
        Task.CODE -> if (main != null && codes(main)) main
                     else files.filter { codes(it) }.let { c -> c.firstOrNull { it.equals(Brains.QWEN_CODER_3B.file, true) } ?: c.firstOrNull() } ?: main
        Task.VISION -> if (main != null && sees(main)) main
                       else files.filter { sees(it) }.let { v -> v.firstOrNull { it.contains("E4B", true) } ?: v.firstOrNull() } ?: main
    }

    /** Il modello che manca per fare bene [task] (da scaricare), null = c'è già quello giusto. */
    fun missing(task: Task, files: List<String>, ramBytes: Long): BrainModel? = when (task) {
        Task.CHAT -> null
        Task.CODE -> if (files.any { codes(it) }) null else Brains.QWEN_CODER_3B
        Task.VISION -> if (files.any { sees(it) }) null
                       else if (ramBytes / 1_073_741_824.0 >= 10.5) Brains.GEMMA4_E4B else Brains.GEMMA4_E2B
    }

    // 1.20: una domanda di programmazione fatta a voce o scritta ("perché questo codice dà errore?") va al cervello per programmare
    private val CODE_TALK = Regex("(?i)```|\\b(?:codice sorgente|questo codice|il codice|nel codice|script|compila|compilare|errore di sintassi|eccezione|" +
        "traceback|stack trace|python|javascript|kotlin|java|html|css|php|sql|mql5|mql4|expert advisor|indicatore mt[45]|bash|powershell|regex|json|debug|bug)\\b|c\\+\\+|c#")

    /** Il lavoro giusto per una domanda al cervello: CODE se parla di programmazione, altrimenti CHAT. */
    fun taskFor(prompt: String): Task = if (CODE_TALK.containsMatchIn(prompt)) Task.CODE else Task.CHAT

    /** Dopo quanto tempo senza lavori speciali si torna al cervello principale. */
    const val BACK_AFTER_MS = 3 * 60_000L
}
