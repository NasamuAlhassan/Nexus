package com.tekyerema.tools

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.Perception
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import com.tekyerema.verifier.Verify
import kotlinx.coroutines.delay
import org.json.JSONObject

/*
 * The universal interaction vocabulary (plan §7). These are the ONLY ways the model touches the
 * device. None of them knows what app is in front. Each tries the lowest reliable mechanism
 * level first: node action → gesture at the node's bounds → gesture at vision coordinates.
 */

internal val TARGET_PARAMS = listOf(
    ToolParam("element_id", "integer", "Element id from the current screen.", required = false),
    ToolParam("label", "string", "Item text, if no element_id (scrolls to find it).", required = false),
    ToolParam("x", "integer", "source=vision elements only.", required = false),
    ToolParam("y", "integer", "source=vision elements only.", required = false)
)

val EXPECTS_PARAM = ToolParam(
    "expects", "string",
    "The observable result if this works (grammar in VERIFY).",
    required = false
)

// §1.19 — spoken to a user who can't see the screen, so it names the problem and the fix plainly.
internal const val NO_SERVICE = "I can't see or control your screen yet — Tɛkyerɛma's accessibility service is off. Turn it on in Settings, under Accessibility."
// §1.20 — a FLAG_SECURE window blocks screen reading; say that instead of implying the screen is empty.
private const val SECURE_SCREEN = "This screen is protected — the app blocks screen reading, so I can't tell you what's on it."

/** Resolve the target at execution time, scrolling to reveal a label if the engine's static pass missed it. */
internal suspend fun resolveForExecute(args: JSONObject, ctx: ToolContext, ctrl: AccessibilityController, allowScroll: Boolean): Pair<ResolvedTarget?, String?> {
    ctx.resolvedTarget?.let { return it to null }
    val target = TargetResolver.parse(args) ?: return null to "No target: give element_id, label, or (x,y) of a vision element."
    val (outcome, _) = TargetResolver.resolve(target, ctrl, ctx.observedBefore, allowScroll)
    return when (outcome) {
        is ResolveOutcome.Found -> outcome.target to null
        is ResolveOutcome.OnlyWeak -> null to (
            "No clear match for ${TargetResolver.describe(target)} — it only appears inside longer, unrelated-looking text " +
                "(${outcome.weakLabels.joinToString("; ") { "'${it.take(60)}${if (it.length > 60) "…" else ""}'" }}), not as its own item. " +
                "Scroll further or use the app's own search rather than tapping it."
            )
        is ResolveOutcome.NotFound -> null to outcome.detail
        is ResolveOutcome.Invalid -> null to outcome.detail
    }
}

internal fun weakVerify(before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
    val changed = Verify.screenChanged(before, after)
    return VerificationResult(false, "screen changed=$changed (weak signal only)").let {
        if (!result.success) VerificationResult(false, "action failed") else it
    }
}

// ---------------------------------------------------------------------------------------------

object TapTool : Tool {
    override val name = "tap"
    override val description = "Touch an item, by element_id (preferred), label, or x,y of a vision element. A label that " +
        "isn't listed yet is found by scrolling down automatically — no need to scroll first."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = TARGET_PARAMS + EXPECTS_PARAM
    override val grounded = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val (t, err) = resolveForExecute(args, ctx, ctrl, allowScroll = true)
        if (t == null) return ToolResult(false, err ?: "Could not resolve target.")
        val nodeId = t.nodeId
        return if (nodeId != null) {
            val ok = ctrl.clickElement(nodeId)
            ToolResult(ok, if (ok) "Tapped ${t.label?.let { "'$it'" } ?: "element $nodeId"} in ${ctx.observedBefore?.appPackage ?: "unknown"}." else "Could not tap element $nodeId.", channel = "node")
        } else {
            val ok = ctrl.tap(t.x, t.y)
            ToolResult(ok, if (ok) "Tapped at (${t.x},${t.y})${t.label?.let { " on '$it'" } ?: ""} (vision)." else "Gesture at (${t.x},${t.y}) was cancelled.", channel = "vision")
        }
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        weakVerify(before, after, result)

