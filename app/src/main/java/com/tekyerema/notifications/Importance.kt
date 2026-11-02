package com.tekyerema.notifications

/**
 * Which notifications matter in "what did I miss" (MASTER_STATUS #2, 2 Nov 2026) — rules only, no model, so it runs
 * offline and says why. A message is important when it is:
 *  - from a saved contact, in a one-to-one chat ([Why.PERSON]);
 *  - from someone named by a relation ("Mum", "My wife", "Pastor Kofi") ([Why.RELATION]);
 *  - money that moved: a Mobile Money / bank sender, or "received / sent / credited / debited" with an amount ([Why.MONEY]);
 *  - about a time today or tomorrow, or an exam, appointment, meeting, interview, hospital visit, funeral ([Why.WHEN]).
 * Everything else is "others" — counted, never read out first. Pure (ImportanceTest in SpeechShieldNoisyTest's file).
 */
object Importance {
    enum class Why { MONEY, WHEN, RELATION, PERSON }

    private val RELATION = Regex("(?i)\\b(?:mum|mom|mummy|mommy|mother|maame|dad|daddy|father|papa|wife|husband|son|daughter|sister|" +
        "brother|auntie|aunty|uncle|grandma|grandpa|nana|pastor|reverend|rev|boss|helper|landlord|doctor|dr|teacher)\\b")
    private val AMOUNT = Regex("(?i)(?:gh₵|gh¢|ghs|ghc|₵)\\s?\\d|\\b\\d[\\d,.]*\\s?(?:cedis?|ghs|pesewas)\\b")
    private val MOVED = Regex("(?i)\\b(?:received|sent|credited|debited|transferred|paid|payment|withdrawn|withdrawal|cash ?out|deposited|reversal)\\b")
    private val SOON = Regex("(?i)\\b(?:today|tomorrow|tonight|this (?:morning|afternoon|evening))\\b")
    private val CLOCK = Regex("(?i)\\b(?:at|by|before|from)\\s+\\d{1,2}(?::\\d{2})?\\s?(?:am|pm|o'?clock)?\\b|\\b\\d{1,2}(?::\\d{2})?\\s?(?:am|pm)\\b|\\b\\d{1,2}:\\d{2}\\b")
    private val EVENT = Regex("(?i)\\b(?:exam|exams|appointment|meeting|interview|hospital|clinic|funeral|wedding|deadline|test|surgery|flight)\\b")

    /**
     * Why [m] is important, or null for "others". [isContact] says whether a sender name is a saved contact; a group chat
     * (its title isn't the sender) is never important for who wrote it, only for what it says.
     */
    fun of(m: IndexedMessage, isContact: (String) -> Boolean = { false }): Why? {
        val text = listOfNotNull(m.title, m.text).joinToString(" ")
        val sender = m.sender?.trim()?.takeIf { it.isNotEmpty() }
        val title = m.title?.trim()?.takeIf { it.isNotEmpty() && !it.equals(m.app, true) }
        val group = sender != null && title != null && !title.equals(sender, true)
        val who = sender ?: title
        // A MoMo sender's promotions are not money that moved: the words have to say it moved (or carry an amount).
        val moneyWords = MOVED.containsMatchIn(text)
        if ((who != null && com.tekyerema.phonecare.MoneyAlerts.isTrustedSender(who) && (moneyWords || AMOUNT.containsMatchIn(text))) ||
            (AMOUNT.containsMatchIn(text) && moneyWords)) return Why.MONEY
        if (EVENT.containsMatchIn(text) || (SOON.containsMatchIn(text) && CLOCK.containsMatchIn(text))) return Why.WHEN
        if (!group && who != null && RELATION.containsMatchIn(who)) return Why.RELATION
        if (!group && who != null && isContact(who)) return Why.PERSON
        return null
    }
}
