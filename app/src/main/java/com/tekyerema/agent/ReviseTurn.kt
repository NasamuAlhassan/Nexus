package com.tekyerema.agent

import org.json.JSONObject

/**
 * A correction spoken at a read-back — "no, send it to Prince Melanin instead", "make it 2 cedis", "actually change that
 * to I will arrive at 7 PM", "on WhatsApp instead" — applied to the PENDING action's own arguments, exactly, from the
 * words. The corrected action is proposed again and confirmed again in full (AgentEngine's revise path).
 *
 * Why (A20e audit 2026-10-01, C09 / F08 / K01 — 3 of 3): the correction used to go back to the planner as a note; the
 * on-device rules then ended the task by reading that note aloud ("You proposed: … The user did NOT approve it …") or
 * by claiming "Opened Mobile Money", and nothing was re-proposed. One changed detail needs no model.
 *
 * Returns null whenever the words are not clearly one of these changes for this tool (a different request, an ambiguous
 * "change that to Kofi" on a message, a name for a tool that only takes a number): the reply then goes to the planner.
 * Pure → unit-tested. Digit-group fixes of a phone number stay with [NumberCorrection], which runs first.
 */
object ReviseTurn {

    /** [unchanged]: the reply restates what is already planned ("no, 10 cedis" when it is 10) — read it back again, nothing to change. */
    data class Revised(val args: JSONObject, val what: String, val unchanged: Boolean = false)

    /** tool → (name key, number key): tools that take a contact name or a number. */
    private val CONTACT_TOOLS = mapOf(
        "send_sms" to ("contact" to "number"), "make_call" to ("contact" to "number"),
        "mobile_money" to ("_recipient" to "_number"),
        // The navigator resolves a contact's name in its own preflight (finance audit 2026-10-01), so a changed person is
        // re-proposed here like a changed number — and read back again with the number it resolved to.
        "ussd_navigate" to ("contact" to "number")
    )
    /** tool → number key: tools that take a number only (a name there needs resolving — the planner's job). */
    private val NUMBER_ONLY = mapOf("momo_send" to "number")
    private val AMOUNT_KEYS = listOf("_amount", "amount")

    private val STOP = Regex("(?i)\\b(?:don'?t|do not|cancel|stop|never ?mind|forget it|leave it|abort)\\b")
    private val LEAD = "(?:(?:no|nope|nah|sorry|wait|actually|oh|please|i said|i mean|i meant)[,.!\\s]+)*"

