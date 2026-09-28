package com.tekyerema.agent.triggers

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.VoiceEngine
import com.tekyerema.voice.VoiceSessionService
import java.util.Calendar
import java.util.TimeZone

/**
 * Unattended triggers (A-11 / A-12 / A-13) — the safety core, approved by the owner 2026-09-28.
 *
 * Until now nothing ran unless the user asked (J-17). A trigger fires with NOBODY watching, so the rule is:
 * a trigger may run a READ-ONLY goal by itself (read notifications/SMS/calendar, the time, the battery, speak a
 * reminder). Anything consequential — send, call, pay, reply, delete, buy, share, post, money — is NEVER executed
 * unattended: the trigger speaks an OFFER / reminder line and does nothing else.
 *
 * [isReadOnlyGoal] is a pure, conservative classifier: an allow-list of read openings AND a deny-list of
 * consequential words AND a per-clause check so a compound goal ("read my messages and reply to Ama") can't smuggle
 * an action behind a read. When unsure it says NO — a denied goal still gets its spoken reminder, so a false
 * "no" costs the user one sentence, while a false "yes" could cost them money or a message they never meant.
 */
object TriggerSafety {
    private const val MAX_GOAL_CHARS = 300

    /** Courtesy / wake words in front of a goal ("please", "hey Tɛkyerɛma, can you …"). */
    private val COURTESY = Regex(
        "^(?:(?:please|kindly|hey|hi|ok|okay|tekyerema|tɛkyerɛma|can you|could you|would you|will you)[,\\s]+)+",
        RegexOption.IGNORE_CASE
    )

    /** The goal's FIRST clause must open with one of these read/speak forms. */
    private val ALLOW_START = Regex(
        "^(?:read|tell me|what|what's|whats|when|who|check|how much|how many|how long|remind me|say|" +
            "do i have|have i got|is there|are there|any)\\b"
    )

    /** Consequential words anywhere in the goal. Word forms are chosen so plain READS survive
     *  ("read my text messages", "who called me", "my missed calls", "my call log"). */
    private val DENY: List<Regex> = listOf(
        "\\bsend(?:s|ing)?\\b",
        "(?<!missed\\s)\\bcall(?:ing)?\\b(?!\\s+(?:log|logs|history)\\b)",
        "\\bdial(?:s|ing|led)?\\b",
        "\\bpay\\w*",
        "\\btransfer\\w*",
        "\\brepl(?:y|ies|ying)\\b",
        "\\bdelet\\w*", "\\berase\\w*", "\\bremov\\w*", "\\buninstall\\w*", "\\binstall\\w*",
        "\\bbuy\\w*", "\\bbought\\b", "\\bpurchas\\w*",
        "\\btext\\b(?!\\s+messages?\\b)",
        "\\bforward\\w*",
        "\\bshar(?:e|es|ed|ing)\\b",
        "\\bpost(?:ing)?\\b",
        "\\bmomo\\b", "\\bmobile money\\b", "\\bmoney\\b", "\\bcash\\b", "\\bairtime\\b", "\\bbundles?\\b",
        "\\bcedis?\\b", "\\bghs\\b", "gh₵", "\\bwallet\\b", "\\bbank\\w*", "\\bbalance\\b", "\\bloan\\w*",
        "\\bwithdraw\\w*", "\\bdeposit\\w*", "\\btop[\\s-]?up\\b",
        "\\bcheck[\\s-]?(?:in|out)\\b",
        "\\bpin\\b", "\\bpassword\\b", "\\botp\\b",
        // "…to Ama on WhatsApp" — addressing someone over a channel is a message, whatever the verb.
        "\\bto\\s+[\\p{L}\\w']+\\s+(?:on|via|in|through|by)\\s+(?:whatsapp|sms|telegram|messenger|e-?mail|facebook|instagram|signal|text)\\b",
        // Messaging verbs in VERB position (clause start or after and/then/to …): "… and message Ama", "remind me to
        // tell Kofi". "tell me …" is a read and stays allowed; "the message from Ama" is a noun and stays allowed.
        "(?:^|[,;]\\s*|\\b(?:and|then|also|please|to|or)\\s+)(?:message|whatsapp|e-?mail|sms|dm|ping|tell|ask|invite|inform|notify|wish)\\s+(?!me\\b)\\S"
    ).map { Regex(it) }

    /** Clause separators for compound goals. */
    private val CLAUSE_SPLIT = Regex("\\s*(?:[,;&]|\\band then\\b|\\bthen\\b|\\band\\b|\\balso\\b|\\bplus\\b)\\s*")

