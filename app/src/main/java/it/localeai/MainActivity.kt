package it.localeai

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CONTEXT_SIZE = 2048
private const val MAX_NEW_TOKENS = 512
private const val HISTORY_MESSAGES = 12
private const val SYSTEM_PROMPT =
    "Sei Locale AI, un assistente utile che funziona offline sul telefono. " +
        "Rispondi sempre in italiano, in modo chiaro e conciso."

internal data class ChatMessage(val fromUser: Boolean, val text: String)

class MainActivity : AppCompatActivity() {
    private val engine = LlmEngine
    private val closed = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var actionButton: Button
    private lateinit var modelButton: Button
    private lateinit var newChatButton: Button
    private lateinit var progressBar: ProgressBar

    private var modelLoaded = false
    private var busyWithModel = false

    private val modelsDir: File by lazy { File(filesDir, "models").apply { mkdirs() } }
    private val modelFile: File get() = File(modelsDir, "model.gguf")

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importModel(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        createUi()
        cleanupLegacyFiles()

        val alreadyLoaded = engine.loadedPath
        when {
            alreadyLoaded != null && File(alreadyLoaded).exists() -> {
                modelLoaded = true
                status.text = "Modello caricato · inferenza locale"
            }
            modelFile.exists() -> loadModel(modelFile, imported = false)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createUi() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF6F7F4.toInt())
        }

        // Spazio per barra di stato, barra di navigazione e tastiera
        // (targetSdk 35 impone la modalità edge-to-edge su Android 15+).
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, dp(8) + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val title = TextView(this).apply {
            text = "Locale AI"
            textSize = 25f
            setTextColor(0xFF17221F.toInt())
            setTypeface(null, Typeface.BOLD)
        }

