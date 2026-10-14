package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.ui.assistant.GlassTier
import com.tekyerema.ui.assistant.GlassTiers
import com.tekyerema.ui.assistant.SurfaceGesture
import com.tekyerema.ui.assistant.VoiceReflect
import com.tekyerema.ui.assistant.VoiceReflect.Cue
import com.tekyerema.ui.assistant.VoiceReflect.Decision
import com.tekyerema.voice.ConfirmPhase
import com.tekyerema.voice.ConfirmRetry
import com.tekyerema.voice.PromptSurface.Kind
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The surface only REFLECTS the voice pipeline and the engine (docs/assistant-ux/PLAN_V2.md, stage 5): every cue and every
 * "decided" highlight is read from real state; nothing here can answer a prompt.
 */
class VoiceReflectTest {
    private val t0 = 1_000_000L

    @Test fun the_listening_hint_appears_only_while_the_pipeline_really_listens() {
        assertEquals(Cue.Listening("Say yes or no, or tap."),
            VoiceReflect.cue(Kind.CONFIRM, VoiceUiState(confirmPhase = ConfirmPhase.LISTENING), hasChoices = false, twi = false))
        assertEquals("speaking the question: no hint", Cue.None,
            VoiceReflect.cue(Kind.CONFIRM, VoiceUiState(confirmPhase = ConfirmPhase.ASKING), hasChoices = false, twi = false))
        assertEquals(Cue.Heard, VoiceReflect.cue(Kind.CONFIRM, VoiceUiState(confirmPhase = ConfirmPhase.HEARD), false, false))
        assertEquals(Cue.None, VoiceReflect.cue(Kind.CONFIRM, VoiceUiState(), false, false))
        assertEquals("the PIN step listens for nothing on screen", Cue.None,
            VoiceReflect.cue(Kind.SECURE, VoiceUiState(confirmPhase = ConfirmPhase.LISTENING), false, false))
        assertEquals(Cue.Listening("Say your answer, or tap one."),
            VoiceReflect.cue(Kind.QUESTION, VoiceUiState(state = VoiceState.LISTENING), hasChoices = true, twi = false))
        assertEquals(Cue.None, VoiceReflect.cue(Kind.QUESTION, VoiceUiState(state = VoiceState.SPEAKING), true, false))
    }

    @Test fun asking_again_says_why_from_the_pipelines_own_reason() {
        assertEquals(Cue.AskingAgain("Didn't catch that. Asking again."),
            VoiceReflect.cue(Kind.CONFIRM, VoiceUiState(confirmPhase = ConfirmPhase.ASKING, confirmRetry = ConfirmRetry.UNCLEAR), false, false))
        assertEquals(Cue.Listening("Didn't catch that. Say yes or no, or tap."),
            VoiceReflect.cue(Kind.CONFIRM, VoiceUiState(confirmPhase = ConfirmPhase.LISTENING, confirmRetry = ConfirmRetry.UNCLEAR), false, false))
        assertEquals("a 'wait' is never shown as a miss", Cue.AskingAgain("Take your time. Asking again."),
            VoiceReflect.cue(Kind.CONFIRM, VoiceUiState(confirmPhase = ConfirmPhase.ASKING, confirmRetry = ConfirmRetry.HOLD), false, false))
    }

    @Test fun the_decision_shown_is_the_engines_own() {
        assertNull("still deciding: nothing highlighted", VoiceReflect.decision(AgentStatus.WAITING_CONFIRMATION, emptyList(), t0))
        assertEquals(Decision.APPROVED, VoiceReflect.decision(AgentStatus.ACTING, emptyList(), t0))
        assertEquals(Decision.DECLINED, VoiceReflect.decision(AgentStatus.STOPPED, emptyList(), t0))
        assertEquals(Decision.REVISED, VoiceReflect.decision(AgentStatus.THINKING,
            listOf(LogEntry(t0 + 10, LogEntry.Kind.POLICY, "User revised instead of approving: \"send it to Kofi\"")), t0))
        assertEquals("an older revision is not this one", Decision.APPROVED, VoiceReflect.decision(AgentStatus.ACTING,
            listOf(LogEntry(t0 - 10, LogEntry.Kind.POLICY, "User revised instead of approving")), t0))
        assertNull("can't-do is not a decision", VoiceReflect.decision(AgentStatus.CANNOT_DO, emptyList(), t0))
    }

    @Test fun the_choice_highlighted_is_the_answer_the_engine_received() {
        val choices = listOf("Prince Tesano AG youth Sec", "Prince Ice P", "Prince Alhassan ending 323")
        assertEquals("Prince Ice P", VoiceReflect.chosen(choices, listOf(LogEntry(t0 + 5, LogEntry.Kind.USER, "prince ice p")), t0))
        assertNull("an answer naming none of them highlights none",
            VoiceReflect.chosen(choices, listOf(LogEntry(t0 + 5, LogEntry.Kind.USER, "the other one")), t0))
        assertNull("the request itself, before the question, is not an answer",
            VoiceReflect.chosen(choices, listOf(LogEntry(t0 - 5, LogEntry.Kind.USER, "Prince Ice P")), t0))
    }

    @Test fun gestures_have_no_approving_outcome_at_all() {
        // MUST-PASS 1: the only things a released gesture can do; none of them answers a prompt.
        assertEquals(setOf("STAY", "EXPAND", "OPEN_INPUT", "COLLAPSE", "DISMISS"), SurfaceGesture.Settle.values().map { it.name }.toSet())
    }

    @Test fun the_light_holds_still_on_a_3gb_phone_and_when_saving_power() {
        val gb = 1_000_000_000L
        assertEquals("Galaxy A20e class", GlassTier.FLAT, GlassTiers.of(30, 2_800_000_000L, false, false, 1f, false))
        assertEquals(GlassTier.FULL, GlassTiers.of(34, 8 * gb, false, false, 1f, false))
        assertEquals(GlassTier.FLAT, GlassTiers.of(34, 8 * gb, false, powerSave = true, animatorScale = 1f, calm = false))
        assertEquals(GlassTier.FLAT, GlassTiers.of(34, 8 * gb, false, false, animatorScale = 0f, calm = false))
        assertEquals(GlassTier.FLAT, GlassTiers.of(34, 8 * gb, false, false, 1f, calm = true))
        assertEquals(GlassTier.FLAT, GlassTiers.of(34, 8 * gb, lowRam = true, powerSave = false, animatorScale = 1f, calm = false))
        assertEquals("a debug override", GlassTier.FULL, GlassTiers.of(30, 2_800_000_000L, false, false, 1f, false, override = "full"))
        assertEquals(GlassTier.FLAT, GlassTiers.of(34, 8 * gb, false, false, 1f, false, override = "flat"))
    }
}
