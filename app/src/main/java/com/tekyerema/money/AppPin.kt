package com.tekyerema.money

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiSnapshot

/**
 * The PIN entered into an APP's own keypad (owner, 28 Oct 2026, testing MyMTN: "I couldn't enter the PIN in the app"). The USSD pad
 * types into the network dialog's box; an app like MyMTN draws its own 0–9 buttons and has no box to type into. So, while the
 * agent waits at an app's PIN screen (AgentEngine.awaitSecureInput), the same [PinPad] — volume keys, taps, and drawn digits —
 * presses the app's own digit buttons one by one, its delete button to take one back, and its confirm button (if it has one)
 * after the last digit. No full-screen layer: the touch pad covers only the keypad's area ([area]).
 *
 * The digits are never logged, spoken or stored (only the counter's char array in [PinPad], wiped at the end). Only the buttons'
 * own labels ("1", "2", "Delete") are read to find them.
 */
object AppPin {
    /** The app's own 0–9 buttons ([digits]), or — when it shows a PIN BOX with the phone's keyboard instead — that box ([field]). */
    data class Keypad(val digits: Map<Char, Int>, val delete: Int?, val confirm: Int?, val area: Box, val field: Int? = null,
                      /** A row of one-digit boxes, left to right: one digit each. */
                      val cells: List<Int> = emptyList())

    private val PIN_WORDS = Regex("(?i)\\b(?:pin|passcode|password|pass code|secret code)\\b")
    private val PIN_VIEW_ID = Regex("(?i)(?:pin|passcode|password|otp|secret)")

    private val DELETE = Regex("(?i)^(?:delete|del|backspace|back ?space|clear|remove|erase|⌫|←)$")
    private val CONFIRM = Regex("(?i)^(?:ok|okay|confirm|submit|continue|done|proceed|next|enter|pay|send|approve|✓)$")

    /** The keypad on [s]: all ten digit buttons (by their label), and delete / confirm when present. Null when it is not one. Pure. */
    fun find(s: UiSnapshot?): Keypad? {
        val els = s?.elements ?: return null
        val digits = HashMap<Char, Int>()
        var area: Box? = null
        for (e in els) {
            val l = e.label?.trim() ?: continue
            if (l.length == 1 && l[0].isDigit() && (e.clickable || e.longClickable) && l[0] !in digits) {
                digits[l[0]] = e.id
                area = area?.let { Box(minOf(it.left, e.bounds.left), minOf(it.top, e.bounds.top), maxOf(it.right, e.bounds.right), maxOf(it.bottom, e.bounds.bottom)) } ?: e.bounds
            }
        }
        val ok = els.firstOrNull { e -> e.clickable && CONFIRM.matches(e.label?.trim().orEmpty()) }
        if (digits.size < 10 || area == null) {
            // 28 Oct 2026 (MyMTN, "the PIN isn't working" — owner: "since the keyboard was supposed to be used, convert that input to
            // be the buttons, just like the USSD way"): no 0–9 buttons — the PIN goes into a box the phone's keyboard would type. The
            // volume keys fill it. Which box: a password box, a box named PIN, the box the keyboard is on (input focus), the only box.
            val edits = els.filter { it.editable && it.enabled }
            if (edits.isEmpty()) return null
            val w = els.maxOf { it.bounds.right }
            val h = els.maxOf { it.bounds.bottom }
            val area = Box(0, h / 2, w, h)
            pinCells(edits, w)?.let { return Keypad(emptyMap(), null, ok?.id, area, cells = it) }
            val box = edits.firstOrNull { it.password }
                ?: edits.firstOrNull { e -> PIN_WORDS.containsMatchIn(e.hint.orEmpty()) || PIN_VIEW_ID.containsMatchIn(e.viewId?.substringAfter(":id/").orEmpty()) }
                ?: edits.firstOrNull { e -> PIN_WORDS.containsMatchIn(e.label.orEmpty()) }
                ?: edits.filterNot(::namedOther).let { rest -> rest.firstOrNull { it.focused } ?: rest.singleOrNull() }
                ?: return null
            return Keypad(emptyMap(), null, ok?.id, area, field = box.id)
        }
        val del = els.firstOrNull { e -> e.clickable && DELETE.matches(e.label?.trim().orEmpty()) }
        var a: Box = area
        for (x in listOfNotNull(del, ok)) a = Box(minOf(a.left, x.bounds.left), minOf(a.top, x.bounds.top), maxOf(a.right, x.bounds.right), maxOf(a.bottom, x.bounds.bottom))
        return Keypad(digits, del?.id, ok?.id, a)
    }

    /** A box that says it is for something else (the form's number, amount, reference...): never where a PIN goes. */
    private fun namedOther(e: com.tekyerema.accessibility.UiElement): Boolean =
        NOT_PIN.containsMatchIn(e.hint.orEmpty()) || NOT_PIN.containsMatchIn(e.viewId?.substringAfter(":id/").orEmpty())
    private val NOT_PIN = Regex("(?i)(?:amount|reference|ref|note|narration|description|purpose|phone|number|msisdn|recipient|wallet|search|name|email|message)")

    /** Four to eight small boxes side by side on one row (a "one digit per box" PIN), left to right; null otherwise. Pure. */
    internal fun pinCells(edits: List<com.tekyerema.accessibility.UiElement>, screenWidth: Int): List<Int>? {
        val small = edits.filter { it.bounds.width in 1..(screenWidth / 4) }
        val row = small.groupBy { it.bounds.top / 40 }.values.maxByOrNull { it.size } ?: return null
        if (row.size !in 4..8) return null
        val ws = row.map { it.bounds.width }
        if (ws.max() > ws.min() * 3 / 2 + 1) return null
        return row.sortedBy { it.bounds.left }.map { it.id }
    }

