package com.tekyerema.companion

/**
 * The fixed, offline lines of companion mode: the openers that start a chat (instant, no model, no network), the small
 * lessons, and the notes about a spending switch. Pure and unit-tested (CompanionLinesTest). Rules for every line here:
 * short enough to say in one breath, nothing that sells or pushes spending, nothing a person could take as a promise,
 * ends with one easy question where it invites a reply.
 */
object CompanionLines {
    // ---------------------------------------------------------------- openers
    private val CHAT_OPENERS = listOf(
        "I'm all ears. How has your day been so far?",
        "Happy to chat. What's been on your mind today?",
        "Sure, let's talk. How are you feeling today?",
        "I'm here. What would you like to talk about?"
    )
    private val BORED_OPENERS = listOf(
        "Let's fix that. Shall I ask you a riddle, or do you want to tell me about your day?",
        "Okay, let's pass the time. Do you feel like a riddle, or a little gist?",
        "I'm glad to keep you company. Want a riddle, or shall we just talk?"
    )

    /** The opener for "let's talk" ([bored] = "I'm bored"). [pick] chooses among the wordings. */
    fun opener(bored: Boolean, pick: Int = 0): String {
        val l = if (bored) BORED_OPENERS else CHAT_OPENERS
        return l[pick.mod(l.size)]
    }

    /** Said after an opener when the chat cannot go on right now (offline, and no model on this phone). */
    const val OFFLINE_NOTE = " I'm offline at the moment, so I can only chat a little. I can still read you your day: just say “brief me”."

    // ---------------------------------------------------------------- lessons
    /** One small lesson: a heading kept for tests, and the words said (about 30 words at most). */
    data class Lesson(val topic: String, val text: String)

    val LESSONS: List<Lesson> = listOf(
        Lesson("scam",
            "Here's a safety tip. A real company or Mobile Money agent will never ask for your PIN or a code sent to your phone. If someone does, hang up and keep your PIN to yourself."),
        Lesson("scam2",
            "A common trick: someone sends money by mistake, then phones to ask you to send it back. Check your own balance and messages first, and never trust the call alone."),
        Lesson("budget",
            "A simple money habit: when money comes in, set a small part aside first, even one cedi in ten, then spend the rest. Small and steady beats big and rare."),
        Lesson("budget2",
            "Before a big spend, wait one night. If you still want it tomorrow, and you can afford it, then it is probably a good choice. Waiting costs nothing."),
        Lesson("emergency",
            "An emergency fund is money you keep aside only for surprises, like a hospital bill. Even a little each week adds up, and you won't have to borrow when trouble comes."),
        Lesson("susu",
            "Susu is saving together with a trusted collector. It helps you keep a habit, but only use one you know well, and ask for a record of what you paid."),
        Lesson("phone1",
            "A phone tip: you can say “what did I miss” and I will read your notifications in one go, so you don't have to swipe through each one."),
        Lesson("phone2",
            "A phone tip: say “where did I leave off” and I will tell you what we were doing, if something was interrupted."),
        Lesson("phone3",
            "A phone tip: speak clearly and not too fast, and hold the phone a little closer in a noisy place. It helps me hear you."),
        Lesson("word1",
            "A word for today: resilient. It means able to recover quickly after something hard. For example, our people have been resilient through many difficult times."),
        Lesson("word2",
            "A word for today: generous. It means happy to give or share. For example, she is generous with her time."),
        Lesson("word3",
            "Borrow and lend are opposites. You borrow from someone, and someone lends to you. I borrowed a pen, and she lent me the pen."),
        Lesson("sankofa",
            "An Akan proverb says it is not wrong to go back and fetch what you forgot. That is Sankofa: learn from the past, and it will help you move forward."),
        Lesson("history",
            "On the sixth of March, 1957, Ghana became the first country in sub-Saharan Africa to gain independence from colonial rule."),
        Lesson("stool",
            "The Golden Stool is the sacred symbol of the Asante nation. Tradition says it holds the soul of the people, which is why it is treated with great respect."),
        Lesson("water",
            "A health reminder: drinking water through the day helps you feel better. A little often is easier than a lot at once. If you feel unwell, a clinic can help."),
        Lesson("safety",
            "A safety tip: don't share a one-time code with anyone, even a friend. A code is only for you, because whoever holds it can act as you.")
    )

    /** The lesson for the [n]th request of a chat, starting from [start] (so a new chat doesn't begin with the same one). */
    fun lesson(n: Int, start: Int = 0): Lesson = LESSONS[(start + n).mod(LESSONS.size)]

    const val LESSON_ASK = " Would you like another one?"

    // ---------------------------------------------------------------- switches
    const val NOTES_OFF = "Okay, I won't mention your spending in your day brief. You can still ask me how you're doing with money any time."
    const val NOTES_ON = "Okay, I'll add a gentle note about your spending to your day brief. Say “stop the spending notes” to switch it off."
}
