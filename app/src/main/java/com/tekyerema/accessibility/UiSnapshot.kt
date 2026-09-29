package com.tekyerema.accessibility

import org.json.JSONArray
import org.json.JSONObject

/** Which perception channel produced an element. Vision elements are structurally less reliable. */
enum class ElementSource { TREE, VISION }

/** min/current/max for a slider/SeekBar-style control (AccessibilityNodeInfo.RangeInfo). */
data class UiRange(val min: Float, val max: Float, val current: Float)

/** Screen bounds as plain ints — no android.graphics dependency, so snapshot logic is unit-testable. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    fun isEmpty(): Boolean = width <= 0 || height <= 0
    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
    fun overlaps(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun toJson(): JSONArray = JSONArray().put(left).put(top).put(right).put(bottom)
    companion object { val NONE = Box(0, 0, 0, 0) }
}

/**
 * One actionable/informative element on screen. `label` is UNTRUSTED (screen-derived) and has
 * already been passed through [com.tekyerema.core.Sanitize].
 */
data class UiElement(
    val id: Int,
    val role: String,
    val label: String?,
    val bounds: Box,
    val source: ElementSource = ElementSource.TREE,
    val editable: Boolean = false,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val password: Boolean = false,
    val range: UiRange? = null,
    /** Internal durable selector (resource id). Never shown to the model. */
    val viewId: String? = null,
    /** Generic structure hint, e.g. "list 12 rows" or "row 3" — no app semantics. */
    val collection: String? = null,
    /** An editable's placeholder hint ("Search", "Message") even when it holds text — internal (policy), never shown. */
    val hint: String? = null,
    /** F-14: a heading (native isHeading, or a web page's heading role) — how screen-reader users skim a page. */
    val heading: Boolean = false,
    /** F-14: a web link. */
    val link: Boolean = false,
    /** G-03: actions the app itself defines on this element ("Archive", "Mark as read") — TalkBack's actions menu. */
    val customActions: List<String> = emptyList(),
    /** Why a person can't perceive this element as the agent reads it ([Perceivability.CRAMMED] / [Perceivability.MISMATCH]),
     *  or null. A flagged element is never the target of a consequential action ([com.tekyerema.policy.StepGate]). */
    val unseen: String? = null,
    /** The app this node belongs to, when it is NOT the window's own app (content embedded from another app). */
    val pkg: String? = null
) {
    val interactive: Boolean
        get() = clickable || longClickable || editable || scrollable || checkable || range != null

    /** The PRIMITIVE names this element supports — the same vocabulary the model calls. */
    val actions: List<String>
        get() = buildList {
            if (clickable || source == ElementSource.VISION) add("tap")
            if (longClickable || source == ElementSource.VISION) add("long_press")
            if (editable) add("type")
            if (scrollable) add("scroll")
            if (range != null) add("set_range")
        }
}

/** Which route produced this snapshot and why — logged on every observation (plan §6). */
data class PerceptionRoute(val source: String, val reason: String) {
    val usedVision: Boolean get() = source.contains("vision")
}

/**
 * A compact, normalized representation of the current screen — NOT the raw accessibility tree.
 * This is the only screen data handed to the planner. Ids are fresh per snapshot and never
 * survive across observations.
 */
