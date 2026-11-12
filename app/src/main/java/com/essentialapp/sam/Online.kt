package com.essentialapp.sam

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/*
 * SAM 2.1: cervelli online con più account che si danno il cambio da soli.
 * Gli account si provano nell'ordine scelto; quando uno finisce i token (o dà errore) va in pausa
 * per il tempo giusto e si passa al successivo. Se non ne resta nessuno risponde il cervello del telefono.
 */

/** Fornitori supportati. Tutti tranne Anthropic usano l'API compatibile OpenAI. */
enum class Provider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
    val anthropic: Boolean = false
) {
    GROQ("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "https://console.groq.com/keys"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "https://aistudio.google.com/apikey"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto", "https://openrouter.ai/keys"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", "https://console.mistral.ai/api-keys"),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1", "gpt-oss-120b", "https://cloud.cerebras.ai"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-5-mini", "https://platform.openai.com/api-keys"),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1", "claude-haiku-5-5", "https://console.anthropic.com/settings/keys", anthropic = true),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "https://platform.deepseek.com/api_keys"),
    CUSTOM("Personalizzato (compatibile OpenAI)", "", "", "");
}

class Account(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var provider: Provider,
    var apiKey: String,
    var model: String,
    var baseUrl: String,
    var enabled: Boolean = true,
    var cooldownUntil: Long = 0L,
    var lastError: String = "",
    var lastUsed: Long = 0L,
    var requests: Int = 0
) {
    val effectiveBaseUrl: String
        get() = (if (provider == Provider.CUSTOM) baseUrl else provider.baseUrl).trimEnd('/')

    fun isUsable(now: Long = System.currentTimeMillis()): Boolean =
        enabled && apiKey.isNotBlank() && model.isNotBlank() &&
            effectiveBaseUrl.isNotBlank() && cooldownUntil <= now
}

enum class EngineMode(val label: String) {
    ONLINE_FIRST("Online, poi il cervello del telefono se finiscono i token"),
    ONLINE_ONLY("Solo online"),
    LOCAL_ONLY("Solo il cervello del telefono (offline)")
}

/** Archivio degli account: chiavi cifrate con una chiave del Keystore Android. */
object AccountStore {
    private lateinit var prefs: SharedPreferences
    val accounts = mutableListOf<Account>()

    var mode: EngineMode = EngineMode.ONLINE_FIRST
        set(value) { field = value; prefs.edit().putString("mode", value.name).apply() }

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences("sam_online", Context.MODE_PRIVATE)
        mode = runCatching { EngineMode.valueOf(prefs.getString("mode", null) ?: "") }
            .getOrDefault(EngineMode.ONLINE_FIRST)
        load()
        initialized = true
    }

    @Synchronized
    private fun load() {
        accounts.clear()
        val raw = prefs.getString("accounts", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val provider = runCatching { Provider.valueOf(o.getString("provider")) }
                    .getOrDefault(Provider.CUSTOM)
                accounts += Account(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", provider.label),
                    provider = provider,
                    apiKey = KeyCrypto.decrypt(o.optString("key", "")),
                    model = o.optString("model", provider.defaultModel),
                    baseUrl = o.optString("baseUrl", ""),
                    enabled = o.optBoolean("enabled", true),
                    cooldownUntil = o.optLong("cooldownUntil", 0L),
                    lastError = o.optString("lastError", ""),
                    lastUsed = o.optLong("lastUsed", 0L),
                    requests = o.optInt("requests", 0)
                )
            }
        }
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("name", a.name)
                put("provider", a.provider.name)
                put("key", KeyCrypto.encrypt(a.apiKey))
                put("model", a.model)
                put("baseUrl", a.baseUrl)
                put("enabled", a.enabled)
                put("cooldownUntil", a.cooldownUntil)
                put("lastError", a.lastError)
                put("lastUsed", a.lastUsed)
                put("requests", a.requests)
            })
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    @Synchronized
    fun usable(): List<Account> {
        val now = System.currentTimeMillis()
        return accounts.filter { it.isUsable(now) }
    }

    @Synchronized
    fun markOk(account: Account) {
        account.lastError = ""
        account.cooldownUntil = 0L
        account.lastUsed = System.currentTimeMillis()
        account.requests += 1
        save()
    }

    @Synchronized
    fun markFailed(account: Account, cooldownMs: Long, reason: String) {
        account.lastError = reason
        account.cooldownUntil = System.currentTimeMillis() + cooldownMs
        save()
    }

    @Synchronized
    fun resetCooldowns() {
        accounts.forEach { it.cooldownUntil = 0L; it.lastError = "" }
        save()
    }

    @Synchronized
    fun move(account: Account, delta: Int) {
        val i = accounts.indexOf(account)
        val j = i + delta
        if (i < 0 || j < 0 || j >= accounts.size) return
        accounts.removeAt(i)
        accounts.add(j, account)
        save()
    }

    @Synchronized
    fun upsert(account: Account) {
        val i = accounts.indexOfFirst { it.id == account.id }
        if (i >= 0) accounts[i] = account else accounts += account
        save()
    }

    @Synchronized
    fun remove(account: Account) {
        accounts.removeAll { it.id == account.id }
        save()
    }
}

