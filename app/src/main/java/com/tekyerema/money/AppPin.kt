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
    data class Keypad(val digits: Map<Char, Int>, val delete: Int?, val confirm: Int?, val area: Box)

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
        if (digits.size < 10 || area == null) return null
        val del = els.firstOrNull { e -> e.clickable && DELETE.matches(e.label?.trim().orEmpty()) }
        val ok = els.firstOrNull { e -> e.clickable && CONFIRM.matches(e.label?.trim().orEmpty()) }
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
        val k = find(s) ?: return false
        keypad = k; pkg = s.appPackage; typed = 0
        com.tekyerema.voice.SecureStep.begin(appPackage = s.appPackage)
        PinPad.arm()
        android.util.Log.i("Tekyerema", "[PINPAD] armed on ${s.appPackage}'s own keypad (confirm=${k.confirm != null}, delete=${k.delete != null})")
        if (com.tekyerema.core.Taught.teach("pin.app.tip")) try {
            com.tekyerema.voice.VoiceEngine.announce("You can draw each number of your PIN on the keypad, one at a time. One buzz means it went in; " +
                "three short buzzes, draw it again. Two fingers take one back. The volume keys work too.")
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
