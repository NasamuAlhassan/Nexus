package com.tekyerema.teach

import org.json.JSONArray
import org.json.JSONObject

/**
 * Teach mode ("show me once", MASTER_STATUS #24) — the data a demonstration is turned into.
 *
 * A step is described SEMANTICALLY: which app, which screen, which element (its label, resource id and role). There are
 * no coordinates anywhere, so a taught skill survives a different screen size, a font change or a re-ordered list, and
 * replay finds the element again by name — or stops and says it couldn't (never taps blindly).
 */
enum class StepKind {
    OPEN_APP, CLICK, LONG_CLICK, TYPE, SCROLL,
    /** The user pressed Back (replayed through the key_event tool, "back"). [TeachStep.pkg] = the app it was pressed in. */
    BACK,
    /** The user pressed Home (replayed as "go to the home screen"). */
    HOME;

    /** A system key: no element to find, so replay does not match the screen against a label. */
    val isKey: Boolean get() = this == BACK || this == HOME
}

data class TeachStep(
    val kind: StepKind,
    /** The app the step happens in (package id — what replay checks the screen against). */
    val pkg: String,
    /** What a person calls [pkg] ("MyMTN"), for the read-back and for opening it. */
    val appName: String? = null,
    /** The window title the step was done on — a hint for the read-back, not a hard match. */
    val screenTitle: String? = null,
    /** CLICK / LONG_CLICK: the element's visible text or description. TYPE: the box's name (never its contents). */
    val label: String? = null,
    /** The element's resource id (`com.app:id/data_tile`) — the most durable selector when the app sets one. */
    val viewId: String? = null,
    /** The element's class, simple name ("Button", "EditText"). */
    val role: String? = null,
    /** TYPE: the box's placeholder hint ("Search"). */
    val hint: String? = null,
    /** SCROLL: "up" | "down" | "left" | "right" (the direction the content is revealed). */
    val direction: String? = null,
    /** TYPE: what was typed — kept ONLY when the user said it isn't private ([TeachSession]); otherwise null. */
    val text: String? = null,
    /** TYPE: how many characters were typed (the text itself is not kept without the user's word). */
    val textLength: Int = 0,
    /** TYPE: the field was submitted (the screen moved on straight after typing, with no button tap). */
    val submit: Boolean = false,
    /** SCROLL: only there to bring the next element into view — skipped when that element is already visible. */
    val optional: Boolean = false,
    /** The step sends, pays, saves or consents (policy would ask): replay hands it to the agent, which reads it back. */
    val sensitive: Boolean = false
) {
    /** True when replay has nothing to find this element by. */
    val unnamed: Boolean get() = (kind == StepKind.CLICK || kind == StepKind.LONG_CLICK) && label.isNullOrBlank() && viewId.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().put("k", kind.name).put("pkg", pkg).apply {
        appName?.let { put("app", it) }
        screenTitle?.let { put("screen", it) }
        label?.let { put("label", it) }
        viewId?.let { put("id", it) }
        role?.let { put("role", it) }
        hint?.let { put("hint", it) }
        direction?.let { put("dir", it) }
        text?.let { put("text", it) }
        if (textLength > 0) put("len", textLength)
        if (submit) put("submit", true)
        if (optional) put("opt", true)
        if (sensitive) put("sens", true)
    }

    companion object {
        fun fromJson(o: JSONObject): TeachStep? {
            val kind = runCatching { StepKind.valueOf(o.optString("k")) }.getOrNull() ?: return null
            val pkg = o.optString("pkg").takeIf { it.isNotBlank() } ?: return null
            fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).takeIf { it.isNotEmpty() } else null
            return TeachStep(kind, pkg, s("app"), s("screen"), s("label"), s("id"), s("role"), s("hint"), s("dir"), s("text"),
                o.optInt("len", 0), o.optBoolean("submit", false), o.optBoolean("opt", false), o.optBoolean("sens", false))
        }
    }
}

/** A saved skill: a name the user says, and the steps to replay. */
data class TeachSkill(
    val name: String,
    val steps: List<TeachStep>,
    val createdAt: Long = 0L,
    /** Recording stopped at a private screen (PIN, password, code): replay ends there and tells the user to take over. */
    val endsAtPrivate: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("at", createdAt).put("private", endsAtPrivate)
        .put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): TeachSkill? {
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            val arr = o.optJSONArray("steps") ?: return null
            val steps = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(TeachStep::fromJson) }
            if (steps.isEmpty()) return null
            return TeachSkill(name, steps, o.optLong("at", 0L), o.optBoolean("private", false))
        }
    }
}
