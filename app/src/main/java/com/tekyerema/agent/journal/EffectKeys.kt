package com.tekyerema.agent.journal

import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.policy.TargetSemantics
import org.json.JSONObject

/**
 * The CONSEQUENCE a step would have — what the effect ledger keys on — derived from the step itself, whichever path
 * carries it out: `send_sms` to Ama with "see you at 6", `type` + submit of "see you at 6" into Ama's chat, and a tap on
 * Send while Ama's chat composer holds "see you at 6" are all one consequence (a message to Ama with those words). The
 * old D-09 key was `tool + args.toString()`: a GUI send had no key at all (a TIER1 primitive) and a re-worded retry got a
 * new one (audit §3 gaps 1–2).
 *
 * Pure: arguments, the resolved element and the screen the model reasoned over. Returns null when the step names no
 * consequence worth remembering (a keypad digit during an approved USSD walk, a tap with nothing to key it on).
 */
object EffectKeys {
    data class Spec(
        val kind: EffectKind,
        /** Matching key source (normalized by the ledger). */
        val target: String,
        /** What the user hears it called ("Ama", "0 2 4 4…", "WhatsApp"). */
        val label: String,
        /** The payload (message text, amount…) — hashed by the ledger, never stored. */
        val payload: String
    )

    private val SEND_WORDS = Regex("(?i)\\b(send|reply|post|share|forward)\\b")
    private val CALL_WORDS = Regex("(?i)^(call|voice call|video call|dial|call back|make a call)\\b")
    private val DELETE_WORDS = Regex("(?i)\\b(delete|remove|erase|uninstall|clear (?:data|storage))\\b")
    private val PHONE_LABEL = Regex("^[+(]?[0-9][0-9\\s().\\-]{5,}$")
    private val NAV_WORDS = Regex("(?i)^(back|navigate up|up|more|more options|search|menu|options|info|close|cancel|done|settings)$")

