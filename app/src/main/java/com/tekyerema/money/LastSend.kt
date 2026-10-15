package com.tekyerema.money

/**
 * Demo step 5 (owner, 14 Oct 2026): after a send, "tell him I've sent it" texts the person what was sent — "I've sent you 5
 * cedis by Mobile Money. Reference: transport." — instead of the bare "I've sent it". Noted when an approved send starts its
 * walk; in memory only, for [maxAgeMs]. The text is still read back and confirmed before it goes (send_sms is confirmed).
 */
object LastSend {
    data class Sent(val name: String?, val number: String?, val amount: String?, val reference: String?, val at: Long,
                    val failed: Boolean = false)

    @Volatile private var last: Sent? = null

    fun note(name: String?, number: String?, amount: String?, reference: String?) {
        last = Sent(name?.trim()?.ifEmpty { null }, number, amount, reference?.trim()?.ifEmpty { null }, System.currentTimeMillis())
    }
    /** The network said the payment didn't go through (wrong PIN, not enough funds, declined): from now on "tell him I've
     *  sent it" never says it was sent (rehearsal, 15 Oct 2026 — it would have texted "I've sent you 5 cedis" after a
     *  wrong PIN). */
    fun markFailed() { last = last?.copy(failed = true) }
    fun clear() { last = null }

    private fun maxAgeMs(): Long = try { com.tekyerema.core.config.AppConfig.long("money.lastSend.maxAgeSeconds", 1800L) * 1000L } catch (_: Throwable) { 1_800_000L }

    fun recent(now: Long = System.currentTimeMillis()): Sent? = last?.takeIf { now - it.at in 0..maxAgeMs() }

    private val SENT_IT = Regex("(?i)^(?:that\\s+)?(?:i(?:'ve|’ve| have)?\\s+(?:already\\s+|just\\s+)?sent\\s+(?:it|the money|you the money|the cash|it to you|you it)" +
        "(?:\\s+(?:already|now|to you))?|it(?:'s|’s| is| has been)\\s+sent|i\\s+sent\\s+it|(?:the )?money (?:is|has been) sent|done)\\s*[.!]*$")

    /** The words to text [to] in place of a bare "I've sent it", or null when [said] isn't that (or nothing was sent lately). */
    fun expand(said: String, to: String?, now: Long = System.currentTimeMillis()): String? {
        if (!SENT_IT.matches(said.trim())) return null
        val s = recent(now) ?: return null
        val amt = s.amount?.let { com.tekyerema.tools.GhanaMobileMoney.cedisPhrase(it) } ?: return null
        val toThem = to == null || s.name == null || s.name.contains(to.trim(), true) || to.trim().contains(s.name, true)
        val who = if (toThem) "you" else "to ${s.name}"
        // The truth instead of the claim — and the user hears it in the read-back before anything is sent.
        if (s.failed) return if (toThem) "I tried to send you $amt by Mobile Money, but it didn't go through."
            else "I tried to send $amt $who by Mobile Money, but it didn't go through."
        val sent = if (toThem) "I've sent you $amt by Mobile Money." else "I've sent $amt $who by Mobile Money."
        return sent + (s.reference?.takeIf { !it.equals("Payment", true) }?.let { " Reference: $it." } ?: "")
    }
}
