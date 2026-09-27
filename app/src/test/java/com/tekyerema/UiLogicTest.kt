package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.LogEntry
import com.tekyerema.history.HistoryStore
import com.tekyerema.ui.voice.Phrases
import com.tekyerema.ui.voice.Presence
import com.tekyerema.ui.voice.presenceOf
import com.tekyerema.voice.Acknowledgements
import com.tekyerema.voice.Lang
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pure-logic tests for the interface layer: what is shown, said and remembered. */
class UiLogicTest {

    @Test
    fun acknowledgements_never_repeat_back_to_back() {
        val rnd = Random(7)
        var last = Acknowledgements.next(Lang.GH_EN, rnd)
        repeat(200) {
            val next = Acknowledgements.next(Lang.GH_EN, rnd)
            assertNotEquals("same phrase twice in a row", last, next)
            last = next
        }
    }

    @Test
    fun acknowledgements_use_twi_when_asked() {
        val twi = (1..20).map { Acknowledgements.next(Lang.TWI) }.toSet()
        assertTrue(twi.all { it in setOf("Meretie.", "Ka.", "Yoo.", "Ɛdeɛn na wohia?", "Mewɔ ha.") })
        assertTrue(twi.size > 1)
    }

    @Test
    fun history_title_is_past_tense_only_when_completed() {
        assertEquals("Called Prince", HistoryStore.titleFor("call Prince", ok = true))
        assertEquals("Call Prince", HistoryStore.titleFor("call Prince", ok = false))
        assertEquals("Set an alarm for 6 AM", HistoryStore.titleFor("set an alarm for 6 AM.", ok = true))
        assertEquals("Read my WhatsApp messages", HistoryStore.titleFor("  read my   WhatsApp messages ", ok = true))
        assertEquals("Turned on Wi-Fi", HistoryStore.titleFor("turn on Wi-Fi", ok = true))
    }

    @Test
    fun history_title_is_bounded() {
        val long = "call " + "Prince ".repeat(40)
        val t = HistoryStore.titleFor(long, ok = true)
        assertTrue(t.length <= 72)
        assertTrue(t.endsWith("…"))
    }

    @Test
    fun acting_phrase_hides_tool_names() {
        fun state(plan: String) = AgentUiState(status = AgentStatus.ACTING,
            log = listOf(LogEntry(0, LogEntry.Kind.PLAN, plan)))
        assertEquals("Opening WhatsApp…", Phrases.acting(state("""Step 2: open_app({"app":"WhatsApp"})""")))
        assertEquals("Calling Prince…", Phrases.acting(state("""Step 3: make_call({"contact":"Prince"})""")))
        assertEquals("Reading your messages…", Phrases.acting(state("""Step 1: read_notifications({})""")))
        assertEquals("Setting the alarm…", Phrases.acting(state("""Step 1: set_alarm({"hour":6,"minute":0})""")))
        assertEquals("Working on it…", Phrases.acting(AgentUiState(status = AgentStatus.ACTING)))
        val shown = Phrases.acting(state("""Step 4: tap({"element_id":12,"label":"Send"})"""))
        assertTrue(shown, !shown.contains("tap(") && !shown.contains("element_id"))
    }

    @Test
    fun presence_collapses_voice_and_agent_states() {
        val idle = AgentUiState()
        assertEquals(Presence.IDLE, presenceOf(VoiceUiState(VoiceState.IDLE), idle))
        assertEquals(Presence.LISTENING, presenceOf(VoiceUiState(VoiceState.LISTENING), idle))
        assertEquals(Presence.THINKING, presenceOf(VoiceUiState(VoiceState.TRANSCRIBING), idle))
        assertEquals(Presence.ACTING, presenceOf(VoiceUiState(VoiceState.EXECUTING), AgentUiState(status = AgentStatus.ACTING)))
        assertEquals(Presence.CONFIRMING, presenceOf(VoiceUiState(VoiceState.EXECUTING), AgentUiState(status = AgentStatus.WAITING_CONFIRMATION)))
        assertEquals(Presence.CONFIRM_LISTENING, presenceOf(VoiceUiState(VoiceState.EXECUTING, confirmPhase = com.tekyerema.voice.ConfirmPhase.LISTENING), AgentUiState(status = AgentStatus.WAITING_CONFIRMATION)))
        // A typed/ADB goal awaiting confirmation is also asked by voice.
        assertEquals(Presence.CONFIRM_LISTENING, presenceOf(VoiceUiState(VoiceState.IDLE, confirmPhase = com.tekyerema.voice.ConfirmPhase.LISTENING), AgentUiState(status = AgentStatus.WAITING_CONFIRMATION)))
        assertEquals(Presence.SPEAKING, presenceOf(VoiceUiState(VoiceState.SPEAKING), AgentUiState(status = AgentStatus.COMPLETED)))
        assertEquals(Presence.ERROR, presenceOf(VoiceUiState(VoiceState.ERROR), idle))
        // A typed/ADB goal with no voice turn still animates.
        assertEquals(Presence.ACTING, presenceOf(VoiceUiState(VoiceState.IDLE), AgentUiState(status = AgentStatus.ACTING)))
    }
}