    override fun confirmationPrompt(args: JSONObject) =
        "Tap ${TargetResolver.parse(args)?.let { TargetResolver.describe(it) } ?: "this"}?"
}

object LongPressTool : Tool {
    override val name = "long_press"
    override val description = "Touch and hold an item (context menus, selection). Same targeting as tap."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = TARGET_PARAMS + ToolParam("duration_ms", "integer", "Hold time in ms (default 600).", required = false) + EXPECTS_PARAM
    override val grounded = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val (t, err) = resolveForExecute(args, ctx, ctrl, allowScroll = true)
        if (t == null) return ToolResult(false, err ?: "Could not resolve target.")
        val ms = (args.intOrNull("duration_ms") ?: 600).coerceIn(400, 5000).toLong()
        val nodeId = t.nodeId
        if (nodeId != null && ctrl.longClickElement(nodeId)) {
            return ToolResult(true, "Long-pressed ${t.label?.let { "'$it'" } ?: "element $nodeId"} (node action).", channel = "node")
        }
        val b = nodeId?.let { ctrl.boundsOf(it) } ?: Box(t.x, t.y, t.x, t.y)
        val ok = ctrl.tap(b.centerX.takeIf { !b.isEmpty() } ?: t.x, b.centerY.takeIf { !b.isEmpty() } ?: t.y, ms)
        return ToolResult(ok, if (ok) "Long-pressed ${t.label?.let { "'$it'" } ?: "(${t.x},${t.y})"} for ${ms}ms (gesture)." else "Long-press gesture was cancelled.",
            channel = if (t.how == "vision") "vision" else "gesture")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        weakVerify(before, after, result)

    override fun confirmationPrompt(args: JSONObject) =
        "Long-press ${TargetResolver.parse(args)?.let { TargetResolver.describe(it) } ?: "this"}?"
}

/** Start/end points for a directional stroke inside a region (default: the screen). */
private fun strokeFor(direction: String, region: Box, fraction: Float): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
    val cx = region.centerX; val cy = region.centerY
    val dx = (region.width * fraction / 2).toInt(); val dy = (region.height * fraction / 2).toInt()
    return when (direction.lowercase()) {
        "up" -> (cx to cy + dy) to (cx to cy - dy)          // finger moves up → content scrolls down/forward
        "down" -> (cx to cy - dy) to (cx to cy + dy)
        "left" -> (cx + dx to cy) to (cx - dx to cy)
        "right" -> (cx - dx to cy) to (cx + dx to cy)
        else -> null
    }
}

object SwipeTool : Tool {
    override val name = "swipe"
    override val description = "One finger stroke up/down/left/right (paging, dismiss). Optional target; default whole screen."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = listOf(
        ToolParam("direction", "string", "Direction the finger moves.", enumValues = listOf("up", "down", "left", "right"))
    ) + TARGET_PARAMS + listOf(
        ToolParam("distance", "string", "short | medium | long (default medium).", required = false),
        ToolParam("duration_ms", "integer", "Stroke time in ms (default 300).", required = false)
    ) + EXPECTS_PARAM
    override val grounded = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val dir = args.str("direction") ?: return ToolResult(false, "No direction.")
        val target = TargetResolver.parse(args)
        val region: Box = if (target != null) {
            val (t, err) = resolveForExecute(args, ctx, ctrl, allowScroll = false)
            if (t == null) return ToolResult(false, err ?: "Could not resolve target.")
            t.nodeId?.let { ctrl.boundsOf(it) } ?: t.element?.bounds ?: Box(t.x - 10, t.y - 10, t.x + 10, t.y + 10)
        } else {
            val (w, h) = ctrl.screenSize(); Box(0, (h * 0.15).toInt(), w, (h * 0.9).toInt())
        }
        val fraction = when (args.str("distance")?.lowercase()) { "short" -> 0.3f; "long" -> 0.9f; else -> 0.6f }
        val (from, to) = strokeFor(dir, region, fraction) ?: return ToolResult(false, "Unknown direction '$dir'.")
        val ms = (args.intOrNull("duration_ms") ?: 300).coerceIn(50, 5000).toLong()
        val ok = ctrl.swipe(from.first, from.second, to.first, to.second, ms)
        return ToolResult(ok, if (ok) "Swiped $dir from (${from.first},${from.second}) to (${to.first},${to.second})." else "Swipe gesture was cancelled.", channel = "gesture")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        weakVerify(before, after, result)

