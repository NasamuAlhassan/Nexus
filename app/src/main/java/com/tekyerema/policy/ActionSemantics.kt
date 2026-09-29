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

    /** A send read-back with neither recipient nor message — the caller re-reads the screen before settling for it. */
    const val BARE_SEND = "Send this message?"

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

    private val NAV_WORDS = Regex("^(back|navigate up|up|call|video call|voice call|more|more options|search|menu|options|" +
        "info|close|cancel|done|settings|share|delete|archive|mute|add|new|edit)$")

    /** The screen's heading — the top-left label of the top app bar, e.g. a chat's contact name — for when the window
     *  title is only the app's name. Seen on-device: Google Messages' chat window title is "Messages" while the
     *  recipient "5554" is the app-bar heading. Generic: position-based, navigation words skipped. */
    fun screenHeading(s: UiSnapshot?): String? {
        val els = s?.elements.orEmpty().filter { it.source == com.tekyerema.accessibility.ElementSource.TREE }
        val bottom = els.maxOfOrNull { it.bounds.bottom }?.takeIf { it > 0 } ?: return null
        return els.filter { !it.editable && it.bounds.top < bottom * 0.15 }
            .sortedWith(compareBy({ it.bounds.top / 40 }, { it.bounds.left }))   // same row (±40 px) → left to right
            .mapNotNull { it.label?.trim() }
            .firstOrNull { l -> l.length in 2..40 && !NAV_WORDS.matches(l.lowercase()) && !l.equals(s?.appName, true) }
    }

    /** A confirmation sentence, or null to fall back to the tool's own prompt. */
    fun confirmation(
        primitive: String, target: UiElement?, requestedLabel: String?, before: UiSnapshot?,
        submit: Boolean = false, typedText: String? = null
    ): String? {
        val text = (target?.label ?: requestedLabel)?.trim().orEmpty()
        val verb = TargetSemantics.consequential.find(text.lowercase())?.value?.lowercase()
        // WHO: the control's own "…to X" ("Send to 5554") beats the screen title, and a title that is just the APP's
        // name ("Messages" on the conversation list) is not a recipient (seen on-device: "Send this message to Messages?").
        val appName = before?.appName?.trim().orEmpty()
        val labelRecipient = Regex("(?i)\\bto\\s+(.{1,40})$").find(text)?.groupValues?.get(1)?.trim()
        fun notApp(t: String) = t.isNotBlank() && !(appName.isNotEmpty() && (t.equals(appName, true) || appName.contains(t, true)))
        val recipient = labelRecipient
            ?: before?.screenTitle?.trim()?.takeIf { notApp(it) }
            ?: screenHeading(before)?.takeIf { notApp(it) }

        // Sending a message: tapping a Send control (message = composer's current text) OR
        // submitting into a communication field (message = the text being typed).
        val isCommsType = primitive == "type" && submit && TargetSemantics.communicationField.containsMatchIn(text.lowercase())
        val isTapSend = verb in SEND_VERBS
        if (isTapSend || isCommsType) {
            val msg = if (isCommsType) typedText?.trim()?.ifBlank { null } else composerText(before)
            val who = recipient?.let { " to $it" } ?: ""
            return when {
                !msg.isNullOrBlank() -> "Send this message$who?\n“$msg”"
                // nothing composed — don't claim "this message": name the control as it reads ("Send to 5554?")
                isTapSend && labelRecipient != null -> "${text.trim().take(60).trimEnd('?', '.')}?"
                else -> "Send this message$who?"
            }
        }
        // J-02: a form being saved — say WHAT is saved (the typed values), not "Tap “Save”?".
        if (primitive == "tap" && TargetSemantics.commitCaption.matches(text.lowercase().trim())) {
            val fields = TargetSemantics.filledFields(before).take(4).joinToString("; ") { f ->
                (f.hint?.trim()?.takeIf { it.isNotBlank() && it.length <= 20 }?.let { "$it: " } ?: "") + f.label!!.trim().take(40)
            }
            if (fields.isNotEmpty()) return "${text.trim().replaceFirstChar { it.uppercaseChar() }} this? $fields"
        }
        if (verb == "call") {
            val stripped = text.replaceFirst(Regex("(?i)^call\\s+"), "").trim()
            val who = requestedLabel?.trim()?.ifBlank { null } ?: recipient ?: stripped.ifBlank { null }
            return "Call ${who ?: "this contact"}?"
        }
        if (verb != null && text.isNotBlank()) {
            return "${verb.replaceFirstChar { it.uppercaseChar() }} “${text.take(60)}”?"
        }
        // Consent and money: someone who can't see the screen must hear what pressing it MEANS, not just its caption.
        if (text.isNotBlank()) {
            // Name the app too ("… in Jiji.com.gh?"): "Continue with Google" means little without knowing WHERE.
            val inApp = before?.appName?.takeIf { it.isNotBlank() && !text.contains(it, ignoreCase = true) }?.let { " in $it" }.orEmpty()
            val shown = "Tap “${text.take(60)}”$inApp?"
            when (TargetSemantics.consentKind(text, target, anchoredOk = target == null || (target.clickable && !target.editable))) {
                TargetSemantics.Consent.ACCOUNT -> return "$shown This signs you in, makes an account, or shares your account with this app."
                TargetSemantics.Consent.INSTALL -> return "$shown This installs or turns on something on your phone."
                TargetSemantics.Consent.PERMISSION -> return "$shown This gives a permission or approves a request."
                TargetSemantics.Consent.REMOVE -> return "$shown This removes data or turns a protection off, and may not be undone."
                TargetSemantics.Consent.SECURITY -> return "$shown This changes how your phone or account is unlocked or approved, like fingerprint or face."
                null -> {}
            }
            if (TargetSemantics.isSpendCaption(text, target)) return "$shown This may charge you money."
        }
        // An UNLABELLED control can't be named, and "Tap element 5?" means nothing to someone who can't see the
        // screen — say what it may do instead (the gate only fires for one in a compose context).
        if (primitive == "tap" && text.isBlank()) {
            val msg = composerText(before)
            return if (msg != null) "Tap the unlabelled button next to “${msg.take(60)}”? It may send it."
            else "Tap an unlabelled button? It may send or submit something."
        }
        return null
    }
}
