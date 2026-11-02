package com.tekyerema.ui.assistant

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A tap outside a finished result closes it AND does what was tapped (owner's decision, 2 Nov 2026; supersedes DECISIONS V2-3,
 * where the first tap only closed the card). The app's root watches every tap in the Initial pass and never consumes it, so
 * the tab, the circle, a "Your usual" tile or the field still gets the same tap. Nothing is lost: the answer is in History.
 *
 * The surface registers [dismiss] only while a FINISHED result shows (working and waiting surfaces never close this way) and
 * keeps [card] — its bounds in the root — up to date; a tap inside the card is the card's own.
 */
object OutsideTap {
    @Volatile internal var dismiss: (() -> Unit)? = null
    @Volatile internal var card: Rect? = null

    /** Pure: does a tap from [down] to [up] close the result? A real tap (within [slop]) that started outside [card]. */
    fun closes(down: Offset, up: Offset, card: Rect?, slop: Float): Boolean =
        (up - down).getDistance() < slop && (card == null || !card.contains(down))

    /** On the app's root: watch, never consume. */
    fun Modifier.closeResultsOnTapOutside(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (dismiss == null) return@awaitEachGesture
            val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
            if (closes(down.position, up.position, card, viewConfiguration.touchSlop)) dismiss?.invoke()
        }
    }
}
