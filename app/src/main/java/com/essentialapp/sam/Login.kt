package com.essentialapp.sam

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/**
 * SAM 2.1.1: "Accedi con OpenRouter". Login vero (OAuth PKCE): si apre il browser, entri col tuo account
 * (Google, GitHub o email), OpenRouter rimanda a un piccolo server di SAM sul telefono (127.0.0.1) e SAM
 * riceve la chiave da solo. Niente chiavi da copiare.
 */
object OpenRouterLogin {
    data class Result(val key: String, val model: String)

    @Volatile private var server: ServerSocket? = null

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun cancel() { try { server?.close() } catch (e: Exception) {} ; server = null }

    /** Avvia il login. [done] arriva sul thread principale: risultato oppure messaggio d'errore. */
    fun start(activity: Activity, done: (Result?, String?) -> Unit) {
        cancel()
        val main = Handler(Looper.getMainLooper())
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = b64url(verifierBytes)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val socket = try {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).also { it.soTimeout = 10 * 60_000 }
        } catch (e: Exception) {
            done(null, "non riesco a preparare il ritorno dal login (${e.message})"); return
        }
        server = socket
        val callback = "http://127.0.0.1:${socket.localPort}/sam"
        val url = "https://openrouter.ai/auth?callback_url=${Uri.encode(callback)}" +
            "&code_challenge=$challenge&code_challenge_method=S256"

        Thread {
            var result: Result? = null
            var error: String? = null
            try {
                val code = waitForCode(socket)
                val key = exchange(code, verifier)
                result = Result(key, pickFreeModel(key))
            } catch (e: SocketTimeoutException) {
                error = "tempo scaduto: il login non è stato completato"
            } catch (e: Exception) {
                error = if (server == null) null else (e.message ?: "errore durante il login")
            } finally {
                try { socket.close() } catch (e: Exception) {}
                if (server === socket) server = null
            }
            main.post { if (result != null || error != null) done(result, error) }
        }.start()

        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            cancel(); done(null, "non trovo un browser per aprire il login")
        }
    }

    /** Aspetta che il browser torni su http://127.0.0.1:porta/sam?code=… e risponde con una pagina "fatto". */
    private fun waitForCode(socket: ServerSocket): String {
        while (true) {
            val client = socket.accept()
            client.use { c ->
                c.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return@use
                val path = requestLine.split(" ").getOrNull(1) ?: ""
                val query = path.substringAfter('?', "")
                val params = query.split('&').filter { it.contains('=') }.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                val code = params["code"]
                val ok = path.startsWith("/sam") && !code.isNullOrBlank()
                val body = if (ok) page("Fatto ✓", "SAM ha ricevuto l'accesso a OpenRouter. Torna su SAM.")
                    else page("SAM", if (path.startsWith("/sam")) "Accesso non completato: riprova da SAM." else "")
                val bytes = body.toByteArray(Charsets.UTF_8)
                val out = c.getOutputStream()
                out.write(("HTTP/1.1 ${if (ok || path.startsWith("/sam")) "200 OK" else "404 Not Found"}\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII))
                out.write(bytes); out.flush()
                if (ok) return code!!
                if (path.startsWith("/sam")) throw IllegalStateException("OpenRouter non ha dato il codice di accesso")
            }
        }
    }

    private fun page(title: String, text: String) =
        "<!doctype html><html lang=\"it\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title><body style=\"font-family:sans-serif;background:#0e1118;color:#e2e7f0;" +
            "display:flex;align-items:center;justify-content:center;height:90vh;text-align:center\">" +
            "<div><h1>$title</h1><p>$text</p></div></body></html>"

    private fun exchange(code: String, verifier: String): String {
        val conn = URL("https://openrouter.ai/api/v1/auth/keys").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("code", code).put("code_verifier", verifier).put("code_challenge_method", "S256")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) throw IllegalStateException("OpenRouter ha rifiutato il codice (errore $status)")
            return JSONObject(text).optString("key").ifBlank { throw IllegalStateException("OpenRouter non ha dato la chiave") }
        } finally { conn.disconnect() }
    }

    /** Un modello gratuito per cominciare (si cambia quando vuoi con «Scegli il modello dall'elenco»). */
    private fun pickFreeModel(key: String): String = try {
        val conn = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $key")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val data = JSONObject(text).optJSONArray("data")
        val free = mutableListOf<String>()
        if (data != null) for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id")
            val p = m.optJSONObject("pricing")
            val isFree = id.endsWith(":free") ||
                (p != null && p.optString("prompt") == "0" && p.optString("completion") == "0")
            if (isFree && id.isNotBlank() && id != "openrouter/auto") free += id
        }
        val preferred = listOf("llama-3.3-70b", "deepseek", "gpt-oss", "qwen", "gemma", "mistral", "llama")
        preferred.firstNotNullOfOrNull { want -> free.firstOrNull { it.contains(want) } } ?: free.firstOrNull() ?: "openrouter/auto"
    } catch (e: Exception) { "openrouter/auto" }
}

/** SAM 2.1.1: riconosce una chiave API copiata negli appunti, servizio per servizio. */
object KeySniffer {
    private val patterns = mapOf(
        Provider.GROQ to Regex("^gsk_[A-Za-z0-9]{20,}$"),
        Provider.GEMINI to Regex("^AIza[0-9A-Za-z_\\-]{30,}$"),
        Provider.OPENROUTER to Regex("^sk-or-v1-[0-9a-zA-Z]{20,}$"),
        Provider.ANTHROPIC to Regex("^sk-ant-[A-Za-z0-9_\\-]{20,}$"),
        Provider.CEREBRAS to Regex("^csk-[A-Za-z0-9]{20,}$"),
        Provider.OPENAI to Regex("^sk-(?!or-|ant-)[A-Za-z0-9_\\-]{20,}$"),
        Provider.DEEPSEEK to Regex("^sk-[0-9a-f]{32}$"),
        Provider.MISTRAL to Regex("^[A-Za-z0-9]{32}$")
    )

    /** La chiave negli appunti, se è del servizio [p]. */
    fun match(p: Provider, text: String?): String? {
        val t = text?.trim()?.removeSurrounding("\"") ?: return null
        if (t.length > 300 || t.contains(' ')) return null
        return t.takeIf { patterns[p]?.matches(it) == true }
    }
}