        status = TextView(this).apply {
            text = "Nessun modello caricato · inferenza solo sul dispositivo"
            textSize = 14f
            setTextColor(0xFF53645E.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        modelButton = Button(this).apply {
            text = "Scegli modello GGUF"
            isAllCaps = false
            setOnClickListener {
                if (!busyWithModel && !generating.get()) pickModel.launch(arrayOf("*/*"))
            }
        }
        newChatButton = Button(this).apply {
            text = "Nuova chat"
            isAllCaps = false
            setOnClickListener {
                if (!generating.get()) {
                    messages.clear()
                    chatAdapter.notifyDataSetChanged()
                }
            }
        }
        buttons.addView(modelButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(newChatButton, LinearLayout.LayoutParams(-2, -2))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        chatAdapter = ChatAdapter(messages)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            adapter = chatAdapter
            itemAnimator = null   // evita lo sfarfallio durante lo streaming
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Scrivi un messaggio…"
            minLines = 1
            maxLines = 4
        }
        actionButton = Button(this).apply {
            text = "Invia"
            isAllCaps = false
            setOnClickListener { onActionButton() }
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(actionButton)

        root.addView(title)
        root.addView(status)
        root.addView(buttons)
        root.addView(progressBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottom)

        setContentView(root)
    }

    /** Rimuove le copie lasciate dalla versione precedente (model-*.gguf). */
    private fun cleanupLegacyFiles() {
        filesDir.listFiles { f -> f.isFile && f.name.startsWith("model-") && f.name.endsWith(".gguf") }
            ?.forEach { it.delete() }
        File(modelsDir, "import.tmp").delete()
    }

    private fun setModelBusy(busy: Boolean) {
        busyWithModel = busy
        modelButton.isEnabled = !busy
        actionButton.isEnabled = !busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun importModel(uri: Uri) {
        if (busyWithModel || generating.get()) return

        setModelBusy(true)
        modelLoaded = false
        progressBar.isIndeterminate = true
        status.text = "Importazione del modello…"

        // Libera il modello attuale prima di sovrascriverne il file.
        engine.executor.execute {
            engine.native.unload()
            engine.loadedPath = null
        }

        lifecycleScope.launch {
            val tmp = File(modelsDir, "import.tmp")
            try {
                withContext(Dispatchers.IO) {
                    val totalBytes = contentResolver.openAssetFileDescriptor(uri, "r")
                        ?.use { it.length } ?: -1L

                    if (totalBytes > 0) {
                        // Il vecchio modello verrà sostituito: il suo spazio conta come libero.
                        val free = StatFs(filesDir.path).availableBytes +
                            (if (modelFile.exists()) modelFile.length() else 0L)
                        if (free < totalBytes + 64L * 1024 * 1024) {
                            error("spazio insufficiente: servono ${totalBytes / 1_048_576} MB liberi")
                        }
                    }

                    val source = contentResolver.openInputStream(uri)
                        ?: error("impossibile leggere il file selezionato")
                    source.use { stream ->
                        val magic = ByteArray(4)
                        var read = 0
                        while (read < 4) {
                            val n = stream.read(magic, read, 4 - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read < 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                            error("il file non è un modello GGUF")
                        }

                        tmp.outputStream().buffered(1 shl 20).use { out ->
                            out.write(magic)
                            val buffer = ByteArray(1 shl 20)
                            var copied = 4L
                            var lastPercent = -1
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                                copied += count
                                if (totalBytes > 0) {
                                    val percent = ((copied * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        withContext(Dispatchers.Main) {
                                            progressBar.isIndeterminate = false
                                            progressBar.progress = percent
                                        }
                                    }
                                }
                            }
                        }
                    }

                    modelFile.delete()
                    if (!tmp.renameTo(modelFile)) error("impossibile salvare il modello")
                }
                loadModel(modelFile, imported = true)
            } catch (failure: Throwable) {
                tmp.delete()
                finishModelLoad(false, failure.message ?: "errore di importazione")
            }
        }
    }

    private fun loadModel(file: File, imported: Boolean) {
        setModelBusy(true)
        progressBar.isIndeterminate = true
        status.text = if (imported) "Caricamento modello…" else "Caricamento ultimo modello…"

        val path = file.absolutePath
        engine.executor.execute {
            val result = try {
                val ok = engine.native.loadModel(path, CONTEXT_SIZE, engine.threads)
                engine.loadedPath = if (ok) path else null
                ok to null
            } catch (failure: Throwable) {
                engine.loadedPath = null
                false to (failure.message ?: "errore sconosciuto")
            }
            runOnUiThread { finishModelLoad(result.first, result.second) }
        }
    }

    private fun finishModelLoad(loaded: Boolean, error: String?) {
        if (closed.get()) return
        modelLoaded = loaded
        setModelBusy(false)
        status.text = when {
            loaded -> "Modello caricato · inferenza locale"
            error != null -> "Errore: $error"
            else -> "Caricamento non riuscito: GGUF non valido o RAM insufficiente"
        }
    }

    private fun onActionButton() {
        if (generating.get()) {
            engine.native.requestStop()
            actionButton.text = "Interruzione…"
            return
        }

        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!modelLoaded) {
            status.text = "Scegli e carica prima un modello GGUF."
            return
        }

        input.text.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)

        messages.add(ChatMessage(fromUser = true, text = text))

        // Snapshot sul thread UI: il thread del modello non tocca `messages`.
        val history = messages.takeLast(HISTORY_MESSAGES)
            .dropWhile { !it.fromUser }
        val roles = ArrayList<String>(history.size + 1)
        val contents = ArrayList<String>(history.size + 1)
        roles += "system"; contents += SYSTEM_PROMPT
        history.forEach {
            roles += if (it.fromUser) "user" else "assistant"
            contents += it.text
        }

        messages.add(ChatMessage(fromUser = false, text = ""))
        val replyIndex = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(replyIndex - 1, 2)
        list.scrollToPosition(replyIndex)

        generating.set(true)
        actionButton.text = "Interrompi"
        modelButton.isEnabled = false
        newChatButton.isEnabled = false
        status.text = "Generazione sul dispositivo…"

        val decoder = Utf8StreamDecoder()
        val appendToReply: (String) -> Unit = { chunk ->
            runOnUiThread {
                if (!closed.get() && replyIndex < messages.size) {
                    val old = messages[replyIndex]
                    messages[replyIndex] = old.copy(text = old.text + chunk)
                    chatAdapter.notifyItemChanged(replyIndex)
                    list.scrollToPosition(replyIndex)
                }
            }
        }

        engine.executor.execute {
            var failure: Throwable? = null
            try {
                engine.native.generate(
                    roles.toTypedArray(),
                    contents.toTypedArray(),
                    MAX_NEW_TOKENS,
                    object : TokenByteCallback {
                        override fun onTokenBytes(bytes: ByteArray) {
                            val chunk = decoder.decode(bytes, finalChunk = false)
                            if (chunk.isNotEmpty()) appendToReply(chunk)
                        }
                    }
                )
            } catch (problem: Throwable) {
                failure = problem
            }

            val tail = decoder.decode(ByteArray(0), finalChunk = true)
            runOnUiThread {
                if (closed.get()) return@runOnUiThread
                if (replyIndex < messages.size) {
                    val old = messages[replyIndex]
                    val finalText = (old.text + tail).trim()
                    messages[replyIndex] = old.copy(
                        text = finalText.ifEmpty { if (failure == null) "(nessuna risposta)" else "" }
                    )
                    chatAdapter.notifyItemChanged(replyIndex)
                }
                generating.set(false)
                actionButton.text = "Invia"
                modelButton.isEnabled = true
                newChatButton.isEnabled = true
                status.text = if (failure == null) {
                    "Modello caricato · pronto"
                } else {
                    "Errore di generazione: ${failure?.message ?: "sconosciuto"}"
                }
            }
        }
    }

    override fun onDestroy() {
        closed.set(true)
        engine.native.requestStop()
        // Il modello si libera solo se l'app viene chiusa davvero: se l'Activity
        // viene ricreata resta caricato. L'executor è unico, quindi l'ordine
        // rilascio → eventuale nuovo caricamento è garantito.
        if (isFinishing) {
            engine.executor.execute {
                engine.native.unload()
                engine.loadedPath = null
            }
        }
        super.onDestroy()
    }
}

/**
 * Decodifica UTF-8 in streaming: i byte di un carattere spezzato tra due
 * token vengono trattenuti fino al callback successivo.
 */
private class Utf8StreamDecoder {
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray, finalChunk: Boolean): String {
        val combined = pending + bytes
        val inBuf = ByteBuffer.wrap(combined)
        val outBuf = CharBuffer.allocate(combined.size + 8)
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

private class ChatAdapter(
    private val items: List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.MessageHolder>() {

    class MessageHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
        val density = parent.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val label = TextView(parent.context).apply {
            textSize = 16f
            setTextColor(0xFF17221F.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextIsSelectable(true)
            val params = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(4), 0, dp(4))
            layoutParams = params
        }
        return MessageHolder(label)
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int) {
        val message = items[position]
        val density = holder.label.resources.displayMetrics.density
        holder.label.background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(if (message.fromUser) 0xFFDCEFEA.toInt() else 0xFFFFFFFF.toInt())
        }
        val role = if (message.fromUser) "Tu" else "Locale AI"
        holder.label.text = if (message.text.isEmpty() && !message.fromUser) "$role\n…" else "$role\n${message.text}"
    }

    override fun getItemCount(): Int = items.size
}