    /**
     * A PIN screen's shape for the log, safe to write at the secure step: role, view id, bounds and flags of each element; a label
     * only when it is a word with no digit and the element is not editable ("Confirm", "Enter PIN") — otherwise just its length
     * (a box or a dots row may hold the PIN). Pure.
     */
    fun layout(s: UiSnapshot): List<String> = s.elements.map { e ->
        val l = e.label?.trim().orEmpty()
        val text = when {
            l.isEmpty() -> "-"
            e.editable || e.password || l.any { it.isDigit() } -> "<${l.length} chars>"
            else -> "'${l.take(30)}'"
        }
        val hint = e.hint?.takeIf { h -> h.isNotBlank() && h.none { it.isDigit() } }?.let { " hint='${it.take(30)}'" }.orEmpty()
        "#${e.id} ${e.role} id=${e.viewId?.substringAfter(":id/") ?: "-"} $text$hint ${e.bounds.left},${e.bounds.top},${e.bounds.right},${e.bounds.bottom}" +
            (if (e.editable) " EDIT" else "") + (if (e.password) " PW" else "") + (if (e.clickable) " click" else "") +
            (if (e.longClickable) " long" else "") + (if (e.focused) " focused" else "") + (if (!e.enabled) " disabled" else "")
    }

    @Volatile var keypad: Keypad? = null; private set
    @Volatile var pkg: String? = null; private set
    /** Digits pressed into the app so far (to know when a "take one back" must press its delete key). */
    @Volatile var typed = 0

    fun active(): Boolean = keypad != null

    /** At an app's PIN screen: arm the pad on its keypad. False when the screen has no 0–9 keypad (then the user types as before). */
    suspend fun begin(ctrl: AccessibilityController): Boolean {
        if (!PinPad.enabled()) return false
        val s = try { ctrl.buildSnapshot() } catch (_: Throwable) { return false }
        val k = find(s) ?: run {
            // Why not: helps tell "no keypad" from "a layout we don't know" — the screen's SHAPE only ([layout]), never what it holds.
            val els = s.elements
            android.util.Log.i("Tekyerema", "[PINPAD] no keypad on ${s.appPackage}: digitKeys=${els.count { e -> e.label?.trim()?.let { it.length == 1 && it[0].isDigit() } == true }} " +
                "editable=${els.count { it.editable }} password=${els.count { it.password }}")
            layout(s).forEach { android.util.Log.i("Tekyerema", "[PINPAD] layout $it") }
            return false
        }
        keypad = k; pkg = s.appPackage; typed = 0
        com.tekyerema.voice.SecureStep.begin(appPackage = s.appPackage)
        PinPad.arm()
        android.util.Log.i("Tekyerema", "[PINPAD] armed on ${s.appPackage}'s ${if (k.field != null) "PIN box" else if (k.cells.isNotEmpty()) "${k.cells.size} PIN boxes" else "own keypad"} (confirm=${k.confirm != null}, delete=${k.delete != null})")
        if (com.tekyerema.core.Taught.teach("pin.app.keys.tip")) try {
            com.tekyerema.voice.VoiceEngine.announce("Use the volume keys for your PIN: press volume down once for each number, then volume up " +
                "to put it in — volume up on its own is a zero. One buzz means it went in. Hold either key to take one back.")
        } catch (_: Throwable) {}
        return true
    }

    fun end() {
        if (keypad == null) return
        keypad = null; pkg = null; typed = 0
        PinPad.disarm()
        com.tekyerema.voice.SecureStep.end()
    }

    /** The keypad as it is NOW (ids can change between frames). */
    private suspend fun fresh(ctrl: AccessibilityController): Keypad? {
        val k = try { find(ctrl.buildSnapshot()) } catch (_: Throwable) { null } ?: return null
        keypad = k
        return k
    }

    /** The PIN so far into the app: its box filled with [soFar] (box layout), or the button of its newest digit ([added], keypad layout). */
    suspend fun enter(ctrl: AccessibilityController, soFar: String, added: Boolean): Boolean {
        val k = keypad ?: return false
        if (k.field != null) return ctrl.setText(k.field, soFar, submit = false).also { if (it) typed = soFar.length }
        if (k.cells.isNotEmpty()) {
            // One digit per box: the newest digit into its own box, or the box just emptied when one is taken back.
            val i = if (added) soFar.length - 1 else soFar.length
            val cell = k.cells.getOrNull(i) ?: return false
            return ctrl.setText(cell, if (added) soFar.last().toString() else "", submit = false).also { if (it) typed = soFar.length }
        }
        return if (added) press(ctrl, soFar.last()) else deleteOne(ctrl)
    }

    suspend fun press(ctrl: AccessibilityController, digit: Char): Boolean {
        val k = fresh(ctrl) ?: return false
        val id = k.digits[digit] ?: return false
        return ctrl.clickElement(id).also { if (it) typed++ }
    }

    suspend fun deleteOne(ctrl: AccessibilityController): Boolean {
        val k = fresh(ctrl) ?: return false
        val id = k.delete ?: return false
        return ctrl.clickElement(id).also { if (it && typed > 0) typed-- }
    }

    /** After the last digit: the app's own confirm button, if it has one (many apps go on by themselves at the last digit). */
    suspend fun confirm(ctrl: AccessibilityController): Boolean {
        val k = fresh(ctrl) ?: return true    // the keypad is gone: the app took the PIN by itself
        return k.confirm?.let { ctrl.clickElement(it) } ?: true
    }
}
