package com.tekyerema.money

/**
 * Demo steps 2–3 (owner, 14 Oct 2026): a message asks for money — "Hi, it's Kofi. Please send me 5 cedis for transport. My
 * MoMo number is 050 000 2222." — "read my message" reads it and notes the request (the amount, the number IN THE CHAT, the
 * name), and "send money to him" sends to it: the number is copied from the message, never guessed, and read back in full
 * with a "from the message" warning before anything moves. Remembered in memory only, for [ttlMs].
 */
object MoneyRequest {
    data class Req(val sender: String, val name: String, val number: String?, val amount: String?, val at: Long = System.currentTimeMillis())

    @Volatile private var last: Req? = null

    private fun ttlMs(): Long = try { com.tekyerema.core.config.AppConfig.long("money.request.ttlSeconds", 900L) * 1000L } catch (_: Throwable) { 900_000L }

    fun note(r: Req) { last = r }
    fun clear() { last = null }
    /** The request read out recently, or null. */
    fun fresh(now: Long = System.currentTimeMillis()): Req? = last?.takeIf { now - it.at in 0..ttlMs() }

    private val CUE = Regex("(?i)\\b(?:send|transfer|momo|mobile money|cedis?|ghs|ghc|pay|refund|borrow|loan|money)\\b|₵|¢")
    private val AMOUNT_BEFORE = Regex("(?i)(?:ghs|gh₵|ghc|gh¢|₵|¢)\\s*(\\d+(?:[.,]\\d{1,2})?)")
    private val AMOUNT_AFTER = Regex("(?i)(\\d+(?:[.,]\\d{1,2})?)\\s*(?:cedis?|ghs|gh₵|ghc|gh¢|₵)")
    private val PHONE = Regex("(?<!\\d)(?:\\+?233[\\s-]?|0)(?:\\d[\\s-]?){8}\\d(?!\\d)")
    private const val NAME = "([A-Z][\\p{L}'-]+(?:\\s+[A-Z][\\p{L}'-]+){0,2})"
    private val NAME_IS = Regex("(?i:\\b(?:my name is|name is|name:|registered (?:as|name)[: ]*))\\s*$NAME")
    private val TO_NAME = Regex("\\b(?:to|for)\\s+$NAME")
    private val NOT_A_NAME = setOf("Mobile", "Momo", "MoMo", "MTN", "Telecel", "AT", "Airtel", "Tigo", "Vodafone", "Me", "Transport", "The")

    /** The request in a message from [sender], or null when it asks for no money (no amount and no number). Pure. */
    fun parse(sender: String, text: String): Req? {
        if (!CUE.containsMatchIn(text)) return null
        val amount = (AMOUNT_BEFORE.find(text) ?: AMOUNT_AFTER.find(text))?.groupValues?.get(1)?.replace(',', '.')
            ?: try { MoneyAsk.amount(text.replace(PHONE, " ")) } catch (_: Throwable) { null }
        val number = PHONE.find(text)?.value?.let { com.tekyerema.agent.NumberSpeech.normalize(it) }?.takeIf { it.length == 10 && it.startsWith("0") }
        if (amount == null && number == null) return null
        val named = (NAME_IS.find(text) ?: TO_NAME.find(text))?.groupValues?.get(1)?.trim()?.takeUnless { n -> n.split(' ').first() in NOT_A_NAME }
        return Req(sender.trim(), named ?: sender.trim(), number, amount)
    }

    /** What the request asks for, as one sentence after the message is read: "It asks for 5 cedis, to 0 5 0, 0 0 0, 2 2 2 2 —
     *  a Telecel number." */
    fun summary(r: Req): String {
        val amt = r.amount?.let { com.tekyerema.tools.GhanaMobileMoney.cedisPhrase(it) } ?: "money"
        val num = r.number?.let { n ->
            com.tekyerema.agent.NumberSpeech.spoken(n) +
                (com.tekyerema.agent.NumberSpeech.networkKey(n)?.let {
                    " — " + com.tekyerema.agent.NumberSpeech.networkPhrase(com.tekyerema.agent.NumberSpeech.networkName(it)) } ?: "")
        }
        return "It asks for $amt" + (if (r.name != r.sender) " for ${r.name}" else "") + (num?.let { ", to $it" } ?: "") + "."
    }
}
