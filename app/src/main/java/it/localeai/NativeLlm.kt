package it.localeai

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class NativeLlm {
    external fun loadModel(path: String, contextSize: Int, threads: Int): Boolean

    /** roles: "system" | "user" | "assistant", allineato a contents. */
    external fun generate(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        callback: TokenByteCallback
    ): String?

    external fun requestStop()
    external fun unload()

    companion object {
        init {
            System.loadLibrary("localeai")
        }
    }
}

internal interface TokenByteCallback {
    fun onTokenBytes(bytes: ByteArray)
}

/**
 * Motore unico per tutto il processo: un solo executor serializza
 * caricamento, generazione e rilascio, anche se l'Activity viene ricreata.
 */
internal object LlmEngine {
    val native = NativeLlm()
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-engine").apply { priority = Thread.MAX_PRIORITY }
    }

    @Volatile
    var loadedPath: String? = null

    val threads: Int
        get() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
}
