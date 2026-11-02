package com.tekyerema.money

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "This is money" (owner, 2 Nov 2026): while any money step is on screen or being spoken (the read-back, the confirm, the
 * USSD walk, the PIN) the user must always know it is money. One flag, [active], fed by:
 *  - [TASK]: the agent raised the read-back of a money tool ([isMoneyTool]); released when that task ends or is stopped;
 *  - [PIN]: the PIN step (voice/SecureStep), released when it ends.
 * The rising edge plays the money earcon ([onStart] → VoiceCues.money), the UI draws its gold edge from [active], and with
 * Settings › Privacy › "Cover the screen for money" on, the dark curtain (phone/PinCurtain) covers the whole flow ([onChange]).
 * A hold that outlives [MAX_MS] is dropped, so a missed release can never leave the phone in "money" for good.
 * Pure Kotlin + a daemon timer → unit-tested (MoneyMomentTest).
 */
object MoneyMoment {
    const val TASK = "task"
    const val PIN = "pin"
    const val MAX_MS = 300_000L

    /** The tools that move money or walk a money menu (operator/Capabilities MONEY_TOOLS + the menu walkers). */
    val MONEY_TOOLS = setOf("momo_send", "mobile_money", BankUssdTool.NAME, TelcoUssdTool.NAME,
        com.tekyerema.money.ussd.UssdNavigatorTool.NAME, "run_ussd")

    fun isMoneyTool(name: String?): Boolean = name != null && name in MONEY_TOOLS

    private val _active = MutableStateFlow(false)
    /** True while any money step is under way. The UI reads this (gold edge on the assistant surface and the island). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Called once on each start (false → true): the earcon. Set by VoiceEngine. */
    @Volatile var onStart: (() -> Unit)? = null
    /** Called on every change with the new value: the whole-flow curtain. Set by VoiceEngine. */
    @Volatile var onChange: ((Boolean) -> Unit)? = null

    private val holds = HashMap<String, Long>()
    private val timer by lazy { java.util.Timer("tk-money-moment", true) }

    fun hold(key: String, now: Long = System.currentTimeMillis(), expire: Boolean = true) {
        val rose = synchronized(holds) {
            val was = holds.isNotEmpty()
            holds[key] = now
            !was
        }
        if (expire) try {
            timer.schedule(object : java.util.TimerTask() {
                override fun run() { releaseIfStale(key, now) }
            }, MAX_MS)
        } catch (_: Throwable) {}
        if (rose) changed(true)
    }

    fun release(key: String) {
        val fell = synchronized(holds) { holds.remove(key) != null && holds.isEmpty() }
        if (fell) changed(false)
    }

    /** The timer's release: only the hold taken at [since]; a newer hold of the same key keeps it. */
    internal fun releaseIfStale(key: String, since: Long) {
        val fell = synchronized(holds) {
            if (holds[key] != since) return
            holds.remove(key); holds.isEmpty()
        }
        if (fell) changed(false)
    }

    fun held(key: String): Boolean = synchronized(holds) { key in holds }

    /** Tests. */
    internal fun reset() { synchronized(holds) { holds.clear() }; _active.value = false; onStart = null; onChange = null }

    private fun changed(on: Boolean) {
        _active.value = on
        if (on) try { onStart?.invoke() } catch (_: Throwable) {}
        try { onChange?.invoke(on) } catch (_: Throwable) {}
    }
}
