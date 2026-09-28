package com.tekyerema.tools



import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The page's main scrolling region: the LARGEST scrollable element, not the first (often a small carousel or tab strip). */
internal fun mainScrollable(snap: UiSnapshot): com.tekyerema.accessibility.UiElement? =
    snap.elements.filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }

/**
 * G-01 / G-02 / G-03 — semantic node actions beyond tap: expand/collapse a row, select, dismiss a sheet, copy or
 * paste in a field, bring an off-screen item into view, page a pager, or run an action the app itself defines on the
 * element (the "custom_actions" listed on it, e.g. "Archive", "Mark as read" — what TalkBack's actions menu offers).
 * A custom action that sounds consequential is raised to TIER2 by the policy.
 */
object NodeActionTool : Tool {
    override val name = "node_action"
    override val description = "Do a node action on an element: expand, collapse, select, dismiss, copy, paste, show_on_screen, " +
        "page_up/down/left/right, or one of its custom_actions (pass custom=<label>)."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = TARGET_PARAMS.take(2) + listOf(
        ToolParam("action", "string", "The action.", required = false, enumValues = listOf("expand", "collapse", "select", "dismiss",
            "copy", "paste", "cut", "show_on_screen", "page_up", "page_down", "page_left", "page_right", "context_click", "custom")),
        ToolParam("custom", "string", "A label from the element's custom_actions (with action=custom).", required = false)
    ) + EXPECTS_PARAM
    override val grounded = true

    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        val action = args.str("action")?.lowercase()
        if (action == null && args.str("custom").isNullOrBlank()) return ToolResult(false, "Say which action (or a custom action label).")
        return null
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val (t, err) = resolveForExecute(args, ctx, ctrl, allowScroll = true)
        if (t == null) return ToolResult(false, err ?: "Could not resolve target.")
        val nodeId = t.nodeId ?: return ToolResult(false, "Node actions need an element from the screen listing, not a vision point.")
        val custom = args.str("custom")?.trim()?.takeIf { it.isNotEmpty() }
        val action = if (custom != null) "custom:$custom" else args.str("action")!!.lowercase()
        val ok = ctrl.nodeAction(nodeId, action)
        val what = custom ?: action.replace('_', ' ')
        return ToolResult(ok, if (ok) "Did \"$what\" on ${t.label?.let { "'$it'" } ?: "element $nodeId"}." else
            "\"$what\" isn't available on ${t.label?.let { "'$it'" } ?: "that element"}.", channel = "node")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        weakVerify(before, after, result)

    override fun confirmationPrompt(args: JSONObject): String =
        "Do \"${args.str("custom") ?: args.str("action")}\" on ${args.str("label") ?: "that item"}?"
}

/**
 * G-18 — find an item by scrolling, in ONE call. Each scroll used to cost a model step (and ~4k tokens); this scrolls
 * the main list until an element with the label appears or the list stops moving, and reports where it is.
 */
