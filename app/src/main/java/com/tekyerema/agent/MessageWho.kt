package com.tekyerema.agent

/**
 * Who a "read the message from …" request means (28 Oct 2026, Infinix: "read the last message from Angela on my WhatsApp" read
 * WhatsApp's own "505 messages from 40 chats" summary — "Angela on my WhatsApp" contains "WhatsApp", the summary's sender). Pure →
 * MessageWhoTest.
 */
object MessageWho {
    private val APP_TAIL = Regex("(?i)\\s+(?:on|in|via|from|at)\\s+(?:my\\s+|the\\s+)?(whatsapp|telegram|messenger)(?:\\s+app)?\\s*$")
    private val APP_NAMES = setOf("whatsapp", "whatsapp business", "telegram", "messenger", "messages", "signal")
    private val SUMMARY = Regex("(?i)^\\s*\\d+\\s+(?:new\\s+)?messages?\\s+from\\s+\\d+\\s+(?:chats?|conversations?)")

    /** ("Angela", "WhatsApp") from "Angela on my WhatsApp"; the name unchanged and null when no app is named. */
    fun split(said: String?): Pair<String?, String?> {
        val s = said?.trim() ?: return null to null
        val m = APP_TAIL.find(s) ?: return s to null
        val app = when (m.groupValues[1].lowercase()) { "whatsapp" -> "WhatsApp"; "telegram" -> "Telegram"; else -> "Messenger" }
        return s.substring(0, m.range.first).trim().ifEmpty { null } to app
    }

    /** An app's own roll-up ("505 messages from 40 chats", sender "WhatsApp") — never read as someone's message. */
    fun isSummary(who: String, text: String?): Boolean =
        who.trim().lowercase() in APP_NAMES || (text != null && SUMMARY.containsMatchIn(text))

    /** The sender [who] is the person [want]: either name holds the other, but never an app's name, and never a one-letter fit. */
    fun matches(who: String, want: String): Boolean {
        val w = who.trim(); val p = want.trim()
        if (w.isEmpty() || p.isEmpty() || w.lowercase() in APP_NAMES) return false
        return w.contains(p, true) || (w.length >= 3 && p.contains(w, true))
    }
}
