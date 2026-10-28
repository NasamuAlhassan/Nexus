package com.tekyerema.money

import android.view.accessibility.AccessibilityEvent
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.TelephonyWindows
import com.tekyerema.accessibility.UiSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The volume keys are the keypad at ANY PIN screen (owner, 28 Oct 2026: "when there's the need to enter PIN on any application,
 * we use the volume buttons — whether offline or online, on any app. During that period the volume buttons become the keypad").
 *
 * Until now the pad was armed only when the agent itself handed a PIN step over (the USSD navigator, AgentEngine.awaitSecureInput).
 * A task that ended at MyMTN's PIN sheet ("enter it on the screen"), or a PIN screen the user reached on their own, left the keys as
 * plain volume. This watches the screen while no task runs: a PIN screen in an app ([AppPin.find] + a PIN word or PIN cells) arms
 * the pad on it, and the network's own USSD PIN dialog arms the USSD pad; the pad is dropped when that screen goes. During a task the
 * agent's own hand-off does it (no second screen reader racing the agent's).
 *
 * Nothing on the screen is logged here — the package and the layout kind only.
 */
object PinWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    /** The pad was armed by this watch (not by the agent's hand-off): the watch also ends it. */
    @Volatile private var mine: String? = null
    @Volatile private var misses = 0

    private val PIN_SCREEN = Regex("(?i)\\b(?:pin|passcode|pass code|secret code|otp)\\b")
    private val NEVER = listOf("com.tekyerema", "com.android.systemui", "com.android.keyguard")

    fun enabled(): Boolean = PinPad.enabled() && try { com.tekyerema.core.config.AppConfig.bool("pin.watch.enabled", true) } catch (_: Throwable) { true }

    private fun excluded(pkg: String): Boolean {
        val never = try { com.tekyerema.core.config.AppConfig.stringList("pin.watch.never", NEVER) } catch (_: Throwable) { NEVER }
        return never.any { pkg == it } || pkg.contains("inputmethod") || pkg.contains("keyboard")
    }

    /** From the accessibility service: a window or its content changed. Cheap — one look at most every [DEBOUNCE_MS]. */
    fun onEvent(ctrl: AccessibilityController, type: Int) {
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (!enabled() || job?.isActive == true) return
        job = scope.launch { delay(DEBOUNCE_MS); try { look(ctrl) } catch (_: Throwable) {} }
    }

    private const val DEBOUNCE_MS = 450L

    /** A PIN screen in an app: the pad's layout on it AND it says PIN (or shows PIN cells). A calculator's 0–9 is not one. Pure. */
    fun appPinScreen(s: UiSnapshot): AppPin.Keypad? {
        val k = AppPin.find(s) ?: return null
        if (k.hiddenBoxCell != null) return k                          // cells named otp / pin by the app itself
        val says = s.elements.any { e -> PIN_SCREEN.containsMatchIn(e.label.orEmpty()) || PIN_SCREEN.containsMatchIn(e.hint.orEmpty()) } ||
            PIN_SCREEN.containsMatchIn(s.screenTitle.orEmpty())
        return k.takeIf { says || s.elements.any { it.password && it.editable } && k.digits.isNotEmpty() }
    }

    private suspend fun look(ctrl: AccessibilityController) {
        if (com.tekyerema.agent.AgentEngine.isBusy) return             // the agent's own hand-off arms the pad in a task
        val owned = mine
        // The USSD pad the navigator armed (not ours): leave it alone.
        if (owned == null && PinPad.isArmed()) return
        // Cheap first: a screen that never says PIN is not read at all (only while the pad is ours is every change looked at).
        if (owned == null && !ctrl.screenMentions(LOOK_FOR)) return
        val s = ctrl.buildSnapshot()
        val pkg = s.appPackage.orEmpty()
        if (pkg.isEmpty() || excluded(pkg)) return                      // the keyboard / our own panel over the PIN screen: no change
        if (TelephonyWindows.matches(pkg)) { ussd(ctrl, owned, pkg); return }
        if (owned != null) {
            val still = owned == pkg && AppPin.find(s) != null
            misses = if (still) 0 else misses + 1
            if (misses >= 2) stop(if (owned != pkg) "another app in front" else "PIN screen gone")
            else if (!still) { job = null; scope.launch { delay(600); try { look(ctrl) } catch (_: Throwable) {} } }   // look again soon
            return
        }
        val k = appPinScreen(s)
        if (k == null || System.currentTimeMillis() - AppPin.endedAt < QUIET_MS) return
        if (AppPin.begin(ctrl)) {
            mine = pkg; misses = 0
            android.util.Log.i("Tekyerema", "[PINPAD] watch: PIN screen on $pkg — the volume keys are its keypad")
        }
    }

    /** The network's PIN dialog the user reached on their own (dialled *170# themselves): the same USSD pad the navigator arms. */
    private suspend fun ussd(ctrl: AccessibilityController, owned: String?, pkg: String) {
        val d = UssdDialog.read(ctrl)
        val pin = d != null && d.inputId != null && BankUssd.kind(d.text, d.hasInput, d.masked) == BankUssd.ScreenKind.PIN
        if (owned != null) { if (!pin) stop("PIN dialog gone"); return }
        if (!pin || System.currentTimeMillis() - AppPin.endedAt < QUIET_MS) return
        mine = pkg
        com.tekyerema.voice.SecureStep.begin()
        PinPad.arm()
        android.util.Log.i("Tekyerema", "[PINPAD] watch: the network's PIN dialog — the volume keys are its keypad")
        try { com.tekyerema.voice.VoiceEngine.announce("Enter your PIN." + PinPad.handOffTip()) } catch (_: Throwable) {}
    }

    private const val QUIET_MS = 4_000L
    private val LOOK_FOR = listOf("pin", "passcode", "pass code", "secret code", "otp")

    private fun stop(why: String) {
        val was = mine ?: return
        mine = null
        // A PIN already sent (the pad disarmed itself) keeps its secure step for the network's reply; only an unfinished one ends here.
        if (AppPin.active()) AppPin.end() else if (PinPad.isArmed()) { PinPad.disarm(); com.tekyerema.voice.SecureStep.end() }
        android.util.Log.i("Tekyerema", "[PINPAD] watch: pad off on $was ($why)")
    }

    /** The pad finished (PIN sent) or the agent took over: the watch lets go without ending anything again. */
    fun release() { mine = null }

    /**
     * The agent's task ended while the user was still at the app's PIN screen (stopped, or the hand-off's wait ended): the pad is
     * NOT dropped — the watch keeps it and ends it when the screen goes (MyMTN, 28 Oct 2026: the task stopped at 21:46:20 and the
     * presses that followed went nowhere). True when it was taken over.
     */
    fun adopt(): Boolean {
        val p = AppPin.pkg ?: return false
        if (!AppPin.active()) return false
        mine = p; misses = 0
        com.tekyerema.core.Privacy.secureStep = true
        android.util.Log.i("Tekyerema", "[PINPAD] watch: kept the pad on $p after the task ended")
        return true
    }
}