object ScrollToFindTool : Tool {
    override val name = "scroll_to_find"
    override val description = "Scroll the screen's list until an item with this text appears (or the list ends); returns its element_id."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = listOf(
        ToolParam("label", "string", "Text of the item to find."),
        ToolParam("direction", "string", "Scroll direction (default down).", required = false, enumValues = listOf("down", "up"))
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val label = args.str("label")?.trim().orEmpty()
        if (label.isEmpty()) return ToolResult(false, "No label to look for.")
        val dir = args.str("direction")?.lowercase() ?: "down"
        val maxScrolls = AppConfig.int("executor.find.maxScrolls", 10)
        var snap = ctrl.buildSnapshot()
        var lastSig = ""
        repeat(maxScrolls + 1) { i ->
            (Grounding.bestMatch(snap.elements, label) as? MatchOutcome.Found)?.let { m ->
                return ToolResult(true, "Found '${m.element.label}' (element_id ${m.element.id})${if (i > 0) " after $i scroll${if (i > 1) "s" else ""}" else ""}.",
                    data = JSONObject().put("element_id", m.element.id), channel = "node")
            }
            val sig = snap.signature()
            if (sig == lastSig) return ToolResult(false, "'$label' isn't in this list — it ended after $i scroll${if (i == 1) "" else "s"}.", channel = "node")
            lastSig = sig
            val scrollable = mainScrollable(snap) ?: return ToolResult(false, "'$label' isn't on this screen and nothing scrolls.")
            ctrl.scrollElement(scrollable.id, dir)
            ctrl.waitForSettle(1200)
            snap = ctrl.buildSnapshot()
        }
        return ToolResult(false, "Scrolled $maxScrolls times without finding '$label'.", channel = "node")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        VerificationResult(result.success, result.message)
}

/**
 * G-18 — wait for something to appear (a page loading, a "Sent" tick, a dialog) instead of spending steps on
 * repeated read_state calls.
 */
object WaitForElementTool : Tool {
    override val name = "wait_for_element"
    override val description = "Wait up to N seconds for an item with this text to appear on screen (page loading, a confirmation)."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = listOf(
        ToolParam("label", "string", "Text to wait for."),
        ToolParam("seconds", "integer", "Longest wait, 1-15 (default 8).", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val label = args.str("label")?.trim().orEmpty()
        if (label.isEmpty()) return ToolResult(false, "No label to wait for.")
        val deadline = System.currentTimeMillis() + (args.intOrNull("seconds") ?: 8).coerceIn(1, 15) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val snap = ctrl.buildSnapshot()
            (Grounding.bestMatch(snap.elements, label) as? MatchOutcome.Found)?.let {
                return ToolResult(true, "'${it.element.label}' appeared (element_id ${it.element.id}).", channel = "node")
            }
            snap.announcements.firstOrNull { it.contains(label, true) }?.let { return ToolResult(true, "The app announced: \"$it\".", channel = "node") }
            delay(500)
        }
        return ToolResult(false, "'$label' didn't appear within the wait.", channel = "node")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        VerificationResult(result.success, result.message)
}

/**
 * F-15 — read a whole long page (an article, terms, a long message): scroll the main list, collect the text of each
 * new screenful, stop when it stops moving. Returns the text in reading order (bounded).
 */
object ReadAllTool : Tool {
    override val name = "read_all"
    override val description = "Read a whole long page by scrolling to the end and collecting its text (articles, long messages, terms)."
    override val scope = CapabilityScope.PERCEIVE
    override val riskTier = RiskTier.TIER0
    override val params = emptyList<ToolParam>()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE)
        val maxPages = AppConfig.int("perception.document.maxPages", 8)
        val maxChars = AppConfig.int("perception.document.maxChars", 2500)
        val seen = LinkedHashSet<String>()
        var snap = ctrl.buildSnapshot()
        // Same privacy rules as read_screen (this result goes to the model): never a PIN / password / code / robot-check
        // screen, never an app on the user's deny list — checked on every page, since scrolling can reveal either.
        if (com.tekyerema.policy.ScreenPrivacy.isPrivate(snap))
            return ToolResult(true, ReadScreenTool.PRIVATE, JSONObject().put("secure", true), channel = "none")
        if (com.tekyerema.policy.ScreenPrivacy.isDenied(snap.appPackage))
            return ToolResult(true, "That app is on your do-not-read list, so I won't read it.", channel = "none")
        var lastSig = ""
        for (page in 0 until maxPages) {
            if (com.tekyerema.policy.ScreenPrivacy.isPrivate(snap) || com.tekyerema.policy.ScreenPrivacy.isDenied(snap.appPackage)) break
            // J-10: a one-time code in the text is hidden before it reaches the model.
            snap.elements.mapNotNull { it.label?.trim() }.filter { it.length > 1 }.forEach { seen.add(com.tekyerema.core.Redact.secrets(it) ?: it) }
            if (seen.sumOf { it.length } >= maxChars) break
            val sig = snap.signature()
            if (sig == lastSig) break
            lastSig = sig
            val scrollable = mainScrollable(snap) ?: break
            if (!ctrl.scrollElement(scrollable.id, "down")) break
            ctrl.waitForSettle(1200)
            snap = ctrl.buildSnapshot()
        }
        if (seen.isEmpty()) return ToolResult(false, "I couldn't find any text to read on this screen.")
        val text = seen.joinToString(". ").take(maxChars)
        return ToolResult(true, text, data = JSONObject().put("chars", text.length), channel = "node")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult) =
        VerificationResult(result.success, "read ${result.data?.optInt("chars") ?: 0} characters")
}
