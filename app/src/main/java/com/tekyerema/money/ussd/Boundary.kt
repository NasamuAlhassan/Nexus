package com.tekyerema.money.ussd

import com.tekyerema.agent.NumberSpeech
import com.tekyerema.tools.MtnConfirm

/**
 * The navigator's last screen — the network's own confirmation / PIN prompt — turned into the ONE line the user hears
 * before authorising, or into a stop (finance audit, 2026-10-01). Pure → unit-tested against the real MTN screen.
 *
 * Why it exists: the navigator became the default send route on 30 September, and its hand-off said only "You're about
 * to send 0.1 cedis to AMA MENSAH. Enter your PIN" — the fixed driver it replaced ([MtnConfirm], live-tested on 29
 * September) also said the fee and the total, checked a number shown on the screen against the one the user confirmed,
 * pressed Cancel itself when the screen was wrong (a blind user was told to "press Cancel on the screen"), and gave the
 * loudspeaker PIN tip. Those are restored here, and one check is new: when the send was to a CONTACT, the name the
 * network has registered for the number is compared with the contact's name — the research's standard defence against a
 * wrong-number send (the registered name is the only thing the network shows before the money moves).
 */
object Boundary {
    sealed class Verdict {
        /** Hand the PIN to the user with [line]; [registeredName] = the name the network showed, for the ledger. */
        data class HandOff(val line: String, val registeredName: String?) : Verdict()
        /** The screen isn't what the user confirmed: nothing is handed over, the dialog is cancelled. */
        data class Stop(val why: String) : Verdict()
    }

    fun decide(tx: TxContext, m: MenuModel.Screen, pinTip: String? = null): Verdict {
        val net = tx.provider?.let { NumberSpeech.networkName(it) } ?: "The network"
        val p = MtnConfirm.parse(m.text)
        val asked = tx.amount?.replace(",", "")?.toBigDecimalOrNull()
        val shown = p.amount ?: m.shownAmount?.toBigDecimalOrNull()
        val moves = tx.intent != TxIntent.CHECK_BALANCE
        // A shown amount that isn't the user's amount, or a shown number that isn't the user's number: a hard stop.
        if (moves && asked != null && shown != null && asked.compareTo(shown) != 0)
            return Verdict.Stop("$net's screen shows ${MtnConfirm.cedis(shown)}, but you asked for ${MtnConfirm.cedis(asked)}")
        // A send whose confirmation shows no amount to check is not handed over (the fixed driver's rule, MtnConfirm) —
        // "You're about to send <what you asked for>" would be Tɛkyerɛma's word, not the network's.
        if (tx.intent == TxIntent.SEND_MONEY && shown == null)
            return Verdict.Stop("$net's screen doesn't show an amount I can check" + (asked?.let { " against the ${MtnConfirm.cedis(it)} you asked for" } ?: ""))
        val want = NumberSpeech.normalize(tx.recipient)
        if (moves && p.number != null && want.isNotEmpty() && p.number.takeLast(9) != want.takeLast(9))
            return Verdict.Stop("$net's screen shows the number ${NumberSpeech.spoken(p.number)}, not ${NumberSpeech.spoken(want)}")

        val registered = (p.name ?: m.shownName)?.let { MtnConfirm.speakName(it) }
        val pin = m.kind == MenuModel.Kind.PIN
        // One sentence up to the instruction, on purpose: "brief" speech keeps the first sentence only.
        val go = if (pin) "if that's right, enter your PIN; if not, press Cancel"
            else "the screen says “${com.tekyerema.money.UssdSpeech.forEar(m.text, intro = "")}”; confirm it there and enter your PIN yourself"
        val amount = (shown ?: asked)?.let { MtnConfirm.cedis(it) }
        val line = when (tx.intent) {
            TxIntent.SEND_MONEY -> {
                val costs = costs(p)
                val nameNote = when (nameMatch(tx.recipientName, registered)) {
                    NameMatch.DIFFERENT -> " — that isn't the name you have saved, ${tx.recipientName}, so make sure it's the right person"
                    NameMatch.PARTIAL -> " — only part of that matches the name you have saved, ${tx.recipientName}, so check it's the right person"
                    else -> ""
                }
                if (registered != null) "$net shows $amount to $registered$costs$nameNote — $go."
                else "$net shows $amount$costs, but no name, so check the number: " +
                    "${NumberSpeech.spoken(want)}${tx.recipientName?.let { ", for $it" } ?: ""} — $go."
            }
            TxIntent.BUY_AIRTIME, TxIntent.SEND_AIRTIME -> {
                val who = tx.recipientName ?: want.takeIf { it.isNotEmpty() && tx.intent == TxIntent.SEND_AIRTIME }?.let { NumberSpeech.spoken(it) }
                "You're about to buy ${amount?.let { "$it of " } ?: ""}airtime${who?.let { " for $it" } ?: " for this phone"}${costs(p)} — $go."
            }
            TxIntent.BUY_DATA, TxIntent.SEND_DATA ->
                "You're about to buy ${tx.product ?: "the bundle"}${tx.recipientName?.let { " for $it" } ?: ""} — $go."
            TxIntent.CHECK_BALANCE -> if (pin) "To hear your Mobile Money balance, enter your PIN now."
                else "To hear your balance — $go."
            else -> "You're about to ${tx.intent.spoken} — $go."
        }
        return Verdict.HandOff(line + " I never see or type your PIN." + (pinTip?.let { " $it" } ?: ""), registered)
    }

