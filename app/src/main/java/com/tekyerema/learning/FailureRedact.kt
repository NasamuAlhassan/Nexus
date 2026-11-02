package com.tekyerema.learning

import com.tekyerema.core.Privacy
import com.tekyerema.core.Redact

/**
 * Learning from mistakes (MASTER_STATUS #25) — what may be KEPT of a request. Pure Kotlin, unit-tested (FailureCoreTest).
 *
 * A note about a task that went wrong is useful without the personal parts, so they are taken out BEFORE anything is
 * stored: PINs, passwords and one-time codes ([Privacy.maskCredentials], [Redact.secrets]); quoted text; e-mail
 * addresses; message bodies (everything after "tell / text / message / reply / say …"); amounts and balances ("20 cedis",
 * "fifty cedis" -> "an amount"); every digit run (phone and account numbers, times, counts -> "#"); the person after
 * "call / ring / text / find …" -> "<person>". A request that is about money, a PIN, a password or a code is not kept at all
 * ([sensitive]) — the money flow has its own safety rules and never needs to be learned from this way.
 * Over-redacting is the safe side: the note loses a word, never keeps a secret.
 */
object FailureRedact {
    const val PERSON = "<person>"
    const val AMOUNT = "an amount"

    private val QUOTED = Regex("[\"“”][^\"“”]{1,200}[\"“”]")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
    /** "tell Ama I'm late", "text him that …", "reply yes", "say hello": the verb stays, the rest is the message. */
    private val MSG_TAIL = Regex("(?i)\\b(tell|text|message|reply|write|say|saying|says|ask|dm)\\b(?!\\s+(?:me|us)\\b).*$")
    /** Four or more spoken digits in a row ("zero two four four …") are a number being dictated. */
    private val DIGIT_WORDS = Regex("(?i)(?:\\b(?:zero|oh|one|two|three|four|five|six|seven|eight|nine)\\b[\\s,.-]*){4,}")
    private val AMOUNT_WORDS = Regex("(?i)\\b(?:(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
        "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|and|a|half)" +
        "[\\s-]+)+(?:ghana\\s+)?(?:cedis?|ghs|pesewas?|dollars?|naira|pounds?)\\b")
    private val MONEY_NUM = Regex("(?i)(?:gh₵|₵|ghs|[\$£€])\\s*\\d[\\d,.]*|\\d[\\d,.]*\\s*(?:gh₵|₵|ghs|cedis?|pesewas?|dollars?|naira)\\b")
    private val DIGITS = Regex("\\d+(?:[\\s,.:-]\\d+)*")
    private val NAME_AFTER = Regex("(?i)\\b(call|ring|dial|phone|find|chat with|share with|send to|forward to|reply to)\\s+" +
        "([\\p{L}][\\p{L}'’-]*)(?:\\s+([\\p{L}][\\p{L}'’-]*))?")

    /** Words after a call verb that are not a person's name (relations, pronouns, places, apps, small words). */
    private val NOT_NAMES = setOf(
        "my", "the", "a", "an", "me", "him", "her", "them", "us", "you", "back", "again", "home", "someone", "somebody", "everyone",
        "emergency", "police", "ambulance", "helper", "voicemail", "up", "on", "in", "to", "using", "via", "with", "for", "from", "and",
        "then", "now", "please", "at", "by", "tomorrow", "today", "later", "tonight", "it", "this", "that", "mum", "mom", "mummy",
        "mother", "dad", "daddy", "father", "wife", "husband", "sister", "brother", "boss", "doctor", "friend", "pastor", "uncle",
        "aunt", "auntie", "aunty", "daughter", "son", "grandma", "grandpa", "whatsapp", "telegram", "messenger", "signal", "sms",
        "instagram", "facebook", "youtube", "spotify", "bolt", "uber", "chrome", "maps", "gmail", "camera", "photos", "contacts",
        "settings", "phone", "video", "voice", "number", "contact", "chat", "group"
    )

    /** A request that must never be kept: money, PINs, passwords, codes, balances, accounts, cards. Mirrors the memory layer's
     *  never-store words (MemoryCore) and adds the money flow's vocabulary. */
    private val SENSITIVE = Regex("(?i)\\b(pin|pins|password|passcode|passphrase|otp|cvv|cvc|secret|token|code|balance|account|card|momo|" +
        "mobile money|ussd|airtime|data bundle|loan|bank|transfer|money|pay|paying|payment|payments|cedis?|ghs|pesewas?|dollars?|naira)\\b|[₵\$£€]")

    /** True when [text] is about money or a secret and so is not kept at all. */
    fun sensitive(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return SENSITIVE.containsMatchIn(text) || Redact.looksSecret(text) || Privacy.maskCredentials(text) != text
    }

    /** [s] as it may be kept: no secrets, quotes, addresses, message bodies, amounts, numbers or names (see the class comment). */
    fun text(s: String, max: Int = 120): String {
        var t = Privacy.maskCredentials(s)
        t = Redact.secrets(t) ?: t
        t = QUOTED.replace(t, "“…”")
        t = EMAIL.replace(t, "…@…")
        t = MSG_TAIL.replace(t) { m -> m.groupValues[1].lowercase() + " …" }
        t = DIGIT_WORDS.replace(t, "# ")
        t = AMOUNT_WORDS.replace(t, AMOUNT)
        t = MONEY_NUM.replace(t, AMOUNT)
        t = DIGITS.replace(t, "#")
        t = maskPeople(t)
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.take(max).trimEnd() else t
    }

    private fun maskPeople(t: String): String = NAME_AFTER.replace(t) { m ->
        val verb = m.groupValues[1]
        val w1 = m.groupValues[2]
        val w2 = m.groupValues[3]
        if (w1.lowercase() in NOT_NAMES) return@replace m.value
        // A second word that is not a small word is a surname ("Kofi Mensah"), also when speech recognition lowercased it.
        val surname = w2.isNotEmpty() && w2.lowercase() !in NOT_NAMES
        verb + " " + PERSON + (if (w2.isNotEmpty() && !surname) " $w2" else "")
    }

    private val LABEL_OK = Regex("^[\\p{L}\\p{N} .,'’&+!?/-]+$")
    private val DIGIT_RUN = Regex("\\d{3,}")

    /**
     * A button / row / app label as it may be kept, or "" to keep none: short (the labels of controls, not message previews),
     * plain characters only (it is read back to the on-phone planner in a hint), no secrets, no long numbers, and nothing
     * [personal] says is a person (the glue passes a contacts check, so a chat row "Ama" is not kept).
     */
    fun label(raw: String?, personal: (String) -> Boolean = { false }, maxLen: Int = 32, maxWords: Int = 4): String {
        val t = raw.orEmpty().replace(Regex("[\\p{Cntrl}\"“”]"), " ").replace(Regex("\\s+"), " ").trim().trim('.', ':', ',', ' ')
        if (t.isEmpty() || t.length > maxLen || t.split(' ').size > maxWords || '@' in t) return ""
        if (DIGIT_RUN.containsMatchIn(t) || sensitive(t) || !LABEL_OK.matches(t)) return ""
        return try { if (personal(t)) "" else t } catch (_: Throwable) { "" }
    }
}
