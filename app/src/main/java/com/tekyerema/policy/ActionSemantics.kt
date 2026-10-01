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
    /** The system's runtime-permission dialogs (Google's and the older installer's). */
    private val PERMISSION_PKGS = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.android.packageinstaller", "com.samsung.android.permissioncontroller")

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

    /** The control's own "…to X" ("Send to 5554"), or null. */
    private fun labelRecipient(text: String): String? = Regex("(?i)\\bto\\s+(.{1,40})$").find(text)?.groupValues?.get(1)?.trim()

    /** WHO a send/call control on [before] reaches: the control's own "…to X" beats the screen title, and a title that is
     *  just the APP's name ("Messages" on the conversation list) is not a recipient (seen on-device: "Send this message to
     *  Messages?"). */
    private fun screenRecipient(text: String, before: UiSnapshot?): String? {
        val appName = before?.appName?.trim().orEmpty()
        fun notApp(t: String) = t.isNotBlank() && !(appName.isNotEmpty() && (t.equals(appName, true) || appName.contains(t, true)))
        return labelRecipient(text)
            ?: before?.screenTitle?.trim()?.takeIf { notApp(it) }
            ?: screenHeading(before)?.takeIf { notApp(it) }
    }

    /** What a bare verb button does, said once after "Tap “Delete” in Clock?". */
    private val VERB_NOTES: List<Pair<Set<String>, String>> = listOf(
        setOf("delete", "remove", "erase", "format", "reset", "uninstall") to "This may not be undone.",
        setOf("shutter", "capture", "take", "record") to "This takes a photo or starts recording."
    )

    /** "1 selected" / "3 items selected" on a selection screen, so "Delete" says HOW MUCH it deletes. */
    private fun selectionNote(s: UiSnapshot?): String? =
        (s?.elements.orEmpty().mapNotNull { it.label?.trim() } + listOfNotNull(s?.screenTitle?.trim()))
            .firstOrNull { it.length <= 30 && Regex("(?i)^\\d+ (?:\\p{L}+ )?selected$").matches(it) }
            ?.let { "$it." }

    /** A caption as it should be spoken: a tile's "Wi-Fi,On.,Button" → "Wi-Fi On". */
    private fun spokenCaption(label: String): String =
        label.replace(Regex("[\\s,]+"), " ").trim().replace(Regex("(?i)\\s*button$"), "").replace(".", "").trim().take(60)

    /** A confirmation sentence, or null to fall back to the tool's own prompt. */
    fun confirmation(
        primitive: String, target: UiElement?, requestedLabel: String?, before: UiSnapshot?,
        submit: Boolean = false, typedText: String? = null
    ): String? {
        val text = (target?.label ?: requestedLabel)?.trim().orEmpty()
        val verb = TargetSemantics.consequential.find(text.lowercase())?.value?.lowercase()
        val labelRecipient = labelRecipient(text)
        val recipient = screenRecipient(text, before)

        // Sending a message: tapping a Send control (message = composer's current text) OR
        // submitting into a communication field (message = the text being typed).
        val isCommsType = primitive == "type" && submit && TargetSemantics.communicationField.containsMatchIn(text.lowercase())
        val isTapSend = verb in SEND_VERBS
        if (isTapSend || isCommsType) {
            val msg = if (isCommsType) typedText?.trim()?.ifBlank { null } else composerText(before)
            // A chat with a BOT or a channel is said to be one: "Richmond" was a public bot that shared the contact's first
            // name, and the read-back gave no hint (A20e audit 2026-10-01, C10).
            val notAPerson = before?.elements.orEmpty().mapNotNull { it.label?.trim()?.lowercase() }
                .firstOrNull { it == "bot" || it == "channel" || Regex("^[\\d\\s,.]+ (?:subscribers?|members?)$").matches(it) }
                ?.let { if (it == "bot") "a bot" else "a channel or group" }
            val who = (recipient?.let { " to $it" } ?: "") + (notAPerson?.let { " — this chat is $it, not a person in your contacts" } ?: "")
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
        // A SYSTEM PERMISSION dialog: the user must hear WHAT is being asked and WHICH button will be pressed —
        // the generic line below produced "Allow “Don't allow”?", which told a blind user neither, and their yes
        // DENIED Telegram contacts access without them knowing (A20e 2026-09-30).
        if (primitive == "tap" && before?.appPackage in PERMISSION_PKGS) {
            val q = before?.elements?.mapNotNull { it.label?.trim() }
                ?.firstOrNull { it.length in 12..160 && Regex("(?i)\\ballow\\b.+\\bto\\b").containsMatchIn(it) }
                ?: before?.screenTitle?.takeIf { !it.isNullOrBlank() }
            val ask = q?.trimEnd('?')?.let { "The phone is asking: “$it?” " } ?: "The phone is asking for a permission. "
            return ask + "Tap “${text.trim().take(40)}”?"
        }
        // Name the app too ("… in Jiji.com.gh?"): "Continue with Google" or "Delete" means little without knowing WHERE.
        val inApp = before?.appName?.takeIf { it.isNotBlank() && !text.contains(it, ignoreCase = true) }?.let { " in $it" }.orEmpty()
        // Audit 2026-10-01: taps with no consequential word in their caption (see [TargetSemantics.TapEffect]).
        if (primitive != "type") TargetSemantics.tapEffect(text, target, before)?.let { fx ->
            val shown = "Tap “${spokenCaption(text)}”$inApp?"
            val inShade = before?.appPackage == "com.android.systemui"
            return when (fx) {
                TargetSemantics.TapEffect.WIPE ->
                    if (inShade) "Clear all your notifications? They can't be brought back."
                    else "$shown This clears the whole list and can't be undone."
                TargetSemantics.TapEffect.CLOSE_ALL -> "Close all your open apps? Anything unsaved in them may be lost."
                TargetSemantics.TapEffect.JOIN ->
                    "$shown This starts a chat with a bot, or joins or follows something. It is not a message to a person you know."
                TargetSemantics.TapEffect.REVEAL_BALANCE -> "$shown This shows your balance on the screen."
                TargetSemantics.TapEffect.CONNECTIVITY -> {
                    val what = TargetSemantics.connectionNamed(text)?.let { if (it.startsWith("wi")) "Wi-Fi" else it } ?: "a connection"
                    "Switch $what on or off? You may lose the connection you are using."
                }
            }
        }
        if (verb != null && text.isNotBlank()) {
            // 'Delete “Delete”?', 'Take picture “Take picture”?', 'Buy “Buy”?' (A20e audit 2026-10-01) repeat the caption and
            // say nothing a listener can judge: name the button and the app, then what is selected and what it does.
            if (text.trim().lowercase().startsWith(verb)) {
                val notes = listOfNotNull(
                    selectionNote(before),
                    VERB_NOTES.firstOrNull { verb in it.first || it.first.any { v -> verb.startsWith(v) } }?.second,
                    "This may charge you money.".takeIf { TargetSemantics.isSpendCaption(text, target) })
                return "Tap “${text.take(60)}”$inApp?" + notes.joinToString("") { " $it" }
            }
            return "${verb.replaceFirstChar { it.uppercaseChar() }} “${text.take(60)}”?"
        }
        // Consent and money: someone who can't see the screen must hear what pressing it MEANS, not just its caption.
        if (text.isNotBlank()) {
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

    // ---------------------------------------------------------------- the recipient read-back rule

    /** Tools that reach another person, named by one of [Provenance.recipientKeys]. */
    private val PERSON_TOOLS = setOf("send_sms", "make_call", "reply_to_notification", "momo_send", "mobile_money")
    /** Where a GUI recipient was read: the control's own words, or the open chat's title. */
    private const val FROM_LABEL = "(label)"
    private const val FROM_CHAT = "(chat)"
    /** Words that don't identify anyone, so they never count as "the user named them". */
    private val NOT_A_NAME = setOf("mr", "mrs", "ms", "dr", "the", "and", "my", "of", "to", "de", "on")

    /**
     * Research §2.2 b (OWASP ASI09): the person a send, call or payment reaches must come from the user. When the goal
     * does NOT name the recipient, the read-back says so and where the recipient came from — the screen, a message I read,
     * the chat that is open — so someone who can't see the screen can catch a name or number that an injected message
     * slipped in. Null when the goal names them (a relation word saved for them counts: "text my mum").
     *
     * @param goal the goal of the run (for a paired computer's task, its request — the read-back already says so)
     * @param tags this step's payload provenance ([StepGate] / [Provenance.tagArgs]): where an argument came from
     * @param relation a relation word in the goal → the contact saved for it ("mum" → "Akosua Mensah")
     */
    fun recipientNotice(
        tool: String, args: org.json.JSONObject, target: UiElement?, requestedLabel: String?, screen: UiSnapshot?,
        submit: Boolean, goal: String, tags: Map<String, com.tekyerema.core.Tagged<String>>,
        relation: (String) -> String? = ::savedRelation
    ): String? {
        val (key, who) = recipientOf(tool, args, target, requestedLabel, screen, submit) ?: return null
        if (namedInGoal(who, goal, relation)) return null
        val subject = if (who.count { it.isDigit() } >= 5) "You didn't say this number" else "You didn't name $who"
        val from = when (key) {
            FROM_CHAT -> "it's the chat that is open on the screen"
            FROM_LABEL -> "I read it on the screen"
            "sender" -> "it's who the message came from"
            else -> when (tags[key]?.why) {
                com.tekyerema.core.Provenance.WHY_SCREEN -> "I read it on the screen"
                com.tekyerema.core.Provenance.WHY_MESSAGE -> "it came from a message I read"
                else -> if (screen?.elements.orEmpty().any { it.label?.contains(who, ignoreCase = true) == true })
                    "I read it on the screen" else "I chose it myself"
            }
        }
        return "$subject — $from."
    }

    /** [prompt] with the recipient notice on its own line (unchanged when there is none). */
    fun withRecipientNotice(prompt: String, notice: String?): String = if (notice.isNullOrBlank()) prompt else "$prompt\n$notice"

    /** (argument key or [FROM_LABEL]/[FROM_CHAT], who) for an action that reaches a person, or null. */
    internal fun recipientOf(
        tool: String, args: org.json.JSONObject, target: UiElement?, requestedLabel: String?, screen: UiSnapshot?, submit: Boolean
    ): Pair<String, String>? {
        if (tool in PERSON_TOOLS) for (k in com.tekyerema.core.Provenance.recipientKeys) {
            val v = if (args.has(k) && !args.isNull(k)) args.optString(k).trim() else ""
            if (v.isNotEmpty()) return k to v
        }
        if (tool != "tap" && tool != "type") return null
        val text = (target?.label ?: requestedLabel)?.trim().orEmpty()
        val verb = TargetSemantics.consequential.find(text.lowercase())?.value?.lowercase()
        val commsType = tool == "type" && submit && TargetSemantics.communicationField.containsMatchIn(text.lowercase())
        if (verb == "call") {
            val named = text.replaceFirst(Regex("(?i)^call\\s+"), "").trim()
            if (named.isNotEmpty() && !named.equals(text, true)) return FROM_LABEL to named
        }
        if (verb in SEND_VERBS || verb == "call" || commsType) {
            labelRecipient(text)?.let { return FROM_LABEL to it }
            screenRecipient(text, screen)?.let { return FROM_CHAT to it }
        }
        return null
    }

    /**
     * Did the goal name [who]? A number (5+ digits) counts when the goal holds the same number (0XX… and +233XX… alike,
     * compared on the last 9 digits); a name when one of its words is a word of the goal, or when the goal uses a
     * relation word saved for that contact.
     */
    fun namedInGoal(who: String, goal: String, relation: (String) -> String? = ::savedRelation): Boolean {
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in NOT_A_NAME }
        val digits = who.filter { it.isDigit() }
        if (digits.length >= 5) {
            val want = digits.takeLast(9)
            return Regex("\\+?\\d[\\d\\s-]{3,}\\d").findAll(goal).any { it.value.filter { c -> c.isDigit() }.takeLast(9) == want }
        }
        val goalWords = words(goal).toSet()
        val whoWords = words(who)
        if (whoWords.any { it in goalWords }) return true
        return goalWords.any { w ->
            val saved = relation(w) ?: return@any false
            saved.equals(who, ignoreCase = true) || words(saved).any { it in whoWords }
        }
    }

    private fun savedRelation(word: String): String? = try { com.tekyerema.android.Relations.contactFor(word) } catch (_: Throwable) { null }
}
