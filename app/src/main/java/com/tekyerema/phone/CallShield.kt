package com.tekyerema.phone

import android.content.Context
import android.util.Log
import com.tekyerema.core.config.AppConfig

/**
 * Call shield (MASTER_STATUS #36, 2 Nov 2026). The commonest Mobile Money scam in Ghana is a caller talking the victim
 * through a send ("I sent it to you by mistake…", "this is MTN, your account…"). Two small, warn-only steps:
 *  1. ringing: a number that is not a contact, not in the call log and never seen ringing before gets
 *     "Unknown number, first time." added to the announced-call line ([IncomingCall]);
 *  2. after such a call was ANSWERED and has ended: one question, once — "Did they ask you to send money or share a
 *     code?" A yes gets the scam reason ([WARN]) and nothing else; a no gets "Okay." No action, ever.
 * Never during quiet hours or while Nexus is busy (a task, a prompt, the PIN step). Numbers are never stored: only salted
 * SHA-256 hashes of their last nine digits (like money/KnownRecipients), and the log keeps no number.
 * `phone.callShield.enabled` (default true). The decisions are pure (CallShieldTest in ScreenSummaryTest's file).
 */
object CallShield {
    private const val TAG = "Tekyerema"
    private const val PREFS = "nexus_call_shield"
    private const val K_SALT = "salt"
    private const val K_SEEN = "seen"
    private const val MAX_SEEN = 2000
    private const val ANSWER_WINDOW_MS = 90_000L

    const val FIRST_TIME = " Unknown number, first time."
    const val QUESTION = "That was a number you'd never spoken to before. Did they ask you to send money or share a code?"
    const val WARN = "Careful: that's how most phone scams work. Don't send money or share a PIN or code with a caller you " +
        "don't know. If they said they're from MTN or your bank, hang up and call the official number yourself."
    const val NO = "Okay."

    fun enabled(): Boolean = AppConfig.bool("phone.callShield.enabled", true)

    /** Pure: the first time this caller rings — not a contact, not in the call log, never seen ringing before. */
    fun isFirstTime(isContact: Boolean, seenBefore: Boolean, inCallLog: Boolean): Boolean = !isContact && !seenBefore && !inCallLog

    /** Pure: ask after the call only when it was flagged and answered, and the moment is right. */
    fun shouldAsk(flagged: Boolean, answered: Boolean, quiet: Boolean, busy: Boolean): Boolean = flagged && answered && !quiet && !busy

    /** Pure: the reply to the user's answer while the question is open; null = not an answer (the turn goes on as usual). */
    fun reply(answer: com.tekyerema.voice.Confirmation.Answer): String? = when (answer) {
        com.tekyerema.voice.Confirmation.Answer.YES -> WARN
        com.tekyerema.voice.Confirmation.Answer.NO -> NO
        else -> null
    }