    /** A later clause that starts with one of these is an ACTION riding on a read ("… then open WhatsApp"). */
    private val ACTION_START = Regex(
        "^(?:open|launch|start|stop|turn|switch|set|enable|disable|play|pause|dial|ring|e-?mail|whatsapp|sms|text|" +
            "message|forward|send|share|post|upload|download|install|uninstall|delete|remove|erase|clear|archive|mark|" +
            "accept|decline|reject|answer|block|unblock|mute|unmute|book|order|buy|pay|transfer|top|withdraw|deposit|" +
            "approve|confirm|cancel|sign|log|join|leave|like|follow|subscribe|unsubscribe|go|tap|click|press|type|" +
            "write|create|add|save|update|change|edit|lock|unlock|record|take|make|give|call|reply|respond|ask|invite|" +
            "schedule|move|copy|paste|close|kill|restart|reboot|reset|wipe|format|enter|fill|submit|register|report)\\b"
    )
    private val TELL_OTHER = Regex("^tell\\s+(?!me\\b)")

    /** Whitespace/apostrophes normalised, trailing punctuation dropped; case kept. */
    internal fun clean(goal: String): String =
        goal.replace('’', '\'').replace('‘', '\'').replace('`', '\'')
            .replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?', ' ')

    private fun stripCourtesy(s: String): String = s.replace(COURTESY, "").trim()

    /** Lower-cased, courtesy-stripped form used for classification. */
    internal fun normalize(goal: String): String = stripCourtesy(clean(goal).lowercase())

    /**
     * True only for goals that READ or SPEAK: the first clause opens with an allowed read form, no consequential word
     * appears anywhere, and no later clause starts with an action verb. Blank, over-long or unknown → false.
     */
    fun isReadOnlyGoal(goal: String): Boolean {
        val g = normalize(goal)
        if (g.isEmpty() || g.length > MAX_GOAL_CHARS) return false
        if (!ALLOW_START.containsMatchIn(g)) return false
        if (DENY.any { it.containsMatchIn(g) }) return false
        val clauses = g.split(CLAUSE_SPLIT).map { stripCourtesy(it) }.filter { it.isNotEmpty() }
        if (clauses.drop(1).any { ACTION_START.containsMatchIn(it) || TELL_OTHER.containsMatchIn(it) }) return false
        return true
    }