    override fun confirmationPrompt(args: JSONObject) = "Swipe ${args.str("direction")} on ${TargetResolver.parse(args)?.let { TargetResolver.describe(it) } ?: "the screen"}?"
}

object DragTool : Tool {
    override val name = "drag"
    override val description = "Drag one item onto another without lifting. Start: element_id/label/x,y. End: to_element_id/to_label/to_x,to_y."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = TARGET_PARAMS + listOf(
        ToolParam("to_element_id", "integer", "Id of the destination element.", required = false),
        ToolParam("to_label", "string", "Label of the destination element.", required = false),
        ToolParam("to_x", "integer", "Destination x (vision element bounds only).", required = false),
        ToolParam("to_y", "integer", "Destination y (vision element bounds only).", required = false),
        ToolParam("duration_ms", "integer", "Total drag time in ms (default 800).", required = false)
    ) + EXPECTS_PARAM
    override val grounded = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val (start, err) = resolveForExecute(args, ctx, ctrl, allowScroll = false)
        if (start == null) return ToolResult(false, err ?: "Could not resolve start target.")
        val endArgs = JSONObject()
        args.intOrNull("to_element_id")?.let { endArgs.put("element_id", it) }
        args.str("to_label")?.let { endArgs.put("label", it) }
        args.intOrNull("to_x")?.let { endArgs.put("x", it) }
        args.intOrNull("to_y")?.let { endArgs.put("y", it) }
        val endTarget = TargetResolver.parse(endArgs) ?: return ToolResult(false, "No destination: give to_element_id, to_label, or to_x/to_y.")
        val end = when (val r = TargetResolver.resolveStatic(endTarget, ctx.observedBefore)) {
            is ResolveOutcome.Found -> r.target
            is ResolveOutcome.OnlyWeak -> return ToolResult(false, "Destination ${TargetResolver.describe(endTarget)} is ambiguous (only weak matches).")
            is ResolveOutcome.NotFound -> return ToolResult(false, r.detail)
            is ResolveOutcome.Invalid -> return ToolResult(false, r.detail)
        }
        val ms = (args.intOrNull("duration_ms") ?: 800).coerceIn(200, 20000).toLong()
        val ok = ctrl.drag(listOf(start.x to start.y, end.x to end.y), ms)
        return ToolResult(ok, if (ok) "Dragged ${start.label?.let { "'$it'" } ?: "(${start.x},${start.y})"} to ${end.label?.let { "'$it'" } ?: "(${end.x},${end.y})"}." else "Drag gesture was cancelled.", channel = "gesture")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        weakVerify(before, after, result)

    override fun confirmationPrompt(args: JSONObject) = "Drag ${TargetResolver.parse(args)?.let { TargetResolver.describe(it) } ?: "this"}?"
}

object PinchTool : Tool {
    override val name = "pinch"
    override val description = "Pinch to zoom in/out, centred on a target or screen centre."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = listOf(
        ToolParam("direction", "string", "Zoom direction.", enumValues = listOf("in", "out"))
    ) + TARGET_PARAMS + EXPECTS_PARAM
    override val grounded = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val zoomIn = (args.str("direction") ?: "in").equals("in", true)
        val (w, h) = ctrl.screenSize()
        var cx = w / 2; var cy = h / 2
        if (TargetResolver.parse(args) != null) {
            val (t, err) = resolveForExecute(args, ctx, ctrl, allowScroll = false)
            if (t == null) return ToolResult(false, err ?: "Could not resolve target.")
            val b = t.nodeId?.let { ctrl.boundsOf(it) } ?: t.element?.bounds
            if (b != null && !b.isEmpty()) { cx = b.centerX; cy = b.centerY } else { cx = t.x; cy = t.y }
        }
        val small = (minOf(w, h) * 0.12).toInt().coerceAtLeast(20)
        val big = (minOf(w, h) * 0.5).toInt().coerceAtLeast(small + 20)
        val ok = if (zoomIn) ctrl.pinch(cx, cy, small, big) else ctrl.pinch(cx, cy, big, small)
        return ToolResult(ok, if (ok) "Pinched ${if (zoomIn) "in (zoom in)" else "out (zoom out)"} at ($cx,$cy)." else "Pinch gesture was cancelled.", channel = "gesture")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        weakVerify(before, after, result)
}

