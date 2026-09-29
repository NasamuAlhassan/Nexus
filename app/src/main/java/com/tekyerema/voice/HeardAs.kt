package com.tekyerema.voice

import org.json.JSONObject

/**
 * Spoken doubt for names (2026-09-29). [EntityCorrector] rewrites a mis-heard name to a contact on the phone — the owner's
 * "Prince" came out "prints" every time (A20e, 2026-09-29). That's right far more often than not, but when the corrected
 * word is WHO a call, a message or money goes to, the user should hear the guess once, in the read-back they approve:
 * "I heard 'prints' — taking it as Prince. Call Prince on 0 2 4 …?". A blind user can't see the transcript; this is the
 * transcript's one uncertain word, said out loud where it matters.
 *
 * [note] is fed by LanguagePipeline (every recognised utterance); [line] is asked by the engine when it builds a
 * confirmation. Only for the same words the agent is working on, only while fresh, only once. Pure → HeardAsTest.
 */
object HeardAs {
    private class Note(val canonical: String, val fixes: List<Pair<String, String>>, val at: Long)

    @Volatile private var last: Note? = null

    /** Who each consequential tool sends to, call or pays (argument names). */
    private val RECIPIENT_ARGS = mapOf(
        "make_call" to listOf("contact"),
        "send_sms" to listOf("contact"),
        "reply_to_notification" to listOf("sender"),
        "mobile_money" to listOf("_recipient", "recipient")
    )

    /** An utterance was recognised as [canonical]; [fixes] are the names the corrector changed in it. An utterance with no
     *  contact fix that repeats the noted words means they were heard right this time — the old note goes. */
    @Synchronized
    fun note(canonical: String, fixes: List<EntityCorrector.Fix>, now: Long = System.currentTimeMillis()) {
        val contact = fixes.filter { it.kind == EntityCorrector.Kind.CONTACT && !it.from.equals(it.to, ignoreCase = true) }
        if (contact.isNotEmpty()) { last = Note(canonical.trim(), contact.map { it.from to it.to }, now); return }
        if (last?.canonical.equals(canonical.trim(), ignoreCase = true)) last = null
    }

    /**
     * "I heard 'prints' — taking it as Prince." when the goal being confirmed is the noted utterance and a corrected name is
     * the RECIPIENT of this [tool] call; null otherwise. Said once.
     */
    @Synchronized
    fun line(goal: String?, tool: String, args: JSONObject, now: Long = System.currentTimeMillis()): String? {
        val n = last ?: return null
        if (now - n.at > FRESH_MS) { last = null; return null }
        if (goal == null || !goal.trim().equals(n.canonical, ignoreCase = true)) return null
        val keys = RECIPIENT_ARGS[tool] ?: return null
        val who = keys.firstNotNullOfOrNull { k -> args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } } ?: return null
        val fix = n.fixes.firstOrNull { (_, to) -> names(who, to) } ?: return null
        last = null
        return "I heard '${fix.first}' — taking it as ${fix.second}."
    }

    fun clear() { last = null }

    private const val FRESH_MS = 120_000L

    /** The recipient is the corrected name ("Prince"), or a fuller form of it ("Prince Nasamu"), or the other way round. */
    private fun names(recipient: String, corrected: String): Boolean {
        val r = recipient.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val c = corrected.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (r.isEmpty() || c.isEmpty()) return false
        return r.containsAll(c) || c.containsAll(r)
    }
}
