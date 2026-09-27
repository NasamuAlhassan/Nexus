package com.tekyerema.policy

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot

/**
 * Builds a human, action-describing confirmation for a consequential step (§19). The safety GATE
 * itself is decided by [TargetSemantics]; this only produces the *sentence the blind user hears* so
 * it names the real consequence — "Send this message to Prince? '…'" — instead of "Tap element 19?".
 *
 * Generic and evidence-based: recipient comes from the conversation title, the message from the
 * composer's current text — never an app name or a hardcoded contact. Best-effort: if the message
 * can't be read confidently it is omitted (the gate still protects); it is never fabricated.
 */
object ActionSemantics {

    private val SEND_VERBS = setOf("send", "reply", "post", "share", "forward")

    /** Looks like a placeholder/hint rather than a typed message. */
    private fun isPlaceholder(label: String): Boolean {
        val l = label.trim().lowercase().trimEnd('.', '…')
        if (l.length < 3) return true
        return Regex("^(type|write|enter|add|search|message|reply|caption|comment|say something)\\b").containsMatchIn(l) &&
            l.split(" ").size <= 4
    }

    /** The composer's current message text, or null if empty/placeholder/absent. */
    fun composerText(before: UiSnapshot?): String? {
        val fields = before?.elements.orEmpty().filter { it.editable && !it.label.isNullOrBlank() }
        // Prefer a focused editable field; then any editable field with content-like text.
        val ordered = fields.sortedByDescending { it.focused }
        for (f in ordered) {
            val t = f.label!!.trim()
            if (!isPlaceholder(t) && (t.contains(' ') || t.length >= 8)) return t
        }
        return null
    }

    /** A confirmation sentence, or null to fall back to the tool's own prompt. */
    fun confirmation(
        primitive: String, target: UiElement?, requestedLabel: String?, before: UiSnapshot?,
        submit: Boolean = false, typedText: String? = null
    ): String? {
        val text = (target?.label ?: requestedLabel)?.trim().orEmpty()
        val verb = TargetSemantics.consequential.find(text.lowercase())?.value?.lowercase()
        val recipient = before?.screenTitle?.trim()?.takeIf { it.isNotBlank() }

        // Sending a message: tapping a Send control (message = composer's current text) OR
        // submitting into a communication field (message = the text being typed).
        val isCommsType = primitive == "type" && submit && TargetSemantics.communicationField.containsMatchIn(text.lowercase())
        val isTapSend = verb in SEND_VERBS
        if (isTapSend || isCommsType) {
            val msg = if (isCommsType) typedText?.trim()?.ifBlank { null } else composerText(before)
            val who = recipient?.let { " to $it" } ?: ""
            return if (!msg.isNullOrBlank()) "Send this message$who?\n“$msg”"
            else "Send this message$who?"
        }
        if (verb == "call") {
            val stripped = text.replaceFirst(Regex("(?i)^call\\s+"), "").trim()
            val who = requestedLabel?.trim()?.ifBlank { null } ?: recipient ?: stripped.ifBlank { null }
            return "Call ${who ?: "this contact"}?"
        }
        if (verb != null && text.isNotBlank()) {
            return "${verb.replaceFirstChar { it.uppercaseChar() }} “${text.take(60)}”?"
        }
        return null
    }
}
