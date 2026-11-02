package com.tekyerema.ui.assistant

import com.tekyerema.agent.AgentStatus
import com.tekyerema.voice.PromptSurface

/*
 * The surface's words and small pure rules (moved unchanged from AssistantSurface.kt in v2): what a confirmation's buttons
 * say, which choices a question offers, how a voice line reads on screen, whether buttons fit side by side, when a tapped
 * choice may answer, the maps link, and the line TalkBack reads. Unit-tested in PromptChoicesTest and AssistantSurfaceModelTest.
 */

/**
 * Whether a tapped choice may be sent as the answer. Only while the agent is still asking (a question from a task), or while
 * nothing waits at all (a question at the end of a reply starts a new turn) — never while a confirmation or the secure step
 * waits: a typed label there is read as "yes", "no" or a change to the confirmation (review, 14 Oct 2026). Pure.
 */
object ChoiceGate {
    fun accepts(status: AgentStatus, engineQuestion: Boolean): Boolean = when (status) {
        AgentStatus.WAITING_CONFIRMATION, AgentStatus.SECURE_INPUT -> false
        AgentStatus.ASKING -> true
        else -> !engineQuestion
    }
}

/** A maps search the phone's maps app runs near the user ("geo:0,0?q=…"); nothing about the place is invented here. */
object MapsLink {
    fun of(query: String): String = "geo:0,0?q=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}

/** The one line TalkBack reads when the surface changes: the title and, for a result or a question, its words. */
internal fun spokenSummary(s: SurfaceModel.State): String = when {
    s.waiting != null -> "${s.title}. ${s.waiting.text}"
    s.preview is SurfaceModel.Preview.Answer -> "${s.title}. ${s.preview.summary}"
    s.preview is SurfaceModel.Preview.Action -> "${s.title}. ${s.preview.summary}"
    s.preview is SurfaceModel.Preview.Problem -> "${s.title}. ${s.preview.message}"
    else -> s.title
}

/**
 * Whether a row of buttons fits side by side at the user's text size: each button gets its weight's share of the width and
 * needs its padding plus its longest word (a word never breaks mid-way; a label of several words may wrap). Pure.
 */
object ButtonFit {
    /** About one character of the buttons' 17 sp semibold label at 100 % text, in dp — a little generous: stack early. */
    private const val CHAR_DP = 10f
    /** BigButton's horizontal padding, both sides. */
    private const val PAD_DP = 48f

    fun sideBySide(labels: List<String>, weights: List<Float>, widthDp: Float, fontScale: Float, gapDp: Float = 10f): Boolean {
        if (labels.size < 2 || weights.size != labels.size) return true
        val total = weights.sum()
        if (total <= 0f) return true
        val free = widthDp - gapDp * (labels.size - 1)
        return labels.indices.all { i ->
            val longest = labels[i].split(' ').maxOf { it.length }
            free * weights[i] / total >= PAD_DP + longest * CHAR_DP * fontScale
        }
    }
}
/** A line built for the voice, as the screen shows it: its pause marks (voice/SpokenText) become line breaks. Pure. */
object ScreenText {
    private val AROUND_BREAK = Regex("[ \\t]*\\n[ \\t]*")
    private val MANY_BREAKS = Regex("\\n{3,}")
    /** The voice spaces digits out ("ending 3 6 1 1"); on screen a group stays on one line (no-break spaces). */
    private val SPACED_DIGITS = Regex("(?<=\\b\\d) (?=\\d\\b)")
    fun of(text: String): String = text.replace(com.tekyerema.voice.SpokenText.BREAK, '\n').replace(com.tekyerema.voice.SpokenText.PAUSE, '\n')
        .replace(AROUND_BREAK, "\n").replace(MANY_BREAKS, "\n\n").replace(SPACED_DIGITS, " ").trim()
}

/** A confirmation's buttons: Cancel and a VERB that says what will happen ("Send", "Call", "Buy") — HIG Snippets. Pure. */
object ConfirmWords {
    private val VERBS = listOf("Send", "Call", "Buy", "Pay", "Open", "Turn", "Delete", "Reply", "Text", "Share", "Post", "Install",
        "Save", "Set", "Book", "Order", "Transfer", "Top up", "Dial", "Remove", "Clear", "Start")
    /** "… Send it?" / "… Call him?" — the verb of a closing question. */
    private val CLOSING = Regex("(?i)\\b([a-z]+(?: up)?)\\s+(?:it|this|that|them|him|her)\\s*\\?\\s*$")

