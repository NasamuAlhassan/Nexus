package com.tekyerema.voice

import com.tekyerema.agent.AgentStatus

/**
 * C-12 — what a NEW request (typed, the mic button, a trigger) does, given what Tɛkyerɛma is doing right now. A typed
 * request sent while the previous reply was still being read used to be dropped without a word ("Still busy" for a
 * sighted user, nothing at all for a blind one). Now nothing the user asks is lost:
 *
 *  - the task is waiting on the user (a confirmation, a question, the secure step) → it is the ANSWER;
 *  - a task is working → it goes to that task as an interjection (stop / pause / status / a correction, C-19);
 *  - only our own talking or listening is in the way (the task behind it is over) → PREEMPT: stop it, the new request
 *    wins (Gemini Live and Google Assistant stop talking the moment you tap or talk);
 *  - the turn is between hearing and acting (the fast path may be changing a setting that instant) → WAIT a moment,
 *    then route again, so a half-done action is never cut;
 *  - nothing in flight → START.
 *
 * Stopping our speech never stops a task and never answers a confirmation (B-07: barge-in may cancel a question but
 * never counts as yes). Pure → unit-tested.
 */
object NewRequest {
    enum class Route { START, PREEMPT, INTERJECT, ANSWER, WAIT }

    private val PROMPTS = setOf(AgentStatus.WAITING_CONFIRMATION, AgentStatus.ASKING, AgentStatus.SECURE_INPUT)

    /** Voice states in which the turn may be acting on the phone right now (the fast path runs in UNDERSTANDING). */
    private val ACTING = setOf(VoiceState.UNDERSTANDING, VoiceState.EXECUTING)

    /** Voice states where only our own speech or an idle tail is in the way — a trigger may preempt it. */
    val TALKING_OR_RESTING = setOf(VoiceState.SPEAKING, VoiceState.IDLE, VoiceState.ERROR, VoiceState.CANCELLED, VoiceState.TYPING)

    /**
     * @param agentStatus the agent's status now; [agentBusy] its task is running (a PAUSED task counts);
     * @param turnActive a voice turn (listen → act → speak) is in flight; [voice] its state.
     */
    fun route(agentStatus: AgentStatus, agentBusy: Boolean, turnActive: Boolean, voice: VoiceState): Route = when {
        agentStatus in PROMPTS -> Route.ANSWER
        agentBusy -> Route.INTERJECT
        !turnActive -> Route.START
        voice in ACTING -> Route.WAIT
        else -> Route.PREEMPT
    }
}