/** Cifratura AES-GCM con chiave non esportabile del Keystore Android. */
private object KeyCrypto {
    private const val ALIAS = "sam_online_keys"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "v1:" + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        }.getOrElse { "" }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith("v1:")) return ""
        return runCatching {
            val data = Base64.decode(stored.substring(3), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }.getOrElse { "" }
    }
}

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

object OnlineBrain {
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


    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    enum class Outcome { ANSWERED, CANCELLED, NONE }

    @Volatile private var current: CallHandle? = null
    /** "Groq personale · openai/gpt-oss-120b" dell'ultima risposta online, per lo stato */
    @Volatile var lastSource: String? = null
        private set
    /** perché nessun account ha risposto (per dirlo a voce) */
    @Volatile var lastProblem: String? = null
        private set

    /** true = conviene provare online (modalità, account pronti, rete). */
    fun shouldTry(context: Context): Boolean {
        AccountStore.init(context)
        return AccountStore.mode != EngineMode.LOCAL_ONLY && AccountStore.usable().isNotEmpty() && isOnline(context)
    }

    fun onlineOnly(context: Context): Boolean {
        AccountStore.init(context)
        return AccountStore.mode == EngineMode.ONLINE_ONLY
    }

    fun cancel() { current?.cancel() }

    /**
     * Prova gli account in ordine. Bloccante: chiamare da un thread di background.
     * [onChunk] riceve i pezzi di testo; [onNotice] i cambi di account ("Groq ha finito i token, passo a Gemini").
     * ANSWERED = c'è una risposta (anche interrotta a metà); CANCELLED = fermato prima di qualsiasi testo;
     * NONE = nessun account ha risposto: usa il cervello del telefono.
     */
    fun ask(context: Context, system: String, user: String, onChunk: (String) -> Unit, onNotice: (String) -> Unit): Outcome {
        AccountStore.init(context)
        lastProblem = null
        val handle = CallHandle()
        current = handle
        try {
            val accounts = AccountStore.usable()
            if (accounts.isEmpty()) { lastProblem = "nessun account online pronto"; return Outcome.NONE }
            for ((i, account) in accounts.withIndex()) {
                if (handle.cancelled) return Outcome.CANCELLED
                var produced = false
                try {
                    OnlineClient.stream(account, system, listOf(Turn("user", user)), handle) {
                        produced = true
                        onChunk(it)
                    }
                    if (handle.cancelled) return if (produced) Outcome.ANSWERED else Outcome.CANCELLED
                    if (!produced) throw ProviderError(500, "risposta vuota", null)
                    AccountStore.markOk(account)
                    lastSource = "${account.name} · ${account.model}"
                    return Outcome.ANSWERED
                } catch (e: Throwable) {
                    if (handle.cancelled) return if (produced) Outcome.ANSWERED else Outcome.CANCELLED
                    val f = classify(e)
                    AccountStore.markFailed(account, f.cooldownMs, f.reason)
                    if (produced) return Outcome.ANSWERED          // risposta a metà: meglio di niente
                    lastProblem = "${account.name}: ${f.reason}"
                    val next = accounts.getOrNull(i + 1)
                    onNotice(if (next != null) "${account.name}: ${f.reason}. Passo a ${next.name}…"
                             else "${account.name}: ${f.reason}.")
                }
            }
            return Outcome.NONE
        } finally {
            if (current === handle) current = null
        }
    }
}
