package com.tekyerema.notifications

/**
 * E-15 / H-09 — "what did I miss?": one short spoken catch-up, built on the phone from what the notification listener
 * already captured (and the call log for missed calls). No model, no network, no opening of apps.
 *
 * Why this exists: a sighted user glances at the shade; a blind user has to swipe through every notification one by
 * one with TalkBack, and the big assistants don't close the gap — Gemini on Android does not read or summarise your
 * notifications by voice on a phone like the A20e, and the Assistant's old "read my notifications" was English-only
 * and read them one at a time. Grouping by app and by person ("WhatsApp: 3 from Ama, 2 in Family") is what makes a
 * backlog hearable in one breath.
 *
 * Privacy: the text was already sanitised and one-time codes hidden at capture (J-07 / J-10); only the newest message
 * is quoted, and it is read to the user, never handed to a model as instructions. Pure — unit-tested.
 */
object NotificationDigest {
    data class MissedCall(val t: Long, val who: String)

    /** The phone app's own notifications: missed calls are counted from the call log instead (never twice). */
    val DIALERS = setOf("com.samsung.android.dialer", "com.android.dialer", "com.google.android.dialer",
        "com.android.server.telecom", "com.samsung.android.incallui", "com.android.phone")

    /** System chatter that is never "something you missed" (charging, USB, device care, downloads…). Config
     *  `notifications.digest.ignorePackages` adds more. */
    val DEFAULT_IGNORE = setOf("android", "com.android.systemui", "com.samsung.android.lool", "com.android.providers.downloads",
        "com.samsung.android.app.smartcapture", "com.samsung.android.scloud", "com.android.vending.billing")

    /** Chat and text-message apps: what "read my messages" means (the rest — YouTube, the Play Store, promotions — only
     *  for "what did I miss" / "read my notifications"). Config `notifications.digest.messagingPackages` adds more. */
    val MESSAGING = setOf("com.whatsapp", "com.whatsapp.w4b", "com.samsung.android.messaging", "com.google.android.apps.messaging",
        "com.android.mms", "com.android.messaging", "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.facebook.orca", "com.facebook.mlite", "com.viber.voip", "com.imo.android.imoim", "com.imo.android.imoimbeta",
        "org.thoughtcrime.securesms", "com.skype.raider", "com.microsoft.teams", "com.truecaller")

    fun isMessage(m: IndexedMessage, extra: Set<String> = emptySet()): Boolean = m.pkg in MESSAGING || m.pkg in extra

    /** A group summary ("5 messages from 3 chats") repeats what the individual notifications already say. */
    private val SUMMARY = Regex("^\\d+ (?:new )?(?:messages?|notifications?)(?: from \\d+ (?:chats?|conversations?))?$", RegexOption.IGNORE_CASE)
    private val MISSED_TITLE = Regex("\\bmissed (?:voice |video )?call", RegexOption.IGNORE_CASE)

    fun isNoise(m: IndexedMessage, ownPkg: String, ignore: Set<String> = DEFAULT_IGNORE): Boolean =
        m.pkg == ownPkg || m.pkg in ignore ||
            (m.text?.trim()?.let { SUMMARY.matches(it) } == true) ||
            (m.title?.trim()?.let { SUMMARY.matches(it) } == true && m.text.isNullOrBlank())

