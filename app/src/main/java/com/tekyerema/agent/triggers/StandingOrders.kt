package com.tekyerema.agent.triggers

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.core.config.FeatureFlags
import com.tekyerema.notifications.NotificationRecord
import com.tekyerema.voice.VoiceSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standing orders (owner, 5 Nov 2026): "when Lydia sends a voice note, send it to my saved messages on Telegram immediately".
 * The app remembers the order, waits for that notification, then does it.
 *
 * This is the one deliberate exception to the 28 Sep rule that a trigger may only speak (TriggerSafety). Its limits:
 *  - only the user's own stored words become the goal; a notification's text decides nothing beyond "this is a voice note";
 *  - it acts alone only when the destination is the user's OWN space ([StandingOrder.toSelf]). Any other person is told to the
 *    user and nothing is sent;
 *  - the run uses a [StandingGrant]: a short list of tools and approved taps. Money, calls, SMS, PIN screens and everything
 *    else keep their normal refusal, and any other question stops the run;
 *  - it never runs on a locked phone (DeviceLock), during a call, a voice turn, a money step or another task; it waits.
 */
data class StandingOrder(
    val id: String,
    /** The person, as the user said it. Matched against the notification's sender, never its text. */
    val who: String,
    /** "voice_note" | "photo" | "video" | "document" | "message" */
    val kind: String,
    /** The app the message must arrive in; null = any chat app. */
    val fromApp: String? = null,
    /** "Saved Messages", "Kofi", ... */
    val destination: String,
    /** The app the destination lives in; null = the app it arrived in. */
    val toApp: String? = null,
    val createdAt: Long = 0L
) {
    val toSelf: Boolean get() = StandingOrderWords.isOwnSpace(destination)
    val valid: Boolean get() = who.isNotBlank() && destination.isNotBlank() && kind in StandingOrderWords.KINDS
}

/** The words of a standing order: pure, so it is unit-tested. */
object StandingOrderWords {
    val KINDS = setOf("voice_note", "photo", "video", "document", "message")
    private val SPOKEN = mapOf("voice_note" to "voice note", "photo" to "photo", "video" to "video", "document" to "document", "message" to "message")

    fun spokenKind(kind: String): String = SPOKEN[kind] ?: "message"

    private val OWN_SPACE = Regex("(?i)^(?:my\\s+)?(?:own\\s+)?(?:saved messages?|saved|message yourself|message myself)$")
    private val ANYONE = Regex("(?i)^(?:i|me|we|you|someone|somebody|anyone|anybody|everyone|everybody|people|they|he|she|a|the)$")

    /** Telegram's chat with yourself. Only the plain names count: "me" or "myself" could hide a mis-heard person. */
    fun isOwnSpace(destination: String): Boolean = OWN_SPACE.matches(destination.trim())

    fun canonicalDestination(destination: String): String {
        val d = destination.trim().trimEnd('.', '!', '?').trim()
        return if (Regex("(?i)^(?:my\\s+)?(?:own\\s+)?saved(?:\\s+messages?)?$").matches(d)) "Saved Messages"
            else d.replace(Regex("(?i)^my\\s+"), "")
    }

    private val KIND_WORD = "voice notes?|voice messages?|voicenotes?|voice|audios?|audio messages?|photos?|pictures?|pics?|images?|" +
        "videos?|documents?|docs?|files?|messages?|texts?"
    private val APP = "telegram|whatsapp|whats app|messenger|signal"
    private val FINISH = "immediately|right away|straight away|at once|right then|as soon as (?:it|that|she|he|they) (?:comes|arrives?|lands?)|for me|please"

    private val ORDER = Regex(
        "(?i)^(?:please\\s+|hey\\s+\\S+[,\\s]+)?(?:when|whenever|every time|each time|anytime|any time|if|once)\\s+" +
            "(.{1,40}?)\\s+(?:sends?|shares?|drops?|forwards?)\\s+(?:me\\s+)?(?:a\\s+|an\\s+|any\\s+|some\\s+)?($KIND_WORD)" +
            "(?:\\s+(?:to me\\s+)?(?:on|in|via|through|over)\\s+($APP))?(?:\\s+to me)?\\s*[,;:]?\\s+" +
            "(?:then\\s+|just\\s+|please\\s+|you\\s+should\\s+|i want you to\\s+)*(?:forward|send|share|move|put|save)\\s+" +
            "(?:it|that|them|those|the\\s+(?:$KIND_WORD))\\s+(?:immediately\\s+|straight\\s+)?(?:to|in|into)\\s+" +
            "(.{1,40}?)(?:\\s+(?:on|in|via)\\s+($APP))?(?:\\s*,?\\s+(?:$FINISH))*$"
    )