object ScrollTool : Tool {
    override val name = "scroll"
    override val description = "Reveal off-screen content; direction = content to reveal (down = see below). Optional target."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = listOf(
        ToolParam("direction", "string", "Which way to scroll.", enumValues = listOf("up", "down", "left", "right"))
    ) + TARGET_PARAMS + EXPECTS_PARAM
    override val grounded = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val direction = args.str("direction") ?: "down"
        val target = TargetResolver.parse(args)
        var region: Box? = null
        var nodeId: Int? = null
        if (target != null) {
            val (t, err) = resolveForExecute(args, ctx, ctrl, allowScroll = false)
            if (t == null) return ToolResult(false, err ?: "Could not resolve target.")
            nodeId = t.nodeId; region = t.nodeId?.let { ctrl.boundsOf(it) } ?: t.element?.bounds
        } else {
            val snap = ctx.observedBefore ?: ctrl.buildSnapshot()
            val s = snap.elements.firstOrNull { it.scrollable }
            nodeId = s?.id; region = s?.bounds
        }
        // Level 1: node scroll action (self or scrollable ancestor).
        if (nodeId != null && ctrl.scrollElement(nodeId, direction)) {
            return ToolResult(true, "Scrolled $direction (node action).", channel = "node")
        }
        // Level 2: generic swipe gesture over the region — covers Canvas/WebView/custom views that
        // expose no scroll action. The finger moves opposite to the content direction.
        val (w, h) = ctrl.screenSize()
        val reg = region?.takeIf { !it.isEmpty() } ?: Box(0, (h * 0.2).toInt(), w, (h * 0.85).toInt())
        val finger = when (direction.lowercase()) { "down" -> "up"; "up" -> "down"; "left" -> "right"; "right" -> "left"; else -> "up" }
        val (from, to) = strokeFor(finger, reg, 0.6f) ?: return ToolResult(false, "Unknown direction '$direction'.")
        val ok = ctrl.swipe(from.first, from.second, to.first, to.second, 350)
        return ToolResult(ok, if (ok) "Scrolled $direction (gesture fallback; no scrollable node responded)." else "Could not scroll.", channel = "gesture")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        weakVerify(before, after, result)
}

