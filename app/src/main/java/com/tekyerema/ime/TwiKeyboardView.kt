package com.tekyerema.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.abs

/**
 * G-05 — the keyboard on screen. Plain Android Views on purpose, not Compose and not a single canvas:
 *  - every key is a real, focusable View with a content description, so TalkBack's explore-by-touch and its typing
 *    modes ("lift to type" / double-tap) work with no custom accessibility plumbing — TalkBack clicks the key under
 *    the finger in an input-method window exactly as it does for Gboard;
 *  - Compose inside an InputMethodService needs hand-wired lifecycle/saved-state owners on the IME window, a known
 *    source of crashes; a keyboard must be the most boring, reliable screen on the phone.
 * Long-press options appear in the strip across the top (not a popup), where TalkBack users can explore them like
 * keys; every option is also a TalkBack custom action on the key itself ("Type ɛ, open e").
 *
 * G-05 (2), 13 October 2026 — the Gboard habits: a swipe across the letters types a word (the view only collects the
 * path and the keys it crossed; [GestureDecoder] and the service decide the word), the top row shows its numbers in
 * small print and a slide up from a key types that number. TalkBack's explore-by-touch turns touches into hover, so
 * with a screen reader on nothing changes: keys are tapped or lifted on as before.
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
        /** A swipe across the letters ended: the keys it crossed, first to last, with the ones the finger paused or turned on marked. */
        fun onGesture(path: List<GestureDecoder.Step>)
    }

    private enum class StripMode { SUGGESTIONS, VARIANTS, MESSAGE, CHOICES }

    private val strip = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val stripContent = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val voiceKey = ImageView(context)
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private var current: KeyboardLayout? = null
    private val keyViews = ArrayList<Pair<Key, View>>()
    private var stripMode = StripMode.SUGGESTIONS
    private var shownSuggestions: List<String> = emptyList()
    private var keyPopup: android.widget.PopupWindow? = null
    private var popupLabel: TextView? = null

    var bigKeys = false
        set(v) { if (field != v) { field = v; current?.let { current = null; setLayout(it) }; sizeStrip() } }

    /** Swipe typing for the field in front: the letters page, not a password or PIN box, the option on. The service sets it. */
    var gesturesEnabled = false

    // The swipe in progress (G-05 (2)).
    private val trail = ArrayList<GestureDecoder.Pt>(256)
    private val trailPath = Path()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = TRAIL
    }
    private var letterBoxes: List<GestureDecoder.KeyBox> = emptyList()
    private var gestureArmed = false     // the touch began on a letter key with swiping on
    private var gesturing = false        // the touch stream belongs to the swipe now
    private var gesturePointer = -1

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(3), dp(4), dp(3), dp(6))
        strip.addView(stripContent, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        voiceKey.setImageResource(com.tekyerema.R.drawable.ic_ime_mic)
        voiceKey.imageTintList = android.content.res.ColorStateList.valueOf(TEXT)   // the icon is drawn white; light keys need ink
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
            VoiceKeyState.IDLE -> voiceLanguageName?.let { "Voice typing in $it. Double-tap and hold to change the language" } ?: "Voice typing"
            VoiceKeyState.LISTENING -> "Stop voice typing"
            VoiceKeyState.OFF -> "Voice typing, off in this box"
        }
        voiceKey.background = keyBackground(if (state == VoiceKeyState.LISTENING) ACCENT else FN_KEY)
        voiceKey.imageTintList = android.content.res.ColorStateList.valueOf(if (state == VoiceKeyState.LISTENING) ON_ACCENT else TEXT)
        voiceKey.imageAlpha = if (state == VoiceKeyState.OFF) 90 else 255
    }

    // ------------------------------------------------------------------------------------------ keys

    /** Show [layout]; when only labels changed (shift, language) the existing views are relabelled in place. */
    fun setLayout(layout: KeyboardLayout) {
        val old = current
        current = layout
        if (old != null && old.shape() == layout.shape() && keyViews.size == layout.rows.sumOf { it.size }) {
            var i = 0
            for ((r, row) in layout.rows.withIndex()) for (key in row) {
                val v = keyViews[i].second
                bind(v, key, hintFor(layout, r, key))
                keyViews[i] = key to v
                i++
            }
            return
        }
        rows.removeAllViews()
        keyViews.clear()
        val height = keyHeight(layout.page)
        for ((r, row) in layout.rows.withIndex()) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL; weightSum = row.sumOf { it.width.toDouble() }.toFloat() }
            for (key in row) {
                val v: View = if (key.kind == KeyKind.SPACER) View(context).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                } else KeyView(context).apply {
                    gravity = Gravity.CENTER
                    setTextColor(TEXT)
                    maxLines = 1
                    isFocusable = true
                    isClickable = true
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                bind(v, key, hintFor(layout, r, key))
                line.addView(v, LayoutParams(0, height, key.width).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                keyViews += key to v
            }
            rows.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** The number a top-row letter gives on a slide up (and on a long-press): drawn small in the key's corner. */
    private fun hintFor(layout: KeyboardLayout, row: Int, key: Key): String? =
        if (layout.page == Page.LETTERS && row == 0 && key.kind == KeyKind.CHAR)
            key.variants.firstOrNull { it.length == 1 && it[0].isDigit() } else null

    @SuppressLint("ClickableViewAccessibility")   // backspace: touches repeat; TalkBack's click deletes once (onClick)
    private fun bind(v: View, key: Key, hint: String?) {
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
        v.setTextColor(if (face == ACCENT) ON_ACCENT else TEXT)
        if (v is KeyView) { v.hint = hint; v.hintSize = sp(if (bigKeys) 13f else 11f); v.hintColor = TEXT_SECONDARY }
        // Custom actions: every long-press option, reachable from TalkBack's actions menu.
        @Suppress("UNCHECKED_CAST")
        (v.getTag(TAG_ACTIONS) as? List<Int>)?.forEach { ViewCompat.removeAccessibilityAction(v, it) }
        val ids = key.variants.map { variant ->
            ViewCompat.addAccessibilityAction(v, "Type ${KeyNames.spoken(variant)}") { _, _ -> listener.onVariant(variant); true }
        }
        v.setTag(TAG_ACTIONS, ids)
        // touch[0]: this touch became a slide up (its number typed); touch[1]: its long-press already fired.
        val touch = BooleanArray(2)
        v.setOnClickListener { listener.onKey(key) }
        v.setOnLongClickListener { touch[1] = true; listener.onKeyLongPress(key) }
        // Only keys that DO something on a long-press say so: otherwise TalkBack would add "double-tap and hold to long
        // press" to every letter, which is noise a blind typist hears hundreds of times a day.
        v.isLongClickable = key.variants.isNotEmpty() ||
            key.kind in setOf(KeyKind.SHIFT, KeyKind.BACKSPACE, KeyKind.SPACE)
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
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touch[0] = false; touch[1] = false
                        listener.onKeyDown(key)
                        if (key.kind == KeyKind.CHAR) showKeyPopup(key, view)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // A slide up off a top-row key types its number — no long-press, no symbols page. (Not after a
                        // long-press that already typed it: a finger that dawdled on the key, then slid, gets one number.)
                        if (!touch[0] && !touch[1] && hint != null && e.y < -view.height * 0.5f && abs(e.x - view.width / 2f) < view.width) {
                            touch[0] = true
                            view.isPressed = false
                            view.cancelLongPress()
                            showKeyPopup(key.copy(text = hint, label = hint), view)
                            listener.onVariant(hint)
                        }
                        touch[0]
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dismissKeyPopup()
                        if (touch[0]) { view.isPressed = false; true } else false
                    }
                    else -> false
                }
            }
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            listener.onBackspaceRepeat()
            keyViews.firstOrNull { it.first.kind == KeyKind.BACKSPACE }?.second?.postDelayed(this, REPEAT_EVERY_MS)
        }
    }

    /** A key: a TextView that can also paint a small hint (the number a top-row key gives) in its top-right corner. */
    @SuppressLint("AppCompatCustomView")
    private class KeyView(context: Context) : TextView(context) {
        var hint: String? = null
            set(v) { if (field != v) { field = v; invalidate() } }
        var hintSize = 0f
        var hintColor = 0
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = hint ?: return
            if (hintSize <= 0f) return
            hintPaint.textSize = hintSize
            hintPaint.color = hintColor
            canvas.drawText(h, width - hintSize * 0.55f, hintSize * 1.2f, hintPaint)
        }
    }

    // --------------------------------------------------------------------------------------- swipe

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturing = false
                gestureArmed = false
                trail.clear()
                if (gesturesEnabled && current?.page == Page.LETTERS) {
                    letterBoxes = letterBoxes()
                    if (letterBoxes.any { it.contains(ev.x, ev.y) }) {
                        gestureArmed = true
                        gesturePointer = ev.getPointerId(0)
                        trail += GestureDecoder.Pt(ev.x, ev.y)
                    }
                }
            }
            // A second finger means two-thumb tapping, not a swipe.
            MotionEvent.ACTION_POINTER_DOWN -> if (!gesturing) { gestureArmed = false; trail.clear() }
            MotionEvent.ACTION_MOVE -> if (gestureArmed && !gesturing) {
                val n = trail.size
                addPoints(ev)
                if (trail.size != n && GestureDecoder.isGesture(trail, letterBoxes, keyUnit() * 0.5f)) {
                    gesturing = true
                    dismissKeyPopup()
                    invalidate()
                    return true   // the key under the finger gets ACTION_CANCEL; the rest of the stream comes to onTouchEvent
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { gestureArmed = false; trail.clear() }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")   // the swipe is a sighted gesture; every key stays a clickable View
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturing) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { addPoints(event); invalidate() }
            MotionEvent.ACTION_UP -> { addPoints(event); finishGesture() }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == gesturePointer) { addPoints(event); finishGesture() }
            MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!gesturing || trail.size < 2) return
        trailPath.rewind()
        trailPath.moveTo(trail[0].x, trail[0].y)
        for (i in 1 until trail.size) trailPath.lineTo(trail[i].x, trail[i].y)
        trailPaint.strokeWidth = dp(5).toFloat()
        canvas.drawPath(trailPath, trailPaint)
    }

    private fun addPoints(e: MotionEvent) {
        val idx = e.findPointerIndex(gesturePointer)
        if (idx < 0) return
        for (h in 0 until e.historySize) push(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
        push(e.getX(idx), e.getY(idx))
    }

    /** A sample that did not move (a held finger, or a flood of identical injected events) adds nothing to the path. */
    private fun push(x: Float, y: Float) {
        val last = trail.lastOrNull()
        if (last != null && abs(last.x - x) < 1f && abs(last.y - y) < 1f) return
        trail += GestureDecoder.Pt(x, y)
    }

    private fun finishGesture() {
        val path = GestureDecoder.steps(trail, letterBoxes)
        endGesture()
        if (path.size >= 2) listener.onGesture(path)
    }

    private fun endGesture() {
        gesturing = false
        gestureArmed = false
        trail.clear()
        invalidate()
    }

    /** The letter keys' boxes in this view's coordinates, grown by their margins so the gaps between keys belong to a key. */
    private fun letterBoxes(): List<GestureDecoder.KeyBox> {
        val m = dp(3).toFloat()
        val out = ArrayList<GestureDecoder.KeyBox>(32)
        for ((key, v) in keyViews) {
            if (key.kind != KeyKind.CHAR || key.text.length != 1 || !key.text[0].isLetter()) continue
            val line = v.parent as? View ?: continue
            val l = (v.left + line.left + rows.left).toFloat()
            val t = (v.top + line.top + rows.top).toFloat()
            out += GestureDecoder.KeyBox(key.text, l - m, t - m, l + v.width + m, t + v.height + m)
        }
        return out
    }

    private fun keyUnit(): Float =
        keyViews.firstOrNull { it.first.kind == KeyKind.CHAR && it.first.width == 1f }?.second?.width?.toFloat() ?: dp(36).toFloat()

    // --------------------------------------------------------------------------------------- popup

    private fun showKeyPopup(key: Key, anchor: View) {
        if (key.kind != KeyKind.CHAR || key.text.length > 2) return
        val tv = popupLabel ?: TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (bigKeys) 38f else 32f)
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(KEY) }
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }.also { popupLabel = it }
        tv.text = key.label
        val pw = keyPopup ?: android.widget.PopupWindow(tv,
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            isClippingEnabled = false; isTouchable = false
        }.also { keyPopup = it }
        pw.contentView = tv
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width / 2 - tv.measuredWidth / 2
        val y = loc[1] - tv.measuredHeight - dp(6)
        try {
            if (pw.isShowing) pw.update(x, y, -1, -1)
            else pw.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
        } catch (_: Throwable) {}
    }

    private fun dismissKeyPopup() {
        try { keyPopup?.dismiss() } catch (_: Throwable) {}
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
        dismissKeyPopup()
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

    /** A short list of choices in the strip ([label] shown, [spoken] for TalkBack): the voice language list, "Twi or English?".
     *  [onPick] gets the index tapped; any other key replaces the strip as usual. */
    fun showChoices(options: List<Pair<String, String>>, onPick: (Int) -> Unit) {
        stripMode = StripMode.CHOICES
        shownSuggestions = emptyList()
        stripContent.removeAllViews()
        options.forEachIndexed { i, (label, spoken) ->
            stripContent.addView(stripButton(label, spoken) { onPick(i) },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
    }

    /** The voice key says which language it listens in ("Voice typing in Twi"). */
    var voiceLanguageName: String? = null

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
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        /** The colours (KeyboardPalette: light, dark or a reading-colour pair, as the app is set), chosen by the service
         *  before it makes the view. */
        @Volatile var palette: KeyboardPalette = KeyboardPalette.DARK
        private val BG get() = palette.bg
        private val KEY get() = palette.key
        private val FN_KEY get() = palette.fnKey
        private val STRIP_KEY get() = palette.stripKey
        private val PRESSED get() = palette.pressed
        private val ACCENT get() = palette.accent
        private val ON_ACCENT get() = palette.onAccent
        private val TRAIL get() = palette.trail
        private val TEXT get() = palette.text
        private val TEXT_SECONDARY get() = palette.textSecondary
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_EVERY_MS = 60L
        private val TAG_ACTIONS = com.tekyerema.R.id.ime_key_actions
        /** Stand-in keys for feedback on the voice key and strip buttons. */
        private val VOICE = Key(KeyKind.CHAR, label = "voice", spoken = "")
        private val STRIP = Key(KeyKind.CHAR, label = "strip", spoken = "")
    }
}
