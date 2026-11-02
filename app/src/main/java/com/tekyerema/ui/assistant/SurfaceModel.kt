package com.tekyerema.ui.assistant

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentStep
import com.tekyerema.agent.AgentUiState
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.VoiceUiState
import org.json.JSONObject

/**
 * The assistant surface's state — a PURE function of the two states the app already keeps ([VoiceUiState] from the voice
 * layer, [AgentUiState] from the agent) plus what the user did to the surface itself ([Local]: dismissed or expanded for a
 * run). No second copy of task state, no timers, nothing the agent did not do (docs/assistant-ux/DECISIONS.md UX-1):
 *  - the activity timeline is [AgentUiState.steps] of the CURRENT run only (a late step of an older run can't appear);
 *  - the result preview is built from the run's real outcome (its summary, a search's sources, a map link);
 *  - a confirmation or a question is the agent's own pending one, with its own sequence number.
 * Unit-tested (AssistantSurfaceModelTest).
 */
object SurfaceModel {

    enum class Mode { HIDDEN, PROCESSING, WORKING, WAITING, PRESENTING, FAILED, CANCELLED }

    /** A step as the user sees it. [active] = the one running now; a RUNNING step that is not the latest was superseded
     *  (shown neutral — never ticked, because nothing confirmed it). */
    data class StepView(val key: String, val label: String, val state: AgentStep.State, val active: Boolean,
                        /** The finished step in the past tense ("Opened battery settings"), shown once the engine reports it done. */
                        val past: String = label)

    sealed class Preview {
        /** An answer or a finished task's summary. [sources] only when a search really returned them; [mapQuery] only
         *  for a request about a place, opened in the phone's maps app (which does its own search — nothing invented). */
        data class Answer(val title: String, val summary: String, val body: String,
                          val sources: List<AgentStep.Source> = emptyList(), val mapQuery: String? = null,
                          val mapLink: String? = null) : Preview()
        /** A device action that finished (the agent's own line, e.g. "Turned Wi-Fi on."). [verified] = the engine verified it. */
        data class Action(val title: String, val summary: String, val verified: Boolean) : Preview()
        /** It did not work: the honest line, and the request to try again (null when trying again makes no sense). */
        data class Problem(val title: String, val message: String, val retryGoal: String?) : Preview()
    }

    /** The agent waits on the user: the same prompt the voice reads ([PromptSurface]), with its confirmation number. */
    data class Waiting(val kind: PromptSurface.Kind, val text: String, val seq: Long)

    data class State(
        val runSeq: Long,
        val mode: Mode,
        val title: String = "",
        val steps: List<StepView> = emptyList(),
        val preview: Preview? = null,
        val waiting: Waiting? = null,
        /** The reply is being read aloud now (the stop control stops the reading, never an action). */
        val speaking: Boolean = false,
        /** The task is paused (nothing is moving: the sign holds still and TalkBack hears "Paused", not "Working on it"). */
        val paused: Boolean = false,
        /** What this surface is about: "run:N" for agent run N, "reply:T" for a reply the voice gave at T without a run. */
        val key: String = "run:$runSeq",
        /** Only "Listening…": the microphone is open and nothing else is going on (Home may show its own full-screen listening). */
        val listenOnly: Boolean = false
    ) {
        val visible: Boolean get() = mode != Mode.HIDDEN
    }

    /** What the user did to the surface: dismissed run N's result (at [dismissedAt]); dismissed the voice reply spoken at
     *  [dismissedReplyAt]. A new request also dismisses the previous run's result (Siri replaces the old snippet). */
    data class Local(val dismissedRun: Long = -1L, val dismissedAt: Long = 0L, val dismissedReplyAt: Long = -1L,
                     /** The reply closed by the user, by its key ("reply:<text>") — it may have been closed while still spoken. */
                     val dismissedReplyKey: String = "")

    /** A result stays on the surface this long after it arrived (an hours-old answer is not "the" answer). */
    const val FRESH_MS = 10 * 60_000L

    private val TERMINAL = setOf(AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED, AgentStatus.CANNOT_DO,
        AgentStatus.FAILED, AgentStatus.STOPPED)