object TypeTool : Tool {
    override val name = "type"
    override val description = "Type into an editable field (element_id/label). submit=true also presses Enter/Search. Never for passwords/PINs."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = TARGET_PARAMS + listOf(
        ToolParam("text", "string", "The text to enter."),
        ToolParam("submit", "boolean", "Also submit (press Enter/Search/Send) after typing.", required = false)
    ) + EXPECTS_PARAM
    override val grounded = true
    override val verifiesOutcome = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val text = args.str("text").orEmpty()
        val submit = args.boolOrDefault("submit", false)
        val (t, err) = resolveForExecute(args, ctx, ctrl, allowScroll = false)
        if (t == null) return ToolResult(false, err ?: "Could not resolve target.")
        val nodeId = t.nodeId ?: return ToolResult(false, "Typing needs an editable tree element, not a vision target.")
        if (t.element?.editable != true) return ToolResult(false, "Element $nodeId is not editable.")
        var ok = ctrl.setText(nodeId, text, submit)
        var how = "node"
        // G-05: some fields (custom editors, some web inputs) reject ACTION_SET_TEXT; paste the text instead.
        if (!ok && text.isNotEmpty()) {
            val cm = ctx.appContext.getSystemService(android.content.ClipboardManager::class.java)
            if (cm != null && try { cm.setPrimaryClip(android.content.ClipData.newPlainText("Tɛkyerɛma", text)); true } catch (_: Throwable) { false }) {
                ok = ctrl.nodeAction(nodeId, "paste")
                how = "paste"
            }
        }
        return ToolResult(ok, if (ok) "Entered text into element $nodeId${if (how == "paste") " (pasted)" else ""}${if (submit && how == "node") " and submitted" else ""}."
            else "Could not set text on element $nodeId.", channel = how)
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        if (!result.success) return VerificationResult(false, "action failed")
        val text = args.str("text").orEmpty()
        val submit = args.boolOrDefault("submit", false)
        val present = Verify.textPresent(after, text)
        if (!submit) return VerificationResult(present, "field contains text=$present")
        // A submit that TOOK EFFECT moves the text out of the input box into page content — a results heading, a
        // sent-message bubble, the results URL (…?q=cape+coast+castle). A no-op submit leaves it only in the field.
        // So: the screen changed AND the text is now in a NON-editable element. (Seen on-device: a successful
        // Chrome search was reported "…but I could not confirm it" because only the field was checked.)
        val t = text.trim().lowercase()
        val forms = listOf(t, t.replace(Regex("\\s+"), "+"), t.replace(Regex("\\s+"), "%20")).filter { it.length >= 2 }
        val inContent = after?.elements?.any { e ->
            !e.editable && e.label?.lowercase()?.let { l -> forms.any { l.contains(it) } } == true
        } == true || forms.any { f -> after?.screenTitle?.lowercase()?.contains(f) == true }
        val changed = Verify.screenChanged(before, after)
        // Still sitting focused in the box with its text (e.g. a suggestion dropdown echoing it) = not submitted.
        val stillEditing = after?.elements?.any { e -> e.editable && e.focused && e.label?.lowercase()?.contains(t) == true } == true
        return if (changed && inContent && !stillEditing) VerificationResult(true, "submitted — the text now appears in the page content")
        else VerificationResult(false, "submitted; screen changed=$changed, text in page content=$inContent — the submit's effect isn't confirmed")
    }

    override fun confirmationPrompt(args: JSONObject) =
        "Type \"${args.str("text")}\" into ${TargetResolver.parse(args)?.let { TargetResolver.describe(it) } ?: "the field"}${if (args.boolOrDefault("submit", false)) " and submit" else ""}?"
}

object SetRangeTool : Tool {
    override val name = "set_range"
    override val description = "Set an adjustable/range control to a value within its min/max."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = TARGET_PARAMS + ToolParam("value", "integer", "Target value within the element's range.") + EXPECTS_PARAM
    override val grounded = true
    override val verifiesOutcome = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val value = args.optDoubleOrNull("value") ?: return ToolResult(false, "No value provided.")
        val (t, err) = resolveForExecute(args, ctx, ctrl, allowScroll = false)
        if (t == null) return ToolResult(false, err ?: "Could not resolve target.")
        val nodeId = t.nodeId ?: return ToolResult(false, "Range control must be a tree element.")
        val ok = ctrl.setRangeValue(nodeId, value.toFloat())
        return ToolResult(ok, if (ok) "Set element $nodeId to $value." else "Could not adjust element $nodeId (it may not be a range control).", channel = "node")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        val target = args.optDoubleOrNull("value")
        val id = ctx.resolvedTarget?.nodeId ?: args.intOrNull("element_id")
        val actual = (id?.let { after?.element(it) } ?: after?.elements?.firstOrNull { it.range != null })?.range?.current
        val close = actual != null && target != null && kotlin.math.abs(actual - target) <= 3.0
        return VerificationResult(result.success && close, "range now=$actual, target=$target")
    }
}

