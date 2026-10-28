package com.tekyerema.safety

import android.content.Context
import com.tekyerema.settings.Settings

/**
 * The spoken set-up of the danger code word: "set my code word to ...", "set my cancel PIN to 4 7 2 9", "add Ama as a trusted contact",
 * "turn on the danger code word", "is my danger code word on". [parse] is pure (DangerCommandsTest); [handle] applies it and returns
 * the line to say. The code word itself is never read back or logged: it is a secret said aloud once, to set it.
 */
object DangerCommands {
    sealed class Cmd {
        data class SetCodeWord(val phrase: String) : Cmd()
        data class SetPin(val digits: String) : Cmd()
        data class AddTrusted(val name: String) : Cmd()
        data class Switch(val on: Boolean, val pause: Boolean = false) : Cmd()
        object Agree : Cmd()
        object Delete : Cmd()
        object Status : Cmd()
    }

    private val SET_WORD = Regex("^(?:please )?(?:set|change|make) (?:my |the )?(?:danger |emergency |safe )?code ?word (?:to|as) (.{3,60})$")
    private val SET_PIN = Regex("^(?:please )?(?:set|change|make) (?:my |the )?(?:danger |alert )?cancel (?:pin|code|number) (?:to|as) (.{4,40})$")
    private val ADD = Regex("^(?:please )?(?:add|save|make) (.{2,40}?) (?:as )?(?:a |my )?trusted contact$")
    private val SWITCH = Regex("^(?:please )?(turn on|turn off|enable|disable|switch on|switch off|activate|deactivate|pause|resume) (?:my |the )?(?:danger |emergency |safe )?code ?word(?: listening)?$|^(?:please )?(pause|resume) (?:my |the )?danger listening$")
    private val AGREE = Regex("^i agree to (?:the |my )?(?:danger |emergency |safe )?code ?word$")
    private val DELETE = Regex("^(?:please )?(?:delete|erase|remove|forget|clear) (?:all )?(?:of )?(?:my |the )?(?:danger|emergency|safety) (?:data|settings|code ?word|information)$|^(?:please )?forget my (?:danger |emergency )?code ?word$")
    private val STATUS = Regex("^(?:is )?(?:my |the )?(?:danger |emergency |safe )?code ?word (?:on|set|working|active)\\??$|^(?:check|what is) (?:my |the )?(?:danger |emergency )?code ?word status$")

    private val DIGIT_WORDS = mapOf("zero" to '0', "oh" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4', "five" to '5',
        "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9')

    fun parse(text: String): Cmd? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?', ' ').replace(Regex("\\s+"), " ")
        SET_PIN.find(t)?.let { m -> val d = digitsOf(m.groupValues[1]); return if (d.length in 4..8) Cmd.SetPin(d) else null }
        SET_WORD.find(t)?.let { return Cmd.SetCodeWord(it.groupValues[1].trim()) }
        ADD.find(t)?.let { return Cmd.AddTrusted(it.groupValues[1].trim()) }
        SWITCH.find(t)?.let { m ->
            val v = m.groupValues[1].ifBlank { m.groupValues[2] }
            val on = v.startsWith("turn on") || v.startsWith("enable") || v.startsWith("switch on") || v.startsWith("activate") || v == "resume"
            return Cmd.Switch(on, pause = v == "pause")
        }
        if (AGREE.matches(t)) return Cmd.Agree
        if (DELETE.matches(t)) return Cmd.Delete
        if (STATUS.matches(t)) return Cmd.Status
        return null
    }

    /** "4 7 2 9", "four seven two nine" and "4729" all give 4729. */
    fun digitsOf(s: String): String = s.split(' ').joinToString("") { w -> if (w.all { it.isDigit() }) w else DIGIT_WORDS[w]?.toString().orEmpty() }

    /** Apply [text] if it is a set-up command; the line to say, or null when it is not one. */
    fun handle(app: Context, text: String): String? {
        val cmd = parse(text) ?: return null
        val s = Settings(app)
        return when (cmd) {
            is Cmd.SetCodeWord -> DangerCodeWord.problem(cmd.phrase) ?: run {
                s.dangerCodeWord = cmd.phrase
                "Your danger code word is saved. I will not read it back. Say turn on the danger code word when you are ready."
            }
            is Cmd.SetPin -> { s.dangerCancelPin = cmd.digits; "Your cancel PIN is saved. Saying it during the five seconds stops the alert." }
            is Cmd.AddTrusted -> addTrusted(app, s, cmd.name)
            is Cmd.Switch -> when {
                cmd.on && s.dangerCodeWord.isBlank() -> "First choose a code word. Say: set my code word to, and a phrase of at least two words."
                cmd.on && s.emergencyContactNumber.isBlank() && s.trustedContacts.isEmpty() ->
                    "First choose who to text. Say: set my emergency contact to, and a name."
                cmd.on && !s.dangerConsented -> CONSENT
                cmd.on -> { s.dangerEnabled = true; ON_LINE }
                cmd.pause -> { s.dangerEnabled = false; "The danger code word is paused. Say resume the danger code word to turn it back on. S O S still works." }
                else -> { s.dangerEnabled = false; "The danger code word is off." }
            }
            Cmd.Agree -> when {
                s.dangerCodeWord.isBlank() -> "First choose a code word. Say: set my code word to, and a phrase of at least two words."
                s.emergencyContactNumber.isBlank() && s.trustedContacts.isEmpty() -> "First choose who to text. Say: set my emergency contact to, and a name."
                else -> { s.dangerConsented = true; s.dangerEnabled = true; ON_LINE }
            }
            Cmd.Delete -> {
                s.dangerEnabled = false; s.dangerConsented = false
                s.dangerCodeWord = ""; s.dangerCancelPin = ""; s.trustedContacts = emptyList()
                "Done. I deleted your danger code word, your cancel PIN and your trusted contacts, and turned the code word off. " +
                    "Your emergency contact and the activity log are separate and were not touched."
            }
            Cmd.Status -> if (s.dangerEnabled) "The danger code word is on." else "The danger code word is off."
        }
    }

    /** Said the first time the code word is turned on: what it does and cannot do, and the exact words that agree. */
    const val CONSENT = "Before I turn this on. When I hear your code word, I give you five seconds to cancel. Then I call 191 on speaker, " +
        "and text your location to your emergency contact and trusted contacts. I only check the requests you say to me, and I record and upload nothing. " +
        "I can make mistakes, and I cannot promise the police will answer or come. If you agree, say: I agree to the danger code word."
    private const val ON_LINE = "The danger code word is on. When I hear it, I will give you five seconds to cancel, then call 191 and text your location to your contacts."

    private fun addTrusted(app: Context, s: Settings, name: String): String {
        val matches = try { com.tekyerema.android.ContactResolver.resolve(app, name) } catch (_: Throwable) { emptyList() }
        return when {
            matches.isEmpty() -> "I couldn't find $name in your contacts."
            matches.size > 1 -> "More than one contact matches $name: ${matches.take(3).joinToString(", ") { it.name }}. Say the full name."
            !com.tekyerema.android.ContactResolver.isSure(name, matches[0]) ->
                "The closest contact to $name is ${matches[0].name}. To add them, say: add ${matches[0].name} as a trusted contact."
            s.trustedContacts.size >= Settings.MAX_TRUSTED -> "You already have ${Settings.MAX_TRUSTED} trusted contacts."
            else -> { s.trustedContacts = s.trustedContacts + (matches[0].name to matches[0].number); "${matches[0].name} is now a trusted contact." }
        }
    }
}
