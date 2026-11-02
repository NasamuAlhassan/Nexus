package com.tekyerema.core

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * P3-02 round 3 (13 October 2026): the heavy singletons — hundreds of regexes in the fast-intent classifier, the target
 * semantics, the Ghana-words fixer, the Twi commands, the tool manifest… — used to initialise on the MAIN thread the first time
 * start-up touched them (the `fastRouter` stage alone was 1.3 s of a 9.7-s cold start after an install). Class initialisation
 * is thread-safe, so a background thread started right after the config is loaded does that work while the main thread goes
 * on with the rest; whatever the main thread reaches first it simply waits for instead of doing itself. The history store and
 * the launchable-app list (a PackageManager query the first task would make) are loaded here too. Never required: every item
 * is a no-op when it has already happened, and a failure here changes nothing.
 */
object StartupWarm {
    fun start(app: Context) {
        val t = Thread({
            val t0 = SystemClock.uptimeMillis()
            val marks = StringBuilder()
            fun warm(name: String, block: () -> Unit) {
                val s = SystemClock.uptimeMillis()
                try { block() } catch (_: Throwable) {}
                marks.append(name).append('=').append(SystemClock.uptimeMillis() - s).append(' ')
            }
            warm("history") { com.tekyerema.history.HistoryStore.init(app) }
            warm("fastIntent") { com.tekyerema.agent.FastIntentClassifier.hashCode() }
            warm("semantics") { com.tekyerema.policy.TargetSemantics.hashCode() }
            warm("ghanaWords") { com.tekyerema.voice.GhanaWords.hashCode() }
            warm("twi") { com.tekyerema.language.TwiCommands.hashCode() }
            warm("verbalize") { com.tekyerema.voice.Verbalize.hashCode(); com.tekyerema.voice.understand.SpokenNumbers.hashCode(); com.tekyerema.agent.NumberSpeech.hashCode() }
            warm("tools") { com.tekyerema.tools.ToolManifest.hashCode(); com.tekyerema.tools.Grounding.hashCode() }
            warm("fraud") { com.tekyerema.money.fraud.ScamScore.model(app) }
            warm("money") { com.tekyerema.money.MoneyAsk.hashCode(); com.tekyerema.money.BankUssd.hashCode(); com.tekyerema.money.TelcoUssd.hashCode(); com.tekyerema.money.ussd.MenuBrain.hashCode() }
            Log.i("Tekyerema", "[STARTUP] warm ${SystemClock.uptimeMillis() - t0} ms: $marks")
            // The launchable-app list (a PackageManager query: 3.4 s on the emulator) is what the FIRST request pays for —
            // fetched here only once start-up is well over, so it never competes with it for the two cores.
            try { Thread.sleep(8_000) } catch (_: InterruptedException) { return@Thread }
            val s = SystemClock.uptimeMillis()
            try { com.tekyerema.android.AppResolver.launchable(app) } catch (_: Throwable) {}
            Log.i("Tekyerema", "[STARTUP] warm apps=${SystemClock.uptimeMillis() - s} ms (deferred)")
        }, "tekyerema-warm")
        // Normal priority, not lower: the main thread may block on a class this thread is initialising (class-init locks
        // do not boost the holder), and a slow holder on a two-core device turns into an input-dispatch ANR.
        t.priority = Thread.NORM_PRIORITY
        t.isDaemon = true
        t.start()
    }
}
