package com.tekyerema.agent

/**
 * "How do you say thank you in Twi?" / "what does akwaaba mean?" — everyday Asante Twi phrases answered on the phone,
 * from a fixed list, online or off.
 *
 * Why: the device suite (A20e, 2026-09-29) asked the cloud model, which was on its small fallback (gpt-oss-20b) after
 * the daily quota ran out, and it said "thank you" is "meda wo akye" — that is "good morning". A blind Ghanaian user
 * asking how to say something in Twi must get it right, and common phrases don't need a model at all. Anything not in
 * the list goes to the planner as before. Pure, unit-tested.
 *
 * The list holds only common, widely taught greetings, courtesies, numbers and words.
 * [owner: have a native speaker check the spellings; add more with config `lexicon.twiPhrases` — "english=twi|alt"]
 */
object TwiPhrasebook {
    /** English → Twi (the first is the usual one; any others are also right). */
    private val DEFAULT: Map<String, List<String>> = linkedMapOf(
        "thank you" to listOf("medaase", "meda wo ase"),
        "thanks" to listOf("medaase"),
        "thank you very much" to listOf("medaase paa"),
        "welcome" to listOf("akwaaba"),
        "you are welcome" to listOf("akwaaba"),
        "good morning" to listOf("maakye"),
        "good afternoon" to listOf("maaha"),
        "good evening" to listOf("maadwo"),
        "good night" to listOf("da yie"),
        "how are you" to listOf("wo ho te sɛn"),
        "i am fine" to listOf("me ho yɛ"),
        "yes" to listOf("aane"),
        "no" to listOf("daabi"),
        "please" to listOf("mepa wo kyɛw"),
        "sorry" to listOf("kafra", "mepa wo kyɛw"),
        "excuse me" to listOf("kafra"),
        "goodbye" to listOf("nante yie", "yɛbɛhyia bio"),
        "bye" to listOf("nante yie"),
        "see you again" to listOf("yɛbɛhyia bio"),
        "see you later" to listOf("yɛbɛhyia bio"),
        "i love you" to listOf("me dɔ wo"),
        "what is your name" to listOf("wo din de sɛn"),
        "my name is" to listOf("me din de"),
        "where are you going" to listOf("worekɔ he"),
        "i don't understand" to listOf("mente aseɛ"),
        "come here" to listOf("bra ha"),
        "come" to listOf("bra"),
        "go" to listOf("kɔ"),
        "wait" to listOf("twɛn"),
        "stop" to listOf("gyae"),
        "help me" to listOf("boa me"),
        "how much is it" to listOf("ɛyɛ ahe"),
        "how much" to listOf("ɛyɛ ahe"),
        "well done" to listOf("ayekoo"),
        "congratulations" to listOf("ayekoo"),
        "god bless you" to listOf("onyame nhyira wo"),
        "i am hungry" to listOf("ɔkɔm de me"),
        "let's go" to listOf("momma yɛnkɔ"),
        "happy new year" to listOf("afehyia pa"),
        "water" to listOf("nsuo"),
        "food" to listOf("aduane"),
        "money" to listOf("sika"),
        "friend" to listOf("adamfo"),
        "mother" to listOf("maame", "ɛna"),
        "father" to listOf("papa", "agya"),
        "child" to listOf("abofra"),
        "today" to listOf("ɛnnɛ"),
        "tomorrow" to listOf("ɔkyena"),
        "yesterday" to listOf("ɛnnora"),
        "one" to listOf("baako"),
        "two" to listOf("mmienu"),
        "three" to listOf("mmiɛnsa"),
        "four" to listOf("ɛnan"),
        "five" to listOf("enum"),
        "six" to listOf("nsia"),
        "seven" to listOf("nson"),
        "eight" to listOf("nwɔtwe"),
        "nine" to listOf("nkron"),
        "ten" to listOf("du")
    )

    private fun book(): Map<String, List<String>> {
        val extra = try {
            com.tekyerema.core.config.AppConfig.stringList("lexicon.twiPhrases", emptyList()).mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else norm(line.substring(0, i)) to line.substring(i + 1).split('|').map { it.trim() }.filter { it.isNotEmpty() }
            }.filter { it.second.isNotEmpty() }.toMap()
        } catch (_: Throwable) { emptyMap() }
        return DEFAULT + extra
    }

    // "how do you say X in Twi", "how do I say X in Twi", "what is X in Twi", "X in Twi", "translate X to Twi",
    // "what is the Twi (word) for X", "Twi for X".
    private val TO_TWI = listOf(
        Regex("^(?:please\\s+)?(?:how\\s+(?:do|can|would|should)\\s+(?:you|i|we|one)\\s+say|how\\s+is|what(?:'s|\\s+is)|say|translate)\\s+(.{1,40}?)\\s+(?:in|into|to)\\s+(?:asante\\s+)?twi$"),
        Regex("^(?:please\\s+)?(?:what(?:'s|\\s+is)\\s+)?(?:the\\s+)?(?:asante\\s+)?twi\\s+(?:word\\s+|phrase\\s+)?for\\s+(.{1,40})$"),
        Regex("^(.{1,40}?)\\s+in\\s+twi$")
    )
    // "what does akwaaba mean", "what is the meaning of medaase", "meaning of maakye".
    private val FROM_TWI = listOf(
        Regex("^(?:please\\s+)?what\\s+does\\s+(.{1,40}?)\\s+mean(?:\\s+in\\s+english)?$"),
        Regex("^(?:please\\s+)?(?:what(?:'s|\\s+is)\\s+)?(?:the\\s+)?meaning\\s+of\\s+(.{1,40}?)(?:\\s+in\\s+english)?$")
    )

    /** The spoken answer for [g] (lower-case), or null when it isn't a phrase question this list can answer. */
    fun answer(g: String): String? {
        val s = g.trim().trimEnd('?', '.', '!').trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        val b = book()
        for (re in TO_TWI) {
            val m = re.matchEntire(s) ?: continue
            val en = norm(m.groupValues[1])
            val twi = b[en] ?: b[en.replace("i'm", "i am").replace("you're", "you are")] ?: return null
            val main = twi.first().replaceFirstChar { it.uppercase() }
            val also = twi.drop(1).firstOrNull()?.let { " You can also say “$it”." }.orEmpty()
            return "In Twi, “$en” is “$main”.$also"
        }
        for (re in FROM_TWI) {
            val m = re.matchEntire(s) ?: continue
            val said = plain(norm(m.groupValues[1]))
            val en = b.entries.firstOrNull { (_, ts) -> ts.any { plain(it) == said } }?.key ?: return null
            val shown = b[en]!!.first { plain(it) == said }.replaceFirstChar { it.uppercase() }
            return "“$shown” is Twi for “$en”."
        }
        return null
    }

    /** "the word “Thank you”" → "thank you". */
    private fun norm(x: String): String = x.lowercase().trim()
        .trim('"', '\'', '“', '”', '‘', '’').trim()
        .removePrefix("the word ").removePrefix("the phrase ").removePrefix("word ")
        .trim('"', '\'', '“', '”', '‘', '’', ',', '.').trim()

    /** A recogniser or a keyboard often writes ɛ/ɔ as e/o: compare Twi without them. */
    private fun plain(x: String): String = x.lowercase().replace('ɛ', 'e').replace('ɔ', 'o').replace(Regex("[^a-z ]"), "").trim()
}
