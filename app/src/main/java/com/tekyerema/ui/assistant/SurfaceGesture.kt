package com.tekyerema.ui.assistant

import kotlin.math.abs

/**
 * The surface's gestures as pure rules (docs/assistant-ux/PLAN_V2.md; unit-tested in SurfaceGestureTest). The composable only
 * measures the finger and animates; every decision about where a released drag goes, how hard the edges resist, and what may
 * be dismissed is made here.
 *
 * Safety (DECISIONS V2-2): only a FINISHED result can be dismissed or opened for a follow-up. A working surface or one that waits
 * on the user (a question, a confirmation, the secure step) never goes away under a gesture — it rubber-bands back and stays on
 * screen, so a prompt is never hidden, and no gesture ever answers it (nothing here can approve or cancel anything).
 */
object SurfaceGesture {

    /** The shapes the surface rests in. INPUT = expanded with the follow-up field showing. */
    enum class Rest { COMPACT, EXPANDED, INPUT }

    /** What a released drag does. */
    enum class Settle { STAY, EXPAND, OPEN_INPUT, COLLAPSE, DISMISS }

    /** What the current state allows. */
    data class Allow(val dismiss: Boolean, val expand: Boolean, val followUp: Boolean)

    /** A release faster than this (dp/s) decides on its own, whatever the distance. */
    const val FLING_DP_S = 900f
    /** A slow pull down this far (dp) expands or opens the field. */
    const val PULL_DP = 56f
    /** A slow push up over this share of the surface's height dismisses (from compact) or collapses (from expanded). */
    const val PUSH_FRACTION = 0.33f
    /** From an expanded surface, a push this far or a fling this much faster than [FLING_DP_S] dismisses at once. */
    const val FAR_PUSH_FRACTION = 0.6f
    const val STRONG_FLING = 2.2f
    /** How far a disallowed direction may stretch before it stops (dp): the rubber band's reach. */
    const val BAND_DP = 48f

    /** Which gestures the state allows: finished results can be dismissed and followed up; a long timeline can grow. */
    fun allow(mode: SurfaceModel.Mode, expandable: Boolean): Allow {
        val finished = mode == SurfaceModel.Mode.PRESENTING || mode == SurfaceModel.Mode.FAILED || mode == SurfaceModel.Mode.CANCELLED
        return Allow(dismiss = finished, expand = expandable, followUp = finished)
    }

    /**
     * Where a released drag settles. [dragDp] > 0 = pulled down, < 0 = pushed up. [velocityDp] = release speed in dp/s,
     * positive downward. A clear fling decides by its direction (the finger may have turned back); otherwise the distance does.
     */
    fun settle(rest: Rest, dragDp: Float, velocityDp: Float, heightDp: Float, allow: Allow): Settle {
        val flingUp = velocityDp < -FLING_DP_S
        val flingDown = velocityDp > FLING_DP_S
        val strongUp = velocityDp < -FLING_DP_S * STRONG_FLING
        val up = flingUp || (!flingDown && dragDp < 0f)
        val down = flingDown || (!flingUp && dragDp > 0f)
        val h = heightDp.coerceAtLeast(1f)
        return when {
            up -> {
                val far = -dragDp > h * PUSH_FRACTION
                val veryFar = -dragDp > h * FAR_PUSH_FRACTION
                when (rest) {
                    Rest.COMPACT -> if ((flingUp || far) && allow.dismiss) Settle.DISMISS else Settle.STAY
                    Rest.EXPANDED, Rest.INPUT -> when {
                        (strongUp || veryFar) && allow.dismiss -> Settle.DISMISS
                        flingUp || far -> Settle.COLLAPSE
                        else -> Settle.STAY
                    }
                }
            }
            down -> {
                val far = dragDp > PULL_DP
                if (!(flingDown || far)) Settle.STAY
                else when (rest) {
                    // Pull down = more of the same object: a finished result opens with its follow-up field (the mission's
                    // "pull down reveals the input"); a long timeline grows; a prompt stays as it is.
                    Rest.COMPACT -> when {
                        allow.followUp -> Settle.OPEN_INPUT
                        allow.expand -> Settle.EXPAND
                        else -> Settle.STAY
                    }
                    Rest.EXPANDED -> if (allow.followUp) Settle.OPEN_INPUT else Settle.STAY
                    Rest.INPUT -> Settle.STAY
                }
            }
            else -> Settle.STAY
        }
    }

    /** The rest a settle leads to (DISMISS keeps the current rest: the surface leaves as it is). */
    fun next(rest: Rest, settle: Settle): Rest = when (settle) {
        Settle.EXPAND -> Rest.EXPANDED
        Settle.OPEN_INPUT -> Rest.INPUT
        Settle.COLLAPSE -> Rest.COMPACT
        Settle.STAY, Settle.DISMISS -> rest
    }

    /**
     * How far the surface moves for a finger [dragDp] away from rest. An allowed direction follows the finger 1:1 (a pull down
     * stretches with gentle resistance after [PULL_DP] × 1.5); a direction the state does not allow stretches only a little
     * and stops at [BAND_DP] — the iOS-style rubber band.
     */
    fun visualOffset(dragDp: Float, allowUp: Boolean, allowDown: Boolean): Float = when {
        dragDp < 0f && allowUp -> dragDp
        dragDp < 0f -> -rubberBand(-dragDp, BAND_DP)
        dragDp > 0f && allowDown -> {
            val free = PULL_DP * 1.5f
            if (dragDp <= free) dragDp else free + rubberBand(dragDp - free, BAND_DP * 1.5f)
        }
        dragDp > 0f -> rubberBand(dragDp, BAND_DP)
        else -> 0f
    }

    /** Resistance past a limit: f(x) = d · (1 − 1 / (0.55·x/d + 1)). Grows ever slower and never exceeds [limit]. */
    fun rubberBand(over: Float, limit: Float): Float {
        if (over <= 0f || limit <= 0f) return 0f
        return limit * (1f - 1f / (over * 0.55f / limit + 1f))
    }

    /** Whether a drag of [dragDp] has crossed the line its release would act on — the moment for a haptic tick. */
    fun crossed(rest: Rest, dragDp: Float, heightDp: Float, allow: Allow): Boolean =
        settle(rest, dragDp, 0f, heightDp, allow) != Settle.STAY

    /** Back collapses an open surface first (true = back was used here). */
    fun backCollapses(rest: Rest): Boolean = rest != Rest.COMPACT

    internal fun nearlyZero(v: Float) = abs(v) < 0.5f
}