    private fun app(word: String?): String? = when (word?.trim()?.lowercase()) {
        null, "" -> null
        "telegram" -> "Telegram"; "whatsapp", "whats app" -> "WhatsApp"; "messenger" -> "Messenger"; "signal" -> "Signal"
        else -> null
    }

    fun kindOfWord(word: String): String {
        val w = word.trim().lowercase()
        return when {
            w.startsWith("voice") || w.startsWith("audio") -> "voice_note"
            w.startsWith("photo") || w.startsWith("pic") || w.startsWith("image") -> "photo"
            w.startsWith("video") -> "video"
            w.startsWith("doc") || w.startsWith("file") -> "document"
            else -> "message"
        }
    }

    /** The order in the words, or null when they aren't one (the caller carries on as if this had not been asked). */
    fun parse(raw: String, nowMs: Long = 0L): StandingOrder? {
        // A spoken pause typed as "..." between the two halves reads as a comma.
        val words = raw.trim().replace('’', '\'').replace(Regex("\\.{2,}|…"), ", ").replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ',', ' ')
        val m = ORDER.matchEntire(words) ?: return null
        return build(m.groupValues[1], kindOfWord(m.groupValues[2]), app(m.groupValues[3]), m.groupValues[4], app(m.groupValues[5]), nowMs)
    }

    /** One door for the phrase parser and the planner's tool, so both obey the same limits. */
    fun build(who: String, kind: String, fromApp: String?, destination: String, toApp: String?, nowMs: Long = 0L): StandingOrder? {
        val person = who.trim().trimEnd(',', '.').replace(Regex("(?i)^(?:my|the)\\s+"), "")
        if (person.isEmpty() || ANYONE.matches(person) || kind !in KINDS) return null
        val dest = canonicalDestination(destination)
        if (dest.isEmpty()) return null
        return StandingOrder("order:" + person.lowercase() + ":" + kind, person.replaceFirstChar { it.uppercase() }, kind, fromApp, dest, toApp, nowMs)
    }

    private val STOP = Regex(
        // The verb and the possessive are required: "don't read my messages" or "do not send Kofi a message" are not about an order.
        "(?i)^(?:please\\s+)?(?:stop|cancel)\\s+(?:forwarding|sending|saving|sharing|moving)\\s+" +
            "([\\p{L}][\\p{L} -]{0,39}?)'s\\s+($KIND_WORD)$"
    )

    /** "stop forwarding Lydia's voice notes" → ("Lydia", "voice_note"); null when it isn't that. */
    fun parseStop(raw: String): Pair<String, String>? {
        val m = STOP.matchEntire(raw.trim().replace('’', '\'').trimEnd('.', '!', '?')) ?: return null
        val who = m.groupValues[1].trim().replace(Regex("(?i)^(?:my|the)\\s+"), "")
        if (who.isEmpty() || ANYONE.matches(who)) return null
        return who to kindOfWord(m.groupValues[2])
    }

    // What the user calls them (A20e 6 Nov 2026: "what rules have I set" went to the brain). A bare "orders" stays out — "show me
    // my orders" is the user's shopping — and every form says the rules are the USER's ("my", "have I set"), so "what are the
    // rules of football" is not this.
    private const val KIND = "(?:standing\\s+(?:orders?|rules?|instructions?)|rules|instructions)"
    private val LIST = Regex("(?i)^(?:" +
        "(?:what|which)\\s+(?:are\\s+)?(?:my\\s+|your\\s+)?(?:standing orders?|standing instructions?|(?:are you|am i having you)\\s+(?:watching|waiting)\\s+for|orders? (?:do|have) (?:i|you) (?:have|given|got))" +
        "|(?:what|which)\\s+(?:are\\s+)?my\\s+$KIND" +
        "|(?:what|which)\\s+$KIND\\s+(?:have|did|do)\\s+i\\s+(?:set|give|given|made|make|have|got)" +
        "|(?:list|show|tell|read|say)(?:\\s+me)?\\s+(?:all\\s+)?(?:of\\s+)?my\\s+$KIND" +
        "|(?:list|show|tell|read|say)(?:\\s+me)?\\s+(?:all\\s+)?(?:of\\s+)?the\\s+standing\\s+(?:orders?|rules?|instructions?)" +
        ").*$")
    fun isListRequest(raw: String): Boolean = LIST.matches(raw.trim().trimEnd('.', '!', '?'))

    fun describe(o: StandingOrder): String {
        val from = o.fromApp?.let { " on $it" }.orEmpty()
        val to = o.toApp?.let { " on $it" }.orEmpty()
        val what = spokenKind(o.kind)
        return if (o.toSelf) "when ${o.who} sends a $what$from, I forward it to your ${o.destination}$to"
            else "when ${o.who} sends a $what$from, I tell you, so you can decide about sending it to ${o.destination}$to"
    }

    private val STRIP_PREFIX = Regex("^.{1,40}?:\\s+")
    private val DECOR = Regex("[^\\p{L}\\p{N}\\s():]")
    private val VOICE = Regex("(?i)^(?:voice (?:message|note)|audio(?: message)?)(?:\\s*\\(?\\d{1,2}:\\d{2}\\)?)?$|^(?:.{1,40}\\s)?sent (?:you )?(?:a |an )?(?:voice (?:message|note)|audio(?: message)?)$")
    private val PHOTO = Regex("(?i)^(?:photo|picture|image)$|^(?:.{1,40}\\s)?sent (?:you )?(?:a |an )?(?:photo|picture|image)$")
    private val VIDEO = Regex("(?i)^video(?:\\s*\\(?\\d{1,2}:\\d{2}\\)?)?$|^(?:.{1,40}\\s)?sent (?:you )?(?:a )?video$")
    private val DOC = Regex("(?i)^(?:document|file)$|^(?:.{1,40}\\s)?sent (?:you )?(?:a )?(?:document|file)$")

    /**
     * What a chat notification carries. Chat apps show an attachment as a short fixed line ("🎤 Voice message (0:12)"), so the WHOLE
     * line must be that form: a typed message saying "please send me a voice note" stays a message.
     */
    fun kindOfText(text: String?): String {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return "message"
        val clean = DECOR.replace(t.replace(STRIP_PREFIX, ""), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            VOICE.matches(clean) -> "voice_note"
            PHOTO.matches(clean) -> "photo"
            VIDEO.matches(clean) -> "video"
            DOC.matches(clean) -> "document"
            else -> "message"
        }
    }

    private fun wholeWord(term: String, field: String?): Boolean =
        !field.isNullOrBlank() && Regex("(?i)(?:^|[^\\p{L}\\p{N}])" + Regex.escape(term) + "(?:$|[^\\p{L}\\p{N}])").containsMatchIn(field)

    /**
     * Does this notification fire [o]? In a group the sender decides: a group whose NAME holds "Lydia" doesn't count as Lydia. The
     * words of a notification are never searched for the person.
     */
    fun matches(o: StandingOrder, rec: NotificationRecord): Boolean {
        if (rec.ongoing || !o.valid) return false
        val from = rec.sender?.takeIf { it.isNotBlank() } ?: rec.title
        if (!wholeWord(o.who, from)) return false
        if (o.fromApp != null && !o.fromApp.equals(rec.appLabel, true)) return false
        return o.kind == "message" || kindOfText(rec.text) == o.kind
    }

    /** The request for the planner, made ONLY from the user's stored words. */
    fun goal(o: StandingOrder, sourceApp: String?, count: Int): String {
        val what = spokenKind(o.kind)
        val which = if (count > 1) "the $count newest ${what}s" else "the newest $what"
        val from = sourceApp?.let { " on $it" }.orEmpty()
        val to = (o.toApp ?: sourceApp)?.let { " on $it" }.orEmpty()
        return "${o.who} just sent a $what$from. Forward $which from ${o.who} to ${o.destination}$to, unchanged. " +
            "Open the chat with ${o.who}, long-press that $what, choose Forward, choose ${o.destination}, then send. Write no message of your own."
    }

    fun encode(list: List<StandingOrder>): String = JSONArray().apply {
        for (o in list) put(JSONObject().put("id", o.id).put("who", o.who).put("kind", o.kind).put("fromApp", o.fromApp ?: JSONObject.NULL)
            .put("destination", o.destination).put("toApp", o.toApp ?: JSONObject.NULL).put("createdAt", o.createdAt))
    }.toString()

    fun decode(json: String?): List<StandingOrder> {
        val a = try { JSONArray(json ?: return emptyList()) } catch (_: Throwable) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            fun opt(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
            StandingOrder(o.optString("id"), o.optString("who"), o.optString("kind"), opt("fromApp"), o.optString("destination"),
                opt("toApp"), o.optLong("createdAt")).takeIf { it.valid && it.id.isNotBlank() }
        }
    }
}