    private fun costs(p: MtnConfirm.Screen): String = buildList {
        when {
            p.fee == null -> {}
            p.fee.signum() == 0 -> add("no fee")
            else -> add("fee ${MtnConfirm.cedis(p.fee)}")
        }
        if (p.tax != null && p.tax.signum() != 0) add("tax ${MtnConfirm.cedis(p.tax)}")
        if (p.total != null) add("total ${MtnConfirm.cedis(p.total)}")
    }.joinToString(", ").let { if (it.isEmpty()) "" else ", $it" }

    /** Words that are not a person's name: titles, kin words and labels people add when saving a contact. */
    private val NOT_NAME = setOf("mr", "mrs", "miss", "madam", "dr", "prof", "rev", "pastor", "bro", "brother", "sis", "sister",
        "auntie", "aunty", "aunt", "uncle", "dad", "daddy", "father", "papa", "mum", "mom", "mummy", "mother", "mama", "maame",
        "wife", "husband", "son", "daughter", "boss", "the", "my", "new", "old", "home", "work", "office", "momo", "mtn",
        "telecel", "vodafone", "airteltigo", "number", "line", "phone", "mobile", "and")

    private fun nameWords(s: String): Set<String> =
        s.lowercase().replace(Regex("['’]s\\b"), "").split(Regex("[^\\p{L}]+")).filter { it.length >= 3 && it !in NOT_NAME }.toSet()

    /**
     * The contact's saved name and the network's registered name share no name word — said so the user can check, never
     * a stop: people save nicknames ("Mum", "Kofi Mechanic"). No saved name, or one with nothing to compare ("Mum"), is
     * not a difference. A word matches when equal, or one starts the other (≥ 4 letters: "Alhassan" / "Alhasan" no,
     * "Chris" / "Christopher" yes).
     */
    fun nameDiffers(saved: String?, registered: String?): Boolean = nameMatch(saved, registered) == NameMatch.DIFFERENT

    enum class NameMatch { UNKNOWN, SAME, PARTIAL, DIFFERENT }

    /**
     * How a saved name sits against a registered one. PARTIAL = both have two or more name words and only one of them
     * matches ("Kofi Mensah" / "KOFI BOATENG": a shared first name is weak evidence — code review, 2026-10-02); a saved
     * single name that matches ("Prince" / "PRINCE NASAMU ALHASSAN") is SAME.
     */
    fun nameMatch(saved: String?, registered: String?): NameMatch {
        val a = nameWords(saved ?: return NameMatch.UNKNOWN)
        val b = nameWords(registered ?: return NameMatch.UNKNOWN)
        if (a.isEmpty() || b.isEmpty()) return NameMatch.UNKNOWN
        val hits = a.count { x -> b.any { y -> x == y || (x.length >= 4 && y.startsWith(x)) || (y.length >= 4 && x.startsWith(y)) } }
        return when {
            hits == 0 -> NameMatch.DIFFERENT
            hits == 1 && a.size >= 2 && b.size >= 2 -> NameMatch.PARTIAL
            else -> NameMatch.SAME
        }
    }
}
