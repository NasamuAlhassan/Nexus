package com.tekyerema.accessibility

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tekyerema.core.Sanitize
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Level-3 perception: screenshot → on-device OCR → coordinate-grounded elements.
 * Runs entirely on the device (bundled ML Kit model). Produces `source=vision` elements that
 * carry bounds because there is no accessibility node to act on — the executor taps the point.
 * Never sends pixels anywhere.
 */
object VisionPerception {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap, startId: Int, existing: List<UiElement>): List<UiElement> {
        val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { cont ->
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        } ?: return emptyList()

        val out = ArrayList<UiElement>()
        var id = startId
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val label = Sanitize.label(line.text) ?: continue
                val r = line.boundingBox ?: continue
                val b = Box(r.left, r.top, r.right, r.bottom)
                if (b.isEmpty()) continue
                // Skip OCR lines that duplicate a tree element with the same text at the same place:
                // the tree node is the better grounding and already offers real actions.
                val dup = existing.any { e ->
                    e.source == ElementSource.TREE && e.label != null &&
                        e.label.equals(label, ignoreCase = true) && e.bounds.overlaps(b)
                }
                if (dup) continue
                out.add(
                    UiElement(
                        id = id++,
                        role = "ocr_text",
                        label = label,
                        bounds = b,
                        source = ElementSource.VISION,
                        clickable = true // an estimate: any visible text may be a tap target
                    )
                )
            }
        }
        return out
    }
}
