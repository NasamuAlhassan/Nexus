package com.tekyerema.money

import android.view.KeyEvent
import com.tekyerema.accessibility.AccessibilityController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Mobile Money PIN entered with the VOLUME KEYS (owner's idea, 2 October 2026) — for a blind user who can't find the
 * keys of the network dialog's keypad. Only on the network's own PIN screen, after a hand-off ([arm]):
 *
 *  - volume UP: one more for the digit being counted (a short tick);
 *  - volume DOWN: that digit is done — 1 press is 1 … 9 presses are 9, 10 presses are 0 — a longer buzz, and it goes
 *    straight into the network's box (a pause never splits a digit: only volume down ends one);
 *  - volume DOWN held: the last digit is taken out;
 *  - volume DOWN with nothing counted, once four digits are in: the dialog's own Send.
 *
 * What is said is only HOW MANY digits are in ("two digits"), never which. The digits exist only in the network's box and,
 * while it is being filled, in one char array here that is wiped at Send, at a hold-to-clear of everything, and when the PIN
 * step ends; nothing is logged, spoken, stored or sent anywhere else.
 */
object PinPad {
    const val MIN_DIGITS = 4
    private const val HOLD_MS = 700L

    /** The counting itself — pure, unit-tested. */
    class Counter(private val maxDigits: Int = 6) {
        sealed class Event {
            data class Tick(val count: Int) : Event()
            data class Digit(val digits: Int) : Event()
            data class Removed(val digits: Int) : Event()
            data class Send(val digits: Int) : Event()
            /** More than ten presses for one digit, or more digits than a PIN has: that digit starts again. */
            object TooMany : Event()
            object Ignored : Event()
        }

        private val digits = CharArray(maxDigits)
        var size = 0; private set
        var count = 0; private set

        fun up(): Event {
            if (count >= 10) { count = 0; return Event.TooMany }
            count++
            return Event.Tick(count)
        }

        fun down(): Event {
            if (count == 0) return if (size >= MIN_DIGITS) Event.Send(size) else Event.Ignored
            if (size >= maxDigits) { count = 0; return Event.TooMany }
            digits[size++] = '0' + (count % 10)
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

    /** Said after the PIN hand-off line, so the user knows the keys are there. */
    fun handOffTip(): String = if (enabled()) " You can also enter it with the volume keys: press up to count each digit, and down to finish it." else ""

    @Volatile private var armed = false
    @Volatile private var counter: Counter? = null
    @Volatile private var downAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The network's PIN screen was just handed to the user. */
    fun arm() { counter?.wipe(); counter = Counter(); armed = true }

    fun disarm() { armed = false; counter?.wipe(); counter = null }

    /** Whether the volume keys are the PIN pad right now. */
    fun handles(): Boolean = enabled() && armed && com.tekyerema.voice.SecureStep.active()

    /**
     * From the accessibility service's key filter. True = the key was the PIN pad's (consumed: the volume doesn't move).
     */
    fun onKey(ctrl: AccessibilityController, keyCode: Int, action: Int, eventTime: Long): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!handles()) { if (armed && !com.tekyerema.voice.SecureStep.active()) disarm(); return false }
        if (action == KeyEvent.ACTION_DOWN) { if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) downAt = eventTime; return true }
        if (action != KeyEvent.ACTION_UP) return true
        val c = counter ?: Counter().also { counter = it }
        val ev = when {
            keyCode == KeyEvent.KEYCODE_VOLUME_UP -> c.up()
            eventTime - downAt >= HOLD_MS -> c.hold()
            else -> c.down()
        }
        scope.launch { act(ctrl, c, ev) }
        return true
    }

    private suspend fun act(ctrl: AccessibilityController, c: Counter, ev: Counter.Event) {
        when (ev) {
            is Counter.Event.Tick -> buzz(longArrayOf(0, 12))
            Counter.Event.Ignored -> {}
            Counter.Event.TooMany -> { buzz(longArrayOf(0, 60, 80, 60)); say("That was more than ten. Count that digit again.") }
            is Counter.Event.Digit, is Counter.Event.Removed -> {
                val box = pinBox(ctrl)
                if (box == null) { disarm(); say("The PIN screen isn't open any more."); return }
                ctrl.setText(box.inputId!!, c.text(), submit = false)
                val n = if (ev is Counter.Event.Digit) ev.digits else (ev as Counter.Event.Removed).digits
                if (ev is Counter.Event.Digit) buzz(longArrayOf(0, 60)) else buzz(longArrayOf(0, 25, 90, 25))
                say(when {
                    ev is Counter.Event.Removed -> "Taken out. ${digits(n)}."
                    n >= MIN_DIGITS -> "${digits(n)}. Press volume down again to send, or hold it to take one out."
                    else -> "${digits(n)}."
                })
            }
            is Counter.Event.Send -> {
                val box = pinBox(ctrl)
                if (box == null) { disarm(); say("The PIN screen isn't open any more."); return }
                buzz(longArrayOf(0, 120))
                // The box already holds the digits; a dialog without a Send button takes them with the box's own submit.
                val sent = box.sendId?.let { ctrl.clickElement(it) } ?: ctrl.setText(box.inputId!!, c.text(), submit = true)
                c.wipe()
                android.util.Log.i("Tekyerema", "[PINPAD] sent with the volume keys (${if (sent) "Send pressed" else "no Send button"})")
                disarm()
            }
        }
    }

    /** The network's PIN dialog, only when it IS a PIN screen with an entry box — the pad never types anywhere else. */
    private suspend fun pinBox(ctrl: AccessibilityController): UssdDialog.Screen? {
        val s = UssdDialog.read(ctrl) ?: return null
        if (s.inputId == null) return null
        return s.takeIf { BankUssd.kind(it.text, it.hasInput, it.masked) == BankUssd.ScreenKind.PIN }
    }

    private fun digits(n: Int) = if (n == 1) "One digit" else "$n digits"

    private fun say(line: String) = try { com.tekyerema.voice.VoiceEngine.announce(line) } catch (_: Throwable) {}

    private fun buzz(pattern: LongArray) {
        try {
            val app = com.tekyerema.agent.AgentEngine.appContext() ?: return
            val v = if (android.os.Build.VERSION.SDK_INT >= 31)
                (app.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Throwable) {}
    }
}