/**
 * What one standing-order run may do without the user. Everything outside it stays refused or stops the run.
 * [packages] = the apps it may approve a tap in: the one the message arrived in, the destination's, and the system share sheet.
 */
data class StandingGrant(val orderId: String, val destination: String, val packages: Set<String>) {
    /** The ordinary "trigger" tools plus just what forwarding on a screen needs. `type` only ever types the destination's name. */
    fun mayCall(tool: String, args: JSONObject): Boolean = when (tool) {
        "tap", "long_press", "scroll", "swipe", "node_action" -> true
        "type" -> args.optString("text").trim().equals(destination, true) && !args.optBoolean("submit", false)
        else -> false
    }

    /** May this confirmation be answered yes? Only a tap on Forward / Send / the destination, in an allowed app; Send only with
     *  the destination's name on the screen, so a misdirected picker is never approved. */
    fun approves(tool: String, label: String?, appPackage: String?, screenWords: String): Boolean {
        if (tool != "tap" && tool != "node_action") return false
        if (appPackage == null || appPackage !in packages) return false
        val l = label?.trim().orEmpty()
        if (l.isEmpty()) return false
        if (l.equals(destination, true) || FORWARD.containsMatchIn(l)) return true
        return SEND.containsMatchIn(l) && screenWords.contains(destination, ignoreCase = true)
    }

