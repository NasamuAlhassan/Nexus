package com.tekyerema.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * N-03 — the screen as the cloud planner reads it: one line per element instead of the compact JSON
 * ([com.tekyerema.accessibility.UiSnapshot.toCompactJson]), which stays the structured form every other reader uses
 * (LocalBrain, trajectories, tests). Same content, far fewer tokens: the JSON spent ~20 tokens of keys, quotes and
 * braces per element (`{"role":"TextView","id":6,"label":"…","actions":["tap"]}`) where a line needs ~4
 * (`[6] text "…" tap`), and up to 45 elements go out on every step under Groq's per-minute token cap.
 *
 *     app: com.android.settings ("Settings") · screen: "Settings"
 *     [1] button "Search" tap
 *     [3] list scroll (list 22 rows x 1 cols)
 *     [10] text "Connections" tap
 *     [42] switch "Bluetooth" tap long_press unchecked
 *
 * Everything the screen supplies (labels, titles, announcements, even the app's display name) is quoted with JSON
 * string escaping, so no screen text can start a line of its own — it can't pose as another element or a prompt
 * section. What the listing drops: the snapshot id and element count (no tool takes them; a cut list says how many
 * more there are), the routine "tree" perception source, and container class names that say nothing
 * (LinearLayout, FrameLayout…). `llm.screen.format` = "json" sends the JSON as before.
 */
object ScreenListing {

    fun enabled(): Boolean = com.tekyerema.core.config.AppConfig.string("llm.screen.format", "lines") != "json"

    /** The prompt text for a compact screen JSON: the lines below, or the JSON itself when the format is "json". */
    fun forPrompt(screen: JSONObject): String = if (enabled()) render(screen) else screen.toString()

    /** Keys [render] knows; anything else a producer adds is still shown, generically, so nothing is silently lost. */
    private val KNOWN = setOf("app", "appName", "screen", "snap", "focus", "elementCount", "perception", "secureWindow",
        "window", "announced", "longScreen", "elements", "note")

    fun render(screen: JSONObject): String = buildString {
        append("app: ").append(clean(screen.optString("app", "unknown")))
        screen.optString("appName").takeIf { it.isNotBlank() }?.let { append(" (").append(quote(it)).append(')') }
        screen.optString("screen").takeIf { it.isNotBlank() }?.let { append(" · screen: ").append(quote(it)) }
        if (screen.has("focus")) append(" · focus: [").append(screen.optInt("focus")).append(']')
        screen.optString("window").takeIf { it.isNotBlank() }?.let { append(" · window: ").append(clean(it)) }
        if (screen.optBoolean("secureWindow")) append(" · secure window (screenshots blocked)")
        screen.optJSONObject("perception")?.let { p ->
            val source = p.optString("source"); val reason = p.optString("reason")
            // The routine case ("tree", no reason) says nothing; a vision look or a failed one does.
            if (source != "tree" || reason.isNotBlank())
                append(" · perception: ").append(clean(source)).append(if (reason.isNotBlank()) " (${clean(reason)})" else "")
        }
        for (k in screen.keys().asSequence().filter { it !in KNOWN }.sorted().toList())
            append(" · ").append(clean(k)).append(": ").append(clean(screen.opt(k).toString()))
        screen.optJSONArray("announced")?.takeIf { it.length() > 0 }?.let { a ->
            append("\nannounced: ").append((0 until a.length()).joinToString(", ") { quote(a.optString(it)) })
        }
        screen.optString("longScreen").takeIf { it.isNotBlank() }?.let { append("\n(").append(clean(it)).append(')') }
        val els = screen.optJSONArray("elements")
        if (els == null || els.length() == 0) append("\n(no elements)")
        else for (i in 0 until els.length()) els.optJSONObject(i)?.let { append('\n').append(line(it)) }
        screen.optString("note").takeIf { it.isNotBlank() }?.let { append("\n(").append(clean(it)).append(')') }
    }

    /** `[id] kind "label" actions state (structure) …` for one element of the compact JSON. */
    internal fun line(o: JSONObject): String = buildString {
        append('[').append(o.opt("id")).append(']')
        kind(o.optString("role"))?.let { append(' ').append(it) }
        o.optString("label").takeIf { it.isNotEmpty() }?.let { append(' ').append(quote(it)) }
        strings(o.optJSONArray("actions")).forEach { append(' ').append(clean(it)) }
        val flags = strings(o.optJSONArray("flags"))
        // A switch's state in the claim vocabulary (`checked: X` / `unchecked: X`), one word instead of two.
        if ("checkable" in flags) append(if ("checked" in flags) " checked" else " unchecked")
        else if ("checked" in flags) append(" checked")
        flags.filter { it != "checkable" && it != "checked" }.forEach { append(' ').append(clean(it)) }
        o.optJSONObject("range")?.let { r ->
            append(" range ").append(num(r.optDouble("min"))).append("..").append(num(r.optDouble("max")))
                .append(" now ").append(num(r.optDouble("current")))
        }
        o.optString("structure").takeIf { it.isNotBlank() }?.let { append(" (").append(clean(it)).append(')') }
        strings(o.optJSONArray("custom_actions")).takeIf { it.isNotEmpty() }?.let { c ->
            append(" custom_actions: ").append(c.joinToString(", ") { quote(it) })
        }
        if (o.optString("source") == "vision") {
            append(" source=vision")
            o.optJSONArray("bounds")?.let { b -> append(" bounds=[").append(strings(b).joinToString(",")).append(']') }
        } else if (o.has("source")) append(" source=").append(clean(o.optString("source")))
    }

    /**
     * A short kind for an Android widget class, or null for a layout container that says nothing a line's actions
     * don't (a tappable LinearLayout is just "tap"). Classes not listed keep their own name (Chip, NumberPicker…).
     */
    internal fun kind(role: String): String? {
        if (role.isBlank()) return null
        val simple = role.substringAfterLast('.').substringAfterLast('$')
        return KINDS[simple] ?: if (simple in CONTAINERS) null else simple
    }

    private val CONTAINERS = setOf("View", "ViewGroup", "LinearLayout", "LinearLayoutCompat", "FrameLayout", "RelativeLayout",
        "ConstraintLayout", "CoordinatorLayout", "CardView", "MaterialCardView", "TableLayout", "TableRow", "GridLayout",
        "AppBarLayout", "CollapsingToolbarLayout", "DrawerLayout", "ComposeView", "AndroidComposeView")

    private val KINDS: Map<String, String> = buildMap {
        for (c in listOf("TextView", "AppCompatTextView", "MaterialTextView", "CheckedTextView")) put(c, "text")
        for (c in listOf("ImageView", "AppCompatImageView", "ShapeableImageView")) put(c, "image")
        for (c in listOf("Button", "AppCompatButton", "MaterialButton", "ImageButton", "AppCompatImageButton",
            "FloatingActionButton", "ExtendedFloatingActionButton")) put(c, "button")
        for (c in listOf("EditText", "AppCompatEditText", "TextInputEditText", "AutoCompleteTextView",
            "AppCompatAutoCompleteTextView", "MultiAutoCompleteTextView", "SearchAutoComplete")) put(c, "field")
        for (c in listOf("Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch")) put(c, "switch")
        for (c in listOf("CheckBox", "AppCompatCheckBox", "MaterialCheckBox")) put(c, "checkbox")
        for (c in listOf("RadioButton", "AppCompatRadioButton", "MaterialRadioButton")) put(c, "radio")
        for (c in listOf("SeekBar", "AppCompatSeekBar", "Slider", "RangeSlider", "RatingBar")) put(c, "slider")
        for (c in listOf("RecyclerView", "ListView", "GridView", "ExpandableListView", "ScrollView", "NestedScrollView",
            "HorizontalScrollView", "ViewPager", "ViewPager2")) put(c, "list")
        for (c in listOf("Spinner", "AppCompatSpinner")) put(c, "dropdown")
        put("ToggleButton", "toggle"); put("ProgressBar", "progress"); put("WebView", "web"); put("TabView", "tab")
        put("Toolbar", "toolbar"); put("ocr_text", "text")
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    /** 128.0 → "128", 0.5 → "0.5". */
    private fun num(d: Double): String = if (d.isNaN()) "?" else if (d == Math.floor(d) && Math.abs(d) < 1e9) d.toLong().toString() else d.toString()

    /** Screen text as a JSON string literal: quotes, backslashes and control/line-break characters escaped, everything
     *  else (Twi letters, ₵, emoji) left as it is — the same escaping Android's org.json applies. */
    internal fun quote(s: String): String = "\"" + escape(s) + "\""

    /** Our own text (keys, reasons) on one line: the same escaping, without quotes. */
    private fun clean(s: String): String = escape(s)

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == '\u007f' || c == ' ' || c == ' ' || c == '\u0085' ->
                sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
