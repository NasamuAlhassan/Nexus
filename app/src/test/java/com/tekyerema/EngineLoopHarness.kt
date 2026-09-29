package com.tekyerema

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.core.config.AppConfig
import org.json.JSONObject
import java.io.File

/**
 * WS-A engine-loop harness: the pieces a JVM test needs to drive the REAL AgentEngine.runTask (through
 * AgentEngine.installForTest / runForTest) — an app context with a private dir and in-memory preferences, a fake
 * accessibility controller that serves scripted screens and records every action, and a scripted brain.
 */

/** A Context with an app-private files dir and in-memory SharedPreferences; everything else is the stub default. */
class FakeContext(private val dir: File) : ContextWrapper(null) {
    private val prefs = HashMap<String, FakePrefs>()
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = dir
    override fun getPackageName(): String = "com.tekyerema"
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.getOrPut(name ?: "") { FakePrefs() }
    override fun getSystemService(name: String): Any? = null
}

class FakePrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Ed()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Ed : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false
        override fun putString(k: String?, v: String?) = apply { puts[k!!] = v }
        override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { puts[k!!] = v }
        override fun putInt(k: String?, v: Int) = apply { puts[k!!] = v }
        override fun putLong(k: String?, v: Long) = apply { puts[k!!] = v }
        override fun putFloat(k: String?, v: Float) = apply { puts[k!!] = v }
        override fun putBoolean(k: String?, v: Boolean) = apply { puts[k!!] = v }
        override fun remove(k: String?) = apply { removes.add(k!!) }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            removes.forEach { map.remove(it) }
            puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}

/** Serves [screen] (changed by the test, or by [onClick]/[onSetText] as the "app" reacts) and records every action. */
class FakeController(@Volatile var screen: UiSnapshot) : AccessibilityController {
    val actions = ArrayList<String>()
    var snapshots = 0
    var onClick: (UiElement) -> Unit = {}
    var onSetText: (UiElement, String, Boolean) -> Unit = { _, _, _ -> }

    override val ready: Boolean = true
    override suspend fun buildSnapshot(): UiSnapshot { snapshots++; return screen }
    override suspend fun captureScreenshot(): Bitmap? = null
    override suspend fun clickElement(id: Int): Boolean {
        val e = screen.element(id) ?: return false
        actions.add("click:${e.label}"); onClick(e); return true
    }
    override suspend fun longClickElement(id: Int): Boolean = false
    override suspend fun setText(id: Int, text: String, submit: Boolean): Boolean {
        val e = screen.element(id) ?: return false
        actions.add("type:$text" + if (submit) "+submit" else ""); onSetText(e, text, submit); return true
    }
    override suspend fun scrollElement(id: Int, direction: String): Boolean = false
    override suspend fun focusElement(id: Int): Boolean = true
    override suspend fun setRangeValue(id: Int, value: Float): Boolean = false
    override fun boundsOf(id: Int): Box? = screen.element(id)?.bounds
    override fun lastScreenshotFailure(): String? = "no screenshots in tests"
    override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean { actions.add("tap@$x,$y"); return true }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
    override suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long): Boolean = true
    override suspend fun pinch(cx: Int, cy: Int, startGap: Int, endGap: Int, durationMs: Long): Boolean = true
    override fun globalAction(action: String): Boolean { actions.add("global:$action"); return true }
    override fun adjustVolume(direction: String): Boolean = true
    override fun foregroundPackage(): String? = screen.appPackage
    override fun priorForegroundPackage(): String? = null
    override fun screenSize(): Pair<Int, Int> = 1080 to 2280
    override suspend fun waitForSettle(timeoutMs: Long) {}

    fun clicks(label: String) = actions.count { it == "click:$label" }
}

/** Each call to [decide] takes the next step of the script (a function of the context it was given); records contexts. */
class ScriptedBrain(private vararg val steps: suspend (BrainContext) -> AgentDecision) : AgentBrain {
    val seen = ArrayList<BrainContext>()
    override suspend fun decide(ctx: BrainContext): AgentDecision {
        seen.add(ctx)
        val i = seen.size - 1
        return if (i < steps.size) steps[i](ctx) else AgentDecision.Done("script ended")
    }
}

fun tool(name: String, args: JSONObject, expects: String? = null) = AgentDecision.CallTool(name, args, null, expects)

/** Screens used by the loop tests: a WhatsApp-like chat with Ama, and its chat list. */
object Screens {
    const val APP = "com.whatsapp"
    const val SEND_ID = 20
    const val COMPOSER_ID = 19
    const val ROW_ID = 5

    private fun el(id: Int, role: String, label: String?, b: Box, clickable: Boolean = false, editable: Boolean = false,
                   focused: Boolean = false, hint: String? = null, password: Boolean = false) =
        UiElement(id, role, label, b, clickable = clickable, editable = editable, focused = focused, hint = hint, password = password)

