package com.essentialapp.sam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/**
 * Gli occhi di SAM: mostra per un attimo quello che inquadra la fotocamera posteriore, scatta da sola
 * dopo un secondo e mezzo (il tempo di mettere a fuoco) e passa la foto al servizio vocale, che la fa
 * guardare al cervello IA. La foto resta sul telefono e viene sovrascritta la volta dopo.
 */
class LookActivity : Activity() {

    companion object {
        /** true = la fotocamera davanti ("guardami", "come sono vestito?") */
        const val EXTRA_FRONT = "front"
        /** 1.21: "look" (guardare: la foto va al cervello) | "photo" (una foto da tenere, nella Galleria) | "video" (filmare) */
        const val EXTRA_MODE = "mode"
        /** autoscatto: secondi prima della foto (o prima di cominciare a filmare) */
        const val EXTRA_DELAY = "delay"
        /** quanti secondi filmare */
        const val EXTRA_SECONDS = "seconds"
    }

    private var mode = "look"
    private var delay = 0
    private var seconds = 15
    private var recorder: MediaRecorder? = null
    private var videoFile: File? = null
    @Volatile private var recording = false

    private var front = false
    private lateinit var texture: TextureView
    private lateinit var label: TextView
    private val thread = HandlerThread("sam-occhi").apply { start() }
    private val bg = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensor = 90
    private var autoFocus = true
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // la schermata c'è: il servizio vocale smette di temere che Android l'abbia bloccata
        VoiceService.lookOpenedAt = System.currentTimeMillis()
        front = intent?.getBooleanExtra(EXTRA_FRONT, false) == true
        mode = intent?.getStringExtra(EXTRA_MODE) ?: "look"
        delay = (intent?.getIntExtra(EXTRA_DELAY, 0) ?: 0).coerceIn(0, 30)
        seconds = (intent?.getIntExtra(EXTRA_SECONDS, 15) ?: 15).coerceIn(2, 180)
        // funziona anche a telefono bloccato: "SAM, cosa vedi?" senza toccarlo
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        label = TextView(this).apply {
            text = when (mode) {
                "photo" -> if (delay > 0) "Foto tra $delay…" else "Sorridi!"
                "video" -> if (delay > 0) "Filmo tra $delay…" else "Preparo la ripresa…"
                else -> if (front) "SAM ti guarda…" else "SAM guarda…"
            }
            setTextColor(Color.WHITE); textSize = 20f
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
        }
        root.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (90 * dp).toInt() })
        setContentView(root)
        // filmando: un tocco sullo schermo ferma la ripresa
        root.setOnClickListener { if (recording) bg.post { stopVideo() } }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 3)
            return
        }
        start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) start()
        else finishWith(null, "Per guardare mi serve il permesso della fotocamera.")
    }

    private fun start() {
        if (texture.isAvailable) openCamera()
        else texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (mode == "look") bg.postDelayed({ finishWith(null, "La fotocamera non risponde.") }, 12_000)
        else bg.postDelayed({ if (!recording) finishWith(null, "La fotocamera non risponde.") }, 15_000L + delay * 1000L)
    }

    /** La dimensione più grande che non supera [maxArea] pixel (o la più piccola, se nessuna ci sta). */
    private fun pick(sizes: Array<Size>, maxArea: Int): Size {
        var best: Size? = null
        var smallest = sizes[0]
        for (s in sizes) {
            val a = s.width * s.height
            if (a < smallest.width * smallest.height) smallest = s
            if (a <= maxArea && (best == null || a > best.width * best.height)) best = s
        }
        return best ?: smallest
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            } ?: cm.cameraIdList.first()
            val ch = cm.getCameraCharacteristics(id)
            sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // la fotocamera davanti spesso ha il fuoco fisso: la messa a fuoco si chiede solo se c'è
            autoFocus = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException("fotocamera senza formati")
            // per guardare basta poco (2,4 megapixel); la foto da tenere invece alla massima qualità (fino a 12 megapixel)
            val photo = pick(map.getOutputSizes(ImageFormat.JPEG), if (mode == "photo") 12_600_000 else 2_400_000)
            if (mode == "video") {
                val vs = videoSize(map.getOutputSizes(MediaRecorder::class.java))
                val pv = pick(map.getOutputSizes(SurfaceTexture::class.java), 1_300_000)
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(c: CameraDevice) { camera = c; startVideo(c, pv, vs) }
                    override fun onDisconnected(c: CameraDevice) { c.close(); camera = null; finishWith(null, "La fotocamera si è chiusa.") }
                    override fun onError(c: CameraDevice, error: Int) { c.close(); camera = null; finishWith(null, "La fotocamera è occupata da un'altra app.") }
                }, bg)
                return
            }
            val preview = pick(map.getOutputSizes(SurfaceTexture::class.java), 1_300_000)
            val r = ImageReader.newInstance(photo.width, photo.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ ir ->
                val img = try { ir.acquireLatestImage() } catch (e: Exception) { null }
                if (img != null) {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    img.close()
                    save(bytes)
                }
            }, bg)
            reader = r
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camera = c; startPreview(c, preview, r) }
                override fun onDisconnected(c: CameraDevice) { c.close(); camera = null; finishWith(null, "La fotocamera si è chiusa.") }
                override fun onError(c: CameraDevice, error: Int) { c.close(); camera = null; finishWith(null, "La fotocamera è occupata da un'altra app.") }
            }, bg)
        } catch (e: Exception) {
            finishWith(null, "Non riesco ad aprire la fotocamera.")
        }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(c: CameraDevice, size: Size, r: ImageReader) {
        val st = texture.surfaceTexture ?: run { finishWith(null, "Non riesco a mostrare la fotocamera."); return }
        st.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(st)
        try {
            c.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(surface)
                        if (autoFocus) req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        s.setRepeatingRequest(req.build(), null, bg)
                        if (delay > 0) countdown(delay) { shoot(c, s, r) }
                        else bg.postDelayed({ shoot(c, s, r) }, 1500)   // il tempo di mettere a fuoco e regolare la luce
                    } catch (e: Exception) { finishWith(null, "Non riesco a usare la fotocamera.") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { finishWith(null, "Non riesco a usare la fotocamera.") }
            }, bg)
        } catch (e: Exception) { finishWith(null, "Non riesco a usare la fotocamera.") }
    }

    private fun shoot(c: CameraDevice, s: CameraCaptureSession, r: ImageReader) {
        if (done) return
        try {
            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            req.addTarget(r.surface)
            if (autoFocus) req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            req.set(CaptureRequest.JPEG_QUALITY, (if (mode == "photo") 95 else 90).toByte())
            // la foto da tenere esce già dritta (telefono in verticale), come quelle della Fotocamera
            if (mode == "photo") req.set(CaptureRequest.JPEG_ORIENTATION, sensor)
            runOnUiThread { label.text = if (mode == "photo") "📸 Clic!" else "Scatto!" }
            s.capture(req.build(), null, bg)
        } catch (e: Exception) { finishWith(null, "Non riesco a scattare la foto.") }
    }

    private fun save(jpeg: ByteArray) {
        if (done) return
        if (mode == "photo") {
            val saved = try { Gallery.savePhoto(applicationContext, jpeg, if (front) "Selfie" else "Foto", -1) } catch (t: Throwable) { null }
            if (saved == null) { finishWith(null, "Non riesco a salvare la foto: forse la memoria del telefono è piena."); return }
            finishMedia(saved, 0L, null)
            return
        }
        val f = File(cacheDir, "vista.jpg")
        if (Images.prepare(jpeg, sensor, f)) finishWith(f.absolutePath, null)
        else finishWith(null, "Non riesco a salvare la foto.")
    }

    private fun finishWith(path: String?, error: String?) {
        if (done) return
        if (mode != "look") { finishMedia(null, 0L, error); return }
        done = true
        try { VoiceService.photoTaken(applicationContext, path, error) } catch (e: Exception) {}
        runOnUiThread { finish() }
    }

    /** La foto o il video da tenere è nella Galleria ([saved]), oppure non è riuscito ([error]): si dice a SAM e si chiude. */
    private fun finishMedia(saved: FileOut.Saved?, durationMs: Long, error: String?) {
        if (done) return
        done = true
        try { VoiceService.mediaTaken(applicationContext, saved, durationMs, error ?: if (saved == null) "Non ci sono riuscito." else null) } catch (e: Exception) {}
        runOnUiThread { finish() }
    }

    /** Il conto alla rovescia sullo schermo (autoscatto), poi [then]. */
    private fun countdown(n: Int, then: () -> Unit) {
        for (k in 0 until n) bg.postDelayed({ runOnUiThread { label.text = "${n - k}…"; label.textSize = 44f } }, k * 1000L)
        bg.postDelayed({ runOnUiThread { label.textSize = 20f }; then() }, n * 1000L)
    }

    // ---- 1.21: filmare -------------------------------------------------
    /** Full HD se c'è, altrimenti la più grande in 16:9 fino a Full HD, altrimenti la più grande fino a Full HD. */
    private fun videoSize(sizes: Array<Size>): Size {
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        val ok = sizes.filter { it.width * it.height <= 1920 * 1080 }
        return ok.filter { it.width * 9 == it.height * 16 }.maxByOrNull { it.width * it.height }
            ?: ok.maxByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height }!!
    }

    @Suppress("DEPRECATION")
    private fun startVideo(c: CameraDevice, preview: Size, size: Size) {
        val st = texture.surfaceTexture ?: run { finishWith(null, "Non riesco a mostrare la fotocamera."); return }
        st.setDefaultBufferSize(preview.width, preview.height)
        val previewSurface = Surface(st)
        val f = File(cacheDir, "sam_ripresa.mp4").apply { delete() }
        videoFile = f
        val rec = try {
            MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(f.absolutePath)
                setVideoEncodingBitRate(if (size.width >= 1920) 16_000_000 else 8_000_000)
                setVideoFrameRate(30)
                setVideoSize(size.width, size.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOrientationHint(sensor)          // il video si vede dritto (telefono in verticale)
                setMaxDuration(seconds * 1000 + 500)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) bg.post { stopVideo() } }
                prepare()
            }
        } catch (e: Exception) { finishWith(null, "Non riesco a preparare la ripresa."); return }
        recorder = rec
        try {
            c.createCaptureSession(listOf(previewSurface, rec.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        req.addTarget(previewSurface); req.addTarget(rec.surface)
                        req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        if (autoFocus) req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        s.setRepeatingRequest(req.build(), null, bg)
                        val go = {
                            try {
                                rec.start(); recording = true
                                val t0 = System.currentTimeMillis()
                                fun tick() {
                                    if (!recording) return
                                    val left = seconds - ((System.currentTimeMillis() - t0) / 1000).toInt()
                                    runOnUiThread { label.text = "● REC  ${MediaCommands.duration(left.coerceAtLeast(0) * 1000L)}  · tocca per fermare" }
                                    if (left <= 0) stopVideo() else bg.postDelayed({ tick() }, 500)
                                }
                                tick()
                            } catch (e: Exception) { finishWith(null, "Non riesco a cominciare la ripresa.") }
                        }
                        if (delay > 0) countdown(delay) { go() } else bg.postDelayed({ go() }, 800)
                    } catch (e: Exception) { finishWith(null, "Non riesco a usare la fotocamera.") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { finishWith(null, "Questa fotocamera non riesce a filmare così.") }
            }, bg)
        } catch (e: Exception) { finishWith(null, "Non riesco a usare la fotocamera.") }
    }

    /** Fine della ripresa: il video va nella Galleria. */
    private fun stopVideo() {
        if (!recording) return
        recording = false
        var ok = true
        try { session?.stopRepeating() } catch (e: Exception) {}
        try { recorder?.stop() } catch (e: Exception) { ok = false }       // troppo corto: niente video
        try { recorder?.release() } catch (e: Exception) {}
        recorder = null
        val f = videoFile
        if (!ok || f == null || f.length() < 1000) { finishWith(null, "La ripresa non è riuscita."); return }
        runOnUiThread { label.text = "Salvo il video…" }
        val dur = try {
            android.media.MediaMetadataRetriever().run { setDataSource(f.absolutePath)
                val d = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; release(); d }
        } catch (e: Exception) { 0L }
        val saved = try { Gallery.saveVideo(applicationContext, f, if (front) "Video selfie" else "Video") } catch (t: Throwable) { null }
        f.delete()
        if (saved == null) finishWith(null, "Non riesco a salvare il video: forse la memoria del telefono è piena.")
        else finishMedia(saved, dur, null)
    }

    override fun onDestroy() {
        if (recording) { recording = false; try { recorder?.stop() } catch (e: Exception) {}; videoFile?.delete() }
        try { recorder?.release() } catch (e: Exception) {}
        try { session?.close() } catch (e: Exception) {}
        try { camera?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        // chiusa prima dello scatto (tasto indietro): SAM smette di aspettare
        if (!done) {
            done = true
            try { if (mode == "look") VoiceService.photoTaken(applicationContext, null, "Ho chiuso la fotocamera.")
                  else VoiceService.mediaTaken(applicationContext, null, 0L, "Ho chiuso la fotocamera.") } catch (e: Exception) {}
        }
        bg.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }
}
