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
        "mobile_money" to listOf("_recipient", "recipient"),
        "ussd_navigate" to listOf("contact"),
        "momo_send" to listOf("contact", "name")
    )

    /** A contact the resolver took on a LOOSE match (android/ContactMatcher, below "sure"): the words heard and who. */
    private class Guess(val heard: String, val name: String, val at: Long)

    @Volatile private var guess: Guess? = null

    /** The resolver matched [heard] to [name] only loosely ("rich man" → Richmond Apenensu) — said in the next read-back
     *  that names that person, so the user hears the guess before approving it (robustness layer, 5 October 2026). */
    @Synchronized
    fun noteGuess(heard: String, name: String, now: Long = System.currentTimeMillis()) {
        if (heard.isBlank() || name.isBlank() || heard.trim().equals(name.trim(), ignoreCase = true)) return
        guess = Guess(heard.trim(), name.trim(), now)
    }

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
        guessLine(tool, args, now)?.let { return it }
        val n = last ?: return null
        if (now - n.at > FRESH_MS) { last = null; return null }
        if (goal == null || !goal.trim().equals(n.canonical, ignoreCase = true)) return null
        val keys = RECIPIENT_ARGS[tool] ?: return null
        val who = keys.firstNotNullOfOrNull { k -> args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } } ?: return null
        val fix = n.fixes.firstOrNull { (_, to) -> names(who, to) } ?: return null
        last = null
        return "I heard '${fix.first}' — taking it as ${fix.second}."
    }

    private fun guessLine(tool: String, args: JSONObject, now: Long): String? {
        val g = guess ?: return null
        if (now - g.at > FRESH_MS) { guess = null; return null }
        val keys = RECIPIENT_ARGS[tool] ?: return null
        val who = keys.firstNotNullOfOrNull { k -> args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } } ?: return null
        if (!names(who, g.name) && !names(who, g.heard)) return null
        guess = null
        // The guess may stand on a name the corrector already changed ("Kwami" → Kwame → Osei Kwame): say what was HEARD,
        // and only once — the corrector's own note for this person goes with it.
        val earlier = last?.fixes?.firstOrNull { (_, to) -> to.equals(g.heard, ignoreCase = true) }
        if (earlier != null) last = null
        return "I heard '${earlier?.first ?: g.heard}' — taking it as ${g.name}."
    }

    fun clear() { last = null; money = null; guess = null }

    // ---------------------------------------------------------------- money and network words (robustness layer, 5 Oct 2026)

    /** A repair that changed WHAT is paid or bought: "10 pesos" → "10 pesewas" (a hundred times less), "20 cities" → "20
     *  cedis", "MTM" → "MTN". [amount] = the amount it now means, in cedis, to match against the tool's own argument. */
    private class MoneyNote(val heard: String, val taken: String, val amount: java.math.BigDecimal?, val at: Long)

    @Volatile private var money: MoneyNote? = null

    private val FIGURE_UNIT = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s+(cedis?|pesewas?)\\b")
    private val NETWORKS = setOf("MTN", "Telecel", "AirtelTigo")

    /**
     * The request as heard ([before]) and as it will be acted on ([after]); [fixes] = word repairs already known (the
     * network names). Notes the first change of a money unit (the figure with it) or of a network; clears an old note when
     * this utterance changed nothing.
     */
    @Synchronized
    fun noteWords(before: String, after: String, fixes: List<Pair<String, String>> = emptyList(), now: Long = System.currentTimeMillis()) {
        val b = before.split(" ").filter { it.isNotEmpty() }
        val a = after.split(" ").filter { it.isNotEmpty() }
        var note: MoneyNote? = null
        if (b.size == a.size) for (i in a.indices) {
            val cb = b[i].trim('.', ',', '!', '?'); val ca = a[i].trim('.', ',', '!', '?')
            if (cb.equals(ca, ignoreCase = true)) continue
            if (ca.lowercase().matches(Regex("cedis?|pesewas?")) && i > 0 && FIGURE_UNIT.matches("${a[i - 1].trim(',')} $ca")) {
                val taken = "${a[i - 1].trim(',')} $ca"
                note = MoneyNote("${b[i - 1].trim(',')} $cb", taken, amountOf(taken), now); break
            }
        }
        if (note == null) fixes.firstOrNull { it.second in NETWORKS && !it.first.equals(it.second, ignoreCase = true) }
            ?.let { note = MoneyNote(it.first, it.second, null, now) }
        if (note != null) money = note
        else if (before.isNotBlank()) money = null
    }

    private fun amountOf(phrase: String): java.math.BigDecimal? {
        val m = FIGURE_UNIT.find(phrase) ?: return null
        val v = m.groupValues[1].toBigDecimalOrNull() ?: return null
        return if (m.groupValues[2].lowercase().startsWith("pesewa")) v.movePointLeft(2) else v
    }

    /** Money tools and where their amount is. */
    private val AMOUNT_ARGS = mapOf("ussd_navigate" to listOf("amount"), "momo_send" to listOf("amount"),
        "mobile_money" to listOf("_amount", "amount"), "telco_ussd" to listOf("amount"))

    /**
     * "I heard '10 pesos' — taking it as 10 pesewas." when [tool] moves or buys the noted amount (or the noted network is
     * named in its arguments); null otherwise. Said once.
     */
    @Synchronized
    fun moneyLine(tool: String, args: JSONObject, now: Long = System.currentTimeMillis()): String? {
        val n = money ?: return null
        if (now - n.at > FRESH_MS) { money = null; return null }
        val keys = AMOUNT_ARGS[tool] ?: return null
        val matches = if (n.amount != null) keys.any { k ->
            args.optString(k).replace(",", "").trim().toBigDecimalOrNull()?.compareTo(n.amount) == 0
        } else args.toString().contains(n.taken, ignoreCase = true) || tool == "ussd_navigate" || tool == "telco_ussd"
        if (!matches) return null
        money = null
        return "I heard '${n.heard}' — taking it as ${n.taken}."
    }

    private const val FRESH_MS = 120_000L

    /** The recipient is the corrected name ("Prince"), or a fuller form of it ("Prince Nasamu"), or the other way round. */
    private fun names(recipient: String, corrected: String): Boolean {
        val r = recipient.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val c = corrected.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (r.isEmpty() || c.isEmpty()) return false
        return r.containsAll(c) || c.containsAll(r)
    }
}
