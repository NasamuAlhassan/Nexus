package com.tekyerema.money

import android.view.KeyEvent
import com.tekyerema.accessibility.AccessibilityController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Mobile Money PIN entered with the VOLUME KEYS (owner's idea, 2 October 2026; the owner's own scheme, 5 October 2026) —
 * for a blind user who can't find the keys of the network dialog's keypad. Only on the network's own PIN screen, after a
 * hand-off ([arm]):
 *
 *  - volume DOWN: one more for the digit being counted — DOWN three times is a 3 (a short tick each);
 *  - volume UP: the counted digit goes into the network's box (a firm buzz) — UP with nothing counted is a 0;
 *  - either key HELD: the digit being counted is dropped, or else the last digit is taken out (two short buzzes);
 *  - after the PIN's last digit ([length], 4 for MoMo) a short pause, then the dialog's own Send (one long buzz) — a key
 *    pressed in that pause stops the send (a hold takes the last digit out to fix it).
 *
 * SILENT: nothing is said while the PIN is entered, not even how many digits are in (owner, 5 October 2026: "it's just
 * supposed to be silent at that moment") — and any line still being read stops at the first key. The digits exist only in
 * the network's box and, while it is being filled, in one char array here that is wiped at Send, at a hold-to-clear of
 * everything, and when the PIN step ends; nothing is logged, spoken, stored or sent anywhere else.
 */
object PinPad {
    const val MIN_DIGITS = 4
    private const val HOLD_MS = 700L
    private const val SEND_PAUSE_MS = 1_500L

    /** The counting itself — pure, unit-tested. */
    class Counter(private val maxDigits: Int = 6) {
        sealed class Event {
            data class Tick(val count: Int) : Event()
            data class Digit(val digits: Int) : Event()
            data class Removed(val digits: Int) : Event()
            /** A tenth press for one digit (a digit is 0–9; 0 is UP alone): that digit starts again. */
            object TooMany : Event()
            /** More digits than a PIN has. */
            object Full : Event()
            object Ignored : Event()
        }

        private val digits = CharArray(maxDigits)
        var size = 0; private set
        var count = 0; private set

        /** Volume DOWN: one more for this digit. */
        fun down(): Event {
            if (count >= 9) { count = 0; return Event.TooMany }
            count++
            return Event.Tick(count)
        }

        /** Volume UP: the counted digit goes in — nothing counted is a 0. */
        fun up(): Event {
            if (size >= maxDigits) { count = 0; return Event.Full }
            digits[size++] = '0' + count
            count = 0
            return Event.Digit(size)
        }

        /** A digit drawn on the pad ([DigitInk]): straight in, whatever was being counted is dropped. */
        fun put(d: Char): Event {
            if (size >= maxDigits) { count = 0; return Event.Full }
            digits[size++] = d
            count = 0
            return Event.Digit(size)
        }

        fun hold(): Event {
            if (count > 0) { count = 0; return Event.Removed(size) }
            if (size == 0) return Event.Ignored
            digits[--size] = '\u0000'
            return Event.Removed(size)
        }

        /** Only ever handed to the network's own entry box. */
        internal fun text(): String = String(digits, 0, size)

        fun wipe() { digits.fill('\u0000'); size = 0; count = 0 }
    }

    fun enabled(): Boolean = try { com.tekyerema.core.config.AppConfig.bool("pin.volumeKeys.enabled", true) } catch (_: Throwable) { true }

    /** How many digits the PIN has — it is sent on its own after the last one. MoMo: 4. */
    fun length(): Int = try { com.tekyerema.core.config.AppConfig.int("pin.volumeKeys.length", MIN_DIGITS) } catch (_: Throwable) { MIN_DIGITS }

    /** Whether the screen is a PIN pad too (a tap counts, a long press keys the digit in — [onGesture]). */
    fun touchEnabled(): Boolean = enabled() && try { com.tekyerema.core.config.AppConfig.bool("pin.touchPad.enabled", true) } catch (_: Throwable) { true }

    /** Said after the PIN hand-off line, so the user knows the keys are there. */
    fun handOffTip(): String = if (!enabled()) "" else if (com.tekyerema.core.Taught.teach("pin.volumeKeys.tip"))
        " You can also enter it with the volume keys: press volume down once for each " +
        "number, then volume up to put it in — volume up on its own is a zero. It sends by itself after the last digit. " +
        (if (touchEnabled()) "The screen works the same way: tap for each number, press and hold to put it in, tap with two fingers to take one back, three fingers give the screen back to the keypad. " else "") +
        "A press while I'm talking only stops me." else if (touchEnabled()) " The volume keys and the screen work too."
        else " The volume keys work too."   // taught three times, then short

    @Volatile private var armed = false
    /** When the volume keys last SENT a PIN (0 = never): the post-PIN watcher says "PIN sent" only after this. */
    @Volatile var lastSentAt = 0L; private set
    @Volatile private var counter: Counter? = null
    /** Which input last counted — only for the log line at Send, never a digit. */
    @Volatile private var lastVia = "the volume keys"
    private val pressedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val silencing: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var hushUntil = 0L
    private const val HUSH_GRACE_MS = 300L
    /** Presses that only stopped the speech, this PIN (for the log line at Send — a count, never a digit). */
    @Volatile private var swallowed = 0
    @Volatile private var sendJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The network's PIN screen was just handed to the user. */
    fun arm() {
        sendJob?.cancel(); counter?.wipe(); counter = Counter(); silencing.clear(); hushUntil = 0L; swallowed = 0; armed = true
        com.tekyerema.accessibility.PinTouchPad.watch()   // the touch layer follows [handles] while the pad is armed
    }

    fun disarm() { armed = false; sendJob?.cancel(); counter?.wipe(); counter = null; silencing.clear() }

    /** Whether the volume keys are the PIN pad right now. */
    fun handles(): Boolean = enabled() && armed && com.tekyerema.voice.SecureStep.active()

    /** Armed (the PIN screen was handed over): the touch layer's watchdog keeps running while this is true. */
    fun isArmed(): Boolean = armed

    /** What a touch on the PIN screen means: the screen is the volume keys. */
    enum class Gesture {
        /** Volume DOWN: one more for the digit being counted. */
        TAP,
        /** Volume UP: the counted digit goes in (nothing counted is a 0). */
        LONG_PRESS,
        /** The key HELD: the counted digit is dropped, or else the last digit is taken out. */
        TWO_FINGER_TAP
    }

    /** The gesture a finished touch was, or null (a swipe or drag is nothing). A long press is decided by the clock while
     *  the finger is still down ([PinTouchPad]); this classifies what ended before it. Pure → PinTouchPadTest. */
    fun classify(durationMs: Long, maxPointers: Int, movedPx: Float, slopPx: Float, longPressMs: Long): Gesture? = when {
        movedPx > slopPx -> null
        durationMs >= longPressMs -> null
        maxPointers >= 3 -> null   // three fingers hand the screen back to the dialog ([PinTouchPad]); never a delete as well
        maxPointers >= 2 -> Gesture.TWO_FINGER_TAP
        else -> Gesture.TAP
    }

    /** The counting for a gesture — the same [Counter] the volume keys drive. Pure → PinTouchPadTest. */
    internal fun apply(c: Counter, g: Gesture): Counter.Event = when (g) {
        Gesture.TAP -> c.down()
        Gesture.LONG_PRESS -> c.up()
        Gesture.TWO_FINGER_TAP -> c.hold()
    }

    /**
     * From the touch layer over the network's PIN screen. True = the touch was the PIN pad's. The same rules as the keys: a
     * touch while the PIN line is still being read only STOPS the reading, a touch in the pause before Send stops the send,
     * nothing is spoken, logged or kept.
     */
    fun onGesture(ctrl: AccessibilityController, g: Gesture, eventTime: Long): Boolean {
        if (!handles() || !touchEnabled()) return false
        sendJob?.cancel()   // a touch in the pause before Send: the user is still at it
        val talking = try { com.tekyerema.voice.NowSpeaking.active } catch (_: Throwable) { false }
        if (talking) {
            try { com.tekyerema.voice.VoiceEngine.hush() } catch (_: Throwable) {}
            scope.launch { buzz(longArrayOf(0, 10, 60, 10)) }
            hushUntil = eventTime + HUSH_GRACE_MS
            swallowed++
            return true
        }
        val c = counter ?: Counter().also { counter = it }
        lastVia = "the screen"
        val ev = apply(c, g)
        scope.launch { act(ctrl, c, ev) }
        return true
    }

    /**
     * A digit DRAWN on the touch pad ([com.tekyerema.accessibility.PinTouchPad] → [DigitInk]), or null when the drawing was not
     * read. One firm buzz = it went in; three short buzzes = draw it again, nothing went in (owner, 28 Oct 2026). Silent otherwise.
     */
    fun onDrawn(ctrl: AccessibilityController, digit: Char?, eventTime: Long): Boolean {
        if (!handles() || !touchEnabled()) return false
        sendJob?.cancel()
        if (eventTime < hushUntil) return true
        if (digit == null) { scope.launch { buzz(longArrayOf(0, 40, 70, 40, 70, 40)) }; return true }
        val c = counter ?: Counter().also { counter = it }
        lastVia = "drawn digits"
        val ev = c.put(digit)
        scope.launch { act(ctrl, c, ev) }
        return true
    }

    /**
     * From the accessibility service's key filter. True = the key was the PIN pad's (consumed: the volume doesn't move, and
     * no other gesture — "talk", "say that again" — ever sees it while the PIN screen is open).
     */
    fun onKey(ctrl: AccessibilityController, keyCode: Int, action: Int, eventTime: Long): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!handles()) { if (armed && !com.tekyerema.voice.SecureStep.active()) disarm(); return false }
        if (action == KeyEvent.ACTION_DOWN) {
            pressedAt[keyCode] = eventTime
            sendJob?.cancel()   // a key in the pause before Send: the user is still at it
            // A press while the PIN line is still being read only STOPS the reading (a light double-tick) — never counts:
            // the owner double-presses to interrupt, then counts (5 October 2026). Presses just after it, too.
            val talking = try { com.tekyerema.voice.NowSpeaking.active } catch (_: Throwable) { false }
            // 28 Oct 2026 (Infinix, "the PIN wasn't working"): every press inside the grace used to re-extend it, so a quick count
            // (down-down-down) right after stopping the line lost presses and the wrong digits went in. Now only the press that
            // stops the speech is swallowed; the grace is for the speech's own tail and is never extended by a press.
            if (talking) {
                try { com.tekyerema.voice.VoiceEngine.hush() } catch (_: Throwable) {}
                scope.launch { buzz(longArrayOf(0, 10, 60, 10)) }
                hushUntil = eventTime + HUSH_GRACE_MS
                silencing.add(keyCode)
                swallowed++
            }
            return true
        }
        if (action != KeyEvent.ACTION_UP) return true
        if (silencing.remove(keyCode)) return true   // that press only silenced the reading
        val c = counter ?: Counter().also { counter = it }
        lastVia = "the volume keys"
        val ev = when {
            eventTime - (pressedAt[keyCode] ?: eventTime) >= HOLD_MS -> c.hold()
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN -> c.down()
            else -> c.up()
        }
        scope.launch { act(ctrl, c, ev) }
        return true
    }

    private suspend fun act(ctrl: AccessibilityController, c: Counter, ev: Counter.Event) {
        when (ev) {
            is Counter.Event.Tick -> buzz(longArrayOf(0, 15))
            Counter.Event.Ignored -> {}
            Counter.Event.TooMany, Counter.Event.Full -> buzz(longArrayOf(0, 40, 70, 40, 70, 40))
            is Counter.Event.Digit, is Counter.Event.Removed -> {
                if (AppPin.active()) { actOnApp(ctrl, c, ev); return }
                val box = pinBox(ctrl)
                if (box == null) { disarm(); say("The PIN screen isn't open any more."); return }
                ctrl.setText(box.inputId!!, c.text(), submit = false)
                if (ev is Counter.Event.Digit) {
                    buzz(longArrayOf(0, 80))
                    if (ev.digits >= length()) sendJob = scope.launch { kotlinx.coroutines.delay(SEND_PAUSE_MS); send(ctrl, c) }
                } else buzz(longArrayOf(0, 25, 90, 25))
            }
        }
    }

    /** An app's own keypad (money/AppPin): the new digit's button, or its delete key for one taken back; the app's confirm after the last. */
    private suspend fun actOnApp(ctrl: AccessibilityController, c: Counter, ev: Counter.Event) {
        if (ev is Counter.Event.Digit) {
            val ok = AppPin.enter(ctrl, c.text(), added = true)
            if (!ok) { buzz(longArrayOf(0, 40, 70, 40, 70, 40)); c.hold(); return }   // not pressed: taken back out, the user tries again
            buzz(longArrayOf(0, 80))
            if (ev.digits >= length()) sendJob = scope.launch { kotlinx.coroutines.delay(SEND_PAUSE_MS); sendOnApp(ctrl, c) }
        } else {
            if (c.size < AppPin.typed) AppPin.enter(ctrl, c.text(), added = false)
            buzz(longArrayOf(0, 25, 90, 25))
        }
    }

    private suspend fun sendOnApp(ctrl: AccessibilityController, c: Counter) {
        buzz(longArrayOf(0, 300))
        val ok = AppPin.confirm(ctrl)
        c.wipe()
        lastSentAt = System.currentTimeMillis()
        android.util.Log.i("Tekyerema", "[PINPAD] sent into the app with $lastVia (${if (ok) "confirmed" else "no confirm button"}); ${length()} digits; $swallowed press(es) only stopped the speech")
        AppPin.end()
        // The agent was waiting at the PIN screen: it carries on and reads what the app says.
        kotlinx.coroutines.delay(1_500)
        try { com.tekyerema.agent.AgentEngine.secureInputDone() } catch (_: Throwable) {}
    }

    /** The last digit is in and the pause passed: the dialog's own Send (one long buzz), and the digits are wiped. */
    private suspend fun send(ctrl: AccessibilityController, c: Counter) {
        val box = pinBox(ctrl)
        if (box == null) { disarm(); say("The PIN screen isn't open any more."); return }
        buzz(longArrayOf(0, 300))
        // The box already holds the digits; a dialog without a Send button takes them with the box's own submit.
        val sent = box.sendId?.let { ctrl.clickElement(it) } ?: ctrl.setText(box.inputId!!, c.text(), submit = true)
        c.wipe()
        lastSentAt = System.currentTimeMillis()
        android.util.Log.i("Tekyerema", "[PINPAD] sent with $lastVia (${if (sent) "Send pressed" else "no Send button"}); ${length()} digits; $swallowed press(es) only stopped the speech")
        disarm()
        // The curtain is for the PIN: it lifts as soon as the PIN is in (owner, 28 Oct 2026 — it stayed until MTN's answer was closed).
        if (!com.tekyerema.core.config.AppConfig.bool("pin.curtain.untilAnswer", false)) com.tekyerema.phone.PinCurtain.hideNow()
    }

    /** The network's PIN dialog, only when it IS a PIN screen with an entry box — the pad never types anywhere else. */
    private suspend fun pinBox(ctrl: AccessibilityController): UssdDialog.Screen? {
        val s = UssdDialog.read(ctrl) ?: return null
        if (s.inputId == null) return null
        return s.takeIf { BankUssd.kind(it.text, it.hasInput, it.masked) == BankUssd.ScreenKind.PIN }
    }

    private fun say(line: String) = try { com.tekyerema.voice.VoiceEngine.announce(line) } catch (_: Throwable) {}

    /** An accessibility vibration (core/Haptics): a phone with touch vibration off dropped every digit buzz (Infinix, 28 Oct 2026). */
    private fun buzz(pattern: LongArray) {
        try { com.tekyerema.core.Haptics.waveform(com.tekyerema.agent.AgentEngine.appContext(), pattern) } catch (_: Throwable) {}
    }
}
