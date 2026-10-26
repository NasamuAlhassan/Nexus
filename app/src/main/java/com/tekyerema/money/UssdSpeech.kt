package com.tekyerema.money

import com.tekyerema.voice.SpokenText

/**
 * A carrier's USSD reply as a blind user can FOLLOW it (owner, A20e 2026-09-30: "Your a/c is
 * GHC0.06Internet bundle:12068.27MB.Data Bonus: 0.00MB…" was read as one run-on line — "the person will not
 * understand"). The blob becomes items, one per breath: glued words are unstuck, each "Label: value" is its own short
 * sentence with a real pause after it (SpokenText marks → AndroidTts silences), a zero is said as "No data bonus"
 * instead of "zero point zero zero M B", big megabytes become gigabytes, and the carrier's shorthand is said as words
 * ("a/c" → account, "J4u" → Just 4 U). Nothing is dropped but bare greetings — an unrecognised line is still read,
 * just paced. Pure → unit-tested.
 */
object UssdSpeech {

    /** The reply, paced and in words. [intro] is spoken first ("The network says"). */
    fun forEar(reply: String, intro: String = "The network says"): String {
        val items = sameBalanceOnce(items(reply))
        if (items.isEmpty()) return "$intro.${SpokenText.PAUSE}${reply.trim()}"
        return "$intro.${SpokenText.PAUSE}" + items.joinToString(SpokenText.PAUSE.toString()) { spoken(it) }
    }

    private val KIND_BALANCE = Regex("(?i)^(current|available|actual|ledger)\\s+bal(?:ance)?\\s*[:,]?\\s*(.+)$")

