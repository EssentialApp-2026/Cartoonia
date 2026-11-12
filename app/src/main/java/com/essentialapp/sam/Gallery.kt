package com.essentialapp.sam

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 1.21 · La Galleria del telefono: trovare foto e video (per periodo, i più recenti, quelli di SAM), prepararli per il
 * video con le foto ([Reel]) e per la vista del cervello, salvare le foto e i video che fa SAM (Galleria › SAM).
 */
object Gallery {

    /** Il permesso di vedere foto e video (su Android 14 può essere "solo alcune": va bene lo stesso). */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun allowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34 &&
            ctx.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED) return true
        return permissions().any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Solo alcune foto (Android 14: "Seleziona foto e video")? */
    fun partial(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 34 &&
        permissions().none { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } &&
        ctx.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED

    /**
     * Foto e/o video ([kind]: photo | video | any | screenshot), dal più recente. [from]..[to] in millisecondi (0 = tutto),
     * [mine] = solo quelli di SAM, al massimo [limit]. Ritorna anche quanti ce ne sono in tutto nel periodo.
     */
    fun find(ctx: Context, kind: String, from: Long, to: Long, mine: Boolean, limit: Int): Pair<List<MediaCommands.Item>, Int> {
        val all = ArrayList<MediaCommands.Item>()
        val wantPhotos = kind != "video"
        val wantVideos = kind == "video" || kind == "any" || (mine && kind != "screenshot" && kind != "photo")
        if (wantPhotos) all += query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, kind, from, to, mine)
        if (wantVideos) all += query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, kind, from, to, mine)
        all.sortByDescending { it.taken }
        return all.take(limit) to all.size
    }

    private fun query(ctx: Context, base: Uri, video: Boolean, kind: String, from: Long, to: Long, mine: Boolean): List<MediaCommands.Item> {
        val cols = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.ORIENTATION)
        if (video) cols += MediaStore.MediaColumns.DURATION
        val out = ArrayList<MediaCommands.Item>()
        // si leggono al massimo 6000 righe, dalle più nuove: la data di scatto si controlla qui (alcune foto copiate non ce l'hanno)
        val sel = if (from > 0) "${MediaStore.MediaColumns.DATE_ADDED} >= ? OR ${MediaStore.MediaColumns.DATE_TAKEN} >= ?" else null
        val args = if (from > 0) arrayOf(((from - 86_400_000L) / 1000).toString(), from.toString()) else null
        try {
            ctx.contentResolver.query(base, cols.toTypedArray(), sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val iOr = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
                val iDur = if (video) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
                var rows = 0
                while (c.moveToNext() && rows++ < 6000) {
                    val path = (c.getString(iPath) ?: "").lowercase()
                    val shot = path.contains("screenshot") || path.contains("screen")
                    if (mine && !path.contains("/sam") && !path.startsWith("sam")) continue
                    if (kind == "screenshot" && !shot) continue
                    if (kind == "photo" && !mine && shot) continue
                    val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000)
                    if (from > 0 && taken < from) continue
                    if (to > 0 && taken >= to) continue
                    val rot = c.getInt(iOr)
                    var w = c.getInt(iW); var h = c.getInt(iH)
                    if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
                    out += MediaCommands.Item(ContentUris.withAppendedId(base, c.getLong(iId)).toString(),
                        c.getString(iMime) ?: if (video) "video/mp4" else "image/jpeg", c.getString(iName) ?: "",
                        if (iDur >= 0) c.getLong(iDur) else 0L, taken, w, h)
                }
            }
        } catch (e: Exception) {}
        return out
    }

    /** Una foto come pixel RGB, ritagliata nelle proporzioni [w]:[h] e grande [w]×[h], già dritta. null = non si apre. */
    fun loadPic(ctx: Context, uri: Uri, w: Int, h: Int): Reel.Pic? = try {
        val r = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val rot = rotation(ctx, uri)
        val sw0 = if (rot % 180 != 0) bounds.outHeight else bounds.outWidth
        val sh0 = if (rot % 180 != 0) bounds.outWidth else bounds.outHeight
        var sample = 1
        while (sw0 / (sample * 2) >= w && sh0 / (sample * 2) >= h) sample *= 2
        val bmp0 = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("non si apre")
        val bmp = if (rot != 0) Bitmap.createBitmap(bmp0, 0, 0, bmp0.width, bmp0.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            .also { if (it !== bmp0) bmp0.recycle() } else bmp0
        val cr = Reel.cover(bmp.width, bmp.height, w, h)
        val crop = Bitmap.createBitmap(bmp, cr[0], cr[1], cr[2], cr[3])
        val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== crop) scaled.recycle()
        if (crop !== bmp) crop.recycle()
        bmp.recycle()
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) { val p = px[i]; rgb[3 * i] = (p shr 16).toByte(); rgb[3 * i + 1] = (p shr 8).toByte(); rgb[3 * i + 2] = p.toByte() }
        Reel.Pic(rgb, w, h)
    } catch (t: Throwable) { null }

    private fun rotation(ctx: Context, uri: Uri): Int = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) { 0 }

    /** Copia la foto in un file (per la vista del cervello, che legge i file). */
    fun toFile(ctx: Context, uri: Uri, out: File): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
        out.length() > 0
    } catch (e: Exception) { false }

    /** Salva una foto (JPEG) nella Galleria, cartella Immagini › SAM. */
    fun savePhoto(ctx: Context, jpeg: ByteArray, title: String, orientation: Int): FileOut.Saved? {
        val name = SamFiles.fileName(title, "jpg", java.time.LocalDateTime.now())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SAM")
            if (orientation >= 0) put(MediaStore.MediaColumns.ORIENTATION, orientation)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            r.openOutputStream(uri)?.use { it.write(jpeg) } ?: throw IllegalStateException()
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            FileOut.Saved(uri, name, "image/jpeg")
        } catch (e: Exception) { try { r.delete(uri, null, null) } catch (x: Exception) {}; null }
    }

    /** Sposta un video appena registrato ([file], nella cache) nella Galleria, cartella Video › SAM. */
    fun saveVideo(ctx: Context, file: File, title: String, folder: String = Environment.DIRECTORY_DCIM + "/SAM"): FileOut.Saved? {
        val name = SamFiles.fileName(title, "mp4", java.time.LocalDateTime.now())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
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

    /** Un elemento della chat da un file salvato. */
    fun item(s: FileOut.Saved, durationMs: Long = 0): MediaCommands.Item = MediaCommands.Item(s.uri.toString(), s.mime, s.name, durationMs)
}
