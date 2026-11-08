package com.tekyerema.accessibility

/**
 * Who said a chat bubble: the user ("You:") or the other side. A20e 8 Nov 2026: asked for the last message Richmond sent, the
 * brain read the user's OWN outgoing bubble — Telegram's tree makes both look alike once the label is cut to 64 characters,
 * because its "Sent at 08:06, Not seen" marker sits at the END of the user's bubbles.
 *
 * Only on a chat screen (a messaging app with its message box showing). The decision uses the app's own markers first
 * (Telegram: "Sent at …" on the user's bubbles; "Received at …" on the other side's), then the bubble's side of the screen
 * (WhatsApp / Messages: the user's bubbles hug the right edge, the other side's the left). Nothing is rewritten in the element:
 * [promptLabel] only PREFIXES the label the planner reads. Pure → ChatBubblesTest.
 */
object ChatBubbles {
    enum class Who { ME, THEM }

    private val CHAT_PKG = Regex("(?i)telegram|whatsapp|messaging|messenger|signal|mms\\b")
    private val OWN = Regex("(?i)[\\s,]*\\b(?:sent at \\d{1,2}:\\d{2}(?:\\s?[ap]m)?(?:,\\s*(?:seen|not seen|read|delivered|sending|sent))?|you sent|sent by you)\\s*\\.?\\s*$")
    private val THEIRS = Regex("(?i)[\\s,]*\\breceived at \\d{1,2}:\\d{2}(?:\\s?[ap]m)?\\s*\\.?\\s*;?\\s*$")
    private val CHROME = Regex("(?i)^(?:message|type a message|send|back|go back|more options|attach|emoji|camera|voice message|search|call|video call)$")

    /** The hint of a chat's message box ("Message", "Type a message", "Enter message"), or its view id. */
    private val COMPOSER_HINT = Regex("(?i)^(?:message|type a message|enter message|text message|write a message|send a message|message…|message\\.\\.\\.)$")
    private val COMPOSER_ID = Regex("(?i)(?:^|:id/|/)(?:entry|message_input|chat_text_edit|chat_input|embedded_text_editor|message_edit_text|input_message)$")
    private val SEND_CONTROL = Regex("(?i)^send(?: message)?$")

    /** A messaging app with a CONVERSATION open: its message box (by hint or id) or its Send control is on screen. A chat LIST has a
     *  search box but no composer — its rows must never be read as bubbles (A20e 8 Nov 2026: "Them: Richmond" in a which-chat question,
     *  and the person guard asking whether "You" was a person). */
    fun isChatScreen(s: UiSnapshot): Boolean {
        if (!CHAT_PKG.containsMatchIn(s.appPackage.orEmpty())) return false
        val tree = s.elements.filter { it.source == ElementSource.TREE }
        val composer = tree.any { e -> e.editable && (COMPOSER_HINT.matches(e.label?.trim().orEmpty()) || COMPOSER_ID.containsMatchIn(e.viewId.orEmpty())) }
        val send = tree.any { e -> !e.editable && SEND_CONTROL.matches(e.label?.trim().orEmpty()) }
        return composer || (send && tree.any { it.editable })
    }

    /** Whose bubble [e] is, or null when it is not a bubble (a control, the box, a full-width header or system line). */
    fun who(e: UiElement, screenWidth: Int): Who? {
        val l = e.label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (e.editable || e.checkable || CHROME.matches(l)) return null
        if (OWN.containsMatchIn(l)) return Who.ME
        if (THEIRS.containsMatchIn(l)) return Who.THEM
        if (screenWidth <= 0 || e.bounds.right <= e.bounds.left) return null
        val w = e.bounds.right - e.bounds.left
        if (w >= screenWidth * 0.9) return null                         // a header, a date line, a system notice
        return when {
            e.bounds.right >= screenWidth * 0.92 && e.bounds.left > screenWidth * 0.15 -> Who.ME
            e.bounds.left <= screenWidth * 0.08 && e.bounds.right < screenWidth * 0.85 -> Who.THEM
            else -> null
        }
    }

    /** The marker stripped: "I'll be there at half past ten, Sent at 08:06, Not seen" → "I'll be there at half past ten". */
    fun words(label: String): String = label.replace(OWN, "").replace(THEIRS, "").trim().trimEnd(',')

    /** Telegram's message list can arrive as ONE node whose description runs the bubbles together, each ending in its marker:
     *  "Heyy... when are we meeting Received at 07:43; I'll be there at half past ten Sent at 08:06, Not seen; …". */
    private val MARKED = Regex("(?i)(.+?)\\s*,?\\s*\\b(sent at \\d{1,2}:\\d{2}(?:\\s?[ap]m)?(?:,\\s*(?:seen|not seen|read|delivered|sending|sent))?|received at \\d{1,2}:\\d{2}(?:\\s?[ap]m)?)\\s*\\.?\\s*;?\\s*")

    /** Every bubble in a run-together label, newest last: (who, words). Empty when the label has no marker. */
    fun bubbles(label: String): List<Pair<Who, String>> =
        MARKED.findAll(label).mapNotNull { m ->
            val text = m.groupValues[1].trim().trimEnd(',', ';')
            if (text.isEmpty()) null else (if (m.groupValues[2].lowercase().startsWith("sent")) Who.ME else Who.THEM) to text
        }.toList()

    /** The label the planner reads for a bubble: "You: …" or "<chat name>: …" (the other side); several bubbles in one node become
     *  "<name>: …; You: …" newest last. Null when [e] is not a bubble or [s] is not a chat screen. [otherName] = the chat's title. */
    fun promptLabel(e: UiElement, s: UiSnapshot, otherName: String?): String? {
        if (!isChatScreen(s)) return null
        val label = e.label ?: return null
        val name = otherName?.takeIf { it.isNotBlank() } ?: "Them"
        fun say(who: Who, text: String) = if (who == Who.ME) "You: $text" else "$name: $text"
        val many = bubbles(label)
        if (many.size >= 2) return many.joinToString("; ") { (w, t) -> say(w, t) }
        val width = s.elements.maxOfOrNull { it.bounds.right } ?: return null
        val who = who(e, width) ?: return null
        val text = words(label)
        if (text.isEmpty()) return null
        return say(who, text)
    }
}