    fun buttons(text: String): Pair<String, String> {
        val plain = ScreenText.of(text).trim()
        // An announced call (policy/ConfirmTiers) goes ahead by itself; the button only makes it go now.
        if (plain.startsWith("Calling ")) return "Cancel" to "Call now"
        // The closing question names the act most exactly ("…The message: "…". Send it?" → Send); else the first word.
        val closing = CLOSING.find(plain)?.groupValues?.get(1)
        val first = plain.trimStart('“', '"').lowercase()
        val verb = VERBS.firstOrNull { v -> closing.equals(v, ignoreCase = true) }
            ?: VERBS.firstOrNull { v -> first.startsWith(v.lowercase() + " ") || first.startsWith(v.lowercase() + "?") }
        return "Cancel" to (verb ?: "Yes, do it")
    }

    private val TO_NAME = Regex("(?i)\\bto\\s+([\\p{L}][\\p{L} .'-]{0,22}?)\\s*(?:[,.?!\\n]|$)")
    private val LEAD_NAME = Regex("(?i)^(?:text|call|message|whatsapp|reply to|ring)\\s+([\\p{L}][\\p{L} .'-]{0,22}?)\\s*(?:,|\\.|\\?|\\n|$|\\s+ending\\b)")

    /**
     * The primary pill's words: the verb, and the person when the read-back names one plainly — "Send to Ama", "Call Ama"
     * (mission v2). A name with digits, a long one, or none found leaves the verb alone; never guessed.
     */
    fun label(text: String): String {
        val verb = buttons(text).second
        if (verb == "Yes, do it") return verb
        val plain = ScreenText.of(text).trim().trimStart('“', '"')
        // "to <Name>" only in the action line (up to its "?"), and a name starts with a capital: "…stop at the PIN for you to
        // enter." further down made the pill "Send to enter" (emulator, 14 Oct 2026).
        val action = plain.substringBefore('?').substringBefore('\n')
        val name = (LEAD_NAME.find(plain)?.groupValues?.get(1) ?: TO_NAME.find(action)?.groupValues?.get(1))
            ?.trim()?.trimEnd('.', ',')
            ?.takeIf { n -> n.isNotEmpty() && n.first().isUpperCase() && n.none { it.isDigit() } && n.split(' ').size <= 3 }
            ?: return verb
        val words = when (verb) {
            "Send", "Text", "Reply", "Share", "Transfer", "Pay" -> "$verb to $name"
            "Call", "Dial" -> "$verb $name"
            else -> verb
        }
        return if (words.length <= 24) words else verb
    }
}

/**
 * The choices a question names, for tapping instead of saying: "Which Prince: Prince Tesano AG youth Sec, Prince Ice P, or
 * Prince Alhassan ending 323? Or someone else?" → the three names. Only clear lists of 2–5 short options; anything else
 * (open questions, money amounts, a single yes/no) shows no buttons and is answered by voice or keyboard. Pure.
 */
object QuestionChoices {
    fun of(question: String): List<String> {
        val q = question.trim()
        // "Which X: a, b, or c?" / "Which X do you want? a, or b?"
        val list = Regex("^(?i)which\\b[^:?]{0,60}[:?]\\s*(.+?)\\?").find(q)?.groupValues?.get(1) ?: return emptyList()
        val parts = list.split(Regex(",\\s*(?:or\\s+)?|\\s+or\\s+")).map { it.trim().trimEnd('.', ',') }.filter { it.isNotEmpty() }
        if (parts.size !in 2..5) return emptyList()
        if (parts.any { it.length > 48 || it.count { ch -> ch.isDigit() } >= 7 }) return emptyList()
        return parts.distinct()
    }
}