data class UiSnapshot(
    val appPackage: String?,
    val screenTitle: String?,
    val elements: List<UiElement>,
    val timestampMs: Long,
    val perception: PerceptionRoute = PerceptionRoute("tree", "default"),
    /** True when the active window declared FLAG_SECURE (screenshot blocked; tree still readable). */
    val secureWindow: Boolean = false,
    /** Elements in the tree that carried hidden-channel text stripped by Sanitize. Logged, not shown. */
    val strippedHiddenText: Int = 0,
    /** What a person calls [appPackage]: the launcher label ("Settings"), or "the home screen" for the
     *  launcher. Spoken to the user instead of a package id; null when unknown. */
    val appName: String? = null,
    /** F-13: the kind of window this came from when it isn't an ordinary app window ("system", "keyboard", "overlay"). */
    val windowType: String? = null,
    /** F-01: the traversal hit its node cap — the screen is longer than what was read. */
    val truncated: Boolean = false,
    /** F-17: what the app just announced (TalkBack announcements, toasts) in the last few seconds. */
    val announcements: List<String> = emptyList()
) {
    fun element(id: Int): UiElement? = elements.firstOrNull { it.id == id }

    val actionableCount: Int get() = elements.count { it.interactive }

    /** F-03: a short id for this reading of the screen (same screen content ⇒ same id), so steps can say which one they saw. */
    val snapshotId: String get() = Integer.toHexString(signature().hashCode())

    /** F-03: caps from config (`perception.tree.maxElements`, `perception.tree.labelMax`). */
    fun toCompactJson(maxElements: Int = com.tekyerema.core.config.AppConfig.int("perception.tree.maxElements", 45).coerceIn(10, 150),
                      labelMax: Int = com.tekyerema.core.config.AppConfig.int("perception.tree.labelMax", 64).coerceIn(16, 400)): JSONObject {
        // Interactive tree elements first, then labelled static text, then vision elements last —
        // so a busy screen never hides its controls and the model sees which grounding it is using.
        val folded = if (com.tekyerema.core.config.AppConfig.bool("perception.tree.foldRows", true)) foldedRowParts() else emptySet()
        val tree = elements.filter { it.source == ElementSource.TREE && it.id !in folded }
        val vision = elements.filter { it.source == ElementSource.VISION }
        val ordered = tree.filter { it.interactive } +
            tree.filter { !it.interactive && !it.label.isNullOrBlank() } +
            vision
        val arr = JSONArray()
        for (e in ordered.take(maxElements)) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("role", e.role)
            // J-10: a one-time code / PIN in a label (an SMS preview, a banner) never reaches the model; the element stays.
            // Hidden text (far more words than the box can show — Perceivability) is withheld: nobody can read it on screen.
            if (!e.label.isNullOrBlank() && e.unseen != Perceivability.CRAMMED)
                o.put("label", (com.tekyerema.core.Redact.secrets(e.label) ?: e.label).take(labelMax))
            e.pkg?.let { o.put("from_app", it) }
            // Mark only vision elements (they need x,y grounding); "tree" is the default, so omit it — this
            // JSON is sent for up to 45 elements on every step, so per-element bytes dominate the request.
            if (e.source == ElementSource.VISION) o.put("source", "vision")
            // Only STATE flags. What an element can DO is in `actions` below (tap = clickable, long_press, type = editable,
            // scroll = scrollable, set_range = adjustable); sending both cost ~20 characters per control on every step,
            // under a free tier capped in tokens per minute. Readers of this JSON take either (LocalBrain.describeScreen).
            val flags = ArrayList<String>()
            if (e.checkable) flags.add("checkable")
            if (e.checked) flags.add("checked")
            if (e.selected) flags.add("selected")
            if (!e.enabled) flags.add("disabled")
            if (e.focused) flags.add("focused")
            if (e.password) flags.add("password")
            if (e.heading) flags.add("heading")   // F-14
            if (e.link) flags.add("link")         // F-14
            e.unseen?.let { flags.add(it) }       // hidden_text / a11y_mismatch: not what a person sees or hears
            if (flags.isNotEmpty()) o.put("flags", JSONArray(flags))
            if (e.customActions.isNotEmpty()) o.put("custom_actions", JSONArray(e.customActions.take(6)))   // G-03
            // Keep the explicit primitive vocabulary per element: the model can't be trusted to map the
            // "adjustable" flag to the `set_range` primitive (names differ), so `actions` earns its bytes.
            val acts = e.actions
            if (acts.isNotEmpty()) o.put("actions", JSONArray(acts))
            e.range?.let { o.put("range", JSONObject().put("min", it.min).put("max", it.max).put("current", it.current)) }
            e.collection?.let { o.put("structure", it) }
            // Coordinates only where they are the grounding: vision elements have no node to act on.
            if (e.source == ElementSource.VISION) o.put("bounds", e.bounds.toJson())
            arr.put(o)
        }
        val listCut = ordered.size > maxElements
        val focus = elements.firstOrNull { it.focused && it.source == ElementSource.TREE }?.id
        return JSONObject()
            .put("app", appPackage ?: "unknown")
            .apply { if (!appName.isNullOrBlank()) put("appName", appName) }
            .apply { if (!screenTitle.isNullOrBlank()) put("screen", com.tekyerema.core.Redact.secrets(screenTitle)) }
            .put("snap", snapshotId)                                                            // F-03
            .apply { if (focus != null) put("focus", focus) }                                   // F-03 input focus
            .put("elementCount", elements.size)
            // The routine "tree exposed N actionable elements" tells the model nothing the listing doesn't; a vision look
            // or a failed one ("vision fallback failed: rate limited") does, so only those carry a reason.
            .put("perception", JSONObject().put("source", perception.source).apply {
                if (perception.source != "tree" || !perception.reason.startsWith("tree exposed")) put("reason", perception.reason)
            })
            .apply { if (secureWindow) put("secureWindow", true) }
            .apply { if (windowType != null) put("window", windowType) }                       // F-13
            .apply { if (announcements.isNotEmpty()) put("announced", JSONArray(announcements.map { com.tekyerema.core.Redact.secrets(it) })) }  // F-17
            // F-01: the service's traversal hit its node cap (not the listing cap below).
            .apply { if (this@UiSnapshot.truncated) put("longScreen", "only part of this screen was read — scroll for more") }
            .put("elements", arr)
            .apply {
                if (listCut) put(
                    "note",
                    "${ordered.size - maxElements} more elements exist on this screen but aren't shown here — " +
                        "scroll, or use the app's own search to reach something not listed rather than assuming it's absent."
                )
            }
    }

    /**
     * N-03: the ids of listing noise inside a tappable row. A list row is usually an UNLABELED clickable container
     * followed by its icon and texts, and the texts are tappable through it (a tap on a text climbs to the nearest
     * clickable ancestor — the row). So the container adds nothing its text doesn't, and neither does an unlabeled
     * image inside a row-sized one; on the A20e's Settings home they were 18 of 40 listed elements, and took slots
     * from real content under the listing cap. Kept: anything labeled, long-pressable, editable, scrollable,
     * checkable, adjustable, flagged or with custom actions; a container with no plain tappable text inside it
     * (tapping it is the only way in); and images inside anything bigger than a row (a full-screen tappable layer
     * mustn't hide an app's unlabeled icon buttons). Pure: bounds and flags only.
     */
    internal fun foldedRowParts(): Set<Int> {
        val tree = elements.filter { it.source == ElementSource.TREE }
        fun plainTap(e: UiElement) = e.label.isNullOrBlank() && e.clickable && !e.longClickable && !e.editable &&
            !e.scrollable && !e.checkable && !e.checked && e.range == null && !e.selected && e.enabled && !e.focused &&
            !e.password && !e.heading && !e.link && e.customActions.isEmpty() && (e.collection == null || e.collection.startsWith("row "))
        fun inside(a: Box, b: Box) = a.left >= b.left && a.top >= b.top && a.right <= b.right && a.bottom <= b.bottom
        // Plain text (not a field — AutoCompleteTextView is a TextView too — and not a checkable CheckedTextView).
        val texts = tree.filter { !it.label.isNullOrBlank() && it.clickable && it.role.endsWith("TextView") && !it.editable && !it.checkable }
        val rows = tree.filter { c -> plainTap(c) && !c.bounds.isEmpty() && texts.any { it.id != c.id && inside(it.bounds, c.bounds) } }
        if (rows.isEmpty()) return emptySet()
        val out = rows.mapTo(HashSet()) { it.id }
        val screenH = tree.maxOf { it.bounds.bottom }
        val rowSized = rows.filter { it.bounds.height * 4 <= screenH }
        for (e in tree) if (e.id !in out && plainTap(e) && e.role.endsWith("ImageView") && rowSized.any { inside(e.bounds, it.bounds) }) out.add(e.id)
        return out
    }

    fun summarize(): String {
        // J-10: this is read_state's result (it goes to the model) — codes hidden like in [toCompactJson].
        val visible = elements.filter { it.unseen != Perceivability.CRAMMED }
            .mapNotNull { it.label?.takeIf { l -> l.isNotBlank() }?.let { l -> com.tekyerema.core.Redact.secrets(l) } }.take(12)
        val head = "App: ${appPackage ?: "unknown"}" + (screenTitle?.let { "; screen: $it" } ?: "") +
            "; perception: ${perception.source}"
        return if (visible.isEmpty()) "$head. No readable text found."
        else "$head. Visible: " + visible.joinToString("; ")
    }

    /** A crude signature used to detect whether the screen changed after an action — a WEAK signal only. */
    fun signature(): String =
        (appPackage ?: "?") + "|" + elements.filter { it.source == ElementSource.TREE }
            .joinToString("¶") { "${it.role}:${it.label ?: ""}" }

    companion object {
        val EMPTY = UiSnapshot(null, null, emptyList(), 0L, PerceptionRoute("none", "no controller"))
    }
}