    fun reduce(voice: VoiceUiState, agent: AgentUiState, local: Local = Local(), now: Long = System.currentTimeMillis()): State {
        val run = agent.runSeq
        val steps = stepsOf(agent)
        // The agent waits on the user — its own prompt, whatever the voice is doing.
        PromptSurface.of(agent.status, PromptSurface.confirmationText(agent.pendingConfirmation, agent.pendingConfirmationTwi),
            PromptSurface.confirmationText(agent.pendingQuestion, agent.pendingQuestionTwi), agent.confirmationSeq)?.let { p ->
            return State(run, Mode.WAITING, titleOfWaiting(p.kind, p.text), steps, waiting = Waiting(p.kind, p.text, p.seq))
        }
        // The engine has its answer (the prompt is cleared) but hasn't moved on yet: still working, never a blink to hidden.
        if (agent.status == AgentStatus.WAITING_CONFIRMATION || agent.status == AgentStatus.ASKING || agent.status == AgentStatus.SECURE_INPUT)
            return State(run, Mode.PROCESSING, "Working on it", steps)
        val inRun = agent.status == AgentStatus.THINKING || agent.status == AgentStatus.ACTING || agent.status == AgentStatus.PAUSED
        if (inRun) {
            val active = steps.lastOrNull { it.active }
            if (agent.status == AgentStatus.PAUSED) return State(run, Mode.PROCESSING, "Paused", steps, paused = true)
            return if (active == null) State(run, Mode.PROCESSING, "Working on it", steps)
            else State(run, Mode.WORKING, active.label, steps)
        }
        // The microphone is open and nothing else is waiting (a follow-up after a reply, the icon pressed): say so — a beep alone
        // is easy to miss (owner, 19 Oct 2026: "always show that it is listening").
        if (voice.state == VoiceState.LISTENING) return State(run, Mode.PROCESSING, "Listening…", listenOnly = true)
        // Understanding what was said (no run yet): a quiet processing state, nothing more to claim.
        if (voice.state == VoiceState.CAPTURED || voice.state == VoiceState.TRANSCRIBING || voice.state == VoiceState.UNDERSTANDING)
            return State(run, Mode.PROCESSING, "Understanding")
        val speaking = voice.state == VoiceState.SPEAKING
        // When the run ended: its result line, or — for a stopped run, which writes none — its last line (the log is cleared
        // when a run starts). A time newer than the host's clock counts as fresh (the clock ticks only on surface changes);
        // 0 = no log yet.
        val resultAt = (agent.log.lastOrNull { it.kind == com.tekyerema.agent.LogEntry.Kind.RESULT } ?: agent.log.lastOrNull())?.timeMs ?: 0L
        val runFresh = resultAt == 0L || now - resultAt <= FRESH_MS
        // A run that finished and was marked STOPPED afterwards (Stop pressed with nothing running) keeps what it really did:
        // its own result, carrying this run's number, says how it ended (emulator, 14 Oct 2026: a finished answer read "Stopped").
        val ownStatus = (try { agent.lastResult?.let { JSONObject(it) } } catch (_: Throwable) { null })
            ?.takeIf { it.optLong("runSeq", -1L) == run }?.optString("status")
            ?.let { s -> AgentStatus.values().firstOrNull { it.name == s } }
        val a = if (agent.status == AgentStatus.STOPPED && ownStatus != null && ownStatus != AgentStatus.STOPPED && ownStatus in TERMINAL)
            agent.copy(status = ownStatus) else agent
        if (a.status in TERMINAL && run > 0 && run != local.dismissedRun && runFresh) {
            val preview = previewOf(a, steps, voice) ?: return State(run, Mode.HIDDEN)
            val mode = when (preview) {
                is Preview.Problem -> if (a.status == AgentStatus.STOPPED) Mode.CANCELLED else Mode.FAILED
                else -> Mode.PRESENTING
            }
            val title = when (preview) { is Preview.Answer -> preview.title; is Preview.Action -> preview.title; is Preview.Problem -> preview.title }
            return State(run, mode, title, steps, preview = preview, speaking = speaking)
        }
        // A reply the voice gave WITHOUT an agent run (the phone's own quick answers: the time, the battery, the volume) —
        // shown as itself, never under an older run's title. Only one newer than the last dismissal, and fresh.
        // While the voice reads a quick answer, its words are in partialHint (spoken/spokenAt are set only when it finishes):
        // show them AS they are spoken — before, the reply sat mid-screen until the voice had stopped (owner, A20e, 14 Oct).
        val speakingLine = if (speaking) voice.partialHint.trim() else ""
        val reply = speakingLine.ifEmpty { voice.spoken.trim() }
        val replyKey = "reply:" + reply.hashCode().toUInt().toString(36)   // the same key while spoken and after
        val replyFresh = speakingLine.isNotEmpty() ||
            (voice.spokenAt > local.dismissedAt && voice.spokenAt != local.dismissedReplyAt && now - voice.spokenAt <= FRESH_MS)
        if (reply.isNotEmpty() && replyFresh && replyKey != local.dismissedReplyKey &&
            (agent.status !in TERMINAL || run == local.dismissedRun || !runFresh)) {
            val asked = titleOf(voice.lastCanonical.ifBlank { voice.lastRaw })
            // Nothing was asked (a notice like "That was too short. Tap and try again."): the card is titled by who is speaking,
            // not "Answer" — there was no question (redesign, 2 Nov 2026).
            val heading = asked.ifEmpty { com.tekyerema.ui.Brand.NAME }
            return State(run, Mode.PRESENTING, heading, key = replyKey,
                preview = Preview.Answer(heading, Summaries.compact(reply), reply), speaking = speaking)
        }
        return State(run, Mode.HIDDEN)
    }

