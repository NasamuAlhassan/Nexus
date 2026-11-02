package com.tekyerema.money.fraud

import android.util.Log
import com.tekyerema.core.config.AppConfig

/**
 * The spoken scam warning where a message is read aloud (notifications/MessageReading.item; 2 Nov 2026). Rules agreed
 * with the brain session for the money safety rules:
 *  1. warn-only, once per message, said BEFORE its words — what is read is never changed and nothing is blocked;
 *  2. the reason never quotes the message (no amount, no number): it names the kind of request ([ScamScore.Cue]);
 *  3. never on the network's own sender ids ("MobileMoney", "MTN MoMo"… — phonecare/MoneyAlerts' trusted list, plus
 *     `fraud.trustedSenders`): a false alarm on a real receipt is worse than a miss;
 *  4. the log keeps the cue name and the model's score, never the text.
 * Off with `fraud.warn.enabled=false`. The model is loaded once at start (StartupWarm); until then only the cues run.
 */
object ScamWarning {
    fun enabled(): Boolean = AppConfig.bool("fraud.warn.enabled", true)

    /** The line to say before [text] from [sender], or null. */
    fun before(sender: String?, text: String?): String? {
        if (!enabled() || text.isNullOrBlank()) return null
        if (trusted(sender)) return null
        val v = try { ScamScore.check(text, ScamScore.cached()) } catch (_: Throwable) { return null }
        if (!v.warn) return null
        try { Log.i("Tekyerema", "[FRAUD] warned cue=${v.cues.firstOrNull()?.name ?: "model"} score=${"%.2f".format(java.util.Locale.US, v.score)}") } catch (_: Throwable) {}
        return v.line
    }

    fun trusted(sender: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        if (com.tekyerema.phonecare.MoneyAlerts.isTrustedSender(sender)) return true
        val n = sender.lowercase().filter { it.isLetterOrDigit() }
        return AppConfig.stringList("fraud.trustedSenders", emptyList()).any { it.lowercase().filter { c -> c.isLetterOrDigit() } == n }
    }
}