    private val REMIND_TO = Regex("^remind me\\s+(?:to|that i (?:need|have) to|that i should)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val REMIND_ABOUT = Regex("^remind me\\s+(?:about|of|that)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val REMIND_BARE = Regex("^remind me\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val SAY = Regex("^say\\s+(.+)$", RegexOption.IGNORE_CASE)

    /**
     * SPEAK-ONLY goals ("remind me to …", "say …") → the exact line to speak, or null for any other goal. These are
     * never handed to the agent — even "remind me to call Ama" only SPEAKS "It's time to call Ama." (a reminder, not
     * a call), and "say …" is spoken locally so it can never become a message to someone.
     */
    fun reminderLine(goal: String): String? {
        val g = stripCourtesy(clean(goal))
        REMIND_TO.matchEntire(g)?.let { m ->
            return AppConfig.phrase("triggers.phrases.remindTo", "It's time to {what}.", "what" to swapPronouns(m.groupValues[1]))
        }
        (REMIND_ABOUT.matchEntire(g) ?: REMIND_BARE.matchEntire(g))?.let { m ->
            return AppConfig.phrase("triggers.phrases.remindAbout", "Reminder: {what}.", "what" to swapPronouns(m.groupValues[1]))
        }
        SAY.matchEntire(g)?.let { m ->
            val s = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            return if (s.last() in ".!?") s else "$s."
        }
        return null
    }

    /** The reminder for a goal that won't run by itself: "It's time: <goal>." */
    fun itsTime(goal: String): String = AppConfig.phrase("triggers.phrases.itsTime", "It's time: {goal}.", "goal" to clean(goal))

    private val PRONOUNS = Regex("\\b(myself|mine|my|me|i'm|i am|i)\\b", RegexOption.IGNORE_CASE)

    /** First person → second person, so the user's own words come back addressed to them. */
    fun swapPronouns(text: String): String = PRONOUNS.replace(text.trim()) { m ->
        when (m.value.lowercase()) {
            "myself" -> "yourself"; "mine" -> "yours"; "my" -> "your"; "me" -> "you"
            "i'm" -> "you're"; "i am" -> "you are"; else -> "you"
        }
    }
}

/**
 * Quiet hours for unattended speech: config `triggers.quietHours` (default "22:00-06:00"; "off"/"none" disables;
 * an unparseable value falls back to the default — the SAFE side). A window may cross midnight.
 */
object QuietHours {
    const val DEFAULT = "22:00-06:00"
    private const val KEY = "triggers.quietHours"

    /** [startMin, endMin) in minutes of the day; start > end crosses midnight. */
    data class Window(val startMin: Int, val endMin: Int)

    private val DEFAULT_WINDOW = Window(22 * 60, 6 * 60)
    private val RX = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?\\s*-\\s*(\\d{1,2})(?:[:.](\\d{2}))?$")

    /** The window, or null when quiet hours are switched off / empty. */
    fun parse(spec: String?): Window? {
        val s = spec?.trim()?.lowercase().orEmpty()
        if (s == "off" || s == "none" || s == "never") return null
        val m = RX.matchEntire(s) ?: return DEFAULT_WINDOW
        val sh = m.groupValues[1].toInt(); val sm = m.groupValues[2].ifEmpty { "0" }.toInt()
        val eh = m.groupValues[3].toInt(); val em = m.groupValues[4].ifEmpty { "0" }.toInt()
        if (sh !in 0..24 || eh !in 0..24 || sm !in 0..59 || em !in 0..59) return DEFAULT_WINDOW
        val start = (sh * 60 + sm) % 1440
        val end = (eh * 60 + em) % 1440
        return if (start == end) null else Window(start, end)
    }

    fun contains(w: Window?, minuteOfDay: Int): Boolean {
        if (w == null) return false
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return if (w.startMin < w.endMin) m >= w.startMin && m < w.endMin else m >= w.startMin || m < w.endMin
    }

    fun minuteOfDay(nowMillis: Long, zone: TimeZone): Int {
        val c = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun configuredSpec(): String = AppConfig.string(KEY, DEFAULT)

    fun isQuiet(nowMillis: Long, zone: TimeZone = TimeZone.getDefault(), spec: String = configuredSpec()): Boolean =
        contains(parse(spec), minuteOfDay(nowMillis, zone))

    /**
     * A schedule may not speak/run in quiet hours unless the rule says so: either its `allowQuiet` flag, or the user
     * deliberately set its time INSIDE the window ("at 11 pm remind me to take my medicine" is explicit consent).
     * A schedule set for 21:55 whose alarm Doze delivers at 22:10 is therefore skipped.
     */
    fun blocksSchedule(s: Schedule, nowMillis: Long, zone: TimeZone = TimeZone.getDefault(), spec: String = configuredSpec()): Boolean {
        val w = parse(spec) ?: return false
        if (!contains(w, minuteOfDay(nowMillis, zone))) return false
        if (s.allowQuiet) return false
        return !contains(w, s.hour * 60 + s.minute)
    }
}

/** Shared runtime gates and plumbing for every trigger. */
object TriggerGate {
    /** One SharedPreferences file for schedules, user notification rules and proactive-offer bookkeeping. */
    const val PREFS = "tekyerema_triggers"
    const val TAG = "Tekyerema"

    private val IDLE_OR_TERMINAL = setOf(
        AgentStatus.IDLE, AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED,
        AgentStatus.CANNOT_DO, AgentStatus.FAILED, AgentStatus.STOPPED
    )

    /** The agent is mid-task (thinking, acting, waiting for an OK, or paused at a PIN screen). */
    fun isAgentBusy(status: AgentStatus): Boolean = status !in IDLE_OR_TERMINAL

    /** The user is busy: an agent task is in flight, a voice turn is live, or a call is ringing / in progress. */
    fun userBusy(ctx: Context): Boolean {
        if (isAgentBusy(AgentEngine.state.value.status)) return true
        if (try { VoiceEngine.isRunning } catch (_: Throwable) { false }) return true
        val mode = (try { ctx.getSystemService(AudioManager::class.java)?.mode } catch (_: Throwable) { null }) ?: AudioManager.MODE_NORMAL
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Speak a line outside a turn and (optionally) leave it in the shade for someone who missed it. */
    fun speak(ctx: Context, line: String, post: Boolean = true) {
        if (line.isBlank()) return
        try { VoiceEngine.announce(line) } catch (_: Throwable) {}
        if (post) try { VoiceSessionService.postResult(ctx.applicationContext, line) } catch (_: Throwable) {}
    }
}

/** At most one event per key per window (e.g. one announcement per sender per 60 s). Bounded; pure. */
class Cooldown(private val maxKeys: Int = 256) {
    private val last = LinkedHashMap<String, Long>()

    @Synchronized
    fun tryAcquire(key: String, nowMs: Long, windowMs: Long): Boolean {
        val prev = last[key]
        if (prev != null && nowMs - prev in 0 until windowMs) return false
        last.remove(key)
        last[key] = nowMs
        while (last.size > maxKeys) last.remove(last.keys.first())
        return true
    }
}
