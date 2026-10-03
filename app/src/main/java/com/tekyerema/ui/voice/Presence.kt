package com.tekyerema.ui.voice

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.voice.ConfirmPhase
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.VoiceUiState
import org.json.JSONObject

/** The single state the whole interface expresses — through motion, sound and a short line. */
enum class Presence { IDLE, LISTENING, THINKING, ACTING, CONFIRMING, CONFIRM_LISTENING, SPEAKING, DONE, ERROR }

/**
 * DEBUG ONLY: forces the Home visual into a given state for design review over ADB
 * (RunGoalReceiver `--es preview_presence LISTENING|...|off`). Null = live state.
 */
object UiDebug {
    val forcedPresence = kotlinx.coroutines.flow.MutableStateFlow<Presence?>(null)
}

/** Collapses the voice state machine and the agent status into one [Presence]. */
fun presenceOf(v: VoiceUiState, a: AgentUiState): Presence = when {
    // Confirmation by voice runs for any pending confirmation, whatever started the task.
    a.status == AgentStatus.WAITING_CONFIRMATION && v.confirmPhase == ConfirmPhase.LISTENING -> Presence.CONFIRM_LISTENING
    a.status == AgentStatus.WAITING_CONFIRMATION -> Presence.CONFIRMING
    // Secure Input Mode (MoMo PIN): keep the assistant present (not DONE/IDLE, so the screen never
    // auto-closes on the user mid-PIN) and let the spoken instruction + partialHint carry the meaning.
    a.status == AgentStatus.SECURE_INPUT -> Presence.CONFIRMING
    else -> presenceOfVoice(v, a)
}

private fun presenceOfVoice(v: VoiceUiState, a: AgentUiState): Presence = when (v.state) {
    VoiceState.LISTENING -> Presence.LISTENING
    VoiceState.CAPTURED, VoiceState.TRANSCRIBING, VoiceState.UNDERSTANDING -> Presence.THINKING
    VoiceState.EXECUTING -> when (a.status) {
        AgentStatus.ACTING -> Presence.ACTING
        AgentStatus.WAITING_CONFIRMATION, AgentStatus.ASKING -> Presence.CONFIRMING
        else -> Presence.THINKING
    }
    VoiceState.CONFIRMATION_REQUIRED -> Presence.CONFIRMING
    VoiceState.SPEAKING -> Presence.SPEAKING
    VoiceState.ERROR -> Presence.ERROR
    VoiceState.IDLE, VoiceState.CANCELLED, VoiceState.TYPING -> when (a.status) {   // typed/ADB goals with no voice turn
        AgentStatus.THINKING -> Presence.THINKING
        AgentStatus.ACTING -> Presence.ACTING
        AgentStatus.WAITING_CONFIRMATION, AgentStatus.ASKING -> Presence.CONFIRMING
        else -> Presence.IDLE
    }
}

/**
 * What Tɛkyerɛma says on screen for each state. Speech carries the real information for blind
 * users; this line is short, human and never technical. "Opening WhatsApp…" is derived from the
 * agent's current step without exposing tool names.
 */
object Phrases {
    fun forState(p: Presence, v: VoiceUiState, a: AgentUiState): String = when (p) {
        Presence.IDLE -> v.partialHint.ifBlank { "" }          // e.g. "I didn't hear anything."
        Presence.LISTENING -> v.partialHint.ifBlank { "I'm listening." }
        Presence.THINKING -> "Thinking…"
        Presence.ACTING -> acting(a)
        Presence.CONFIRMING, Presence.CONFIRM_LISTENING -> com.tekyerema.voice.PromptSurface.confirmationText(a.pendingConfirmation, a.pendingConfirmationTwi) ?: com.tekyerema.voice.PromptSurface.confirmationText(a.pendingQuestion, a.pendingQuestionTwi) ?: v.partialHint.ifBlank { "Do you want me to go ahead?" }
        Presence.SPEAKING -> v.partialHint.ifBlank { v.spoken }
        Presence.DONE -> v.spoken.ifBlank { "Done." }
        Presence.ERROR -> v.error.ifBlank { "Something went wrong." }
    }

    /** Humanize the newest planned step: "Step 3: open_app({"app":"WhatsApp"})" → "Opening WhatsApp…". */
    fun acting(a: AgentUiState): String {
        val plan = a.log.lastOrNull { it.kind == LogEntry.Kind.PLAN }?.text ?: return "Working on it…"
        val m = Regex("^Step \\d+: ([a-z_]+)\\((.*)\\)$").find(plan) ?: return "Working on it…"
        val tool = m.groupValues[1]
        val args = try { JSONObject(m.groupValues[2]) } catch (_: Throwable) { JSONObject() }
        fun s(k: String) = args.optString(k).takeIf { it.isNotBlank() }
        return when (tool) {
            "open_app" -> s("app")?.let { "Opening $it…" } ?: "Opening the app…"
            "make_call" -> (s("contact") ?: s("number"))?.let { "Calling $it…" } ?: "Placing the call…"
            "read_notifications", "search_messages" -> "Reading your messages…"
            "reply_to_notification" -> "Sending your reply…"
            "find_contact", "search_contacts" -> (s("query") ?: s("contact"))?.let { "Finding $it…" } ?: "Finding the contact…"
            "set_alarm" -> "Setting the alarm…"
            "set_timer" -> "Setting the timer…"
            "set_volume", "volume_up", "volume_down", "set_range" -> "Adjusting…"
            "run_ussd" -> "Dialling the code…"
            "type" -> "Typing…"
            "tap", "long_press" -> s("label")?.let { "Tapping $it…" } ?: "Tapping…"
            "scroll", "swipe" -> "Looking through the screen…"
            "read_state" -> "Reading the screen…"
            "wait" -> "One moment…"
            "key_event" -> "Pressing a key…"
            else -> "Working on it…"
        }
    }
}
