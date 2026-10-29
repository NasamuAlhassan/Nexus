package com.tekyerema.language

/**
 * Offline, a Twi request no exact pattern in [TwiCommands] took: a guess from the words the phone's own recogniser gets right — the
 * key words, not the whole sentence (owner, 29 Oct 2026: "the offline routers should understand these… keywords should help it guess
 * the message", after "kenkran nkran no a me yere so ma me no" was answered "needs the internet" four times).
 *
 * Only what can't do harm unasked is guessed: reading a message, the balance, the time — and a send of money with BOTH an amount and
 * a person, which still asks for the reference and is read back in full for a yes before anything moves. Never a call (a call goes
 * ahead when nobody answers its confirmation), never a message's words. Pure.
 */
object TwiKeywords {
    /** "kenkan" (read) as it is written: kenkran, kɛnkan, thinkan, kankan; "kan" (read); "hwɛ" (look at); "tie" (listen to). */
    private val READ = Regex("^(?:(?:k|th)[eai]nkr?a[nm]?|kan|hwe|tie)$")
    /** "nkra" (message): nkraa, nkoraa, nkran, nkrataa, krataa; "message" as said in Twi. */
    private val MESSAGE = Regex("^(?:n?ko?r(?:a{1,2}|ata{1,2})n?|message|messages|mesege|text)$")
    private val SEND = Regex("^(?:s[oua]\\s?ma+|mesoma|somaa)$")
    private val MONEY = Regex("^(?:sika|momo|s[ie]di|c[ei]dis?|sedi|pesewa|pesewas|mobile)$")
    private val HOW_MUCH = Regex("^(?:sen|aka|balance)$")
    private val TIME = Regex("^(?:bere|don|abo|mprempren|seesei)$")
    private val WHICH = Regex("^(?:sen|ben)$")
    private val GLUE = setOf("me", "ne", "no", "a", "na", "ma", "koma", "ko", "de", "so", "wo", "mu", "yi", "nyinaa", "sen", "ben")
    private val NUMBER = mapOf("baako" to 1, "biako" to 1, "bako" to 1, "bko" to 1, "mmienu" to 2, "abien" to 2, "mmiensa" to 3,
        "abiesa" to 3, "anan" to 4, "anum" to 5, "enum" to 5, "nsia" to 6, "nson" to 7, "nwotwe" to 8, "nkron" to 9, "du" to 10,
        "aduonu" to 20, "aduasa" to 30, "aduanan" to 40, "aduonum" to 50, "oha" to 100)
    /** "me yere" / "meyere" / "ne kunu" — a family word after "my" (as [TwiCommands.who] knows them). */
    private val KIN = setOf("yere", "kunu", "maame", "ena", "papa", "agya", "nua", "nuabarima", "nuabaa", "ba", "nana")

    fun guess(text: String, isContact: (String) -> Boolean = { false }): String? {
        val words = TwiText.fold(text).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 14) return null
        val person = personIn(text, words, isContact)
        val read = words.any { READ.matches(it) }
        val message = words.any { MESSAGE.matches(it) }
        val money = words.any { MONEY.matches(it) }
        // "… somaa me" / "so ma me" — sent TO ME: a message to read, not one to send
        val sentToMe = Regex("\\bs[oua]\\s?ma+\\s+me\\b").containsMatchIn(words.joinToString(" "))
        if (message && !money && (read || sentToMe || words.first().let { MESSAGE.matches(it) })) {
            return if (person != null && person != "me") "read the message $person sent" else if (read) "read my messages" else null
        }
        if (money) {
            val amount = amountIn(words)
            if (amount != null && person != null && person != "me" && words.any { SEND.matches(it) || it == "ma" || it == "koma" })
                return "send $amount to $person"
            if (amount == null && person == null && words.any { HOW_MUCH.matches(it) }) return "check my mobile money balance"
            return null
        }
        if (words.any { TIME.matches(it) } && words.any { WHICH.matches(it) } && person == null) return "what's the time"
        return null
    }

    /** The person: a family word after "me"/"ne" ("me yere" → my wife), glued or not, or a saved contact of one to three words. */
    private fun personIn(text: String, words: List<String>, isContact: (String) -> Boolean): String? {
        for ((i, w) in words.withIndex()) {
            val kin = when {
                (w == "me" || w == "ne") && i + 1 < words.size && words[i + 1] in KIN -> words[i + 1]
                w.length > 2 && (w.startsWith("me") || w.startsWith("ne")) && w.drop(2) in KIN -> w.drop(2)
                else -> null
            }
            if (kin != null) return TwiCommands.who("me $kin")
        }
        // a contact's name is never one of the key words ("kenkran" fuzzily close to a saved "Kenneth") nor a little Twi word
        val raw = text.replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun key(w: String) = TwiText.fold(w).let { f -> f in GLUE || listOf(READ, MESSAGE, SEND, MONEY, HOW_MUCH, TIME).any { it.matches(f) } || f.toIntOrNull() != null || f in NUMBER }
        for (n in 3 downTo 1) for (i in 0..raw.size - n) {
            if (raw.subList(i, i + n).any(::key)) continue
            val phrase = raw.subList(i, i + n).joinToString(" ")
            if (phrase.length >= 3 && isContact(phrase)) return phrase.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        }
        return null
    }

    /** "5 cedis" / "1 cedi" / "50 pesewas": a figure or a Twi number word, with the money word said. */
    private fun amountIn(words: List<String>): String? {
        val n = words.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { v -> v in 1..100_000 } ?: NUMBER[it] } ?: return null
        val pesewas = words.any { it.startsWith("pesewa") }
        return if (pesewas) "$n pesewa" + (if (n == 1) "" else "s") else "$n cedi" + (if (n == 1) "" else "s")
    }
}