    private fun a(args: JSONObject, k: String): String? = args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }

    /**
     * @param tool the tool name; [element] the resolved target (if any); [requestedLabel] the label the model named;
     * [submit] type's submit flag; [before] the screen the decision was made on.
     */
    fun describe(tool: String, args: JSONObject, element: UiElement?, requestedLabel: String?, submit: Boolean, before: UiSnapshot?): Spec? {
        val app = before?.appName ?: before?.appPackage ?: ""
        return when (tool) {
            "send_sms" -> {
                val who = a(args, "number") ?: a(args, "contact") ?: return null
                Spec(EffectKind.SMS, who, a(args, "contact") ?: who, a(args, "message").orEmpty())
            }
            "reply_to_notification" -> {
                val who = a(args, "sender") ?: return null
                Spec(EffectKind.CHAT_MESSAGE, who, who, a(args, "message").orEmpty())
            }
            "make_call" -> {
                val who = a(args, "number") ?: a(args, "contact") ?: return null
                Spec(EffectKind.CALL, who, a(args, "contact") ?: who, "")
            }
            "emergency" -> Spec(EffectKind.CALL, "emergency", "your emergency contact", a(args, "message").orEmpty())
            "momo_send" -> {
                val who = a(args, "number") ?: return null
                Spec(EffectKind.PAYMENT, who, who, "amount=" + a(args, "amount").orEmpty())
            }
            "mobile_money" -> {
                val who = a(args, "_number") ?: a(args, "_recipient") ?: a(args, "number") ?: a(args, "recipient") ?: "mobile money"
                Spec(EffectKind.PAYMENT, who, a(args, "_recipient") ?: who,
                    (a(args, "_kind") ?: a(args, "action") ?: "") + " amount=" + (a(args, "_amount") ?: a(args, "amount")).orEmpty())
            }
            "run_ussd" -> a(args, "code")?.let { Spec(EffectKind.PAYMENT, "ussd", "the USSD service", it) }
            "install_app" -> {
                val what = a(args, "package") ?: a(args, "app") ?: a(args, "name") ?: return null
                Spec(EffectKind.INSTALL, what, a(args, "app") ?: what, "")
            }
            "google_sign_in" -> Spec(EffectKind.ACCOUNT, app.ifBlank { "google" }, app.ifBlank { "Google" }, a(args, "account").orEmpty())
            "create_contact" -> Spec(EffectKind.OTHER, "contact:" + (a(args, "name") ?: ""), a(args, "name") ?: "a new contact",
                a(args, "number").orEmpty())
            "share_text" -> Spec(EffectKind.CHAT_MESSAGE, a(args, "app") ?: "share", a(args, "app") ?: "the share sheet", a(args, "text").orEmpty())
            "type" -> {
                if (!submit) return null   // typing without submitting sends nothing
                val text = a(args, "text") ?: return null
                val who = chatTitle(before) ?: return null
                Spec(EffectKind.CHAT_MESSAGE, who, who, text)
            }
            "tap", "long_press", "node_action" -> guiTap(element, requestedLabel, args, before, app)
            else -> {
                // Any other non-idempotent tool the gate stopped: keyed on the tool and its arguments' values.
                val vals = args.keys().asSequence().sorted().filter { it != "expects" }.joinToString("|") { "$it=${args.opt(it)}" }
                Spec(EffectKind.OTHER, "$tool@$app", tool.replace('_', ' '), vals)
            }
        }
    }

    private fun guiTap(element: UiElement?, requestedLabel: String?, args: JSONObject, before: UiSnapshot?, app: String): Spec? {
        val label = (element?.label ?: requestedLabel ?: a(args, "label"))?.trim().orEmpty()
        // A keypad digit (USSD menu, calculator) is navigation, even when a dialer gate asked about it — the approved
        // transfer itself is the effect, recorded when it was confirmed.
        if (label.length <= 2 && label.all { it.isDigit() || it == '*' || it == '#' }) return null
        val composer = composerText(before)
        val chat = chatTitle(before)
        // A generic caption ("Allow", "Install", "Delete", "Save") says little on its own: two DIFFERENT permission dialogs
        // or two app pages both have one. Those consequences are keyed on the exact screen too, so only a re-tap of the
        // same thing is "the same"; messages, calls and payments key on who/what (above/below) and survive a changed screen.
        val screenKey = "screen:" + Checkpoint.sigHash(before?.signature().orEmpty())
        return when {
            SEND_WORDS.containsMatchIn(label) || (label.isEmpty() && composer != null) -> {
                val who = Regex("(?i)\\bto\\s+(.{1,40})$").find(label)?.groupValues?.get(1)?.trim() ?: chat ?: app
                // Nothing typed (a photo, a shared file): key on the exact screen, so two different items sent to the same
                // person are two consequences, and only a re-tap on the identical screen is the same one.
                Spec(EffectKind.CHAT_MESSAGE, who, who, composer ?: ("screen:" + Checkpoint.sigHash(before?.signature().orEmpty())))
            }
            CALL_WORDS.containsMatchIn(label) -> {
                val who = label.replaceFirst(Regex("(?i)^(call|voice call|video call|dial)\\s*"), "").trim().ifEmpty { chat ?: app }
                Spec(EffectKind.CALL, who, who, "")
            }
            PHONE_LABEL.matches(label) -> Spec(EffectKind.CALL, label, label, "")
            TargetSemantics.isSpendCaption(label, element) -> Spec(EffectKind.PAYMENT, "$label@$app", app.ifBlank { label }, label)
            DELETE_WORDS.containsMatchIn(label) -> Spec(EffectKind.DELETE, "$label@$app@${chat.orEmpty()}", chat ?: app, "$label|$screenKey")
            label.isEmpty() -> null
            else -> when (TargetSemantics.consentKind(label, element)) {
                TargetSemantics.Consent.INSTALL -> Spec(EffectKind.INSTALL, "$app:$label", chat ?: app.ifBlank { label }, screenKey)
                TargetSemantics.Consent.ACCOUNT, TargetSemantics.Consent.SECURITY, TargetSemantics.Consent.PERMISSION ->
                    Spec(EffectKind.ACCOUNT, "$app:$label", app.ifBlank { label }, "$label|$screenKey")
                TargetSemantics.Consent.REMOVE -> Spec(EffectKind.DELETE, "$app:$label", app.ifBlank { label }, "$label|$screenKey")
                null -> Spec(EffectKind.OTHER, "$label@$app@${chat.orEmpty()}", label, filled(before) + "|" + screenKey)
            }
        }
    }

    /** The conversation this screen is: the window title unless it is just the app's name, else the app bar's heading. */
    fun chatTitle(s: UiSnapshot?): String? {
        s ?: return null
        val appName = s.appName?.trim().orEmpty()
        fun notApp(t: String) = t.isNotBlank() && !(appName.isNotEmpty() && (t.equals(appName, true) || appName.contains(t, true)))
        s.screenTitle?.trim()?.takeIf { notApp(it) }?.let { return it }
        val els = s.elements.filter { it.source == ElementSource.TREE }
        val bottom = els.maxOfOrNull { it.bounds.bottom }?.takeIf { it > 0 } ?: return null
        return els.filter { !it.editable && it.bounds.top < bottom * 0.15 }
            .sortedWith(compareBy({ it.bounds.top / 40 }, { it.bounds.left }))
            .mapNotNull { it.label?.trim() }
            .firstOrNull { l -> l.length in 2..40 && !NAV_WORDS.matches(l) && notApp(l) }
    }

    /** The message in the composer — a focused (or any) editable field holding real text, not a placeholder hint. */
    fun composerText(s: UiSnapshot?): String? {
        val fields = s?.elements.orEmpty().filter { it.editable && !it.password && !it.label.isNullOrBlank() }
        for (f in fields.sortedByDescending { it.focused }) {
            val t = f.label!!.trim()
            if (f.hint != null && t.equals(f.hint.trim(), ignoreCase = true)) continue
            if (TargetSemantics.isQueryBox(f)) continue
            if (Regex("(?i)^(type|write|enter|add|search|message|reply|caption|comment|say something)\\b").containsMatchIn(t) &&
                t.split(' ').size <= 4) continue
            if (t.length >= 2) return t
        }
        return null
    }

    private fun filled(s: UiSnapshot?): String =
        TargetSemantics.filledFields(s).take(4).joinToString("|") { it.label.orEmpty().trim() }
}