    /** "Current Balance: GHS 5.83. Available Balance: GHS 5.83" said twice back to back (A20e, 26 Oct 2026) — when two adjacent
     *  balance lines carry the same figure they are one item: "Current and available balance: GHS 5.83". Different figures stay
     *  as they are. Pure → UssdSpeechTest. */
    internal fun sameBalanceOnce(items: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < items.size) {
            val a = KIND_BALANCE.find(items[i])
            val b = items.getOrNull(i + 1)?.let { KIND_BALANCE.find(it) }
            if (a != null && b != null && a.groupValues[1].lowercase() != b.groupValues[1].lowercase() &&
                a.groupValues[2].filter { it.isDigit() } == b.groupValues[2].filter { it.isDigit() } && a.groupValues[2].any { it.isDigit() }) {
                out.add("${a.groupValues[1].replaceFirstChar { it.uppercaseChar() }} and ${b.groupValues[1].lowercase()} balance: ${a.groupValues[2]}")
                i += 2
            } else { out.add(items[i]); i++ }
        }
        return out
    }

    /**
     * "What's my airtime balance?" answered as a sentence: the airtime figure first, then the data that is left — and
     * nothing else. The full reply ([forEar]) reads every line the carrier sends ("No data bonus. No BOR. No just 4 u.
     * Welcome to the new world."), and the owner asked the same question again in the middle of it (2 October 2026).
     * Null when the reply has no account figure — then the full reading is used.
     */
    fun airtimeBalance(reply: String): String? {
        val items = items(reply)
        val account = items.firstNotNullOfOrNull { ACCOUNT.find(it)?.groupValues?.get(1) } ?: return null
        val data = items.firstNotNullOfOrNull { i -> DATA.find(i)?.let { m -> m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }?.let { m.value.substringAfter(':').trim() } } }
        val line = "Your airtime balance is ${com.tekyerema.phonecare.MoneyAlerts.spokenCedis(account)}."
        return if (data == null) line else "$line${SpokenText.PAUSE}You also have ${bigMb(data).replace(Regex("\\bMB\\b"), "megabytes")} of data."
    }

    /** The Mobile Money balance in the network's answer after the PIN — the AVAILABLE one when both are shown — as the
     *  figure ("12.50"), or null when the answer has none (a wrong PIN, an error). */
    fun momoBalance(reply: String): String? {
        val all = MOMO_BALANCE.findAll(reply).toList()
        val m = all.firstOrNull { it.groupValues[1].isNotBlank() } ?: all.firstOrNull() ?: return null
        return m.groupValues[2].replace(",", "")
    }

    /**
     * The answer as ONE Twi sentence for a Twi conversation — "MTN se: wo Momo mu sika a aka yɛ sidi du." — the amount in
     * Twi words, so the lab's voice says all of it (one voice: owner, 4 October 2026). It was read in English by the
     * phone's voice in the middle of a Twi conversation (A20e, 9 October 2026). Null when no balance figure is in it — that
     * answer is read as the network wrote it. [owner: verify the Twi]
     */
    fun twiBalance(reply: String, network: String): String? {
        val words = momoBalance(reply)?.let { com.tekyerema.voice.understand.TwiSpeechText.cedis(it) } ?: return null
        return "$network se: wo Momo mu sika a aka yɛ $words."
    }

    private val MOMO_BALANCE = Regex("(?i)\\b(available\\s+)?bal(?:ance)?\\b(?:\\s+is)?\\s*:?\\s*(?:GH₵|GH¢|GHS|GHC|₵|¢)?\\s*" +
        "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)(?![\\d.,]*\\d)")

    private val ACCOUNT = Regex("(?i)\\b(?:a/c|account|airtime|main)\\b[^\\d]{0,24}?(\\d+(?:\\.\\d+)?)")
    private val DATA = Regex("(?i)\\b(?:internet|data)\\s+bundle\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*MB")

    /** The blob as items: glued words unstuck, then split at separators before words and where a new "Label:" begins. */
    internal fun items(reply: String): List<String> {
        var s = reply.trim()
        // Unstick "0.06Internet" (a value glued to the next label) and "GHC0.06" (a currency glued to its figure).
        s = s.replace(Regex("(?<=[a-z0-9%])(?=[A-Z])"), " ")
        s = s.replace(Regex("(?i)\\b(GH[CS]?[c₵¢]?)(?=\\d)"), "$1 ")
        val out = ArrayList<String>()
        // A separator followed by a letter ends an item ("…MB.Data Bonus", "…GHC,J4u"); a decimal point does not.
        for (part in s.split(Regex("\\s*[.,;]\\s*(?=[A-Za-z])"))) {
            val t = part.trim()
            if (t.isEmpty()) continue
            // A new "Label:" beginning mid-item starts its own ("… balance: GHc 3 Bundle: 63 MB" → two items).
            // A label is the one-to-four words right before a ':'.
            val cuts = LABEL.findAll(t).mapNotNull { it.groups[1]?.range?.first }.filter { it > 0 }
            val bounds = listOf(0) + cuts + listOf(t.length)
            for (i in 0 until bounds.size - 1) out.add(t.substring(bounds[i], bounds[i + 1]).trim())
        }
        return out.map { it.replace(Regex("(?i)^y'?ello\\s+"), "").trim().trimEnd('.', ',', ';', ':').trim() }
            .filter { it.isNotEmpty() && !GREETING.matches(it) }   // "Y'ello" between items is noise, not news
    }

    private val LABEL = Regex("(?:^|\\s)((?:[A-Za-z][\\w'/4]*\\s+){0,3}[A-Za-z][\\w'/4]*)\\s*:")

    /** One item as its short sentence: "No X" for a zero, gigabytes for big megabytes, words for the shorthand. */
    internal fun spoken(item: String): String {
        var t = bigMb(item)
        for ((abbr, word) in TERMS) t = t.replace(abbr, word)
        val label = t.substringBefore(':', "").trim()
        val value = t.substringAfter(':', "").trim()
        if (label.isNotEmpty() && value.isNotEmpty()) {
            if (ZERO.matches(value)) {
                // "Your bonus balance: 0" → "No bonus balance." — lowercased words, acronyms kept.
                val l = label.replace(Regex("(?i)^your\\s+"), "").split(' ').joinToString(" ") { w ->
                    if (w.length > 1 && w == w.uppercase()) w else w.replaceFirstChar { it.lowercase() }
                }
                return "No $l."
            }
            return "$label, $value."
        }
        return t.trim().let { if (it.endsWith(".") || it.endsWith("?") || it.endsWith("!")) it else "$it." }
    }

    /** "12068.27 MB" → "about 11.8 gigabytes" — a figure nobody holds in their head becomes one they can. */
    internal fun bigMb(value: String): String = MB.replace(value) { m ->
        val mb = m.groupValues[1].toDoubleOrNull() ?: return@replace m.value
        if (mb < 1024) m.value else "about " + (Math.round(mb / 1024 * 10) / 10.0).let {
            if (it == Math.floor(it)) it.toInt().toString() else it.toString()
        } + " gigabytes"
    }

    private val MB = Regex("(\\d+(?:\\.\\d+)?)\\s*MB\\b")
    private val ZERO = Regex("(?i)0(?:[.,]0+)?\\s*(?:MB|GB|megabytes|gigabytes|GHS|GHC|GH₵|cedis?|minutes?|mins?|sms)?\\s*\\.?")
    private val GREETING = Regex("(?i)(?:y'?ello|hello|hi|akwaaba)[.!]?")
    private val TERMS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\ba/c\\b") to "account",
        Regex("(?i)\\bacct\\b") to "account",
        Regex("(?i)\\bbal\\b") to "balance",
        Regex("(?i)\\bJ4u\\b") to "Just 4 U",
        Regex("(?i)^y'?ello\\s+") to "",
        Regex("(?i)\\bGH[C₵¢]\\b|\\bGhc\\b") to "GHS",   // one currency form, so the voice says cedis, not letters
        Regex("\\bMB\\b") to "megabytes",
        Regex("\\bGB\\b") to "gigabytes",
        Regex("(?i)\\bmins?\\b") to "minutes"
    )
}
