package com.tekyerema

import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.LanguagePipeline
import com.tekyerema.voice.Lang
import com.tekyerema.voice.PhoneticKey
import com.tekyerema.voice.SherpaAsr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-19 — what the recogniser gets wrong on the owner's own clips, and the pure fixes for it: the restart trim
 * (Whisper-tiny repeating its sentence into the padding) and the name correction against the phone's contacts/apps.
 * Every "heard" string below is a real Whisper-tiny output from audit/padding_experiment or the overnight tail bench.
 */
class AsrAccuracyTest {
    private val contacts = listOf("Kofi Mensah", "Kwame", "Ama", "Akosua Boateng", "Kwabena", "Prince", "Lydia", "Adjoa")
    private val apps = listOf("WhatsApp", "YouTube", "YT Music", "Google Maps", "Camera", "Settings", "Play Store", "Chrome")
    private val vocab = EntityCorrector.Vocab.of(contacts, apps)
    private fun fix(s: String) = EntityCorrector.correct(s, vocab).text

    @After fun reset() { LanguagePipeline.vocabulary = { com.tekyerema.voice.SpeechVocabulary.current() } }

    // ---------------------------------------------------------------- restart trim

    @Test fun restart_after_a_sentence_is_cut() {
        assertEquals("What time is it?", SherpaAsr.trimRestart("What time is it? What"))
        assertEquals("No, cancel.", SherpaAsr.trimRestart("No, cancel. No, cancel"))
        assertEquals("How much money do I have?", SherpaAsr.trimRestart("How much money do I have? How much"))
        assertEquals("That cost 12 cedis.", SherpaAsr.trimRestart("That cost 12 cedis. That cost 12"))
        assertEquals("100 cedis.", SherpaAsr.trimRestart("100 cedis. 100 ced"))     // the last repeat cut short
        assertEquals("This thing is too loud, turn it down.", SherpaAsr.trimRestart("This thing is too loud, turn it down. This thing is too"))
    }

    @Test fun real_second_sentences_are_kept() {
        assertEquals("Stop. Stop the music.", SherpaAsr.trimRestart("Stop. Stop the music."))
        assertEquals("Yes. Send it to Ama.", SherpaAsr.trimRestart("Yes. Send it to Ama."))
        assertEquals("Call Kofi. Then read my messages", SherpaAsr.trimRestart("Call Kofi. Then read my messages"))
        assertEquals("open WhatsApp", SherpaAsr.trimRestart("open WhatsApp"))
        assertEquals("", SherpaAsr.trimRestart(""))
    }

    @Test fun restart_then_repetition_guard_compose() {
        // tail-300 output on N03: the trailing run collapses, the rest is left as heard
        assertEquals("11 12 13 14 15", SherpaAsr.guardRepetition(SherpaAsr.trimRestart("11 12 13 14 15 15 15 15 15 15")))
    }

    // ---------------------------------------------------------------- phonetic key

    @Test fun sound_alike_names_share_a_key() {
        assertEquals(PhoneticKey.of("Kofi"), PhoneticKey.of("coffee"))
        assertEquals(PhoneticKey.of("Kwame"), PhoneticKey.of("Kwami"))
        assertEquals(PhoneticKey.of("Ama"), PhoneticKey.of("Amma"))
        assertEquals(PhoneticKey.of("Yaw"), PhoneticKey.of("Yao"))
        assertEquals(PhoneticKey.of("Kɔfi"), PhoneticKey.of("Kofi"))                   // Akan letters fold
        assertTrue(PhoneticKey.of("Emma") != PhoneticKey.of("Ama"))                    // different names stay apart
        assertTrue(PhoneticKey.of("Ben") != PhoneticKey.of("Bob"))
    }

    // ---------------------------------------------------------------- name correction

    @Test fun misheard_contact_names_are_corrected_in_their_slot() {
        assertEquals("message Kofi", fix("message coffee"))
        assertEquals("call Kwame.", fix("call Kwami."))
        assertEquals("send it to Ama", fix("send it to Amma"))
        assertEquals("Send 5 cedis to Ama.", fix("Send 5 cedis to Amma."))
        assertEquals("call Kwabena", fix("call Krabena"))
        assertEquals("call Akosua", fix("call Ako Sua"))
        assertEquals("Send a message to Kwame", fix("Send a message to Kwami"))
    }

    @Test fun split_app_names_are_joined() {
        assertEquals("open YouTube", fix("open you tube"))
        assertEquals("open WhatsApp and send a message to Kofi", fix("open whats app and send a message to coffee"))
    }

    @Test fun correct_or_unknown_words_are_left_alone() {
        assertEquals("call Kwame", fix("call Kwame"))
        assertEquals("call Kofi Mensah", fix("call Kofi Mensah"))
        assertEquals("call my brother", fix("call my brother"))
        assertEquals("text her", fix("text her"))
        assertEquals("call Emma", fix("call Emma"))                                     // not in contacts: no guess
        assertEquals("open water", fix("open water"))                                   // too far from anything
        assertEquals("call 0244123456", fix("call 0244123456"))
        assertEquals("what time is it", fix("what time is it"))
        assertEquals("I want coffee", fix("I want coffee"))                             // not a name slot
        assertEquals("open WhatsApp", fix("open WhatsApp"))
    }

    @Test fun two_equally_good_candidates_are_not_guessed() {
        val v = EntityCorrector.Vocab.of(listOf("Kwame", "Kwami"), emptyList())
        assertEquals("call Kwame", EntityCorrector.correct("call Kwame", v).text)           // exact: fine
        val w = EntityCorrector.Vocab.of(listOf("Kofi", "Kofe"), emptyList())
        assertEquals("message coffee", EntityCorrector.correct("message coffee", w).text)   // two sound-alikes: ask later
    }

    @Test fun empty_vocabulary_changes_nothing() {
        assertEquals("message coffee", EntityCorrector.correct("message coffee", EntityCorrector.Vocab.EMPTY).text)
    }

    @Test fun pipeline_corrects_canonical_but_keeps_raw() {
        LanguagePipeline.vocabulary = { vocab }
        val tr = LanguagePipeline.process("message coffee", Lang.GH_EN, Lang.GH_EN)
        assertEquals("message coffee", tr.raw)
        assertEquals("message Kofi", tr.canonical)
        assertEquals(listOf("contact:coffee→Kofi"), tr.corrections)
    }

    @Test fun twi_call_verb_is_a_name_slot() {
        assertEquals("frɛ Kwame", fix("frɛ Kwami"))
    }
}
