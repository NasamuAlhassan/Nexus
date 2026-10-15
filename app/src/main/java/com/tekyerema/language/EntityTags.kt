package com.tekyerema.language

/**
 * Names, amounts and phone numbers as TAGS across every translation (owner, 2 October 2026: "create a tag placeholder…
 * a dedicated layer maps the tag back to the person's real name"; amounts and numbers stay in English). Before text goes
 * to a translator — or a Twi request to the planner — a person becomes `[P1]`, an amount `[A1]`, a phone number `[N1]`;
 * the translator and the planner only ever see the tags, and [restore] puts the real values back afterwards. Our
 * translator copied `[P1]`-style tags intact in 11 of 11 sentences both ways (audit/twi-plan-2026-10-02), where the bare
 * name "Kofi" came back as "coffee". A tag that is lost or doubled means the line is not used (never a wrong name).
 * Pure → unit-tested.
 */
object EntityTags {
    enum class Kind { PERSON, AMOUNT, NUMBER, QUANTITY }

    /** [value]: what the tag stands for — a name as the user said it (or their contact's spelling), an amount in plain
     *  English ("5 cedis"), a phone number as digits. */
    data class Entity(val tag: String, val kind: Kind, val value: String)

    data class Tagged(val text: String, val entities: List<Entity>) {
        val isEmpty: Boolean get() = entities.isEmpty()
    }

    // ---------------------------------------------------------------- finding them

    /** A phone number: 10 digits from 0 (or +233…), possibly spaced or comma-grouped as the read-backs say them. */
    private val PHONE = Regex("(?<![\\d])(?:\\+?233[\\s,-]*|0)(?:\\d[\\s,-]*){8}\\d(?![\\d])")
    /** An amount: "5 cedis", "0.10 cedis", "50 pesewas", "5 Ghana cedis", "GHS 5", "GH₵ 5.00", "₵5". */
    private val AMOUNT = Regex("(?i)(?:\\b(?:GHS|GHC|GH₵|GH¢)\\s*|[₵¢]\\s*)(\\d+(?:[.,]\\d{1,2})?)|" +
        "\\b(\\d+(?:[.,]\\d{1,2})?)\\s*(ghana\\s+cedis?|cedis?|pesewas?|ghs|ghc)\\b")
    private val TAG = Regex("\\[(?:P|A|N|Q)\\d+\\]")
    private val WORD = Regex("[\\p{L}][\\p{L}'’-]*")

    /** Akan day names and other common Ghanaian given names — tagged even when they aren't saved contacts. */
    val COMMON_NAMES: Set<String> = setOf(
        "kofi", "kwame", "kwabena", "kwaku", "kwasi", "kwesi", "kojo", "kwadwo", "yaw", "kweku", "ekow", "fiifi", "kobina",
        "ama", "akosua", "akɔsua", "abena", "akua", "yaa", "afua", "afia", "adwoa", "adjoa", "esi", "efua", "ekua", "araba",
        "mansa", "serwaa", "nana", "akwasi", "kwamena", "abenaa", "adzo", "dzifa", "selorm", "fatima", "abdul", "ibrahim",
        "mariam", "prince", "gifty", "comfort", "emmanuel", "zelia")
    /** Names that are also everyday Twi words ("ama" = "has given", "nana" = grandparent): a name only after a word that
     *  takes a person ("ma", "frɛ", "ne"…) or at the very start. */
    private val AMBIGUOUS = setOf("ama", "yaa", "nana", "esi", "ekua")
    private val PERSON_CUE = setOf("ma", "kɔma", "frɛ", "ne", "kyerɛ", "fi", "efi", "hɔ", "to", "call", "for", "and", "from",
        "with", "tell", "text", "message", "soma", "twerɛ")

