package com.tekyerema.agent

/**
 * "Spell accommodation" / "how do you spell necessary" — the word read out letter by letter, on the phone. A blind user
 * writing a message or a form can't glance at the word; blind voice-assistant users name spelling as an everyday use
 * (Abdolrahmani et al., "Siri Talks at You", ASSETS 2018). No model is needed: the recogniser already wrote the word.
 *
 * Words that SOUND the same are the catch — the recogniser picks one of "their / there / they're" at random — so for
 * the common sound-alikes the other spellings are read too, each with what it means ("If you meant belonging to them:
 * their, T, H, E, I, R."). Only plain English words (a–z, apostrophe, hyphen; at most two words) are spelled: "spell
 * it", "spell my name", "spell check", a Twi word with ɛ/ɔ, or a number all go on to the model. Pure, unit-tested.
 */
object Spelling {
    private val RE_SPELL = listOf(
        Regex("^(?:(?:please|can you|could you|would you|will you|help me|kindly)\\s+)*spell(?: out)?(?: for me)? (?:the word |the words )?(.+?)(?: for me)?$"),
        Regex("^how (?:do|does|would|should|can|will) (?:you|i|we|one|someone) spell (?:the word )?(.+)$"),
        Regex("^how to spell (?:the word )?(.+)$"),
        Regex("^how (?:is|do you) (?:the word )?(.+?) spel(?:led|t)$"),
        Regex("^what(?:'s| is) the spelling (?:of|for) (?:the word )?(.+)$"),
        Regex("^(?:the )?spelling (?:of|for) (?:the word )?(.+)$")
    )
    private val WORD = Regex("^[a-z]+(?:['-][a-z]+)*$")
    private val SPACES = Regex("\\s+")
    private val PLEASE = Regex(" please$")
    /** "Spell it / that / this", "spell my name": about something said before — the model's, with the conversation. */
    private val CONTEXT_ONLY = setOf("it", "that", "this", "these", "those", "them", "him", "me", "us", "again", "out")
    private val CONTEXT_WORDS = setOf("name", "names", "word", "words", "number", "message", "text", "address", "email", "check", "checker",
        "checking", "please")
    /** "Spell necessary in Twi", "spell it for Ama" — a second request, not a two-word phrase. */
    private val LINK_WORDS = setOf("in", "into", "for", "to", "of", "with", "and", "or")
    private val POSSESSIVE_LEAD = setOf("my", "your", "his", "her", "their", "our", "its", "the", "that", "this", "a", "an")

    /** Sound-alikes: each spelling with what it means. The first group that holds the word is read. */
    private val SOUND_ALIKES: List<List<Pair<String, String>>> = listOf(
        listOf("there" to "over there", "their" to "belonging to them", "they're" to "they are"),
        listOf("to" to "going to a place", "too" to "also, or too much", "two" to "the number 2"),
        listOf("your" to "belonging to you", "you're" to "you are"),
        listOf("its" to "belonging to it", "it's" to "it is"),
        listOf("here" to "this place", "hear" to "with your ears"),
        listOf("where" to "which place", "wear" to "to put on clothes"),
        listOf("no" to "the opposite of yes", "know" to "to be sure of something"),
        listOf("right" to "correct, or the opposite of left", "write" to "to put words on paper"),
        listOf("weather" to "rain or sunshine", "whether" to "if"),
        listOf("buy" to "to pay for something", "by" to "near, or done by", "bye" to "goodbye"),
        listOf("see" to "with your eyes", "sea" to "the ocean"),
        listOf("peace" to "calm, no fighting", "piece" to "a part of something"),
        listOf("whole" to "all of it", "hole" to "an opening"),
        listOf("new" to "not old", "knew" to "the past of know"),
        listOf("week" to "seven days", "weak" to "not strong"),
        listOf("meet" to "to come together", "meat" to "food from animals"),
        listOf("son" to "a male child", "sun" to "in the sky"),
        listOf("hour" to "sixty minutes", "our" to "belonging to us"),
        listOf("flower" to "a plant", "flour" to "for baking"),
        listOf("morning" to "the early part of the day", "mourning" to "grieving someone who died"),
        listOf("principal" to "the head of a school, or main", "principle" to "a rule or belief"),
        listOf("stationary" to "not moving", "stationery" to "paper, pens and envelopes"),
        listOf("break" to "to smash, or a rest", "brake" to "to stop a car"),
        listOf("mail" to "letters and post", "male" to "a man or boy"),
        listOf("sale" to "selling, or low prices", "sail" to "on a boat"),
        listOf("steal" to "to take what isn't yours", "steel" to "the metal")
    )

    /** The spoken spelling, or null when [g] (lower-case) isn't a request to spell a plain word. */
    fun answer(g: String): String? {
        val s = g.lowercase().replace('’', '\'').trim().trimEnd('?', '.', '!').trim().replace(SPACES, " ").replace(PLEASE, "")
        val raw = RE_SPELL.firstNotNullOfOrNull { it.matchEntire(s) }?.groupValues?.get(1) ?: return null
        val phrase = raw.trim().trim('"', '\'', '“', '”', '‘', '’').trim()
        val words = phrase.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 2 || words.any { !WORD.matches(it) || it.length > 30 }) return null
        if (words.size == 1 && words[0] in CONTEXT_ONLY) return null
        if (words.size == 2 && (words[0] in POSSESSIVE_LEAD || words[0] in CONTEXT_ONLY || words.any { it in LINK_WORDS })) return null
        if (words.any { it in CONTEXT_WORDS }) return null
        val word = words.joinToString(" ")
        val main = "“${cap(word)}” is spelled ${letters(word)}."
        if (words.size > 1) return main
        val group = SOUND_ALIKES.firstOrNull { gr -> gr.any { it.first == word } } ?: return main
        val meaning = group.first { it.first == word }.second
        val others = group.filter { it.first != word }.joinToString(" ") { (w, m) -> "If you meant $m: $w, ${letters(w)}." }
        return "“${cap(word)}”, as in $meaning, is spelled ${letters(word)}. $others"
    }

    /** "T, H, E, Y, apostrophe, R, E"; two words have "space" between them. */
    private fun letters(w: String): String = w.map { c ->
        when (c) { '\'' -> "apostrophe"; '-' -> "hyphen"; ' ' -> "space"; else -> c.uppercaseChar().toString() }
    }.joinToString(", ")

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
}
