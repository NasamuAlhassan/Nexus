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
    data class Keypad(val digits: Map<Char, Int>, val delete: Int?, val confirm: Int?, val area: Box, val field: Int? = null)

    private val PIN_WORDS = Regex("(?i)\\b(?:pin|passcode|password|pass code|secret code)\\b")

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
            // 28 Oct 2026 (MyMTN, "the PIN isn't working"): no 0–9 buttons — a PIN box typed with the phone's keyboard. The pad fills
            // the box; its touch area is the screen's lower half, where that keyboard sits.
            val box = els.firstOrNull { e -> e.editable && (e.password || PIN_WORDS.containsMatchIn(e.hint.orEmpty()) || PIN_WORDS.containsMatchIn(e.label.orEmpty())) }
                ?: return null
            val w = els.maxOf { it.bounds.right }.coerceAtLeast(box.bounds.right)
            val h = els.maxOf { it.bounds.bottom }.coerceAtLeast(box.bounds.bottom)
            return Keypad(emptyMap(), null, ok?.id, Box(0, h / 2, w, h), field = box.id)
        }
        val del = els.firstOrNull { e -> e.clickable && DELETE.matches(e.label?.trim().orEmpty()) }
        var a: Box = area
        for (x in listOfNotNull(del, ok)) a = Box(minOf(a.left, x.bounds.left), minOf(a.top, x.bounds.top), maxOf(a.right, x.bounds.right), maxOf(a.bottom, x.bounds.bottom))
        return Keypad(digits, del?.id, ok?.id, a)
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
            // Why not (counts only, no screen text): helps tell "no keypad" from "a layout we don't know".
            val els = s.elements
            android.util.Log.i("Tekyerema", "[PINPAD] no keypad on ${s.appPackage}: digitKeys=${els.count { e -> e.label?.trim()?.let { it.length == 1 && it[0].isDigit() } == true }} " +
                "editable=${els.count { it.editable }} password=${els.count { it.password }}")
            return false
        }
        keypad = k; pkg = s.appPackage; typed = 0
        com.tekyerema.voice.SecureStep.begin(appPackage = s.appPackage)
        PinPad.arm()
        android.util.Log.i("Tekyerema", "[PINPAD] armed on ${s.appPackage}'s ${if (k.field != null) "PIN box" else "own keypad"} (confirm=${k.confirm != null}, delete=${k.delete != null})")
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
