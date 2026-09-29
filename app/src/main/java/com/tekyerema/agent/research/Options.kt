package com.tekyerema.agent.research

import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Confirmation

/**
 * Choices by voice (research brief §B.3): at most three short labels (four only if each is ≤ 3 words), most likely
 * first, the name before any detail — "Which one: A, B, or C?" — and never a count ("I found 3…", owner rule).
 */
object SpokenOptions {
    /** The planner's comma-separated `options` → labels (≤ 3, or 4 when every one is ≤ 3 words). */
    fun split(options: String?): List<String> {
        val all = options.orEmpty().split(Regex("\\s*[,;]\\s*|\\s+or\\s+")).map { it.trim().trimEnd('.', '?') }
            .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val cap = if (all.size >= 4 && all.take(4).all { it.split(Regex("\\s+")).size <= 3 }) 4 else 3
        return all.take(cap)
    }

    /** "A", "A, or B", "A, B, or C". */
    fun list(labels: List<String>): String = when (labels.size) {
        0 -> ""
        1 -> labels[0]
        else -> labels.dropLast(1).joinToString(", ") + ", or " + labels.last()
    }

    /** Our own choice question. One option: "Do you mean A?"; [narrowing]: more fit than we read out. */
    fun question(labels: List<String>, narrowing: Boolean = false): String = when (labels.size) {
        0 -> "Which one do you mean?"
        1 -> "Do you mean ${labels[0]}?"
        else -> "Which one: ${list(labels)}?" + if (narrowing) " Or something else?" else ""
    }

    /** The planner's ask_user question with its options read out ("Which Ama? Ama Mensah, or Ama Boateng?"). */
    fun withQuestion(question: String, labels: List<String>): String {
        val q = question.trim()
        if (labels.isEmpty()) return q
        return if (q.endsWith("?")) "$q ${list(labels)}?" else "${q.trimEnd('.')}. ${question(labels)}"
    }
}

/**
 * The spoken answer to a choice question → which option. Accepts an ordinal ("the second one", "number two", "last"),
 * a name ("Sakai", "the MIS one"), "none" / "something else", and "stop" / "never mind". Pure → unit-tested.
 */
object OptionAnswer {
    sealed class Pick {
        data class Chosen(val index: Int) : Pick()
        object None : Pick()
        object Stop : Pick()
        object Unclear : Pick()
    }

    private val STOP = Regex("^(?:stop|cancel|never ?mind|forget it|leave it|quit|gyae|twa mu)\\b")
    private val NONE = Regex("^(?:none|neither|nothing|no one|none of (?:them|those|these|that)|something else|another (?:one|site|page)|" +
        "not (?:those|these|any|them|that one|any of them)|no,? (?:none|neither|something else|another)|daabi)\\b")
    // A bare number word counts only on its own ("two"): in "the second one" the "one" is not an ordinal.
    private val ORDINALS = listOf(
        Regex("\\b(?:first|1st|number one|option one)\\b|^(?:the )?(?:one|1)$") to 0,
        Regex("\\b(?:second|2nd|number two|option two)\\b|^(?:the )?(?:two|2)$") to 1,
        Regex("\\b(?:third|3rd|number three|option three)\\b|^(?:the )?(?:three|3)$") to 2,
        Regex("\\b(?:fourth|4th|number four|option four)\\b|^(?:the )?(?:four|4)$") to 3
    )
    private val LAST = Regex("\\b(?:last|final) (?:one|option)\\b|^(?:the )?last$")
    private val FILLER = setOf("the", "one", "a", "an", "please", "i", "want", "mean", "meant", "open", "site", "website", "page",
        "that", "it", "is", "yes", "yeah", "its", "it's", "go", "to", "for", "of", "with", "on")

    fun parse(answer: String?, options: List<String>): Pick {
        val a = answer.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}ɛɔ'\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        if (a.isEmpty()) return Pick.Unclear
        if (STOP.containsMatchIn(a)) return Pick.Stop
        if (NONE.containsMatchIn(a)) return Pick.None
        // A name first: "the MIS one" must not read "one" as the first option.
        byName(a, options)?.let { return Pick.Chosen(it) }
        if (LAST.containsMatchIn(a) && options.isNotEmpty()) return Pick.Chosen(options.lastIndex)
        val ord = ORDINALS.filter { (r, i) -> i < options.size && r.containsMatchIn(a) }.map { it.second }.distinct()
        if (ord.size == 1) return Pick.Chosen(ord[0])
        if (options.size == 1) return when (Confirmation.interpret(a)) {
            Confirmation.Answer.YES -> Pick.Chosen(0)
            Confirmation.Answer.NO -> Pick.None
            else -> Pick.Unclear
        }
        if (Confirmation.interpret(a) == Confirmation.Answer.NO && a.split(' ').size <= 2) return Pick.None
        return Pick.Unclear
    }

    /** The one option whose words the answer names (ties or nothing → null). */
    private fun byName(a: String, options: List<String>): Int? {
        val words = a.split(' ').filter { it.length >= 2 && it !in FILLER }.toSet()
        if (words.isEmpty()) return null
        val scores = options.map { o ->
            val ow = o.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.length >= 2 && it !in FILLER }.toSet()
            words.count { it in ow }
        }
        val max = scores.maxOrNull() ?: return null
        if (max == 0 || scores.count { it == max } > 1) return null
        return scores.indexOf(max)
    }
}

/**
 * research brief §B.4 question budget: never the same question twice in a task (≤ 1 per slot), and at most
 * `agent.askUser.maxChoiceQuestions` (2) "which one" questions per task. Information only the user has ("how much?")
 * isn't a choice question and isn't capped here — a money task legitimately asks for number, amount and reference.
 */
class QuestionBudget(private val maxChoices: Int = AppConfig.int("agent.askUser.maxChoiceQuestions", 2)) {
    private val asked = HashSet<String>()
    private var choices = 0

    /** Null = ask it; else why not (the line for the planner's history). */
    fun refusal(question: String, isChoice: Boolean): String? {
        val k = norm(question)
        if (k.isNotEmpty() && k in asked) return "you already asked the user that in this task — use their answer, or call cannot_do"
        if (isChoice && choices >= maxChoices) return "you have already asked the user to choose twice in this task — act on what they said, or call cannot_do"
        return null
    }

    fun note(question: String, isChoice: Boolean) {
        norm(question).takeIf { it.isNotEmpty() }?.let { asked.add(it) }
        if (isChoice) choices++
    }

    private fun norm(q: String) = q.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
