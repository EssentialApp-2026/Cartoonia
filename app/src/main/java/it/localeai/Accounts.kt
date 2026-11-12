package it.localeai

import android.content.Context
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
    ONLINE_FIRST("Online, poi modello locale se finiscono i token"),
    ONLINE_ONLY("Solo online"),
    LOCAL_ONLY("Solo locale (offline)")
}

/** Archivio degli account: chiavi cifrate con una chiave del Keystore Android. */
object AccountStore {
    private lateinit var prefs: SharedPreferences
    val accounts = mutableListOf<Account>()

    var mode: EngineMode = EngineMode.ONLINE_FIRST
        set(value) { field = value; prefs.edit().putString("mode", value.name).apply() }
    var speakReplies: Boolean = true
        set(value) { field = value; prefs.edit().putBoolean("speak", value).apply() }
    var speechRate: Float = 1.0f
        set(value) { field = value; prefs.edit().putFloat("rate", value).apply() }
    var muteBeeps: Boolean = true
        set(value) { field = value; prefs.edit().putBoolean("muteBeeps", value).apply() }
    var permissionsAsked: Boolean = false
        set(value) { field = value; prefs.edit().putBoolean("permAsked", value).apply() }

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences("sam", Context.MODE_PRIVATE)
        mode = runCatching { EngineMode.valueOf(prefs.getString("mode", null) ?: "") }
            .getOrDefault(EngineMode.ONLINE_FIRST)
        speakReplies = prefs.getBoolean("speak", true)
        speechRate = prefs.getFloat("rate", 1.0f)
        muteBeeps = prefs.getBoolean("muteBeeps", true)
        permissionsAsked = prefs.getBoolean("permAsked", false)
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
    private const val ALIAS = "sam_api_keys"

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
