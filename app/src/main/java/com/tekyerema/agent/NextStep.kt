package com.tekyerema.agent

import android.util.Log
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Confirmation

/**
 * The next step OFFERED, never taken (owner, 15 Oct 2026: "improve the whole idea"). A good assistant knows what usually
 * comes next and asks once:
 *  - after a payment to a person — "MTN confirms 5 cedis sent to Kofi Demo. Shall I text Kofi Demo that it's sent?" — or,
 *    when it didn't go through, "Shall I tell Kofi Demo it didn't go through?";
 *  - after money arrives from a saved person — "Kofi Demo sent you 20 cedis. Shall I text Kofi Demo to say thank you?".
 * A yes hands the request to the agent exactly as if the user had said it ("tell Kofi Demo I've sent it" — money/LastSend
 * turns that into the truth, sent or not), so the text is still READ BACK and confirmed before it goes: send_sms is always
 * confirmed. A no drops it; anything else drops it too and is handled as said ("now call him" still calls). One offer at a
 * time, for `agent.nextStep.ttlMs` (60 s). Switch: `agent.nextStep.enabled`.
 *
 * And the LATE YES: a read-back that ended with no clear answer (two silences — VoiceController), then a plain "yes"
 * within `agent.lateYes.windowMs` (30 s), starts the same request again — so its read-back is asked again. Nothing runs on
 * the late yes itself. (A slow reply after the window used to become a new, contextless turn: "Who should I send it to?" —
 * twin rehearsal en 29/30, 15 Oct 2026.)
 *
 * The Twi offers are marked for the owner's ear in demo-package/TWIN_NETWORK.md ("[owner: verify]").
 */
object NextStep {
    data class Offer(val goal: String, val english: String, val twi: String?, val at: Long, val kind: String)

    @Volatile private var pending: Offer? = null
    @Volatile private var late: Pair<String, Long>? = null

    fun enabled(): Boolean = try { AppConfig.bool("agent.nextStep.enabled", true) } catch (_: Throwable) { true }
    private fun ttlMs(): Long = try { AppConfig.long("agent.nextStep.ttlMs", 60_000L) } catch (_: Throwable) { 60_000L }
    private fun lateMs(): Long = try { AppConfig.long("agent.lateYes.windowMs", 30_000L) } catch (_: Throwable) { 30_000L }

    /** After a send to [name]: tell them it went — or that it didn't. Null without a person's name (a bare number). Pure. */
    fun afterSend(name: String?, failed: Boolean, now: Long): Offer? {
        val who = person(name) ?: return null
        return if (failed) Offer("tell $who I've sent it", "Shall I tell $who it didn't go through?",
            "Menka nkyerɛ $who sɛ sika no ankɔ?", now, "send-failed")
        else Offer("tell $who I've sent it", "Shall I text $who that it's sent?", "Menka nkyerɛ $who sɛ masoma?", now, "send")
    }

    /** Money received from [name] (a saved contact): a thank-you. [cedis] as it is said ("20 cedis"), or null. Pure. */
    fun afterReceived(name: String?, cedis: String?, now: Long): Offer? {
        val who = person(name) ?: return null
        val what = cedis?.let { " the $it" } ?: " the money"
        return Offer("tell $who thank you, I've received$what", "Shall I text $who to say thank you?",
            "Menka nkyerɛ $who sɛ meda no ase?", now, "thanks")
    }

    /** A text claims money arrived (from an ordinary number) or begs for "money sent by mistake" back: the warning says
     *  "check your balance" — so offer to check it, on the network's own menu (the PIN stays the user's). Pure. */
    fun afterScamWarning(now: Long): Offer =
        Offer("check my mobile money balance", "Shall I check your Mobile Money balance now?",
            "Menhwɛ wo Mobile Money mu sika seesei?", now, "check-balance")

    private fun person(name: String?): String? =
        name?.trim()?.takeIf { n -> n.length in 2..40 && n.any { it.isLetter() } && n.none { it.isDigit() } }

    fun offer(o: Offer) {
        pending = o
        Log.i("Tekyerema", "[NEXT_STEP] offered (${o.kind})")   // never the name
    }

    /** The offer still standing — taken: it answers this reply or no other. */
    fun take(now: Long = System.currentTimeMillis()): Offer? {
        val p = pending
        pending = null
        return p?.takeIf { now - it.at in 0..ttlMs() }
    }

    fun clear() { pending = null; late = null }

    /** A read-back for [goal] ended with no clear answer. */
    fun noteUnanswered(goal: String?, now: Long = System.currentTimeMillis()) {
        if (goal.isNullOrBlank()) return
        late = goal to now
    }

    /** The request a late yes asks again, when one is recent — taken. */
    fun takeLate(now: Long = System.currentTimeMillis()): String? {
        val l = late
        late = null
        return l?.takeIf { now - it.second in 0..lateMs() }?.first
    }

    /** Only "yes" / "no" (three words at most: "yes please", "aane", "no thanks") — longer words, and anything that starts
     *  like a command, are a new request: Confirmation reads "now call him" as a yes (to a call read-back that is right),
     *  but after "Shall I text Kofi?" it is the next request, not a yes to the text. Pure. */
    fun shortAnswer(text: String): Confirmation.Answer? {
        if (text.trim().split(Regex("\\s+")).count { it.isNotBlank() } > 3) return null
        if (com.tekyerema.voice.understand.AnswerOrNew.kindOf(text) != null) return null
        return when (val a = Confirmation.interpret(text)) {
            Confirmation.Answer.YES, Confirmation.Answer.NO -> a
            else -> null
        }
    }
}