object KeyEventTool : Tool {
    override val name = "key_event"
    override val description = "Press a system key: back/home/recents/volume keys, lock_screen, power_menu, screenshot, " +
        "split_screen, or pull down quick_settings (Wi-Fi, Bluetooth, Do Not Disturb, Flashlight… tiles) or notifications."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = listOf(
        ToolParam("key", "string", "The key.", enumValues = listOf("back", "home", "recents", "quick_settings", "notifications",
            "volume_up", "volume_down", "volume_mute", "lock_screen", "power_menu", "screenshot", "split_screen"))
    ) + EXPECTS_PARAM

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val key = args.str("key")?.lowercase() ?: return ToolResult(false, "No key.")
        val ok = when (key) {
            // E-18: every global action the device exposes (getSystemActions) — unavailable ones fail honestly.
            "back", "home", "recents", "quick_settings", "notifications",
            "lock_screen", "power_menu", "screenshot", "split_screen" -> ctrl.globalAction(key)
            "volume_up", "volume_down", "volume_mute" -> ctrl.adjustVolume(key)
            else -> return ToolResult(false, "Unknown key '$key'.")
        }
        return ToolResult(ok, if (ok) "Pressed $key." else "$key failed.", channel = "system")
    }

    // Authoritative for "home" (a checkable SYSTEM goal state). back/recents/volume keep the weak screen-diff
    // signal (always unverified), so this never weakens anything — OutcomeVerifier only ADDS a confirmed outcome.
    override val verifiesOutcome = true

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        if (!result.success) return VerificationResult(false, "action failed")
        if (args.str("key")?.lowercase() == "home") {
            // GOAL-STATE check, not a screen-diff proxy: the device's default launcher is in front. Correct even when
            // the user was ALREADY on the home screen (nothing changes) — the old weak check read that as a failure
            // (found by the on-device eval: go_home → COMPLETED_UNVERIFIED).
            val launcher = defaultLauncherPackage(ctx.appContext) ?: "launcher"
            val (ok, fg) = Verify.pollForegroundContains(ctx.controller, launcher, attempts = 5, intervalMs = 250L)
            return if (ok) VerificationResult(true, "home screen is in front ($fg)")
            else VerificationResult(false, "expected the home screen ($launcher); foreground is ${fg ?: "unknown"}")
        }
        return weakVerify(before, after, result)
    }
}

