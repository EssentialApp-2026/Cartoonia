package com.essentialapp.sam

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Il video MP4 (H.264) animato da un disegno, col codificatore video del telefono (veloce: pochi secondi).
 * Finisce nella Galleria, cartella "Movies › SAM". Il calcolo dei fotogrammi è in [Motion].
 */
object VideoOut {
    private const val MIME = "video/avc"

    /**
     * Anima [rgb] (lato [src], RGB) e salva l'MP4. [onFrame] riceve (fatti, totale) e ritorna false per fermarsi.
     * Ritorna il file salvato, o null se il telefono non ce l'ha fatta (allora resta comunque l'immagine).
     */
    fun make(ctx: Context, rgb: ByteArray, src: Int, title: String, style: Motion.Style,
             onFrame: (Int, Int) -> Boolean = { _, _ -> true }): FileOut.Saved? {
        val tmp = File(ctx.cacheDir, "sam_video.mp4")
        tmp.delete()
        val n = Motion.SIZE
        val mf = Motion.Frame(n)
        val total = Motion.FPS * Motion.SECONDS
        val ok = encode(n, n, Motion.FPS, total, 6_000_000, tmp, onFrame) { i, f ->
            Motion.frame(rgb, src, style, i, total, mf)
            mf.y.copyInto(f.y); mf.u.copyInto(f.u); mf.v.copyInto(f.v)
        }
        if (!ok) { tmp.delete(); return null }
        return try { publish(ctx, tmp, title) } finally { tmp.delete() }
    }

    /**
     * 1.21: il video con le foto ([Reel]): [pic] dà la foto numero k già pronta (ritagliata e grande quanto serve).
     * Si salva in Video › SAM. null = il codificatore del telefono non ce l'ha fatta.
     */
    fun makeReel(ctx: Context, plan: Reel.Plan, title: String, pic: (Int) -> Reel.Pic,
                 onFrame: (Int, Int) -> Boolean = { _, _ -> true }): FileOut.Saved? {
        val tmp = File(ctx.cacheDir, "sam_ricordi.mp4")
        tmp.delete()
        val other = Reel.Frame(plan.w, plan.h)
        val ok = encode(plan.w, plan.h, Reel.FPS, plan.total, 10_000_000, tmp, onFrame) { i, f -> Reel.frame(plan, i, pic, f, other) }
        if (!ok) { tmp.delete(); return null }
        return try { publish(ctx, tmp, title) } finally { tmp.delete() }
    }

    /** Codifica [total] fotogrammi [w]×[h] (riempiti da [produce]) in un MP4 H.264. */
    private fun encode(w: Int, h: Int, fps: Int, total: Int, bitrate: Int, file: File, onFrame: (Int, Int) -> Boolean,
                       produce: (Int, Reel.Frame) -> Unit): Boolean {
        val fmt = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        var muxStarted = false
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val frame = Reel.Frame(w, h)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var i = 0
            var inputDone = false
            var outputDone = false
            val t0 = System.currentTimeMillis()
            while (!outputDone) {
                if (System.currentTimeMillis() - t0 > 20 * 60_000L) return false      // non dovrebbe mai servire
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val pts = i * 1_000_000L / fps
                        if (i >= total) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            if (!onFrame(i, total)) return false
                            produce(i, frame)
                            val img = codec.getInputImage(idx) ?: return false
                            copyPlanes(img, frame)
                            codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
                            i++
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxStarted = true
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && muxStarted) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return muxStarted
        } catch (t: Throwable) {
            return false
        } finally {
            try { codec.stop() } catch (t: Throwable) {}
            try { codec.release() } catch (t: Throwable) {}
            try { if (muxStarted) muxer?.stop() } catch (t: Throwable) {}
            try { muxer?.release() } catch (t: Throwable) {}
        }
    }

    /** Copia i piani Y, U, V nel buffer del codificatore (qualunque disposizione usi: I420, NV12, NV21…). */
    private fun copyPlanes(img: android.media.Image, f: Reel.Frame) {
        val planes = img.planes
        fun put(p: android.media.Image.Plane, data: ByteArray, w: Int, h: Int) {
            val b = p.buffer
            val rs = p.rowStride; val ps = p.pixelStride
            if (ps == 1) {
                for (r in 0 until h) { b.position(r * rs); b.put(data, r * w, w) }
            } else {
                for (r in 0 until h) {
                    val base = r * rs
                    for (c in 0 until w) b.put(base + c * ps, data[r * w + c])
                }
            }
        }
        put(planes[0], f.y, f.w, f.h)
        put(planes[1], f.u, f.w / 2, f.h / 2)
        put(planes[2], f.v, f.w / 2, f.h / 2)
    }

    private fun publish(ctx: Context, file: File, title: String): FileOut.Saved? {
        val name = SamFiles.fileName(title, "mp4", java.time.LocalDateTime.now())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SAM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            r.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException()
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            FileOut.Saved(uri, name, "video/mp4")
        } catch (e: Exception) { try { r.delete(uri, null, null) } catch (x: Exception) {}; null }
    }
}
