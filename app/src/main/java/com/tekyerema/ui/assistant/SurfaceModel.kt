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
    data class StepView(val key: String, val label: String, val state: AgentStep.State, val active: Boolean)

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
        /** What this surface is about: "run:N" for agent run N, "reply:T" for a reply the voice gave at T without a run. */
        val key: String = "run:$runSeq"
    ) {
        val visible: Boolean get() = mode != Mode.HIDDEN
    }

    /** What the user did to the surface: dismissed run N's result (at [dismissedAt]); dismissed the voice reply spoken at
     *  [dismissedReplyAt]. A new request also dismisses the previous run's result (Siri replaces the old snippet). */
    data class Local(val dismissedRun: Long = -1L, val dismissedAt: Long = 0L, val dismissedReplyAt: Long = -1L)

    /** A result stays on the surface this long after it arrived (an hours-old answer is not "the" answer). */
    const val FRESH_MS = 10 * 60_000L

    private val TERMINAL = setOf(AgentStatus.COMPLETED, AgentStatus.COMPLETED_UNVERIFIED, AgentStatus.CANNOT_DO,
        AgentStatus.FAILED, AgentStatus.STOPPED)

    fun reduce(voice: VoiceUiState, agent: AgentUiState, local: Local = Local(), now: Long = System.currentTimeMillis(),
               twi: Boolean = false): State {
        val run = agent.runSeq
        val steps = stepsOf(agent)
        // The agent waits on the user — its own prompt, whatever the voice is doing.
        PromptSurface.of(agent.status, PromptSurface.confirmationText(agent.pendingConfirmation, agent.pendingConfirmationTwi),
            PromptSurface.confirmationText(agent.pendingQuestion, agent.pendingQuestionTwi), agent.confirmationSeq, twi)?.let { p ->
            return State(run, Mode.WAITING, titleOfWaiting(p.kind), steps, waiting = Waiting(p.kind, p.text, p.seq))
        }
        val inRun = agent.status == AgentStatus.THINKING || agent.status == AgentStatus.ACTING || agent.status == AgentStatus.PAUSED
        if (inRun) {
            val active = steps.lastOrNull { it.active }
            return if (active == null) State(run, Mode.PROCESSING, if (agent.status == AgentStatus.PAUSED) "Paused" else "Working on it", steps)
            else State(run, Mode.WORKING, active.label, steps)
        }
        // Understanding what was said (no run yet): a quiet processing state, nothing more to claim.
        if (voice.state == VoiceState.CAPTURED || voice.state == VoiceState.TRANSCRIBING || voice.state == VoiceState.UNDERSTANDING)
            return State(run, Mode.PROCESSING, "Understanding")
        val speaking = voice.state == VoiceState.SPEAKING
        val resultAt = agent.log.lastOrNull { it.kind == com.tekyerema.agent.LogEntry.Kind.RESULT }?.timeMs ?: 0L
        val runFresh = resultAt == 0L || now - resultAt in 0..FRESH_MS
        if (agent.status in TERMINAL && run > 0 && run != local.dismissedRun && runFresh) {
            val preview = previewOf(agent, steps, voice) ?: return State(run, Mode.HIDDEN)
            val mode = when (preview) {
                is Preview.Problem -> if (agent.status == AgentStatus.STOPPED) Mode.CANCELLED else Mode.FAILED
                else -> Mode.PRESENTING
            }
            val title = when (preview) { is Preview.Answer -> preview.title; is Preview.Action -> preview.title; is Preview.Problem -> preview.title }
            return State(run, mode, title, steps, preview = preview, speaking = speaking)
        }
        // A reply the voice gave WITHOUT an agent run (the phone's own quick answers: the time, the battery, the volume) —
        // shown as itself, never under an older run's title. Only one newer than the last dismissal, and fresh.
        val reply = voice.spoken.trim()
        if (reply.isNotEmpty() && voice.spokenAt > local.dismissedAt && voice.spokenAt != local.dismissedReplyAt &&
            now - voice.spokenAt in 0..FRESH_MS && (agent.status !in TERMINAL || run == local.dismissedRun || !runFresh)) {
            val asked = titleOf(voice.lastCanonical.ifBlank { voice.lastRaw })
            return State(run, Mode.PRESENTING, asked.ifEmpty { "Answer" }, key = "reply:${voice.spokenAt}",
                preview = Preview.Answer(asked.ifEmpty { "Answer" }, Summaries.compact(reply), reply), speaking = speaking)
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
            val view = StepView("${s.runSeq}:${s.index}", label, s.state, active = s === lastRunning)
            if (out.isNotEmpty() && out.last().label == label) out[out.lastIndex] = view else out += view
        }
        return out.takeLast(MAX_STEPS)
    }

    /** The timeline shows the latest few; the expanded view shows all of this run's. */
    const val MAX_STEPS = 12
    const val COMPACT_STEPS = 3

    // ------------------------------------------------------------------ previews

    internal fun previewOf(agent: AgentUiState, steps: List<StepView>, voice: VoiceUiState): Preview? {
        val result = try { agent.lastResult?.let { JSONObject(it) } } catch (_: Throwable) { null }
        val summary = (result?.optString("summary")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?: agent.log.lastOrNull { it.kind == com.tekyerema.agent.LogEntry.Kind.RESULT }?.text?.trim()
            ?: voice.spoken.trim()).ifEmpty { return null }
        val goal = (agent.request ?: agent.goal).orEmpty().trim()
        val title = titleOf(goal)
        return when (agent.status) {
            AgentStatus.STOPPED -> Preview.Problem("Stopped", summary.ifBlank { "Stopped." }, retryGoal = goal.ifEmpty { null })
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

    private fun titleOfWaiting(kind: PromptSurface.Kind): String = when (kind) {
        PromptSurface.Kind.CONFIRM -> "Please confirm"
        PromptSurface.Kind.QUESTION -> "One question"
        PromptSurface.Kind.SECURE -> "Your PIN — I'm not looking"
    }
}

/** User-facing words for a real step (the planner's tool and its whitelisted subject). Pure. */
object StepLabels {
    fun label(tool: String, subject: String?): String {
        val x = subject?.trim()?.takeIf { it.isNotEmpty() }
        return when (tool) {
            "web_search", "search_web" -> x?.let { "Searching the web for “${it.take(40)}”" } ?: "Searching the web"
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
            "play_music" -> x?.let { "Playing $it" } ?: "Playing music"
            "type" -> "Typing"
            "tap", "tap_sequence", "long_press" -> "Tapping on the screen"
            "scroll", "swipe" -> "Looking through the screen"
            "key_event" -> "Pressing a key"
            "open_url", "open_link" -> "Opening the link"
            "take_photo", "read_image", "camera" -> "Looking through the camera"
            else -> "Working on it"
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