    /** Missed calls read off the phone app's notifications — used only when the call log itself can't be read. */
    fun callsFromNotifications(messages: List<IndexedMessage>): List<MissedCall> = messages
        .filter { it.pkg in DIALERS && (MISSED_TITLE.containsMatchIn(it.title.orEmpty()) || MISSED_TITLE.containsMatchIn(it.text.orEmpty())) }
        .map { m ->
            // "Missed call" / "Ama" — the person is whichever of the two isn't the "missed call" words.
            val who = listOfNotNull(m.sender, m.text, m.title).map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !MISSED_TITLE.containsMatchIn(it) } ?: "someone"
            MissedCall(m.t, who)
        }

    /**
     * The spoken catch-up. [messages] are already limited to the window; [sinceLabel] opens the line ("since you last
     * asked", "in the last 12 hours"). Apps with the most notifications come first; within an app, the busiest people.
     */
    /** @param quoteNewest false = say who and how many only (private reading on the loudspeaker, [PrivateReading]). */
    /** @param noun "notification", or "message" when the user asked for their messages (chats and texts only). */
    fun build(messages: List<IndexedMessage>, calls: List<MissedCall>, sinceLabel: String,
              maxApps: Int = 3, maxPeople: Int = 3, quoteNewest: Boolean = true, noun: String = "notification"): String {
        val items = messages.filter { it.pkg !in DIALERS }.sortedByDescending { it.t }
        val missed = calls.sortedByDescending { it.t }
        if (items.isEmpty() && missed.isEmpty()) return if (noun == "message") "No new messages ${sinceLabel}." else "Nothing new ${sinceLabel}."
        val parts = ArrayList<String>()
        if (missed.isNotEmpty()) {
            val who = missed.map { it.who }.distinct()
            parts.add(count(missed.size, "missed call") + ", from " + joinAnd(who.take(3) + more(who.size - 3)))
        }
        if (items.isNotEmpty()) {
            // Newest first inside each group, so ties keep the most recent app / person in front.
            val byApp = items.groupBy { it.app?.takeIf { a -> a.isNotBlank() } ?: it.pkg }
            val apps = byApp.entries.sortedWith(compareByDescending<Map.Entry<String, List<IndexedMessage>>> { it.value.size }
                .thenByDescending { it.value.first().t })
            val appLines = apps.take(maxApps).map { (app, list) -> "$app: " + peopleLine(app, list, maxPeople) }
            val otherApps = apps.drop(maxApps).sumOf { it.value.size }
            parts.add(count(items.size, noun) + ". " + appLines.joinToString(". ") +
                if (otherApps > 0) ". And ${count(otherApps, "more")} from other apps" else "")
            if (quoteNewest) latest(items)?.let { parts.add(it) }
        }
        // A pause between the groups, so each lands before the next (SpokenText; a line break on screen).
        val body = parts.joinToString("." + com.tekyerema.voice.SpokenText.BREAK)
        return sinceLabel.replaceFirstChar { it.uppercaseChar() } + ": " + body + if (body.last() in ".!?…") "" else "."
    }

    /**
     * MASTER_STATUS #2 (2 Nov 2026): the catch-up that opens with what matters ([Importance]) — missed calls, then up to
     * [maxQuoted] important messages by name with a few of their words ("Ama: are you coming at 4?"), then the rest as one
     * count ("and 11 others, most in WhatsApp"). Kept to about 25 spoken words; "read my messages" reads everything.
     * Private reading uses [build] with quoteNewest = false instead (counts and senders only).
     */
    fun buildPrioritised(messages: List<IndexedMessage>, calls: List<MissedCall>, sinceLabel: String,
                         isContact: (String) -> Boolean = { false }, maxQuoted: Int = 2, wordsEach: Int = 7,
                         noun: String = "notification"): String {
        val items = messages.filter { it.pkg !in DIALERS }.sortedByDescending { it.t }
        val missed = calls.sortedByDescending { it.t }
        if (items.isEmpty() && missed.isEmpty()) return build(messages, calls, sinceLabel, noun = noun)
        val rank = mapOf(Importance.Why.MONEY to 0, Importance.Why.WHEN to 1, Importance.Why.RELATION to 2, Importance.Why.PERSON to 3)
        val marked = items.map { it to Importance.of(it, isContact) }
        val important = marked.filter { it.second != null }.sortedWith(compareBy<Pair<IndexedMessage, Importance.Why?>> { rank[it.second] }
            .thenByDescending { it.first.t }).map { it.first }
        val others = items - important.toSet()
        val parts = ArrayList<String>()
        if (missed.isNotEmpty()) {
            val who = missed.map { it.who }.distinct()
            parts.add(count(missed.size, "missed call") + ", from " + joinAnd(who.take(2) + more(who.size - 2)))
        }
        // One line per person: the newest important message from each, so three texts from Ama aren't three lines.
        val quoted = important.distinctBy { (it.sender ?: it.title ?: it.app ?: it.pkg).lowercase() }.take(maxQuoted)
        for (m in quoted) {
            val who = m.sender?.takeIf { it.isNotBlank() } ?: m.title?.takeIf { it.isNotBlank() } ?: m.app ?: "Someone"
            val words = m.text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
            val said = if (words.size <= wordsEach) words.joinToString(" ") else words.take(wordsEach).joinToString(" ") + "…"
            parts.add(if (said.isEmpty()) who else "$who: " + com.tekyerema.voice.SpokenText.forEar(said).trimEnd('.'))
        }
        val moreImportant = important.size - important.count { it in quoted }
        val rest = others.size + moreImportant
        if (rest > 0) {
            val topApp = (others.ifEmpty { important }).groupBy { it.app?.takeIf { a -> a.isNotBlank() } ?: it.pkg }
                .maxByOrNull { it.value.size }?.key
            val lead = if (parts.isEmpty()) "" else "And "
            parts.add(lead + count(rest, if (parts.isEmpty()) noun else "other") + (topApp?.let { ", most in $it" } ?: ""))
        }
        // A pause between the parts; a part that already ends a sentence ("…at 4?") gets no extra full stop.
        val body = parts.joinToString(com.tekyerema.voice.SpokenText.BREAK.toString()) { p -> if (p.last() in ".!?…") p else "$p." }
        return sinceLabel.replaceFirstChar { it.uppercaseChar() } + ": " + body + if (body.last() in ".!?…") "" else "."
    }

    /** "3 from Ama, 2 in Family" — a person's chat says "from", a group chat (title ≠ sender) says "in". */
    private fun peopleLine(app: String, list: List<IndexedMessage>, maxPeople: Int): String {
        data class Convo(val name: String?, val group: Boolean)
        val convos = list.groupBy { m ->
            val title = m.title?.trim()?.takeIf { it.isNotEmpty() && !it.equals(app, true) }
            val sender = m.sender?.trim()?.takeIf { it.isNotEmpty() }
            when {
                title != null && sender != null && !title.equals(sender, true) -> Convo(title, true)
                else -> Convo(sender ?: title, false)
            }
        }.entries.sortedWith(compareByDescending<Map.Entry<Convo, List<IndexedMessage>>> { it.value.size }.thenByDescending { it.value.first().t })
        val named = convos.filter { it.key.name != null }
        val unnamed = convos.filter { it.key.name == null }.sumOf { it.value.size }
        val bits = named.take(maxPeople).map { (c, l) -> "${l.size} ${if (c.group) "in" else "from"} ${c.name}" }.toMutableList()
        val rest = named.drop(maxPeople).sumOf { it.value.size } + unnamed
        if (bits.isEmpty()) return count(rest, "notification")
        if (rest > 0) bits.add("$rest other${if (rest == 1) "" else "s"}")
        return bits.joinToString(", ")
    }

    /** The newest message, quoted briefly: "The newest is from Ama on WhatsApp: Are you coming?" */
    private fun latest(items: List<IndexedMessage>): String? {
        val m = items.firstOrNull { !it.text.isNullOrBlank() } ?: return null
        val who = m.sender?.takeIf { it.isNotBlank() } ?: m.title?.takeIf { it.isNotBlank() && !it.equals(m.app, true) }
        val where = m.app?.takeIf { it.isNotBlank() }
        val head = when {
            who != null && where != null -> "The newest is from $who on $where"
            who != null -> "The newest is from $who"
            where != null -> "The newest is from $where"
            else -> "The newest says"
        }
        // Who, a breath, then the words as they'd be said — not "…on WhatsApp: are you coming" in one run.
        return "$head." + com.tekyerema.voice.SpokenText.PAUSE + com.tekyerema.voice.SpokenText.forEar(clip(m.text!!.trim(), 100))
    }

    private fun clip(s: String, max: Int): String {
        // A question keeps its "?" (the voice rises); SpokenText.forEar adds the full stop a statement needs.
        val t = s.replace(Regex("\\s+"), " ").trim().trimEnd('.')
        if (t.length <= max) return t
        val cut = t.take(max)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: cut.length) + "…"
    }

    private fun more(n: Int): List<String> = if (n > 0) listOf("$n more") else emptyList()
    private fun count(n: Int, noun: String) = if (noun == "more") "$n more" else "$n $noun${if (n == 1) "" else "s"}"
    private fun joinAnd(xs: List<String>): String = when (xs.size) {
        0 -> ""; 1 -> xs[0]; else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }
}