    // ------------------------------------------------------------------ steps

    /** Tools that are bookkeeping, not something a person would call a step (reading the screen, waiting, asking). */
    private val QUIET = setOf("wait", "read_state", "read_screen", "read_all", "find_tool", "ask_user", "observe", "cannot_do",
        "describe_screen")

    internal fun stepsOf(agent: AgentUiState): List<StepView> {
        val mine = agent.steps.filter { it.runSeq == agent.runSeq && it.tool !in QUIET }
        val running = agent.status == AgentStatus.THINKING || agent.status == AgentStatus.ACTING
        val lastRunning = if (running) mine.lastOrNull()?.takeIf { it.state == AgentStep.State.RUNNING } else null
        // One line per distinct label in a row ("Tapping…" four times is one line of activity).
        val out = ArrayList<StepView>()
        for (s in mine) {
            val label = StepLabels.label(s.tool, s.subject)
            val view = StepView("${s.runSeq}:${s.index}", label, s.state, active = s === lastRunning, past = StepLabels.done(s.tool, s.subject))
            if (out.isNotEmpty() && out.last().label == label) out[out.lastIndex] = view else out += view
        }
        return out.takeLast(MAX_STEPS)
    }

    /** The timeline shows the latest few; the expanded view shows all of this run's. */
    const val MAX_STEPS = 12
    const val COMPACT_STEPS = 3

    // ------------------------------------------------------------------ previews

    internal fun previewOf(agent: AgentUiState, steps: List<StepView>, voice: VoiceUiState): Preview? {
        // Only THIS run's words. A stopped run writes no result, so lastResult still holds the previous run's (the emulator
        // showed it under the new run's "Stopped", 14 Oct 2026): a result counts only with this run's number. The log is
        // cleared when a run starts, so its lines are this run's; the voice's last reply counts only if spoken during it.
        val result = (try { agent.lastResult?.let { JSONObject(it) } } catch (_: Throwable) { null })
            ?.takeIf { it.optLong("runSeq", -1L) == agent.runSeq }
        val runStart = agent.log.firstOrNull()?.timeMs ?: Long.MAX_VALUE
        val own = result?.optString("summary")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?: agent.log.lastOrNull { it.kind == com.tekyerema.agent.LogEntry.Kind.RESULT }?.text?.trim()
        // A stopped run says only its own words (or that it stopped) — never a line the voice said along the way.
        val summary = (own ?: voice.spoken.trim().takeIf { voice.spokenAt >= runStart && agent.status != AgentStatus.STOPPED }).orEmpty()
        val goal = (agent.request ?: agent.goal).orEmpty().trim()
        val title = titleOf(goal)
        if (summary.isEmpty() && agent.status != AgentStatus.STOPPED) return null
        return when (agent.status) {
            AgentStatus.STOPPED -> Preview.Problem("Stopped", summary.ifBlank { "Stopped before it finished." }, retryGoal = goal.ifEmpty { null })
            AgentStatus.FAILED, AgentStatus.CANNOT_DO -> Preview.Problem(title.ifEmpty { "Couldn't finish" }, summary,
                retryGoal = goal.takeIf { it.isNotEmpty() && agent.status == AgentStatus.FAILED })
            else -> {
                val mine = agent.steps.filter { it.runSeq == agent.runSeq }
                val sources = mine.filter { it.state == AgentStep.State.DONE }.flatMap { it.sources }.distinctBy { it.url }.take(6)
                val mapLink = mine.lastOrNull { it.state == AgentStep.State.DONE && it.link != null }?.link
                val acted = mine.any { it.tool !in QUIET && it.tool !in READS }
                when {
                    sources.isNotEmpty() || mapLink != null || !acted ->
                        Preview.Answer(title.ifEmpty { "Answer" }, Summaries.compact(summary), summary, sources,
                            PlaceQuery.of(goal), mapLink)
                    else -> Preview.Action(title.ifEmpty { "Done" }, summary, verified = agent.status == AgentStatus.COMPLETED)
                }
            }
        }
    }

