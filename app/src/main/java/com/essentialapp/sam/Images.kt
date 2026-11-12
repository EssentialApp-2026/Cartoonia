package com.essentialapp.sam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/** Foto pronte per il cervello IA: dritte e non troppo grandi (le guarda più in fretta e con meno memoria). */
object Images {

    /** Da un file (foto della galleria, immagine condivisa) a un JPEG dritto di al massimo [maxSide] pixel. */
    fun prepare(src: File, out: File, maxSide: Int = 1024): Boolean = try {
        val rotation = try {
            when (ExifInterface(src.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) { 0 }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, o)
        val bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample(o, maxSide) })
        bmp != null && save(bmp, rotation, out, maxSide)
    } catch (e: Throwable) { false }

    /** Da un JPEG appena scattato: si gira di [rotation] gradi (il sensore della fotocamera è montato di lato). */
    fun prepare(jpeg: ByteArray, rotation: Int, out: File, maxSide: Int = 1024): Boolean = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample(o, maxSide) })
        bmp != null && save(bmp, rotation, out, maxSide)
    } catch (e: Throwable) { false }

    private fun sample(o: BitmapFactory.Options, maxSide: Int): Int {
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        return s
    }

    private fun save(bmp: Bitmap, rotation: Int, out: File, maxSide: Int): Boolean {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val m = Matrix().apply { if (rotation != 0) postRotate(rotation.toFloat()); if (scale < 1f) postScale(scale, scale) }
        val ready = if (rotation != 0 || scale < 1f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) else bmp
        out.outputStream().use { ready.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (ready !== bmp) ready.recycle()
        bmp.recycle()
        return true
    }
}