    /** Pure: the salted hash of a number's last nine digits (the same number in 0XX… or +233XX… form), or null. */
    fun hash(salt: String, number: String?): String? {
        val d = number?.filter { it.isDigit() }?.takeLast(9) ?: return null
        if (d.length < 7) return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest((salt + d).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ the call in progress (IncomingCall's states)

    @Volatile private var flagged = false
    @Volatile private var answered = false
    @Volatile private var askedAt = 0L
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Ringing from [number] that is not a contact: true when the announcement should add [FIRST_TIME]. */
    fun onRinging(ctx: Context, number: String, isContact: Boolean): Boolean {
        flagged = false; answered = false
        if (!enabled()) return false
        val seen = try { seen(ctx, number) } catch (_: Throwable) { true }   // unsure = don't alarm
        val first = isFirstTime(isContact, seen, inCallLog(ctx, number))
        try { remember(ctx, number) } catch (_: Throwable) {}
        flagged = first
        Log.i(TAG, "[CALL] shield: ${if (first) "first-time unknown caller" else "known or seen before"}")
        return first
    }

    fun onOffHook() { if (flagged) answered = true }

    // ------------------------------------------------------------------ fraud step 2: a call, then money (MASTER_STATUS #13)
    // The scam pattern: an unknown caller talks, then a "sent to you by mistake" SMS or a money flow follows. For
    // [CALL_WINDOW_MS] after an ANSWERED first-time unknown call, ScamWarning adds [CALL_THEN_MONEY] before a money message,
    // and a money flow can ask [recentUnknownCall]. Only the time is kept — never the number.
    const val CALL_THEN_MONEY = "Careful: an unknown number called you just now, and now there's a money message. That's how " +
        "the commonest MoMo scam works. Check your balance with MTN yourself before you send anything back."
    private const val CALL_WINDOW_MS = 30 * 60_000L
    @Volatile private var unknownCallEndedAt = 0L

    /** Pure: is [now] within the window after an answered first-time unknown call that ended at [endedAt]? */
    fun withinCallWindow(endedAt: Long, now: Long, windowMs: Long = AppConfig.long("phone.callShield.moneyWindowMs", CALL_WINDOW_MS)): Boolean =
        endedAt > 0L && now >= endedAt && now - endedAt <= windowMs

    /** True for a while after an answered call from a first-time unknown number (money flows may warn on it). */
    fun recentUnknownCall(now: Long = System.currentTimeMillis()): Boolean = enabled() && withinCallWindow(unknownCallEndedAt, now)

    internal fun callEndedForTest(at: Long) { unknownCallEndedAt = at }

    fun onIdle(ctx: Context) {
        if (flagged && answered) unknownCallEndedAt = System.currentTimeMillis()
        val ask = shouldAsk(flagged, answered,
            quiet = try { com.tekyerema.agent.triggers.QuietHours.isQuiet(System.currentTimeMillis()) } catch (_: Throwable) { false },
            busy = !com.tekyerema.voice.VoiceEngine.canOffer())
        flagged = false; answered = false
        if (!ask) return
        // A moment after the call: the dialler closes and the audio comes back to us.
        main.postDelayed({
            if (!com.tekyerema.voice.VoiceEngine.canOffer()) return@postDelayed
            askedAt = System.currentTimeMillis()
            Log.i(TAG, "[CALL] shield: asked after a first-time call")
            com.tekyerema.voice.VoiceEngine.announce(QUESTION, listenAfter = true)
        }, 1_500)
    }

    /** The user's words while the question is open: the line to say, or null (not an answer — the turn goes on). */
    fun answer(text: String, now: Long = System.currentTimeMillis()): String? {
        if (askedAt == 0L || now - askedAt > ANSWER_WINDOW_MS) return null
        val line = reply(com.tekyerema.voice.Confirmation.interpret(text)) ?: return null
        askedAt = 0L
        Log.i(TAG, "[CALL] shield: answered ${if (line == WARN) "yes — warned" else "no"}")
        return line
    }

    /** For tests: open the question as if it had just been asked. */
    internal fun openForTest(now: Long) { askedAt = now }

    // ------------------------------------------------------------------ storage (hashes only)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun salt(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(K_SALT, null)?.let { return it }
        val s = java.util.UUID.randomUUID().toString()
        p.edit().putString(K_SALT, s).apply()
        return s
    }

    private fun seen(ctx: Context, number: String): Boolean {
        val h = hash(salt(ctx), number) ?: return true
        return prefs(ctx).getString(K_SEEN, "")!!.split(',').contains(h)
    }

    private fun remember(ctx: Context, number: String) {
        val h = hash(salt(ctx), number) ?: return
        val list = prefs(ctx).getString(K_SEEN, "")!!.split(',').filter { it.length == 64 && it != h }.takeLast(MAX_SEEN - 1) + h
        prefs(ctx).edit().putString(K_SEEN, list.joinToString(",")).apply()
    }

    /** Any earlier call with this number (in or out) in the phone's call log; false without the permission. */
    private fun inCallLog(ctx: Context, number: String): Boolean = try {
        val last9 = number.filter { it.isDigit() }.takeLast(9)
        if (last9.length < 7) false
        else ctx.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, arrayOf(android.provider.CallLog.Calls.NUMBER),
            null, null, android.provider.CallLog.Calls.DEFAULT_SORT_ORDER)?.use { c ->
            var found = false; var n = 0
            while (!found && n < 1000 && c.moveToNext()) { n++; found = c.getString(0).orEmpty().filter { it.isDigit() }.endsWith(last9) }
            found
        } ?: false
    } catch (_: Throwable) { false }
}
