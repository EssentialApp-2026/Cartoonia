package it.localeai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject

/** Turno di conversazione inviato ai motori: role = "user" | "assistant". */
data class Turn(val role: String, val content: String)

class ProviderError(val code: Int, val body: String, val retryAfterSec: Long?) :
    IOException("HTTP $code")

/** Permette di interrompere una richiesta in corso (online o locale). */
class CallHandle {
    @Volatile var cancelled = false
        private set
    @Volatile var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        LlmEngine.native.requestStop()
        val c = connection
        if (c != null) Thread { runCatching { c.disconnect() } }.start()
    }
}

object OnlineClient {
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 90_000

    private fun open(url: String, account: Account, post: Boolean): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.requestMethod = if (post) "POST" else "GET"
        conn.setRequestProperty("Accept", if (post) "text/event-stream" else "application/json")
        if (account.provider.anthropic) {
            conn.setRequestProperty("x-api-key", account.apiKey.trim())
            conn.setRequestProperty("anthropic-version", "2023-06-01")
        } else {
            conn.setRequestProperty("Authorization", "Bearer ${account.apiKey.trim()}")
        }
        if (account.provider == Provider.OPENROUTER) {
            conn.setRequestProperty("HTTP-Referer", "https://essentialapp-2026.github.io")
            conn.setRequestProperty("X-Title", "SAM")
        }
        if (post) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return conn
    }

    private fun checkResponse(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val body = runCatching {
            (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
        }.getOrDefault("")
        val retry = conn.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.toLong()
        throw ProviderError(code, body.take(2000), retry)
    }

    /** Chat in streaming. Bloccante: va chiamata da un thread in background. */
    fun stream(
        account: Account,
        system: String,
        turns: List<Turn>,
        handle: CallHandle,
        onDelta: (String) -> Unit
    ) {
        if (account.provider.anthropic) streamAnthropic(account, system, turns, handle, onDelta)
        else streamOpenAi(account, system, turns, handle, onDelta)
    }

    private fun streamOpenAi(
        account: Account, system: String, turns: List<Turn>,
        handle: CallHandle, onDelta: (String) -> Unit
    ) {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        turns.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject()
            .put("model", account.model.trim())
            .put("messages", messages)
            .put("stream", true)

        val conn = open("${account.effectiveBaseUrl}/chat/completions", account, post = true)
        handle.connection = conn
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            checkResponse(conn)
            readSse(conn, handle) { data ->
                if (data == "[DONE]") return@readSse false
                val json = JSONObject(data)
                json.optJSONObject("error")?.let { err ->
                    throw ProviderError(err.optInt("code", 500), err.toString(), null)
                }
                val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                val content = delta?.opt("content")
                if (content is String && content.isNotEmpty()) onDelta(content)
                true
            }
        } finally {
            handle.connection = null
            conn.disconnect()
        }
    }

    private fun streamAnthropic(
        account: Account, system: String, turns: List<Turn>,
        handle: CallHandle, onDelta: (String) -> Unit
    ) {
        // Anthropic richiede ruoli alternati che iniziano con "user".
        val merged = mutableListOf<Turn>()
        turns.forEach { t ->
            if (merged.isNotEmpty() && merged.last().role == t.role) {
                merged[merged.lastIndex] = Turn(t.role, merged.last().content + "\n\n" + t.content)
            } else merged += t
        }
        while (merged.isNotEmpty() && merged.first().role != "user") merged.removeAt(0)

        val messages = JSONArray()
        merged.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject()
            .put("model", account.model.trim())
            .put("max_tokens", 2048)
            .put("system", system)
            .put("messages", messages)
            .put("stream", true)

        val conn = open("${account.effectiveBaseUrl}/messages", account, post = true)
        handle.connection = conn
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            checkResponse(conn)
            readSse(conn, handle) { data ->
                val json = JSONObject(data)
                when (json.optString("type")) {
                    "content_block_delta" -> {
                        val text = json.optJSONObject("delta")?.opt("text")
                        if (text is String && text.isNotEmpty()) onDelta(text)
                        true
                    }
                    "error" -> {
                        val err = json.optJSONObject("error")
                        val type = err?.optString("type") ?: ""
                        val code = when (type) {
                            "rate_limit_error" -> 429
                            "overloaded_error" -> 529
                            "authentication_error" -> 401
                            else -> 500
                        }
                        throw ProviderError(code, json.toString(), null)
                    }
                    "message_stop" -> false
                    else -> true
                }
            }
        } finally {
            handle.connection = null
            conn.disconnect()
        }
    }

    /** Legge le righe "data:" di uno stream SSE; onData restituisce false per fermarsi. */
    private fun readSse(conn: HttpURLConnection, handle: CallHandle, onData: (String) -> Boolean) {
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            while (!handle.cancelled) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (!onData(data)) break
            }
        }
    }

    /** Elenco dei modelli disponibili per l'account (per scegliere quello giusto). */
    fun listModels(account: Account): List<String> {
        val conn = open("${account.effectiveBaseUrl}/models", account, post = false)
        try {
            checkResponse(conn)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id") ?: continue
                if (id.isNotBlank()) out += id.removePrefix("models/")
            }
            return out.distinct().sorted()
        } finally {
            conn.disconnect()
        }
    }
}