    /** Tools that look something up rather than change anything (their run's result is an answer, not an action). */
    private val READS = setOf("web_search", "search_web", "read_notifications", "search_messages", "find_contact", "search_contacts",
        "get_location", "where_am_i", "my_location", "battery", "get_time", "calendar_events", "read_calendar", "money_status")

    /** "explain the difference between photosynthesis and respiration" → "Explain the difference between photosynthesis and
     *  respiration" — the user's own request as the preview's title, shortened at a word. */
    internal fun titleOf(goal: String): String {
        val g = goal.trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
            .replace(Regex("^(?i)(?:hey\\s+t[eɛ]kyer[eɛ]ma[,!]?\\s+|okay\\s+|ok\\s+|so\\s+|please\\s+|can you\\s+|could you\\s+|i want you to\\s+)+"), "")
        if (g.isEmpty()) return ""
        val cut = if (g.length <= 72) g else g.take(72).substringBeforeLast(' ') + "…"   // two header lines
        return cut.replaceFirstChar { it.uppercaseChar() }
    }

    /** An announced call (policy/ConfirmTiers: "Calling Kofi Demo… Say stop to cancel.") is a preview, not a question. */
    internal fun titleOfWaiting(kind: PromptSurface.Kind, text: String = ""): String =
        if (kind == PromptSurface.Kind.CONFIRM && text.trimStart().startsWith("Calling ")) "Calling" else titleOfWaiting(kind)

    internal fun titleOfWaiting(kind: PromptSurface.Kind): String = when (kind) {
        PromptSurface.Kind.CONFIRM -> "Please confirm"
        PromptSurface.Kind.QUESTION -> "One question"
        PromptSurface.Kind.SECURE -> "Your PIN — I'm not looking"
    }
}

/** User-facing words for a real step (the planner's tool and its whitelisted subject). Pure. */
object StepLabels {
    /** At most [n] characters, cut at a word: "…headlines in Ghana…", not "…headlines in Ghana t" (emulator, 14 Oct 2026). */
    internal fun clip(s: String, n: Int): String =
        if (s.length <= n) s else (s.take(n).substringBeforeLast(' ').ifEmpty { s.take(n) }).trimEnd(',', ';', ':') + "…"

    fun label(tool: String, subject: String?): String {
        val x = subject?.trim()?.takeIf { it.isNotEmpty() }
        return when (tool) {
            "web_search", "search_web" -> x?.let { "Searching the web for “${clip(it, 40)}”" } ?: "Searching the web"
            "open_app" -> x?.let { "Opening $it" } ?: "Opening the app"
            "close_app" -> x?.let { "Closing $it" } ?: "Closing the app"
            "install_app" -> x?.let { "Installing $it" } ?: "Installing the app"
            "open_setting" -> x?.let { "Opening ${it.replace('_', ' ')} settings" } ?: "Opening settings"
            "make_call" -> x?.let { "Calling $it" } ?: "Placing the call"
            "send_sms" -> x?.let { "Texting $it" } ?: "Sending the text"
            "reply_to_notification" -> x?.let { "Replying to $it" } ?: "Sending your reply"
            "read_notifications", "search_messages" -> "Reading your messages"
            "find_contact", "search_contacts" -> x?.let { "Finding $it in your contacts" } ?: "Finding the contact"
            "get_location", "where_am_i", "my_location" -> "Checking your location"
            "ussd_navigate" -> "Going through the Mobile Money menu"
            "telco_ussd" -> "Going through the network's menu"
            "run_ussd" -> "Dialling the network's code"
            "momo_send", "mobile_money" -> "Opening Mobile Money"
            "bank_ussd" -> "Going through the bank's menu"
            "set_alarm" -> "Setting the alarm"
            "set_timer" -> "Setting the timer"
            "set_volume", "volume_up", "volume_down", "set_range" -> "Adjusting the volume"
            "play_music" -> x?.let { "Playing ${clip(it, 48)}" } ?: "Playing music"
            "type" -> "Typing"
            "tap", "tap_sequence", "long_press" -> "Tapping on the screen"
            "scroll", "swipe" -> "Looking through the screen"
            "key_event" -> "Pressing a key"
            "open_url", "open_link" -> "Opening the link"
            "take_photo", "send_photo", "read_image", "camera" -> "Looking through the camera"
            else -> "Working on it"
        }
    }

