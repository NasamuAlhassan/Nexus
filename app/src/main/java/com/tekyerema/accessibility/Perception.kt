package com.tekyerema.accessibility

/**
 * The perception router (plan §6.1). Deterministic — the model never chooses the source, but it
 * may REQUEST vision through `read_state(vision=true)` when it has reason to believe something is
 * on screen that the tree does not show. Every decision is recorded in the snapshot's
 * `perception` field and logged by the engine.
 */
object Perception {
    suspend fun observe(controller: AccessibilityController?, requestVision: Boolean, requestReason: String? = null): UiSnapshot {
        val c = controller ?: return UiSnapshot.EMPTY
        if (!c.ready) return UiSnapshot.EMPTY
        val tree = try { c.buildSnapshot() } catch (t: Throwable) { UiSnapshot.EMPTY }
        val actionable = tree.actionableCount
        val foregrounded = tree.appPackage != null
        // Auto-escalate when a real app is in front but the tree exposes nothing to act on — the
        // signature of a Canvas/WebView/unlabelled-Compose surface.
        val treeInsufficient = foregrounded && actionable == 0
        val reason = when {
            requestVision -> "vision requested by the model" + (requestReason?.let { ": $it" } ?: "")
            treeInsufficient -> "tree exposed 0 actionable elements on a foregrounded app"
            else -> "tree exposed $actionable actionable elements"
        }
        if (!requestVision && !treeInsufficient) {
            return tree.copy(perception = PerceptionRoute("tree", reason))
        }
        val bmp = c.captureScreenshot()
        if (bmp == null) {
            val why = c.lastScreenshotFailure() ?: "screenshot unavailable"
            return tree.copy(
                perception = PerceptionRoute("tree", "$reason; vision fallback failed: $why"),
                secureWindow = why.contains("secure", ignoreCase = true)
            )
        }
        val vision = try {
            VisionPerception.recognize(bmp, startId = tree.elements.size, existing = tree.elements)
        } catch (t: Throwable) { emptyList() } finally { bmp.recycle() }
        return tree.copy(
            elements = tree.elements + vision,
            perception = PerceptionRoute("tree+vision", "$reason; OCR found ${vision.size} text elements")
        )
    }
}
