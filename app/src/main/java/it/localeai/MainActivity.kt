package it.localeai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal const val ACCENT = 0xFF2E796B.toInt()
internal const val INK = 0xFF17221F.toInt()
internal const val MUTED = 0xFF53645E.toInt()
internal const val BG = 0xFFF6F7F4.toInt()

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

internal fun Context.pillButton(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else INK)
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (primary) ACCENT else 0xFFE4EAE6.toInt())
        }
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

/** Applica le inset (barre di sistema, tastiera) come padding: necessario con targetSdk 35. */
internal fun View.applySystemInsets(base: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        v.setPadding(base + bars.left, base + bars.top, base + bars.right, base / 2 + bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

class MainActivity : AppCompatActivity(), SamBrain.Listener {
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var micButton: Button
    private lateinit var wakeButton: Button
    private lateinit var speech: SpeechInput
    private var dictating = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshButtons() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SamBrain.init(this)
        speech = SpeechInput(this, ::onDictation, ::onDictationEnd)
        createUi()

        if (!AccountStore.permissionsAsked) {
            AccountStore.permissionsAsked = true
            requestAllPermissions()
        }
        if (AccountStore.accounts.isEmpty() && !LocalModel.file(this).exists() && SamBrain.messages.isEmpty()) {
            SamBrain.messages += ChatMessage(
                fromUser = false,
                text = "Ciao, sono SAM. Per iniziare tocca ⚙ e aggiungi un account online " +
                    "(ad esempio Groq o Gemini, gratuiti) oppure importa un modello GGUF per usarmi offline.",
                source = "Benvenuto"
            )
        }
    }

    fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionRequest.launch(perms.toTypedArray())
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.applySystemInsets(dp(14))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "SAM"
            textSize = 26f
            setTextColor(INK)
            setTypeface(null, Typeface.BOLD)
        }
        val settings = pillButton("⚙ Modelli") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(settings)

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(8))
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        micButton = pillButton("🎙 Parla", primary = true) { onMic() }
        wakeButton = pillButton("Ascolto «SAM»") { toggleWake() }
        val newChat = pillButton("Nuova chat") { SamBrain.newChat() }
        actions.addView(micButton)
        actions.addView(space())
        actions.addView(wakeButton)
        actions.addView(space())
        actions.addView(newChat)

        chatAdapter = ChatAdapter(SamBrain.messages)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).also { it.stackFromEnd = true }
            adapter = chatAdapter
            itemAnimator = null
            setPadding(0, dp(8), 0, dp(8))
            clipToPadding = false
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Scrivi a SAM…"
            minLines = 1
            maxLines = 4
        }
        sendButton = pillButton("Invia", primary = true) { onSend() }
        bottom.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(sendButton)

        root.addView(header)
        root.addView(status)
        root.addView(actions)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottom)
        setContentView(root)
    }

    private fun space() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }

    private fun onSend() {
        if (SamBrain.busy || SamBrain.voice.isSpeaking) {
            SamBrain.stopAll()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        SamBrain.submit(text, speak = false)
    }

    private fun onMic() {
        if (!hasMic()) {
            requestAllPermissions()
            return
        }
        SamBrain.voice.stop()
        if (WakeService.running) {
            WakeService.listenNow(this)
            return
        }
        if (dictating) {
            speech.cancel()
            dictating = false
            refreshButtons()
            return
        }
        if (!speech.available) {
            SamBrain.setStatus("Riconoscimento vocale non disponibile: installa l'app Google.")
            return
        }
        dictating = true
        refreshButtons()
        SamBrain.setStatus("Ti ascolto…")
        speech.start(partials = true, silenceMs = 1800L)
    }

    private fun onDictation(text: String, isFinal: Boolean) {
        if (isFinal) {
            dictating = false
            input.setText("")
            refreshButtons()
            SamBrain.submit(text, speak = true)
        } else {
            input.setText(text)
            input.setSelection(input.text.length)
        }
    }

    private fun onDictationEnd(error: Int?) {
        if (dictating) {
            dictating = false
            refreshButtons()
            if (error != null) SamBrain.setStatus("Non ho capito, riprova.")
        }
    }

    private fun toggleWake() {
        if (WakeService.running) {
            WakeService.stop(this)
        } else {
            if (!hasMic()) {
                requestAllPermissions()
                return
            }
            speech.cancel()
            WakeService.start(this)
        }
        wakeButton.postDelayed({ refreshButtons() }, 400)
    }

    private fun refreshButtons() {
        val busy = SamBrain.busy || SamBrain.voice.isSpeaking
        sendButton.text = if (busy) "Stop" else "Invia"
        micButton.text = if (dictating) "■ Ferma" else "🎙 Parla"
        wakeButton.text = if (WakeService.running) "● Ascolto attivo" else "Ascolto «SAM»"
        status.text = SamBrain.status
    }

    override fun onMessagesChanged() {
        chatAdapter.notifyDataSetChanged()
        if (SamBrain.messages.isNotEmpty()) list.scrollToPosition(SamBrain.messages.lastIndex)
        refreshButtons()
    }

    override fun onStateChanged() = refreshButtons()

    private val voiceIdle: () -> Unit = { refreshButtons() }

    override fun onStart() {
        super.onStart()
        SamBrain.uiVisible = true
        SamBrain.addListener(this)
        SamBrain.voice.addIdleListener(voiceIdle)
        SamBrain.refreshStatus()
        onMessagesChanged()
    }

    override fun onStop() {
        SamBrain.uiVisible = false
        SamBrain.removeListener(this)
        SamBrain.voice.removeIdleListener(voiceIdle)
        if (dictating) {
            speech.cancel()
            dictating = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        speech.destroy()
        super.onDestroy()
    }
}

private class ChatAdapter(
    private val items: List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.MessageHolder>() {

    class MessageHolder(val box: LinearLayout, val label: TextView, val body: TextView) :
        RecyclerView.ViewHolder(box)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
        val ctx = parent.context
        val label = TextView(ctx).apply {
            textSize = 12f
            setTextColor(MUTED)
        }
        val body = TextView(ctx).apply {
            textSize = 16f
            setTextColor(INK)
            setTextIsSelectable(true)
            setPadding(0, ctx.dp(2), 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            addView(label)
            addView(body)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, ctx.dp(4), 0, ctx.dp(4)) }
        }
        return MessageHolder(box, label, body)
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int) {
        val m = items[position]
        val ctx = holder.box.context
        holder.box.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(14).toFloat()
            setColor(if (m.fromUser) 0xFFDCEFEA.toInt() else 0xFFFFFFFF.toInt())
        }
        holder.label.text = if (m.fromUser) "Tu" else if (m.source.isBlank()) "SAM" else "SAM · ${m.source}"
        holder.body.text = if (m.text.isEmpty() && !m.fromUser) "…" else m.text
    }

    override fun getItemCount(): Int = items.size
}
