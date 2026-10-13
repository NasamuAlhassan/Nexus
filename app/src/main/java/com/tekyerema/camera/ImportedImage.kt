package com.tekyerema.camera

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A picture the user already has — picked from the gallery, or shared to Tɛkyerɛma from another app — on its way into the
 * same reading pipeline as the camera (mission P5-02, 13 October 2026; research R-A4: import through the Photo Picker and
 * the share sheet, copy the file at once because an intent's read grant dies with the activity, keep images in memory
 * only). Pure helpers over the platform decoders; nothing is logged, nothing leaves the phone from here.
 */
object ImportedImage {
    /** The picture at [uri], downscaled so its longer side is at most [maxSide] (EXIF rotation applied on API 28+), as a
     *  software bitmap ML Kit can read — or null when it can't be opened or isn't an image. */
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val longer = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longer / sample > maxSide) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longer = maxOf(bounds.outWidth, bounds.outHeight)
            if (longer <= 0) null else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 1; while (longer / inSampleSize > maxSide) inSampleSize *= 2 }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        }
    } catch (_: Throwable) { null }

    /** [bmp] as a JPEG no larger than [maxSide] on its longer side (what the cloud describer is sent). */
    fun jpeg(bmp: Bitmap, maxSide: Int, quality: Int = 80): ByteArray {
        val longer = maxOf(bmp.width, bmp.height)
        val scaled = if (longer <= maxSide) bmp else {
            val f = maxSide.toFloat() / longer
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bmp) scaled.recycle()
        return out.toByteArray()
    }

    /** A private copy of a shared picture (the sharing app's grant lasts only while its intent is being handled) — in the
     *  app's cache, replaced on the next import, never kept beyond that. Null when the stream can't be read. */
    fun copyIn(ctx: Context, uri: Uri): Uri? {
        return try {
            val dir = File(ctx.cacheDir, "imports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "shared-${System.currentTimeMillis()}.img")
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { src -> f.outputStream().use { src.copyTo(it) } }
            if (f.length() == 0L) { f.delete(); null } else Uri.fromFile(f)
        } catch (t: Throwable) {
            // The class only, never the URI or the bytes: a MediaStore item shared without a usable grant fails here with a
            // SecurityException (seen when the emulator's shell "shared" one, 13 October 2026).
            android.util.Log.w("Tekyerema", "[IMPORT] copy failed: ${t.javaClass.simpleName}"); null
        }
    }

    /** Text blocks in reading order (top to bottom, then left to right), the lines of a block as one sentence. */
    fun readingOrder(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>): String =
        blocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .joinToString("\n") { b -> b.lines.joinToString(" ") { it.text } }.trim()
}
