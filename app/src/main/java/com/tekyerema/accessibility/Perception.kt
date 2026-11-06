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
        // A list the app draws itself (BlindRows): the rows are there, their words are not. Never on a credential screen.
        val blind = foregrounded && tree.blindRows.size >= BlindRows.MIN_ROWS && !tree.secureWindow &&
            !com.tekyerema.policy.TargetSemantics.isCredentialScreen(tree)
        val reason = when {
            requestVision -> "vision requested by the model" + (requestReason?.let { ": $it" } ?: "")
            treeInsufficient -> "tree exposed 0 actionable elements on a foregrounded app"
            blind -> "tree exposed ${tree.blindRows.size} list rows with no text"
            else -> "tree exposed $actionable actionable elements"
        }
        if (!requestVision && !treeInsufficient && !blind) {
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
        com.tekyerema.core.telemetry.Telemetry.noteScreenshot()   // Q-10
        var ocr = try {
            VisionPerception.recognize(bmp, startId = tree.elements.size, existing = tree.elements)
                .also { com.tekyerema.core.telemetry.Telemetry.noteOcr() }
        } catch (t: Throwable) { emptyList() } finally { bmp.recycle() }
        // A list caught mid-draw (Telegram 1.4 s after opening, A20e 6 Nov 2026: 8 rows, OCR found nothing): one more look.
        if (blind && ocr.isEmpty()) {
            kotlinx.coroutines.delay(600)
            val again = c.captureScreenshot()
            if (again != null) ocr = try { VisionPerception.recognize(again, startId = tree.elements.size, existing = tree.elements) }
                catch (t: Throwable) { emptyList() } finally { again.recycle() }
        }
        val (rows, rest) = BlindRows.merge(tree.blindRows, ocr, startId = tree.elements.size)
        // Debug builds only (Privacy): which rows the OCR named, so a wrong pick can be judged against what was on screen.
        if (rows.isNotEmpty() && com.tekyerema.core.Privacy.logContent())
            android.util.Log.d("Tekyerema", "[BLIND_ROWS] ${rows.size} of ${tree.blindRows.size}: " + rows.joinToString(" | ") { it.label.orEmpty().take(40) })
        // Only the rows, unless a look was asked for: the loose lines around them repeat what the tree already lists.
        val vision = rows + if (requestVision || treeInsufficient) rest.mapIndexed { i, e -> e.copy(id = tree.elements.size + rows.size + i) } else emptyList()
        return tree.copy(
            elements = tree.elements + vision,
            perception = PerceptionRoute("tree+vision", "$reason; OCR found ${vision.size} text elements")
        )
    }
}
