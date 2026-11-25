package com.hk.gallery

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps GalleryKnife working on Android 8 to 10 as well as on Android 11 and newer. */
object Compat {

    /** "All files" access on Android 11+, or the classic storage permission on older Android. */
    fun hasAllFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Decodes an image to at most [maxDim] pixels on its longest side, with the right rotation. */
    fun decode(ctx: Context, uri: Uri, maxDim: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val s = min(1f, maxDim.toFloat() / max(info.size.width, info.size.height))
                dec.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
        var bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Can't decode image")
        val orient = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val big = max(bmp.width, bmp.height)
        if (big > maxDim) {
            val s = maxDim.toFloat() / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
        }
        return bmp
    }

    /** A thumbnail of a photo or video, about [px] pixels on its longest side. */
    fun thumbnail(ctx: Context, uri: Uri, video: Boolean, px: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 29) return ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
        if (!video) return decode(ctx, uri, px)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val f = r.getFrameAtTime(0) ?: error("No video frame")
            val big = max(f.width, f.height)
            return if (big > px) Bitmap.createScaledBitmap(f, (f.width * px.toFloat() / big).roundToInt().coerceAtLeast(1), (f.height * px.toFloat() / big).roundToInt().coerceAtLeast(1), true) else f
        } finally { r.release() }
    }

    class Out(val stream: OutputStream, val finish: () -> Unit)

    /** Opens a new file in Pictures/GalleryKnife (or Movies/GalleryKnife) so it shows up in the gallery. */
    fun openOut(ctx: Context, name: String, mime: String, video: Boolean, dateTaken: Long): Out? {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/GalleryKnife" else "Pictures/GalleryKnife")
                put("datetaken", dateTaken)
            }
            val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = ctx.contentResolver.insert(coll, cv) ?: return null
            val s = ctx.contentResolver.openOutputStream(uri) ?: return null
            return Out(s) {}
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "GalleryKnife")
        dir.mkdirs()
        var f = File(dir, name)
        var n = 2
        while (f.exists()) f = File(dir, name.substringBeforeLast('.') + " $n." + name.substringAfterLast('.', "")).also { n++ }
        val s = runCatching { f.outputStream() }.getOrNull() ?: return null
        return Out(s) { MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf(mime), null) }
    }
}