/** Motivo leggibile e durata della pausa per un account che ha dato errore. */
data class Failure(val reason: String, val cooldownMs: Long)

object AiRouter {
    const val SYSTEM_PROMPT =
        "Sei SAM, l'assistente personale di Sam sul suo telefono Android. " +
            "Rispondi sempre in italiano, in modo naturale, chiaro e conciso: le risposte " +
            "vengono spesso lette ad alta voce, quindi evita tabelle, elenchi lunghi e " +
            "formattazione Markdown salvo richiesta esplicita."

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    fun classify(e: Throwable): Failure = when (e) {
        is ProviderError -> {
            val b = e.body.lowercase()
            val retryMs = e.retryAfterSec?.let { it * 1000 }
            when {
                e.code == 401 || (e.code == 403 && !b.contains("quota")) ->
                    Failure("chiave non valida o senza accesso", 12 * HOUR)
                e.code == 402 || b.contains("insufficient") || b.contains("credit") || b.contains("billing") ->
                    Failure("credito esaurito", 12 * HOUR)
                e.code == 429 && (b.contains("per_minute") || b.contains("perminute") ||
                    b.contains("per minute") || b.contains("rpm") || b.contains("tpm")) ->
                    Failure("limite al minuto raggiunto, pausa breve", (retryMs ?: MIN).coerceIn(10_000L, 15 * MIN))
                e.code == 429 && (b.contains("per day") || b.contains("daily") || b.contains("tpd") ||
                    b.contains("rpd") || b.contains("quota") || b.contains("exhausted")) ->
                    Failure("token o quota del giorno esauriti", maxOf(retryMs ?: 0L, HOUR))
                e.code == 429 ->
                    Failure("troppe richieste, pausa breve", (retryMs ?: MIN).coerceIn(10_000L, 15 * MIN))
                e.code == 404 || (e.code == 400 && b.contains("model")) ->
                    Failure("modello non valido: scegline un altro", HOUR)
                e.code == 413 || b.contains("context") || b.contains("too long") || b.contains("too large") ->
                    Failure("conversazione troppo lunga per questo modello", 2 * MIN)
                e.code in 500..599 -> Failure("servizio non disponibile", 2 * MIN)
                else -> Failure("errore ${e.code}", 5 * MIN)
            }
        }
        is UnknownHostException -> Failure("rete non disponibile", 30_000L)
        is SocketTimeoutException -> Failure("nessuna risposta (timeout)", MIN)
        else -> Failure(e.message ?: "errore di rete", MIN)
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    interface Callbacks {
        fun onSource(label: String)
        fun onDelta(text: String)
        fun onNotice(text: String)
    }

    /**
     * Prova gli account nell'ordine scelto; se uno ha finito i token o dà errore
     * lo mette in pausa e passa al successivo. Infine usa il modello locale.
     * Bloccante: chiamare da un thread in background.
     */
    fun generate(context: Context, turns: List<Turn>, handle: CallHandle, cb: Callbacks) {
        val mode = AccountStore.mode

        if (mode != EngineMode.LOCAL_ONLY) {
            val candidates = AccountStore.usable()
            val online = isOnline(context)
            if (online) {
                for (account in candidates) {
                    if (handle.cancelled) return
                    cb.onSource("${account.name} · ${account.model}")
                    var produced = false
                    try {
                        OnlineClient.stream(account, SYSTEM_PROMPT, turns, handle) {
                            produced = true
                            cb.onDelta(it)
                        }
                        if (handle.cancelled) return
                        if (!produced) throw ProviderError(500, "risposta vuota", null)
                        AccountStore.markOk(account)
                        return
                    } catch (e: Throwable) {
                        if (handle.cancelled) return
                        val failure = classify(e)
                        AccountStore.markFailed(account, failure.cooldownMs, failure.reason)
                        if (produced) {
                            cb.onNotice("${account.name}: ${failure.reason} (risposta interrotta)")
                            return
                        }
                        cb.onNotice("${account.name}: ${failure.reason} → passo al successivo")
                    }
                }
            }
            if (mode == EngineMode.ONLINE_ONLY) {
                throw IOException(
                    when {
                        !online -> "Sei offline e la modalità è «Solo online»."
                        candidates.isEmpty() -> "Nessun account online disponibile: aggiungine uno in ⚙."
                        else -> "Tutti gli account online sono in pausa o esauriti."
                    }
                )
            }
            if (!online) cb.onNotice("Offline: uso il modello locale")
            else if (candidates.isNotEmpty()) cb.onNotice("Account online esauriti: uso il modello locale")
        }

        generateLocal(context, turns, handle, cb)
    }

    private fun generateLocal(context: Context, turns: List<Turn>, handle: CallHandle, cb: Callbacks) {
        if (LlmEngine.loadedPath == null) {
            val file = LocalModel.file(context)
            if (!file.exists()) {
                throw IOException(
                    "Nessun motore disponibile: aggiungi un account online o importa un modello GGUF in ⚙."
                )
            }
            if (!LocalModel.loadBlocking(file)) throw IOException("Il modello locale non si carica.")
        }
        if (handle.cancelled) return

        cb.onSource("Locale · ${LocalModel.displayName(context)}")
        val roles = mutableListOf("system")
        val contents = mutableListOf(SYSTEM_PROMPT)
        turns.forEach { roles += it.role; contents += it.content }

        val decoder = Utf8StreamDecoder()
        var failure: Throwable? = null
        val done = CountDownLatch(1)
        LlmEngine.executor.execute {
            try {
                if (!handle.cancelled) {
                    LlmEngine.native.generate(
                        roles.toTypedArray(), contents.toTypedArray(), 512,
                        object : TokenByteCallback {
                            override fun onTokenBytes(bytes: ByteArray) {
                                val s = decoder.decode(bytes, finalChunk = false)
                                if (s.isNotEmpty()) cb.onDelta(s)
                            }
                        }
                    )
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        done.await()
        val tail = decoder.decode(ByteArray(0), finalChunk = true)
        if (tail.isNotEmpty()) cb.onDelta(tail)
        failure?.let { throw it }
    }
}

/** Gestione del file GGUF locale (una sola copia privata). */
object LocalModel {
    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }
    fun file(context: Context): File = File(dir(context), "model.gguf")

    fun displayName(context: Context): String =
        context.getSharedPreferences("sam", Context.MODE_PRIVATE)
            .getString("localModelName", null) ?: "GGUF"

    fun setDisplayName(context: Context, name: String) {
        context.getSharedPreferences("sam", Context.MODE_PRIVATE).edit()
            .putString("localModelName", name).apply()
    }

    /** Carica il modello sull'executor del motore e attende l'esito. */
    fun loadBlocking(file: File): Boolean {
        var ok = false
        val latch = CountDownLatch(1)
        LlmEngine.executor.execute {
            try {
                ok = LlmEngine.native.loadModel(file.absolutePath, 2048, LlmEngine.threads)
                LlmEngine.loadedPath = if (ok) file.absolutePath else null
            } catch (_: Throwable) {
                LlmEngine.loadedPath = null
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return ok
    }

    fun unloadAsync() {
        LlmEngine.executor.execute {
            LlmEngine.native.unload()
            LlmEngine.loadedPath = null
        }
    }
}

/**
 * Decodifica UTF-8 in streaming: i byte di un carattere spezzato tra due
 * token vengono trattenuti fino al callback successivo.
 */
class Utf8StreamDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    @Synchronized
    fun decode(bytes: ByteArray, finalChunk: Boolean): String {
        val combined = pending + bytes
        val inBuf = java.nio.ByteBuffer.wrap(combined)
        val outBuf = java.nio.CharBuffer.allocate(combined.size + 8)
        decoder.decode(inBuf, outBuf, finalChunk)
        if (finalChunk) {
            decoder.flush(outBuf)
            decoder.reset()
        }
        pending = if (finalChunk) ByteArray(0) else combined.copyOfRange(inBuf.position(), combined.size)
        outBuf.flip()
        return outBuf.toString()
    }
}
