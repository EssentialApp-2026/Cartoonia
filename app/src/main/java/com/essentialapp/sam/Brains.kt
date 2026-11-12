package com.essentialapp.sam

import java.util.Locale

/** Un cervello IA che SAM sa usare (file .litertlm per il motore LiteRT-LM, sul processore). */
data class BrainModel(
    val name: String,
    val file: String,
    val bytes: Long,
    /** dove scaricarlo, una volta sola, con il browser del telefono (SAM non si collega mai) */
    val url: String,
    val note: String
)

/** Le caratteristiche del telefono che contano per il cervello IA. */
data class PhoneSpecs(
    val ramBytes: Long,
    val cores: Int,
    /** core "grandi" (prestazioni): 0 = non si sa */
    val bigCores: Int,
    val maxGhz: Double,
    /** spazio libero dove vanno i modelli: <= 0 = non si sa */
    val freeBytes: Long,
    val soc: String
)

/**
 * Sceglie il cervello in base all'hardware: RAM, processore e spazio libero.
 * Kotlin puro: si collauda senza telefono.
 */
object Brains {

    private const val HF = "https://huggingface.co/litert-community"

    val GEMMA4_E4B = BrainModel(
        "Gemma 4 E4B", "gemma-4-E4B-it.litertlm", 3_659_530_240L,
        "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        "il più intelligente: capisce, ragiona e segue le istruzioni meglio, ma risponde più lentamente"
    )
    val GEMMA4_E2B = BrainModel(
        "Gemma 4 E2B", "gemma-4-E2B-it.litertlm", 2_588_147_712L,
        "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        "conversa bene in italiano ed è abbastanza veloce"
    )
    val QWEN35_2B = BrainModel(
        "Qwen 3.5 2B", "Qwen3.5-2B_int8.litertlm", 2_116_592_816L,
        "$HF/Qwen3.5-2B/resolve/main/Qwen3.5-2B_int8.litertlm",
        "discreto, ma in italiano Gemma va meglio"
    )
    val QWEN35_08B = BrainModel(
        "Qwen 3.5 0.8B", "Qwen3.5-0.8B_int8.litertlm", 963_184_864L,
        "$HF/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm",
        "piccolo e veloce, ma conversa peggio"
    )
    /** dal più bravo al più leggero */
    val CATALOG = listOf(GEMMA4_E4B, GEMMA4_E2B, QWEN35_2B, QWEN35_08B)

    /** 1.14: il cervello per programmare, in formato GGUF (motore llama.cpp). Non entra nella scelta del "più bravo". */
    val QWEN_CODER_3B = BrainModel(
        "Qwen2.5 Coder 3B", "qwen2.5-coder-3b-instruct-q4_k_m.gguf", 2_100_000_000L,
        "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        "il più bravo a scrivere codice; per chiacchierare in italiano Gemma va meglio"
    )
    val EXTRA = listOf(QWEN_CODER_3B)

    private const val GIB = 1_073_741_824.0

    /** I cervelli adatti a questo telefono, dal migliore al più leggero. */
    fun compatible(p: PhoneSpecs): List<BrainModel> {
        val ram = p.ramBytes / GIB      // la RAM che Android dichiara: un "8 GB" vale circa 7,3–7,6
        // con 12 GB l'E4B ci sta comodo anche con un processore medio: è più lento dell'E2B, ma nettamente più bravo
        // (dalla 1.8: prima su questi telefoni si consigliava l'E2B, per la velocità)
        return when {
            ram >= 10.5 -> listOf(GEMMA4_E4B, GEMMA4_E2B, QWEN35_2B, QWEN35_08B)
            ram >= 5.2 -> listOf(GEMMA4_E2B, QWEN35_2B, QWEN35_08B)
            ram >= 2.8 -> listOf(QWEN35_08B)
            else -> emptyList()
        }
    }

    /**
     * Il cervello consigliato. Serve spazio per il file scaricato, la sua copia dentro SAM
     * e la cache del motore (circa quanto il file): se manca, si scende a uno più leggero.
     */
    fun recommend(p: PhoneSpecs): BrainModel? {
        val ok = compatible(p)
        return ok.firstOrNull { p.freeBytes <= 0 || p.freeBytes >= it.bytes * 2.2 } ?: ok.firstOrNull()
    }

    enum class Fit { OK, HEAVY, TOO_BIG }

    /** Un file di questa dimensione regge su questo telefono? */
    fun fit(fileBytes: Long, p: PhoneSpecs): Fit {
        if (p.ramBytes <= 0) return Fit.OK
        val r = fileBytes.toDouble() / p.ramBytes
        return when {
            r > 0.60 -> Fit.TOO_BIG
            r > 0.45 -> Fit.HEAVY
            else -> Fit.OK
        }
    }

    /** Posizione nel catalogo: più bassa = più bravo. */
    fun rank(m: BrainModel): Int = CATALOG.indexOf(m).takeIf { it >= 0 } ?: 50

    /** Il modello del catalogo corrispondente a un file importato (null = sconosciuto). */
    fun byFile(fileName: String): BrainModel? = (CATALOG + EXTRA).firstOrNull { it.file.equals(fileName, ignoreCase = true) }

    /** Quanti thread dare al motore: i core grandi, da 2 a 4 (i core piccoli rallentano tutti). */
    fun threadsFor(p: PhoneSpecs): Int = (if (p.bigCores > 0) p.bigCores else p.cores).coerceIn(2, 4)

    /** Parametri di campionamento adatti alla famiglia del modello. */
    data class Sampling(val topK: Int, val topP: Float, val temperature: Float, val presence: Float)

    fun samplingFor(fileName: String): Sampling = when {
        // 1.18: un po' meno fantasia (0,8 → 0,6): inventa meno ricordi e dettagli
        fileName.contains("gemma", ignoreCase = true) -> Sampling(64, 0.95f, 0.6f, 0.5f)
        fileName.contains("qwen", ignoreCase = true) -> Sampling(20, 0.8f, 0.7f, 1.2f)
        else -> Sampling(40, 0.9f, 0.7f, 0.8f)
    }

    // ---------------------------------------------------------------- testi
    /** RAM "commerciale": 7,4 GiB dichiarati -> 8 GB. */
    fun nominalRamGb(ramBytes: Long): Int {
        val g = ramBytes / GIB
        return intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32, 64).firstOrNull { it >= g - 0.05 } ?: Math.round(g).toInt()
    }

    fun gb(bytes: Long): String = String.format(Locale.ITALY, "%.1f GB", bytes / 1_000_000_000.0)

    fun describe(p: PhoneSpecs): String {
        val parts = ArrayList<String>()
        parts.add("${nominalRamGb(p.ramBytes)} GB di RAM")
        parts.add(if (p.maxGhz > 0) String.format(Locale.ITALY, "%d core fino a %.1f GHz", p.cores, p.maxGhz) else "${p.cores} core")
        if (p.soc.isNotBlank()) parts.add(p.soc)
        if (p.freeBytes > 0) parts.add("${gb(p.freeBytes)} liberi")
        return parts.joinToString(" · ")
    }
}