    /**
     * Tag the names, amounts and phone numbers in [text]. [contact] returns the contact's display name for a word or a
     * two-word phrase that is a saved contact, else null. Numbers first (so their digits are never an amount), then
     * amounts, then people.
     */
    fun protect(text: String, contact: (String) -> String? = { null }): Tagged {
        val ents = ArrayList<Entity>()
        var out = text
        var n = 0; var a = 0; var p = 0
        out = PHONE.replace(out) { m -> n++; Entity("[N$n]", Kind.NUMBER, m.value.filter { it.isDigit() || it == '+' }).also { ents += it }.tag }
        out = AMOUNT.replace(out) { m ->
            a++
            val fig = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(',', '.')
            val unit = m.groupValues[3].lowercase()
            val value = if (unit.startsWith("pesewa")) "$fig ${if (fig == "1") "pesewa" else "pesewas"}" else "$fig ${if (fig == "1") "cedi" else "cedis"}"
            Entity("[A$a]", Kind.AMOUNT, value).also { ents += it }.tag
        }
        // People: two-word contact names first ("Prince Melanin"), then single words.
        val words = WORD.findAll(out).toList()
        val replace = ArrayList<Pair<IntRange, String>>()   // ranges in [out] → tag
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val prev = words.getOrNull(i - 1)?.value?.lowercase()
            val two = words.getOrNull(i + 1)?.let { nx -> if (out.substring(w.range.last + 1, nx.range.first).isBlank()) "${w.value} ${nx.value}" else null }
            val twoName = two?.let(contact)
            if (twoName != null) {
                p++; ents += Entity("[P$p]", Kind.PERSON, twoName)
                replace += (w.range.first..words[i + 1].range.last) to "[P$p]"; i += 2; continue
            }
            val low = w.value.lowercase()
            val asContact = contact(w.value)
            // Right after an amount or a number is where a person goes too: the recogniser swallows the "ma" of "ma Ama"
            // ("sidi anum ma Ama" came back "sidi anua ama").
            val afterTag = out.substring(0, w.range.first).trimEnd().endsWith("]")
            val common = low in COMMON_NAMES && (low !in AMBIGUOUS || i == 0 || prev in PERSON_CUE || afterTag)
            if (asContact != null || common) {
                // The same person said twice keeps one tag.
                val name = asContact ?: w.value.replaceFirstChar { it.uppercaseChar() }
                val existing = ents.firstOrNull { it.kind == Kind.PERSON && it.value.equals(name, true) }
                val tag = existing?.tag ?: run { p++; "[P$p]".also { ents += Entity(it, Kind.PERSON, name) } }
                replace += w.range to tag
            }
            i++
        }
        for ((range, tag) in replace.sortedByDescending { it.first.first }) out = out.replaceRange(range, tag)
        return Tagged(out, ents)
    }

    private const val NUMW = "(?:zero|oh|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|" +
        "fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|point|and)"
    /** An amount said in words — replies reach the voice already verbalised ("zero point one zero cedis"). */
    private val SPELLED_AMOUNT = Regex("(?i)\\b($NUMW(?:[\\s-]+$NUMW)*)\\s+(ghana\\s+)?(cedis?|pesewas?)\\b")
    private val DIGIT_WORD = mapOf("zero" to '0', "oh" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4',
        "five" to '5', "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9')
    /** A phone number read out digit by digit ("zero five five, two zero nine, three six nine zero"). */
    private val SPELLED_PHONE = Regex("(?i)\\b(?:(?:zero|oh|one|two|three|four|five|six|seven|eight|nine)[\\s,]+){6,}(?:zero|oh|one|two|three|four|five|six|seven|eight|nine)\\b")

    /**
     * An ENGLISH line (a reply, a confirmation) tagged for English→Twi translation: phone numbers and amounts (in digits
     * or in words), then each of [terms] — the names, app names and quoted words the line must keep (ReplyLocalizer's
     * protected terms) — as a name-like tag, so the translator never touches them.
     */
    fun protectEnglish(text: String, terms: Set<String>): Tagged {
        val first = protect(text)                              // digits: numbers and amounts
        val ents = first.entities.filter { it.kind != Kind.PERSON }.toMutableList()
        // protect() also tags lexicon names; in an English line only the protected terms (below) are names.
        var out = first.text
        first.entities.filter { it.kind == Kind.PERSON }.forEach { e -> out = out.replace(e.tag, e.value) }
        var n = ents.count { it.kind == Kind.NUMBER }; var a = ents.count { it.kind == Kind.AMOUNT }; var p = 0
        out = SPELLED_PHONE.replace(out) { m ->
            n++; Entity("[N$n]", Kind.NUMBER, m.value.lowercase().split(Regex("[\\s,]+")).mapNotNull { DIGIT_WORD[it] }.joinToString(""))
                .also { ents += it }.tag
        }
        out = SPELLED_AMOUNT.replace(out) { m ->
            a++; Entity("[A$a]", Kind.AMOUNT, "${m.groupValues[1]} ${m.groupValues[3].lowercase()}").also { ents += it }.tag
        }
        // Any other quantity — a count, a size, a time, a percentage, a USSD code — is said in English inside the Twi line
        // too (owner, 2 October 2026: "all numbers and amounts should be kept and pronounced in normal English"). Until
        // 4 October one such number kept the WHOLE reply in English: "Usain Bolt is the fastest man, holding the one
        // hundred‑metre world record." was read by the English voice in Twi mode (A20e). A lone "one" ("which one") is a
        // word, not a quantity, and is left to the translator.
        var q = 0
        fun quantity(value: String): String { q++; return Entity("[Q$q]", Kind.QUANTITY, value.trim()).also { ents += it }.tag }
        out = SPOKEN_CODE.replace(out) { m -> quantity(m.value) }
        out = DIGIT_QUANTITY.replace(out) { m -> quantity(m.value) }
        out = SPELLED_QUANTITY.replace(out) { m ->
            val run = m.value.replace(TRAILING_JOINER, "")
            if (run.isBlank() || run.equals("one", ignoreCase = true)) m.value else quantity(run) + m.value.substring(run.length)
        }
        for (term in terms.sortedByDescending { it.length }) {
            if (term.isBlank() || !out.contains(term)) continue
            p++
            val tag = "[P$p]"
            out = Regex("(?<![\\p{L}\\[])" + Regex.escape(term) + "(?![\\p{L}\\]])").replace(out) { tag }
            ents += Entity(tag, Kind.PERSON, term)
        }
        return Tagged(out, ents)
    }

    private const val NUMQ = "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|" +
        "fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|" +
        "million|billion|point|and|oh)"
    private const val JOIN = "[\\s\\-‐‑–]+"
    /** A unit glued to the number by a hyphen ("‑metre") or said after it ("percent", "o'clock"). */
    private const val UNIT = "(?:[\\-‐‑–]\\p{L}+|\\s?%|\\s+(?:percent|per\\s+cent|o'clock))?"
    /** A USSD code as the voice says it: "star one seven zero hash". */
    private val SPOKEN_CODE = Regex("(?i)\\bstar(?:\\s+(?:star|zero|oh|one|two|three|four|five|six|seven|eight|nine))+\\s+hash\\b")
    /** Digits left after amounts and phone numbers: "100", "3:45", "85%", "2.5", "100‑metre" — never the digit of a tag. */
    private val DIGIT_QUANTITY = Regex("(?<![\\p{L}\\d\\[.,:])\\d+(?:[.,:]\\d+)*$UNIT(?![\\d\\]])")
    /** A number said in words — replies reach the voice already verbalised ("one hundred‑metre", "forty-five"); never
     *  starting on "and", "point" or "oh". */
    private val SPELLED_QUANTITY = Regex("(?i)\\b(?!(?:and|point|oh)\\b)$NUMQ(?:$JOIN$NUMQ)*\\b$UNIT")
    private val TRAILING_JOINER = Regex("(?i)(?:$JOIN(?:and|point|oh))+$")

    // ---------------------------------------------------------------- putting them back

    /** Every tag of [entities] appears exactly as often in [out] as in [src], and [out] invents no tag of its own. */
    fun intact(src: String, out: String, entities: List<Entity>): Boolean {
        if (entities.any { e -> count(out, e.tag) != count(src, e.tag) || count(out, e.tag) == 0 }) return false
        val known = entities.map { it.tag }.toSet()
        return TAG.findAll(out).all { it.value in known }
    }

    private fun count(s: String, tag: String): Int = s.windowed(tag.length).count { it == tag }

    /** Quotation marks right round a tag (“[P1]”) — they confused the Khaya translator, which then dropped a bracket. */
    private val QUOTED_TAG = Regex("[“\"‘']\\s*(\\[[A-Z]\\d+\\])\\s*[”\"’']")

    /** [text] for a translator: a tag's surrounding quotation marks taken off (its value is spoken the same either way). */
    fun forTranslator(text: String): String = text.replace(QUOTED_TAG, "$1")

    /**
     * A translator's small slips with a known tag mended — "“P1]", "[P1", a bare "P1" — when the tag itself is missing
     * (Khaya web, 15 Oct 2026: “[P1]” came back as “P1]). Only tags of [entities]; anything else is left as it is.
     */
    fun mend(out: String, entities: List<Entity>): String {
        var s = out
        for (e in entities) {
            if (count(s, e.tag) > 0) continue
            val core = Regex.escape(e.tag.removePrefix("[").removeSuffix("]"))
            s = s.replace(Regex("(?<![\\p{L}\\p{N}])\\[?$core\\]?(?![\\p{L}\\p{N}])"), e.tag)
        }
        return s
    }

    /** [text] with every tag replaced by [render] of its entity (default: its value). Unknown tags are left as they are. */
    fun restore(text: String, entities: List<Entity>, render: (Entity) -> String = { it.value }): String {
        val byTag = entities.associateBy { it.tag }
        return TAG.replace(text) { m -> byTag[m.value]?.let(render) ?: m.value }
    }

    /** The facts line the planner gets with a tagged request: what each tag is, never asking it to translate a name. */
    fun facts(entities: List<Entity>): String = entities.joinToString("; ") { e ->
        when (e.kind) {
            Kind.PERSON -> "${e.tag} = a person (${e.value})"
            Kind.AMOUNT -> "${e.tag} = an amount (${e.value})"
            Kind.NUMBER -> "${e.tag} = a phone number (${e.value})"
            Kind.QUANTITY -> "${e.tag} = a number (${e.value})"
        }
    }

    // ---------------------------------------------------------------- English inside Twi (owner's option B)

    /** Marks a stretch a Twi line keeps in ENGLISH, spoken by the English voice (amounts and numbers, owner 2 October
     *  2026: "all numbers and amounts should be kept and pronounced in normal English"). */
    const val EN_OPEN = "⟦en:"
    const val EN_CLOSE = "⟧"
    private val EN_SPAN = Regex("⟦en:(.*?)⟧")

    /** How an entity sits in a Twi line: a name as written (the Twi voice says Akan names well), an amount as "5 Ghana
     *  cedis" and a number digit by digit in 3-3-4 groups — both marked for the English voice. */
    fun inTwi(e: Entity): String = when (e.kind) {
        Kind.PERSON -> e.value
        Kind.AMOUNT -> EN_OPEN + e.value.replace(Regex("\\bcedis?\\b")) { "Ghana ${it.value}" } + EN_CLOSE
        Kind.NUMBER -> EN_OPEN + com.tekyerema.agent.NumberSpeech.spoken(e.value) + EN_CLOSE
        Kind.QUANTITY -> EN_OPEN + e.value + EN_CLOSE
    }

    /** The line as it is SHOWN (markers dropped). */
    fun display(marked: String): String = EN_SPAN.replace(marked) { it.groupValues[1] }

    /** The line as spoken: Twi pieces and English pieces in order (blank pieces dropped). */
    fun segments(marked: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        var at = 0
        for (m in EN_SPAN.findAll(marked)) {
            marked.substring(at, m.range.first).trim().takeIf { it.isNotEmpty() }?.let { out += it to false }
            m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { out += it to true }
            at = m.range.last + 1
        }
        marked.substring(at).trim().takeIf { it.isNotEmpty() }?.let { out += it to false }
        return out
    }

    fun hasEnglishSpans(s: String): Boolean = s.contains(EN_OPEN)
}

/**
 * The tags of the Twi request now being planned (set where the request is translated for the planner, read where the
 * planner's decisions come back). Keyed by the request, so a later task never inherits an earlier one's names.
 */
object TwiTagSession {
    data class Entry(val goal: String, val twiTagged: String, val englishTagged: String?, val entities: List<EntityTags.Entity>)

    @Volatile private var entry: Entry? = null

    fun set(e: Entry) { entry = e }
    fun forGoal(goal: String): Entry? = entry?.takeIf { it.goal == goal }
    fun clear() { entry = null }
}