    /** The same step once the engine reports it done, in the past tense: "Opened battery settings" (mission v2). */
    fun done(tool: String, subject: String?): String {
        val x = subject?.trim()?.takeIf { it.isNotEmpty() }
        return when (tool) {
            "web_search", "search_web" -> x?.let { "Searched the web for “${clip(it, 40)}”" } ?: "Searched the web"
            "open_app" -> x?.let { "Opened $it" } ?: "Opened the app"
            "close_app" -> x?.let { "Closed $it" } ?: "Closed the app"
            "install_app" -> x?.let { "Installed $it" } ?: "Installed the app"
            "open_setting" -> x?.let { "Opened ${it.replace('_', ' ')} settings" } ?: "Opened settings"
            "make_call" -> x?.let { "Called $it" } ?: "Placed the call"
            "send_sms" -> x?.let { "Texted $it" } ?: "Sent the text"
            "reply_to_notification" -> x?.let { "Replied to $it" } ?: "Sent your reply"
            "read_notifications", "search_messages" -> "Read your messages"
            "find_contact", "search_contacts" -> x?.let { "Found $it in your contacts" } ?: "Found the contact"
            "get_location", "where_am_i", "my_location" -> "Checked your location"
            "ussd_navigate" -> "Went through the Mobile Money menu"
            "telco_ussd" -> "Went through the network's menu"
            "run_ussd" -> "Dialled the network's code"
            "momo_send", "mobile_money" -> "Opened Mobile Money"
            "bank_ussd" -> "Went through the bank's menu"
            "set_alarm" -> "Set the alarm"
            "set_timer" -> "Set the timer"
            "set_volume", "volume_up", "volume_down", "set_range" -> "Adjusted the volume"
            "play_music" -> x?.let { "Played ${clip(it, 48)}" } ?: "Played music"
            "type" -> "Typed"
            "tap", "tap_sequence", "long_press" -> "Tapped on the screen"
            "scroll", "swipe" -> "Looked through the screen"
            "key_event" -> "Pressed a key"
            "open_url", "open_link" -> "Opened the link"
            "take_photo", "send_photo", "read_image", "camera" -> "Looked through the camera"
            else -> "Did a step"
        }
    }
}

/** The short form of a reply for the compact preview: its first sentence or two, at most ~180 characters. Pure. */
object Summaries {
    fun compact(text: String, max: Int = 180): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length <= max) return t
        val sentences = Regex("(?<=[.!?])\\s+").split(t)
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + 1 + s.length > max) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        val out = sb.toString().ifEmpty { t.take(max).substringBeforeLast(' ') + "…" }
        return if (out.length > max) out.take(max).substringBeforeLast(' ') + "…" else out
    }
}

/**
 * The place a request is about, for the phone's maps app: "Find the nearest shopping mall and show me how to get there" →
 * "nearest shopping mall". Only for requests that are clearly about a place or the way there; null otherwise. The maps
 * app searches it near the user itself — no place, distance or route is invented here. Pure.
 */
object PlaceQuery {
    private val ABOUT_PLACE = Regex("(?i)\\b(?:nearest|closest|near me|nearby|around here|directions?|how (?:do i|to) get|way to|where is|where's)\\b")
    private val NOUN = Regex("(?i)\\b((?:nearest|closest)\\s+[\\p{L} ]{2,40}?)(?=\\s+(?:and|from|to|near|around|in|please|so)\\b|[,.?!]|$)|" +
        "\\b(?:where is|where's|directions to|way to|get to)\\s+(?:the\\s+)?([\\p{L}0-9' ]{2,40}?)(?=\\s+(?:and|from|please)\\b|[,.?!]|$)|" +
        "\\b([\\p{L} ]{2,30}?)\\s+(?:near me|nearby|around here)\\b")

    fun of(goal: String): String? {
        if (!ABOUT_PLACE.containsMatchIn(goal)) return null
        val m = NOUN.find(goal) ?: return null
        val q = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).trim()
            .replace(Regex("(?i)^(?:find|show me|get me|the|a)\\s+"), "").trim()
        return q.takeIf { it.length >= 3 && it.split(' ').size <= 6 }
    }
}