    companion object {
        private val FORWARD = Regex("(?i)^(?:forward|share)\\b")
        private val SEND = Regex("(?i)^send\\b")
        val SHARE_SHEET = setOf("android", "com.android.intentresolver", "com.google.android.intentresolver", "com.samsung.android.intentresolver")
        val APP_PACKAGES = mapOf(
            "telegram" to setOf("org.telegram.messenger", "org.telegram.messenger.web"),
            "whatsapp" to setOf("com.whatsapp", "com.whatsapp.w4b"),
            "messenger" to setOf("com.facebook.orca"),
            "signal" to setOf("org.thoughtcrime.securesms")
        )
    }
}

/** Storage, the notification door and the waiting line. */
object StandingOrders {
    const val FEATURE = NotificationTriggers.FEATURE      // "alerts" off in Settings stops these too
    private const val KEY = "standing_orders"
    private const val POLL_MS = 15_000L
    private const val PENDING_MAX_MS = 12 * 3_600_000L    // a day-old voice note is not "immediately": the user is told instead
    private const val RUN_TIMEOUT_MS = 180_000L
    private const val REPOST_MS = 5_000L

    private data class Pending(val order: StandingOrder, val sourcePackage: String, val sourceApp: String?, var count: Int, val firstAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayList<Pending>()
    private val cooldown = Cooldown()
    private var pump: Job? = null

    @Synchronized fun list(ctx: Context): List<StandingOrder> = StandingOrderWords.decode(TriggerGate.prefs(ctx).getString(KEY, null))

    @Synchronized
    fun add(ctx: Context, o: StandingOrder): Boolean {
        if (!o.valid) return false
        TriggerGate.prefs(ctx).edit().putString(KEY, StandingOrderWords.encode(list(ctx).filter { it.id != o.id } + o)).apply()
        Log.i(TriggerGate.TAG, "[STANDING] added ${o.kind} toSelf=${o.toSelf}")   // never the person's name
        return true
    }

    /** Removes matching orders, and any firing of them still waiting. */
    @Synchronized
    fun remove(ctx: Context, who: String, kind: String?): Int {
        val all = list(ctx)
        val keep = all.filterNot { it.who.equals(who.trim(), true) && (kind == null || it.kind == kind) }
        TriggerGate.prefs(ctx).edit().putString(KEY, StandingOrderWords.encode(keep)).apply()
        queue.removeAll { p -> keep.none { it.id == p.order.id } }
        return all.size - keep.size
    }

    @Synchronized
    fun clear(ctx: Context) { TriggerGate.prefs(ctx).edit().remove(KEY).apply(); queue.clear() }

    /** From the listener, live posts only. Cheap: the work happens on the pump. */
    fun onPosted(ctx: Context, rec: NotificationRecord) {
        try {
            if (!FeatureFlags.isEnabled(FEATURE, true) || rec.packageName == ctx.packageName) return
            val app = ctx.applicationContext
            val hits = list(app).filter { StandingOrderWords.matches(it, rec) }
            if (hits.isEmpty()) return
            val now = System.currentTimeMillis()
            synchronized(this) {
                for (o in hits) {
                    if (!cooldown.tryAcquire(o.id + "|" + rec.key + "|" + rec.text.hashCode(), now, REPOST_MS)) continue
                    val waiting = queue.firstOrNull { it.order.id == o.id }
                    if (waiting != null) waiting.count++ else queue.add(Pending(o, rec.packageName, rec.appLabel, 1, now))
                    Log.i(TriggerGate.TAG, "[STANDING] ${o.kind} fired from ${rec.packageName}")
                }
                if (pump?.isActive != true) pump = scope.launch { drain(app) }
            }
        } catch (t: Throwable) {
            Log.w(TriggerGate.TAG, "[STANDING] onPosted failed: ${t.message}")
        }
    }

    private fun ready(ctx: Context): Boolean {
        if (com.tekyerema.core.DeviceLock.isLocked(ctx)) return false
        if (ctx.getSystemService(PowerManager::class.java)?.isInteractive == false) return false
        return com.tekyerema.core.NetworkMonitor.isOnline()
    }

    private suspend fun drain(app: Context) {
        while (true) {
            val next = synchronized(this) { queue.firstOrNull() } ?: return
            val expired = System.currentTimeMillis() - next.firstAt > PENDING_MAX_MS
            val who = next.order.who
            val what = StandingOrderWords.spokenKind(next.order.kind)
            when {
                expired -> {
                    synchronized(this) { queue.remove(next) }
                    tellQuietly(app, "$who sent a $what a while ago. I couldn't get to it, so I didn't forward it.")
                }
                !next.order.toSelf -> {
                    // To another person: nothing goes without the user's yes, so the order only tells.
                    if (withContext(Dispatchers.Main) { TriggerGate.userBusy(app) }) { delay(POLL_MS); continue }
                    synchronized(this) { queue.remove(next) }
                    tellQuietly(app, "$who sent a $what. You wanted it sent to ${next.order.destination}. Tell me when you'd like me to do that.")
                }
                !ready(app) || withContext(Dispatchers.Main) { TriggerGate.userBusy(app) } -> delay(POLL_MS)
                else -> {
                    synchronized(this) { queue.remove(next) }
                    run(app, next)
                }
            }
        }
    }

    private fun tellQuietly(app: Context, line: String) {
        if (!QuietHours.isQuiet(System.currentTimeMillis())) TriggerGate.speak(app, line) else
            try { VoiceSessionService.postResult(app, line) } catch (_: Throwable) {}
    }

    private suspend fun run(app: Context, p: Pending) {
        val o = p.order
        val goal = StandingOrderWords.goal(o, p.sourceApp, p.count)
        val toPkgs = o.toApp?.let { StandingGrant.APP_PACKAGES[it.lowercase()] }.orEmpty()
        val grant = StandingGrant(o.id, o.destination, setOf(p.sourcePackage) + toPkgs + StandingGrant.SHARE_SHEET)
        val what = StandingOrderWords.spokenKind(o.kind)
        val accepted = withContext(Dispatchers.Main) {
            if (TriggerGate.userBusy(app)) false
            else { AgentEngine.submitGoal(goal, unattended = true, grant = grant); AgentEngine.state.value.goal == goal }
        }
        if (!accepted) { synchronized(this) { queue.add(0, p) }; delay(POLL_MS); return }
        val end = withTimeoutOrNull(RUN_TIMEOUT_MS) {
            AgentEngine.state.first { it.goal == goal && it.status !in setOf(AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.IDLE) }
        }
        val status = end?.status
        Log.i(TriggerGate.TAG, "[STANDING] run ended: $status")
        when (status) {
            AgentStatus.COMPLETED -> tellQuietly(app, "${o.who} sent a $what. I forwarded it to your ${o.destination}.")
            AgentStatus.COMPLETED_UNVERIFIED -> tellQuietly(app, "${o.who} sent a $what. I tried to forward it to your ${o.destination}, but I can't confirm it went.")
            AgentStatus.STOPPED -> Unit   // the user stopped it
            else -> {
                withContext(Dispatchers.Main) {
                    val st = AgentEngine.state.value
                    if (st.goal == goal && TriggerGate.isAgentBusy(st.status)) AgentEngine.stop()
                }
                val why = end?.log?.lastOrNull { it.kind == LogEntry.Kind.RESULT }?.text?.takeIf { it.isNotBlank() }
                tellQuietly(app, "${o.who} sent a $what, but I didn't forward it." + (why?.let { " $it" }.orEmpty()))
            }
        }
    }
}
