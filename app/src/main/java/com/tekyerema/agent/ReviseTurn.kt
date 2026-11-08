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
    // "3 Ghana cedis" (owner's live test, 22 Oct 2026: not read as an amount, so the cloud asked "How much?")
    private val AMOUNT_UNIT = Regex("(?i)\\b$NUM\\s*(?:ghana\\s+)?(cedis?|ghs|gh₵|ghc|pesewas?)\\b")
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
    /** A time-or-count word the message may contain once: "five", "6", "7:30", "ten o'clock". */
    private const val NUMBERISH = "(?:\\d{1,2}(?::\\d{2})?(?:\\s*(?:am|pm|a\\.m\\.|p\\.m\\.))?|one|two|three|four|five|six|seven|eight|nine|ten|" +
        "eleven|twelve|noon|midnight)(?:\\s+o'?clock)?"
    /** "Sorry, make it six" at a read-back whose message holds exactly one such word: that word changes, nothing else
     *  (A20e 8 Nov 2026: the correction re-planned the whole send in the Messages app instead). */
    private val MAKE_IT = Regex("(?i)^$LEAD(?:make|change)\\s+(?:it|that)\\s+(?:to\\s+)?($NUMBERISH)(?:\\s+instead)?[.!]?$")
    /** "change five to six", "replace five with six", "six, not five", "not five, six". */
    private val SWAP = Regex("(?i)^$LEAD(?:change|replace)\\s+([\\p{L}\\p{N}:']+)\\s+(?:to|with)\\s+([\\p{L}\\p{N}:']+)(?:\\s+instead)?[.!]?$")
    private val NOT_X_Y = Regex("(?i)^$LEAD(?:not\\s+([\\p{L}\\p{N}:']+)[,\\s]+([\\p{L}\\p{N}:']+)|([\\p{L}\\p{N}:']+)[,\\s]+not\\s+([\\p{L}\\p{N}:']+))[.!]?$")
    private val NUM_IN_MSG = Regex("(?i)(?<![\\p{L}\\p{N}'])$NUMBERISH(?![\\p{L}\\p{N}'])")

    private val MESSAGE = listOf(
        Regex("(?i)^$LEAD(?:change|make)\\s+(?:that|it|the message|the text|the words?)\\s+(?:to(?: say)?|say)\\s+(.+)$"),
        Regex("(?i)^$LEAD(?:the message|it) should (?:be|say|read)\\s+(.+)$"),
        Regex("(?i)^$LEAD(?:say|write|tell (?:him|her|them)(?: that)?)\\s+(.+)$")
    )
    /** "change the reference to food", "the reference is school fees", "…and make the reference 50 cedis" — the words after it are
     *  the new reference, as said (owner's live test, 22 Oct 2026: the reference change was dropped, or the whole reply re-planned). */
    private val REFERENCE = Regex("(?i)(?:^|[,\\s]+)(?:and\\s+)?(?:(?:please\\s+)?(?:change|set|make|put|use)\\s+)?(?:the\\s+)?" +
        "(?:reference|narration|description)(?:\\s+(?:to|is|should be|as|be|of))?[:\\s]+(.+)$")
    private val AIRTIME_FOR = Regex("(?i)\\bfor\\s+(?!me\\b|myself\\b|this phone\\b|my phone\\b|my number\\b)(.+)$")
    private val UNPARSED_TARGET = Regex("(?i)\\b(?:for|to)\\s+\\S+")
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

        // --- the reference (money tools): first, so its words ("50 cedis", "2 cds") are never read as an amount or a number ---
        if (args.has("reference") || tool == "ussd_navigate" || tool == "momo_send") REFERENCE.find(rest)?.let { m ->
            val ref = m.groupValues[1].replace(TRAIL, "").trim().trim('"', '“', '”').trimEnd('.', '!')
            if (ref.isEmpty() || ref.length > 60) return null
            // "…, the reference is fine / okay / correct" KEEPS the reference — it was changed to "fine" (audit, 22 Oct 2026)
            if (!Regex("(?i)^(?:fine|ok(?:ay)?|good|correct|right|(?:the )?same|no change)$").matches(ref)) {
                out.put("reference", ref); changes.add("the reference to “$ref”") }
            rest = rest.substring(0, m.range.first).trim().trimEnd(',').replace(Regex("(?i)\\s+(?:and also|and|also)$"), "").trim()
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

        // --- one word of the message ("make it six", "change five to six", "six, not five") ---
        var messageSet = false
        if (args.has("message") && amountKey == null && rest.isNotBlank()) {
            val msg = args.optString("message")
            fun edit(range: IntRange, new: String) {
                out.put("message", msg.replaceRange(range, new)); changes.add("the message"); messageSet = true; rest = ""
            }
            MAKE_IT.find(rest)?.let { m ->
                val hits = NUM_IN_MSG.findAll(msg).toList()
                if (hits.size != 1) return null            // no number, or several: which one? — the planner
                edit(hits[0].range, m.groupValues[1])
            }
            if (!messageSet) (SWAP.find(rest)?.let { it.groupValues[1] to it.groupValues[2] }
                ?: NOT_X_Y.find(rest)?.let { m -> if (m.groupValues[1].isNotEmpty()) m.groupValues[1] to m.groupValues[2] else m.groupValues[4] to m.groupValues[3] })
                ?.let { (old, new) ->
                    val at = Regex("(?i)(?<![\\p{L}\\p{N}'])${Regex.escape(old)}(?![\\p{L}\\p{N}'])").findAll(msg).toList()
                    if (at.size == 1) edit(at[0].range, new)
                }
        }
        if (!messageSet && args.has("message")) for (rx in MESSAGE) {
            val m = rx.find(rest) ?: continue
            val words = m.groupValues[1].trim().replace(Regex("(?i)\\s+instead[.!]?$"), "").trim().trim('"', '“', '”')
            if (words.isEmpty()) return null
            // "change that to Prince Melanin" on a message could be the words or the person — never guess.
            if (rx === MESSAGE[0] && NAME_LIKE.matches(words)) return null
            out.put("message", words); changes.add("the message"); messageSet = true; rest = ""
            break
        }

        // --- the person / the number ---
        // Airtime "for Loglo" names who it is for (owner's live test, 22 Oct 2026: "I said buy 2 cedis airtime for Loglo" was read
        // back unchanged, for this phone). Only for airtime: on a money send "for food" is the purpose, not a person.
        val airtime = args.optString("_kind") == "buy_airtime" || args.optString("intent").contains("airtime")
        if (!messageSet && rest.isNotBlank()) {
            val forWho = if (airtime) AIRTIME_FOR.find(rest)?.groupValues?.get(1)?.replace(TRAIL, "")?.trim()?.trim('"', '“', '”', ',') else null
            val who = forWho ?: RECIPIENT.withIndex().firstNotNullOfOrNull { (i, rx) ->
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
                // Airtime now for someone: the navigator's intent follows. BUY_AIRTIME skips the recipient, so "… for Loglo" was read
                // back "for this phone" again and bought there — the live path is ussd_navigate, not mobile_money (audit, 22 Oct 2026).
                if (tool == "ussd_navigate" && out.optString("intent") == "buy_airtime" &&
                    !Regex("(?i)^(?:me|myself|my (?:own )?(?:phone|number|line)|this (?:phone|number|line))$").matches(who)) out.put("intent", "send_airtime")
            } else if (changes.isEmpty()) return null
        }

        if (changes.isEmpty()) return null
        // Words left that name someone or something ("… for Loglo", "… to Kofi") were not understood here: the brain reads them —
        // never a read-back "unchanged" that ignores them (owner's live test, 22 Oct 2026).
        if (out.toString() == args.toString() && UNPARSED_TARGET.containsMatchIn(rest)) return null
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