    /** Ama's chat: a title, a few controls, the bubbles, the composer (holding [composer] or empty) and Send. */
    fun chat(composer: String?, bubbles: List<String> = emptyList(), title: String = "Ama"): UiSnapshot {
        val els = arrayListOf(
            el(0, "android.widget.ImageButton", "Navigate up", Box(0, 60, 100, 160), clickable = true),
            el(1, "android.widget.TextView", title, Box(120, 60, 600, 160)),
            el(2, "android.widget.ImageButton", "More options", Box(980, 60, 1080, 160), clickable = true),
            el(3, "android.widget.ImageButton", "Emoji", Box(0, 2100, 100, 2200), clickable = true),
            el(4, "android.widget.ImageButton", "Attach", Box(700, 2100, 800, 2200), clickable = true),
            el(6, "android.widget.ImageButton", "Camera", Box(800, 2100, 900, 2200), clickable = true)
        )
        bubbles.forEachIndexed { i, b -> els.add(el(10 + i, "android.widget.TextView", b, Box(300, 400 + 120 * i, 1000, 500 + 120 * i))) }
        els.add(el(COMPOSER_ID, "android.widget.EditText", composer ?: "Message", Box(100, 2100, 700, 2200),
            clickable = true, editable = true, focused = true, hint = "Message"))
        els.add(el(SEND_ID, "android.widget.ImageButton", "Send", Box(900, 2100, 1080, 2200), clickable = true))
        return UiSnapshot(APP, title, els, 1L, appName = "WhatsApp")
    }

    /** The chat list: Ama's row opens her chat. */
    fun chatList(): UiSnapshot = UiSnapshot(APP, "WhatsApp", listOf(
        el(0, "android.widget.TextView", "WhatsApp", Box(0, 60, 400, 160)),
        el(1, "android.widget.ImageButton", "Search", Box(880, 60, 980, 160), clickable = true),
        el(2, "android.widget.ImageButton", "More options", Box(980, 60, 1080, 160), clickable = true),
        el(3, "android.widget.TextView", "Chats", Box(0, 200, 360, 280), clickable = true),
        el(4, "android.widget.TextView", "Status", Box(360, 200, 720, 280), clickable = true),
        el(ROW_ID, "android.widget.TextView", "Ama", Box(0, 300, 1080, 420), clickable = true),
        el(7, "android.widget.TextView", "Kofi", Box(0, 420, 1080, 540), clickable = true),
        el(8, "android.widget.ImageButton", "New chat", Box(900, 2000, 1060, 2160), clickable = true)
    ), 1L, appName = "WhatsApp")

    /** A sign-in page with a password field — a credential screen (Secure Input). */
    fun signIn(): UiSnapshot = UiSnapshot("com.bank.app", "Sign in", listOf(
        el(0, "android.widget.TextView", "Sign in to your bank", Box(0, 100, 1080, 200)),
        el(1, "android.widget.EditText", "Username", Box(0, 300, 1080, 400), clickable = true, editable = true),
        el(2, "android.widget.EditText", null, Box(0, 450, 1080, 550), clickable = true, editable = true, password = true),
        el(3, "android.widget.Button", "Continue", Box(0, 600, 1080, 700), clickable = true),
        el(4, "android.widget.Button", "Forgot?", Box(0, 750, 1080, 850), clickable = true),
        el(5, "android.widget.Button", "Help", Box(0, 900, 1080, 1000), clickable = true),
        el(6, "android.widget.Button", "Register", Box(0, 1050, 1080, 1150), clickable = true)
    ), 1L, appName = "Bank")

    /** WhatsApp's "Couldn't connect" dialog — nothing on it says whether a message went. */
    fun connectionDialog(): UiSnapshot = UiSnapshot(APP, null, listOf(
        el(0, "android.widget.TextView", "Couldn't connect. Try again later.", Box(100, 900, 980, 1000)),
        el(1, "android.widget.Button", "OK", Box(700, 1050, 980, 1150), clickable = true),
        el(2, "android.widget.Button", "Retry", Box(400, 1050, 680, 1150), clickable = true),
        el(3, "android.widget.Button", "Help", Box(100, 1050, 380, 1150), clickable = true),
        el(4, "android.widget.Button", "Settings", Box(100, 1200, 380, 1300), clickable = true),
        el(5, "android.widget.Button", "Close", Box(400, 1200, 680, 1300), clickable = true),
        el(6, "android.widget.Button", "Report", Box(700, 1200, 980, 1300), clickable = true)
    ), 1L, appName = "WhatsApp")
}

/** The real config files (tool manifest, policy tiers, flags), as the app loads them. */
fun loadAppConfig() {
    val dir = listOf(File("src/main/assets/config"), File("app/src/main/assets/config")).first { it.isDirectory }
    AppConfig.loadFrom(dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { "config/${it.name}" to it.readText() })
}