/** The device's default home app (launcher), resolved from the system — no hardcoded OEM launcher names. */
internal fun defaultLauncherPackage(ctx: android.content.Context): String? = try {
    ctx.packageManager.resolveActivity(
        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName?.takeIf { it != "android" }   // "android" = the chooser (no default launcher set)
} catch (_: Throwable) { null }

object ReadStateTool : Tool {
    override val name = "read_state"
    override val description = "You are ALREADY given the current screen every step, so you rarely need this — re-reading an unchanged screen just wastes a step. Use it ONLY with vision=true to read custom/canvas/web content the accessibility tree misses (on-device screenshot + OCR)."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = listOf(
        ToolParam("vision", "boolean", "Also read the screen visually (screenshot + on-device OCR).", required = false),
        ToolParam("reason", "string", "Why vision is needed (logged).", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        if (!ctrl.ready) return ToolResult(false, NO_SERVICE)   // §1.19 — service attached but not yet ready
        val vision = args.boolOrDefault("vision", false)
        // SECURE INPUT BOUNDARY: this tool observes on its own (a direct question's first step never went through the
        // loop's credential check), so inspect the raw tree FIRST and never screenshot, OCR or describe a PIN,
        // password, one-time-code or robot-check screen.
        val raw = try { ctrl.buildSnapshot() } catch (_: Throwable) { null }
        if (raw != null && (com.tekyerema.policy.TargetSemantics.isCredentialScreen(raw) || com.tekyerema.policy.TargetSemantics.isHumanCheckScreen(raw)))
            return ToolResult(true, "This is a private screen (a PIN, password or code) — it is not read. Tell the user to finish it themselves.",
                data = JSONObject().put("vision", false).put("secure", true), channel = "none")
        val snap = Perception.observe(ctrl, vision, args.str("reason"))
        val data = JSONObject().put("vision", vision).put("route", snap.perception.source)
        // §1.20 — a protected (FLAG_SECURE) screen with nothing readable must not be reported as "empty".
        if (snap.secureWindow && snap.actionableCount == 0)
            return ToolResult(true, SECURE_SCREEN, data = data.put("secure", true), channel = "none")
        return ToolResult(true, snap.summarize(), data = data, channel = "none")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        VerificationResult(result.success, "observation")
}

/**
 * Reads the screen to the user word for word, a few sentences at a time ([com.tekyerema.agent.ScreenReadout]) —
 * on the phone, no internet (the tree, or on-device OCR when the tree is empty). `continue` gives the next part of the
 * same screen. Same privacy rules as [ReadStateTool]: a PIN / password / code / robot-check screen is never read, not
 * even OCR'd; an app on the user's deny list is never read.
 */
object ReadScreenTool : Tool {
    override val name = "read_screen"
    override val description = "Read the text on the screen to the user word for word, in reading order, a few sentences at a time " +
        "(continue=true for the next part)."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = listOf(ToolParam("continue", "boolean", "The next part of the same reading.", required = false))

    const val PRIVATE = "This is a private screen — a PIN, password or code — so I won't read it."

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        if (!ctrl.ready) return ToolResult(false, NO_SERVICE)
        fun private(s: UiSnapshot) = com.tekyerema.policy.TargetSemantics.isCredentialScreen(s) ||
            com.tekyerema.policy.TargetSemantics.isHumanCheckScreen(s)
        // The raw tree first: a credential screen is never screenshotted or OCR'd.
        val raw = try { ctrl.buildSnapshot() } catch (_: Throwable) { null }
        if (raw != null && private(raw)) {
            com.tekyerema.agent.ScreenReadCursor.clear()
            return ToolResult(true, PRIVATE, JSONObject().put("secure", true), channel = "none")
        }
        val pkg = raw?.appPackage
        if (pkg != null && pkg in com.tekyerema.policy.PolicyConfig.denyApps())
            return ToolResult(true, "That app is on your do-not-read list, so I won't read it.", channel = "none")
        val key = raw?.let { com.tekyerema.agent.ScreenReadCursor.keyOf(it) }
        if (args.boolOrDefault("continue", false) && key != null)
            com.tekyerema.agent.ScreenReadCursor.next(key)?.let { return ToolResult(true, it, channel = "none") }
        val snap = Perception.observe(ctrl, false, "read the screen")
        if (private(snap)) { com.tekyerema.agent.ScreenReadCursor.clear(); return ToolResult(true, PRIVATE, channel = "none") }
        if (snap.secureWindow && snap.elements.none { !it.label.isNullOrBlank() })
            return ToolResult(true, SECURE_SCREEN, JSONObject().put("secure", true), channel = "none")
        val parts = com.tekyerema.agent.ScreenReadout.compose(snap)
        // K-15: "what changed?" compares against what was just read.
        com.tekyerema.agent.ScreenMemory.remember(snap.appPackage, snap.elements.mapNotNull { e -> e.label?.takeIf { it.isNotBlank() } })
        return ToolResult(true, com.tekyerema.agent.ScreenReadCursor.start(parts, key ?: com.tekyerema.agent.ScreenReadCursor.keyOf(snap)),
            JSONObject().put("parts", parts.size).put("route", snap.perception.source), channel = "none")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        VerificationResult(result.success, "observation")
}

object WaitTool : Tool {
    override val name = "wait"
    override val description = "Wait a few seconds for the screen or an app to finish loading."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = listOf(ToolParam("seconds", "integer", "Seconds to wait (1-5).", required = false))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val secs = (args.intOrNull("seconds") ?: 1).coerceIn(1, 5)
        delay(secs * 1000L)
        return ToolResult(true, "Waited ${secs}s.", channel = "none")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        VerificationResult(true, "waited")
}

/**
 * Not an action: a DECISION the engine intercepts. The model uses it to stop honestly when the
 * environment (not a missing tool) prevents the goal. Reason classes are structural (plan §15),
 * never capability names.
 */
object CannotDoTool : Tool {
    override val name = "cannot_do"
    override val description = "Report the goal cannot be completed (environment blocker), with reason_class + what you observed."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = listOf(
        ToolParam("reason_class", "string", "Why.", enumValues = CANNOT_DO_CLASSES),
        ToolParam("detail", "string", "Specifically what was tried and what blocks it.")
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult =
        ToolResult(true, "cannot_do", channel = "none")
}

val CANNOT_DO_CLASSES = listOf(
    "no_accessible_ui", "permission_missing", "app_blocks_automation", "authentication_required",
    "device_restriction", "network_unavailable", "resource_unavailable", "repeated_failure", "unsafe_or_uncertain"
)

/** Convenience for the engine: is this element from the tree with a live node? */
internal val UiSnapshot.hasTree: Boolean get() = elements.any { it.source == ElementSource.TREE }
