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
    val collection: String? = null
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
    val appName: String? = null
) {
    fun element(id: Int): UiElement? = elements.firstOrNull { it.id == id }

    val actionableCount: Int get() = elements.count { it.interactive }

    fun toCompactJson(maxElements: Int = 45): JSONObject {
        // Interactive tree elements first, then labelled static text, then vision elements last —
        // so a busy screen never hides its controls and the model sees which grounding it is using.
        val tree = elements.filter { it.source == ElementSource.TREE }
        val vision = elements.filter { it.source == ElementSource.VISION }
        val ordered = tree.filter { it.interactive } +
            tree.filter { !it.interactive && !it.label.isNullOrBlank() } +
            vision
        val arr = JSONArray()
        for (e in ordered.take(maxElements)) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("role", e.role)
            if (!e.label.isNullOrBlank()) o.put("label", e.label.take(64))
            // Mark only vision elements (they need x,y grounding); "tree" is the default, so omit it — this
            // JSON is sent for up to 45 elements on every step, so per-element bytes dominate the request.
            if (e.source == ElementSource.VISION) o.put("source", "vision")
            val flags = ArrayList<String>()
            if (e.editable) flags.add("editable")
            if (e.clickable) flags.add("clickable")
            if (e.longClickable) flags.add("long_pressable")
            if (e.scrollable) flags.add("scrollable")
            if (e.checkable) flags.add("checkable")
            if (e.checked) flags.add("checked")
            if (e.selected) flags.add("selected")
            if (!e.enabled) flags.add("disabled")
            if (e.focused) flags.add("focused")
            if (e.password) flags.add("password")
            if (e.range != null) flags.add("adjustable")
            if (flags.isNotEmpty()) o.put("flags", JSONArray(flags))
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
        val truncated = ordered.size > maxElements
        return JSONObject()
            .put("app", appPackage ?: "unknown")
            .apply { if (!appName.isNullOrBlank()) put("appName", appName) }
            .apply { if (!screenTitle.isNullOrBlank()) put("screen", screenTitle) }
            .put("elementCount", elements.size)
            .put("perception", JSONObject().put("source", perception.source).put("reason", perception.reason))
            .apply { if (secureWindow) put("secureWindow", true) }
            .put("elements", arr)
            .apply {
                if (truncated) put(
                    "note",
                    "${ordered.size - maxElements} more elements exist on this screen but aren't shown here — " +
                        "scroll, or use the app's own search to reach something not listed rather than assuming it's absent."
                )
            }
    }

    fun summarize(): String {
        val visible = elements.mapNotNull { it.label?.takeIf { l -> l.isNotBlank() } }.take(12)
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
