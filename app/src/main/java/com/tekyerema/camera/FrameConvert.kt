package com.tekyerema.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * CameraX frame plumbing. Kept apart from the pure logic (CameraLogic.kt) because it touches ImageProxy and
 * Bitmap; everything here copies pixels out of the frame so the ImageProxy can be closed at once and the camera
 * pipeline never stalls on our work.
 */
object FrameConvert {

    /** Copy one plane's bytes (strides preserved), reusing [reuse] when it is big enough — no garbage at 2 fps. */
    fun planeBytes(plane: ImageProxy.PlaneProxy, reuse: ByteArray?): ByteArray {
        val buf = plane.buffer.duplicate()
        buf.rewind()
        val n = buf.remaining()
        val out = if (reuse != null && reuse.size >= n) reuse else ByteArray(n)
        buf.get(out, 0, n)
        return out
    }

    /** YUV_420_888 (any row/pixel stride) → tightly packed NV21, the layout YuvImage can encode. */
    fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val cw = w / 2
        val ch = h / 2
        val out = ByteArray(w * h + 2 * cw * ch)
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer.duplicate()
        val uBuf = uP.buffer.duplicate()
        val vBuf = vP.buffer.duplicate()

        var pos = 0
        val yRow = yP.rowStride
        val yPix = yP.pixelStride
        for (row in 0 until h) {
            val base = row * yRow
            if (yPix == 1) {
                yBuf.position(base)
                yBuf.get(out, pos, w)
                pos += w
            } else {
                for (col in 0 until w) out[pos++] = yBuf.get(base + col * yPix)
            }
        }
        val uRow = uP.rowStride
        val uPix = uP.pixelStride
        val vRow = vP.rowStride
        val vPix = vP.pixelStride
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                out[pos++] = vBuf.get(row * vRow + col * vPix)   // NV21 is V first, then U
                out[pos++] = uBuf.get(row * uRow + col * uPix)
            }
        }
        return out
    }

    /**
     * NV21 → upright JPEG, long side ≤ [maxSide], at [quality]. Rotated here (not left to EXIF) because the bytes go
     * to a model that sees raw pixels: a sideways photo describes worse. Returns null if the frame can't be encoded.
     */
    fun nv21ToJpeg(
        nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int,
        maxSide: Int = 1024, quality: Int = 80
    ): ByteArray? = try {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val scale = min(1f, maxSide.toFloat() / max(width, height))
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (scale >= 1f && rotation == 0) {
            // Already small and upright: encode once.
            val direct = ByteArrayOutputStream()
            if (yuv.compressToJpeg(Rect(0, 0, width, height), quality, direct)) direct.toByteArray() else null
        } else {
            val raw = ByteArrayOutputStream()
            if (!yuv.compressToJpeg(Rect(0, 0, width, height), 95, raw)) {
                null
            } else {
                val bytes = raw.toByteArray()
                val full: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (full == null) {
                    null
                } else {
                    val m = Matrix().apply {
                        postScale(scale, scale)
                        postRotate(rotation.toFloat())
                    }
                    val upright = Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
                    val out = ByteArrayOutputStream()
                    upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    if (upright !== full) upright.recycle()
                    full.recycle()
                    out.toByteArray()
                }
            }
        }
    } catch (_: Throwable) {
        null
    }
}
