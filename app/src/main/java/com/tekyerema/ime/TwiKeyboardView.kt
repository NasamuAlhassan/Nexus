package com.tekyerema.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * G-05 — the keyboard on screen. Plain Android Views on purpose, not Compose and not a single canvas:
 *  - every key is a real, focusable View with a content description, so TalkBack's explore-by-touch and its typing
 *    modes ("lift to type" / double-tap) work with no custom accessibility plumbing — TalkBack clicks the key under
 *    the finger in an input-method window exactly as it does for Gboard;
 *  - Compose inside an InputMethodService needs hand-wired lifecycle/saved-state owners on the IME window, a known
 *    source of crashes; a keyboard must be the most boring, reliable screen on the phone.
 * Long-press options appear in the strip across the top (not a popup), where TalkBack users can explore them like
 * keys; every option is also a TalkBack custom action on the key itself ("Type ɛ, open e").
 * The view never keeps, logs or reads typed text — it only reports which key was touched.
 */
@SuppressLint("ViewConstructor")
class TwiKeyboardView(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onKeyDown(key: Key)                 // feedback only (vibration / click sound)
        fun onKey(key: Key)
        fun onKeyLongPress(key: Key): Boolean
        fun onBackspaceRepeat()
        fun onSuggestion(word: String)
        fun onVariant(text: String)
        fun onCloseVariants()
        fun onVoiceKey()
        fun onVoiceLongPress(): Boolean
    }

    private enum class StripMode { SUGGESTIONS, VARIANTS, MESSAGE }

    private val strip = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val stripContent = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val voiceKey = ImageView(context)
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private var current: KeyboardLayout? = null
    private val keyViews = ArrayList<Pair<Key, View>>()
    private var stripMode = StripMode.SUGGESTIONS
    private var shownSuggestions: List<String> = emptyList()

    var bigKeys = false
        set(v) { if (field != v) { field = v; current?.let { current = null; setLayout(it) }; sizeStrip() } }

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(3), dp(4), dp(3), dp(6))
        strip.addView(stripContent, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        voiceKey.setImageResource(com.tekyerema.R.drawable.ic_ime_mic)
        voiceKey.scaleType = ImageView.ScaleType.CENTER
        voiceKey.background = keyBackground(FN_KEY)
        voiceKey.isFocusable = true
        voiceKey.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) listener.onKeyDown(VOICE); false }
        voiceKey.setOnClickListener { listener.onVoiceKey() }
        voiceKey.setOnLongClickListener { listener.onVoiceLongPress() }
        strip.addView(voiceKey)
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setVoiceState(VoiceKeyState.IDLE)
        sizeStrip()
        showSuggestions(emptyList())
    }

    enum class VoiceKeyState { IDLE, LISTENING, OFF }

    fun setVoiceState(state: VoiceKeyState) {
        voiceKey.contentDescription = when (state) {
            VoiceKeyState.IDLE -> "Voice typing"
            VoiceKeyState.LISTENING -> "Stop voice typing"
            VoiceKeyState.OFF -> "Voice typing, off in this box"
        }
        voiceKey.background = keyBackground(if (state == VoiceKeyState.LISTENING) ACCENT else FN_KEY)
        voiceKey.imageAlpha = if (state == VoiceKeyState.OFF) 90 else 255
    }

    // ------------------------------------------------------------------------------------------ keys

    /** Show [layout]; when only labels changed (shift, language) the existing views are relabelled in place. */
    fun setLayout(layout: KeyboardLayout) {
        val old = current
        current = layout
        if (old != null && old.shape() == layout.shape() && keyViews.size == layout.rows.sumOf { it.size }) {
            var i = 0
            for (row in layout.rows) for (key in row) { val v = keyViews[i].second; bind(v, key); keyViews[i] = key to v; i++ }
            return
        }
        rows.removeAllViews()
        keyViews.clear()
        val height = keyHeight(layout.page)
        for (row in layout.rows) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL; weightSum = row.sumOf { it.width.toDouble() }.toFloat() }
            for (key in row) {
                val v: View = if (key.kind == KeyKind.SPACER) View(context).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                } else TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextColor(TEXT)
                    maxLines = 1
                    isFocusable = true
                    isClickable = true
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                bind(v, key)
                line.addView(v, LayoutParams(0, height, key.width).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                keyViews += key to v
            }
            rows.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    @SuppressLint("ClickableViewAccessibility")   // backspace: touches repeat; TalkBack's click deletes once (onClick)
    private fun bind(v: View, key: Key) {
        if (key.kind == KeyKind.SPACER || v !is TextView) return
        v.text = key.label
        val letterLike = key.label.length <= 2 && key.kind == KeyKind.CHAR
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (letterLike) (if (bigKeys) 30f else 24f) else (if (bigKeys) 19f else 15f))
        v.typeface = if (letterLike) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        v.contentDescription = key.spoken
        val face = when {
            key.kind == KeyKind.ENTER && key.label != "⏎" -> ACCENT
            key.kind == KeyKind.SHIFT && key.label == "⇪" -> ACCENT
            key.kind == KeyKind.CHAR || key.kind == KeyKind.SPACE -> KEY
            else -> FN_KEY
        }
        v.background = keyBackground(face)
        v.setTextColor(if (face == ACCENT) BG else TEXT)
        // Custom actions: every long-press option, reachable from TalkBack's actions menu.
        @Suppress("UNCHECKED_CAST")
        (v.getTag(TAG_ACTIONS) as? List<Int>)?.forEach { ViewCompat.removeAccessibilityAction(v, it) }
        val ids = key.variants.map { variant ->
            ViewCompat.addAccessibilityAction(v, "Type ${KeyNames.spoken(variant)}") { _, _ -> listener.onVariant(variant); true }
        }
        v.setTag(TAG_ACTIONS, ids)
        v.setOnClickListener { listener.onKey(key) }
        v.setOnLongClickListener { listener.onKeyLongPress(key) }
        if (key.kind == KeyKind.BACKSPACE) {
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        listener.onKeyDown(key); listener.onKey(key)
                        view.removeCallbacks(repeat); view.postDelayed(repeat, REPEAT_START_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; view.removeCallbacks(repeat) }
                }
                true
            }
        } else {
            v.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) listener.onKeyDown(key); false }
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            listener.onBackspaceRepeat()
            keyViews.firstOrNull { it.first.kind == KeyKind.BACKSPACE }?.second?.postDelayed(this, REPEAT_EVERY_MS)
        }
    }

    // ----------------------------------------------------------------------------------------- strip

    fun showSuggestions(words: List<String>) {
        if (stripMode == StripMode.SUGGESTIONS && words == shownSuggestions && stripContent.childCount > 0) return
        stripMode = StripMode.SUGGESTIONS
        shownSuggestions = words
        stripContent.removeAllViews()
        if (words.isEmpty()) {
            // An empty strip still holds its height, so the keys never jump under a finger.
            stripContent.addView(View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            return
        }
        for (w in words) stripContent.addView(stripButton(w, "Suggestion: $w") { listener.onSuggestion(w) },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }

    fun showVariants(variants: List<String>) {
        stripMode = StripMode.VARIANTS
        stripContent.removeAllViews()
        for (t in variants) stripContent.addView(stripButton(t, KeyNames.spoken(t), big = true) { listener.onVariant(t) },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        stripContent.addView(stripButton("✕", "Close options") { listener.onCloseVariants() },
            LayoutParams(dp(48), LayoutParams.MATCH_PARENT).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }

    fun showMessage(text: String) {
        stripMode = StripMode.MESSAGE
        shownSuggestions = emptyList()
        stripContent.removeAllViews()
        stripContent.addView(TextView(context).apply {
            this.text = text
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (bigKeys) 17f else 14f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(dp(12), 0, dp(8), 0)
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    /** TalkBack speaks [text] (no-op without a screen reader). */
    fun say(text: String) { if (text.isNotBlank()) announceForAccessibility(text) }

    private fun stripButton(label: String, spoken: String, big: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = spoken
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) (if (bigKeys) 28f else 22f) else (if (bigKeys) 19f else 16f))
        background = keyBackground(STRIP_KEY)
        isFocusable = true
        setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) listener.onKeyDown(STRIP); false }
        setOnClickListener { onClick() }
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
    }

    private fun sizeStrip() {
        val h = dp(if (bigKeys) 60 else 48)
        strip.layoutParams = (strip.layoutParams as? LayoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, h)).apply { height = h }
        voiceKey.layoutParams = LayoutParams(dp(if (bigKeys) 72 else 60), LayoutParams.MATCH_PARENT).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
    }

    private fun keyHeight(page: Page) = dp(when {
        page == Page.PAD -> if (bigKeys) 78 else 62
        else -> if (bigKeys) 68 else 54
    })

    private fun keyBackground(color: Int): StateListDrawable {
        fun shape(c: Int) = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(c) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(PRESSED))
            addState(intArrayOf(), shape(color))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        // The app's palette (ui/theme Tk): ink surfaces, near-white text, the brand blue for a pressed key and cyan
        // for "on" (caps lock, listening, an action enter key). Text on keys is 14:1+ against the key face.
        private const val BG = 0xFF070A15.toInt()
        private const val KEY = 0xFF1C2440.toInt()
        private const val FN_KEY = 0xFF121A30.toInt()
        private const val STRIP_KEY = 0xFF0F1628.toInt()
        private const val PRESSED = 0xFF1B49C8.toInt()
        private const val ACCENT = 0xFF38BDF8.toInt()
        private const val TEXT = 0xFFF4F6FB.toInt()
        private const val TEXT_SECONDARY = 0xFF9AA6C4.toInt()
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_EVERY_MS = 60L
        private val TAG_ACTIONS = com.tekyerema.R.id.ime_key_actions
        /** Stand-in keys for feedback on the voice key and strip buttons. */
        private val VOICE = Key(KeyKind.CHAR, label = "voice", spoken = "")
        private val STRIP = Key(KeyKind.CHAR, label = "strip", spoken = "")
    }
}