    private val WORD_NUM = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "twenty" to "20", "fifty" to "50", "hundred" to "100")
    private const val NUM = "(\\d+(?:\\.\\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|twenty|fifty|hundred)"
    private val AMOUNT_UNIT = Regex("(?i)\\b$NUM\\s*(cedis?|ghs|gh₵|ghc|pesewas?)\\b")
    /** Nothing but a new amount: "no, 20 cities", "make it 20 cities instead", "it's 2 cds 50 ps". */
    private const val FIGURE = "(?:\\d+(?:[.,]\\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|" +
        "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred)"
    private val AMOUNT_ONLY = Regex("(?i)^$LEAD(?:(?:make|change) (?:it|that|the amount)(?: to)?\\s+|it'?s\\s+|it is\\s+)?" +
        "$FIGURE\\s+[\\p{L}']+(?:\\s+(?:and\\s+)?$FIGURE\\s+[\\p{L}']+)?(?:\\s+instead)?$")
    private val AMOUNT_BARE = Regex("(?i)^$LEAD(?:make|change) (?:it|that|the amount)(?: to)?\\s+$NUM(?:\\s+instead)?[.!]?$")

    private val TRAIL = Regex("(?i)[\\s,]+(?:instead|please|rather|not (?:him|her|them|that one)|thanks?|thank you)[.!]?$")
    private val CHANNEL = Regex("(?i)\\b(?:on|by|via|through|use|using|with|as)\\s+(?:an?\\s+)?(whatsapp|whats app|sms|text message|text|telegram|signal|messenger)\\b")
    private val RECIPIENT = listOf(
        Regex("(?i)^$LEAD(?:send|text|message|give|forward|pay|transfer|call|ring|phone)\\s+(?:it\\s+|that\\s+|this\\s+|the money\\s+|the message\\s+)?to\\s+(.+)$"),
        Regex("(?i)^$LEAD(?:it'?s for|it is for|make it to|it should (?:go|be) to|to)\\s+(.+)$"),
        Regex("(?i)^$LEAD(?:call|ring|phone|text|message)\\s+(.+?)\\s+instead[.!]?$"),
        Regex("(?i)^(?:(?:no|nope|nah|sorry|wait|actually|not (?:him|her|them))[,.!\\s]+)+(?:i meant|i mean|i said)?\\s*(.+)$")
    )
    private val MESSAGE = listOf(
        Regex("(?i)^$LEAD(?:change|make)\\s+(?:that|it|the message|the text|the words?)\\s+(?:to(?: say)?|say)\\s+(.+)$"),
        Regex("(?i)^$LEAD(?:the message|it) should (?:be|say|read)\\s+(.+)$"),
        Regex("(?i)^$LEAD(?:say|write|tell (?:him|her|them)(?: that)?)\\s+(.+)$")
    )
    /** One to three capitalised words and nothing else: "Prince Melanin", "Kofi" — a name, not a sentence. */
    private val NAME_LIKE = Regex("^(?:\\p{Lu}[\\p{L}'.-]*)(?:\\s+\\p{Lu}[\\p{L}'.-]*){0,2}$")
    private val HAS_DIGITS = Regex("\\d(?:[\\s-]?\\d){8,12}")

    fun patch(tool: String, args: JSONObject, reply: String): Revised? {
        // A correction is heard by the same recognisers as a request: "make it 20 pesos" is 20 PESEWAS, and at a payment's
        // confirmation "no, 20 cities" is 20 cedis (voice/GhanaWords). Before this, neither changed the amount.
        // "About money" only when the reply is nothing but an amount ("no, 20 cities", "make it 20 cities instead"): a
        // correction to the recipient or the reference ("the reference is 2 cds") must not become a new amount.
        val heard = reply.trim().trimEnd('.', '!')
        val said = com.tekyerema.voice.GhanaWords.fix(heard, aboutMoney = AMOUNT_KEYS.any { args.has(it) } && AMOUNT_ONLY.matches(heard))
        if (said.isEmpty() || said.length > 200 || STOP.containsMatchIn(said)) return null
        val out = JSONObject(args.toString())
        val changes = ArrayList<String>()
        var rest = said

        // --- the app / channel (texts only) ---
        if (tool == "send_sms") CHANNEL.find(rest)?.let { m ->
            when (m.groupValues[1].lowercase().replace(" ", "")) {
                "whatsapp" -> { out.put("via", "whatsapp"); changes.add("the app to WhatsApp") }
                "sms", "text", "textmessage" -> { out.remove("via"); changes.add("it to a text message") }
                else -> return null          // an app this tool can't send through → the planner
            }
            rest = (rest.substring(0, m.range.first) + rest.substring(m.range.last + 1)).trim().trimEnd(',')
        }

        // --- the amount (money tools) ---
        val amountKey = AMOUNT_KEYS.firstOrNull { args.has(it) }
        if (amountKey != null) {
            val m = AMOUNT_UNIT.find(rest)
            val bare = if (m == null) AMOUNT_BARE.find(rest) else null
            val (raw, unit) = when {
                m != null -> m.groupValues[1] to m.groupValues[2].lowercase()
                bare != null -> bare.groupValues[1] to "cedis"
                else -> null to ""
            }
            if (raw != null) {
                val n = (WORD_NUM[raw.lowercase()] ?: raw).toBigDecimalOrNull() ?: return null
                val cedis = if (unit.startsWith("pesewa")) n.movePointLeft(2) else n
                if (cedis.signum() <= 0) return null
                val plain = cedis.stripTrailingZeros().toPlainString()
                out.put(amountKey, plain)
                changes.add("the amount to ${spokenAmount(cedis)}")
                rest = if (m != null) (rest.substring(0, m.range.first) + rest.substring(m.range.last + 1)).trim() else ""
            }
        }

        // --- the words (tools with a message) ---
        var messageSet = false
        if (args.has("message")) for (rx in MESSAGE) {
            val m = rx.find(rest) ?: continue
            val words = m.groupValues[1].trim().replace(Regex("(?i)\\s+instead[.!]?$"), "").trim().trim('"', '“', '”')
            if (words.isEmpty()) return null
            // "change that to Prince Melanin" on a message could be the words or the person — never guess.
            if (rx === MESSAGE[0] && NAME_LIKE.matches(words)) return null
            out.put("message", words); changes.add("the message"); messageSet = true; rest = ""
            break
        }

        // --- the person / the number ---
        if (!messageSet && rest.isNotBlank()) {
            val who = RECIPIENT.withIndex().firstNotNullOfOrNull { (i, rx) ->
                val w = rx.find(rest)?.groupValues?.get(1)?.replace(TRAIL, "")?.trim()?.trim('"', '“', '”', ',') ?: return@firstNotNullOfOrNull null
                // The loosest form ("no, Prince Melanin") only counts when what follows is plainly a name or a number —
                // "no, that's wrong" is not a recipient.
                if (i == RECIPIENT.lastIndex && !NAME_LIKE.matches(w) && !HAS_DIGITS.containsMatchIn(w)) null else w
            }
            if (!who.isNullOrBlank()) {
                if (who.length > 40 || Regex("(?i)\\b(?:saying|that says|and|then|with the message)\\b").containsMatchIn(who)) return null
                val digits = HAS_DIGITS.find(who)?.value?.filter { it.isDigit() || it == '+' }
                val contact = CONTACT_TOOLS[tool]
                val numberOnly = NUMBER_ONLY[tool]
                when {
                    digits != null && contact != null -> { out.put(contact.second, digits); out.remove(contact.first); changes.add("the number") }
                    digits != null && numberOnly != null -> { out.put(numberOnly, digits); changes.add("the number") }
                    digits != null -> return null
                    contact != null -> { out.put(contact.first, who); out.remove(contact.second); changes.add("the person to $who") }
                    else -> return null       // a name for a number-only tool: the planner resolves it
                }
            } else if (changes.isEmpty()) return null
        }

        if (changes.isEmpty()) return null
        // The user repeated the correction already made (take A2, A20e, 21 Oct 2026: a second "daabi, sidi du" on the corrected
        // read-back went to the cloud planner, which re-ran the original request — and the amount went back to 5).
        if (out.toString() == args.toString()) return Revised(out, changes.joinToString(" and "), unchanged = true)
        return Revised(out, changes.joinToString(" and "))
    }

    private fun spokenAmount(cedis: java.math.BigDecimal): String {
        if (cedis < java.math.BigDecimal.ONE) return "${cedis.movePointRight(2).stripTrailingZeros().toPlainString()} pesewas"
        val p = cedis.stripTrailingZeros().toPlainString()
        return if (p == "1") "1 cedi" else "$p cedis"
    }
}
