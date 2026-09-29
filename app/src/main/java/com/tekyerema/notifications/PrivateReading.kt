package com.tekyerema.notifications

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Private reading on the loudspeaker (2026-09-29). Blind users hear their messages read aloud by the phone — in a trotro, a
 * market, an office — and told researchers they want privacy when the phone speaks in public
 * (docs/agent/research/assistive_tech_blind.md). With "private reading" on (Settings › Voice, or "read my messages
 * privately"; off by default) and no headset or Bluetooth audio connected, reading messages says WHO and HOW MANY, not what
 * they say — "You have 3 new messages: from Ama, Kofi and MTN. Say “read them” to hear them." — and "read them" reads them.
 * With headphones they're read as before. While it's on, a money balance inside a message is never read unless the user
 * asked about a balance. Applies to read_notifications, read_sms (the last text) and the "what did I miss" catch-up.
 *
 * The decisions and the words are pure (PrivateReadingTest); only [privateOutputConnected] and [enabled] touch Android.
 */
object PrivateReading {
    enum class Mode { FULL, WHO_ONLY }

    /** The decision: private reading on and the phone's own speaker in use → who and how many only. */
    fun mode(privateOn: Boolean, privateOutput: Boolean): Mode = if (privateOn && !privateOutput) Mode.WHO_ONLY else Mode.FULL

    /** Headsets, earphones and Bluetooth audio (the spec's "headset/Bluetooth"); a Bluetooth SPEAKER is not private. */
    private val PRIVATE_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
        26 /* TYPE_BLE_HEADSET, API 31 */
    )

    fun isPrivateOutput(deviceTypes: Collection<Int>): Boolean = deviceTypes.any { it in PRIVATE_TYPES }

    /**
     * "You have 3 new messages: from Ama, Kofi and MTN. Say “read them” to hear them." — [senders] one per message, newest
     * first (blank = unknown). [latest] = the last few texts of the inbox ("Your last 3 text messages are from …").
     */
    fun whoOnly(senders: List<String?>, noun: String = "message", latest: Boolean = false): String {
        val n = senders.size
        if (n == 0) return if (latest) "You have no $noun${if (noun.endsWith("s")) "" else "s"}." else "You have no new ${noun}s."
        val names = senders.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinctBy { it.lowercase() }
        val shown = names.take(3)
        val others = names.size - shown.size
        val from = if (shown.isEmpty()) "" else joinAnd(shown + if (others > 0) listOf("$others other${if (others == 1) "" else "s"}") else emptyList())
        val head = when {
            latest && n == 1 -> "Your last $noun is" + (if (from.isEmpty()) " here" else " from $from") + "."
            latest -> "Your last $n ${noun}s are" + (if (from.isEmpty()) " here" else " from $from") + "."
            n == 1 -> "You have a new $noun" + (if (from.isEmpty()) "" else " from $from") + "."
            else -> "You have $n new ${noun}s" + (if (from.isEmpty()) "" else ": from $from") + "."
        }
        return head + if (n == 1) " Say “read it” to hear it." else " Say “read them” to hear them."
    }

    // ---------------------------------------------------------------- money balances

    private val BALANCE = Regex("(?i)\\b((?:(?:new|current|available|actual|your|acct\\.?|account|momo|wallet)\\s+)*bal(?:ance)?\\.?\\s*(?:is|of|:|=)?\\s*:?\\s*)" +
        "(?:gh(?:s|₵|¢|c)\\.?\\s*|₵\\s*|¢\\s*)?\\d[\\d,]*(?:\\.\\d+)?(?:\\s*(?:gh(?:s|₵|¢)|cedis?)\\b)?")

    /** "Current Balance: GHS 1,234.50" → "Current Balance: hidden". Other amounts (money received) stay. */
    fun maskBalances(text: String): String = BALANCE.replace(text) { m -> m.groupValues[1] + "hidden" }

    private val ASKS_BALANCE = Regex("(?i)\\bbal(?:ance)?s?\\b|\\bhow much (?:money )?(?:do i have|is (?:left|in my))")

    /** The user asked about a balance in so many words (then a balance may be read). */
    fun asksForBalance(vararg texts: String?): Boolean = texts.any { it != null && ASKS_BALANCE.containsMatchIn(it) }

    // ---------------------------------------------------------------- voice commands (pure)

    private val ON = Regex("^(?:please )?(?:read (?:my )?(?:messages|texts|notifications) (?:privately|in private|quietly|to me privately)|" +
        "(?:turn on|switch on|enable|start|use) private reading|private reading on|keep my messages private)(?: please)?$")
    private val OFF = Regex("^(?:please )?(?:read (?:my )?(?:messages|texts|notifications) (?:out loud|aloud|normally|in full|on the speaker)|" +
        "(?:turn off|switch off|disable|stop) private reading|private reading off)(?: please)?$")
    private val READ_THEM = Regex("^(?:(?:ok(?:ay)?|yes|yeah|yes please|go on|go ahead)[, ]+)?(?:please )?(?:read (?:them|it|those|these|the messages?|" +
        "my messages)(?: out| to me| out loud| aloud| now)*|go ahead and read (?:them|it))(?: please| now)?$")

    private fun norm(t: String) = t.trim().lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trimEnd('.', '!', '?').trim()

    /** true = "read my messages privately", false = "read my messages out loud", null = neither. */
    fun toggleOf(text: String): Boolean? {
        val g = norm(text)
        return when { ON.matches(g) -> true; OFF.matches(g) -> false; else -> null }
    }

    /** "read them" — honoured only while something is held back ([take]). */
    fun isReadThem(text: String): Boolean = READ_THEM.matches(norm(text))

    // ---------------------------------------------------------------- what "read them" reads

    private class Held(val text: String, val at: Long)
    @Volatile private var held: Held? = null

    fun hold(text: String, now: Long = System.currentTimeMillis()) { held = Held(text, now) }

    /** The held-back reading if it's fresh (`privacy.privateReading.holdMs`, 5 min), cleared once read. */
    @Synchronized
    fun take(now: Long = System.currentTimeMillis()): String? {
        val h = held ?: return null
        held = null
        return h.text.takeIf { now - h.at < com.tekyerema.core.config.AppConfig.long("privacy.privateReading.holdMs", 300_000L) }
    }

    fun clear() { held = null }

    /** "Ama on WhatsApp: Are you coming? Kofi: I'm outside." — what "read them" says for a catch-up (newest first). */
    fun readOut(messages: List<IndexedMessage>, max: Int = 5): String {
        val withText = messages.filter { !it.text.isNullOrBlank() }.sortedByDescending { it.t }
        val lines = withText.take(max).map { m ->
            val who = m.sender?.takeIf { it.isNotBlank() } ?: m.title?.takeIf { it.isNotBlank() && !it.equals(m.app, true) }
            val where = m.app?.takeIf { it.isNotBlank() }
            val head = listOfNotNull(who, where?.let { if (who != null) "on $it" else it }).joinToString(" ").ifEmpty { "A message" }
            "$head: ${m.text!!.replace(Regex("\\s+"), " ").trim().let { if (it.length > 160) it.take(157).trimEnd() + "…" else it }}"
                .let { if (it.last() in ".!?…") it else "$it." }
        }
        val more = withText.size - lines.size
        return lines.joinToString(" ") + if (more > 0) " And $more more." else ""
    }

    private fun joinAnd(xs: List<String>): String = when (xs.size) {
        0 -> ""; 1 -> xs[0]; else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }

    // ---------------------------------------------------------------- Android

    fun enabled(ctx: Context): Boolean = try { com.tekyerema.settings.Settings(ctx).privateReading } catch (_: Throwable) { false }

    /** A headset / Bluetooth audio output is connected (AudioManager.getDevices — no permission). Unknown → false, which
     *  keeps the reading private: the stricter answer. */
    fun privateOutputConnected(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am != null && isPrivateOutput(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
    } catch (_: Throwable) { false }

    /** For a message-reading tool: the who-only line (the [full] reading held for "read them") when private reading applies
     *  now, else null (read as usual). */
    fun gate(ctx: Context, senders: List<String?>, full: String, explicitBalance: Boolean, noun: String = "message", latest: Boolean = false): String? {
        if (senders.isEmpty() || !enabled(ctx)) return null
        if (mode(true, privateOutputConnected(ctx)) == Mode.FULL) return null
        hold(if (explicitBalance) full else maskBalances(full))
        return whoOnly(senders, noun, latest)
    }

    /** A reading as it may be spoken: while private reading is on, balances are hidden unless asked for. */
    fun spoken(ctx: Context, text: String, explicitBalance: Boolean): String =
        if (!explicitBalance && enabled(ctx)) maskBalances(text) else text

    /** The catch-up ("what did I miss"): [full] as built, or — private on the loudspeaker — [brief] (no message quoted)
     *  with the messages held for "read them". */
    fun digest(ctx: Context, full: String, brief: String, messages: List<IndexedMessage>): String {
        if (!enabled(ctx)) return full
        if (privateOutputConnected(ctx)) return maskBalances(full)
        val words = readOut(messages)
        if (words.isBlank()) return maskBalances(brief)
        hold(maskBalances(words))
        return "$brief Say “read them” to hear them."
    }

    /** The request the agent is working on now (a balance question lets a balance be read), or null. */
    fun currentGoal(): String? = try {
        com.tekyerema.agent.AgentEngine.takeIf { it.isBusy }?.state?.value?.goal
    } catch (_: Throwable) { null }
}
