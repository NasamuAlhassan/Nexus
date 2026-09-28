package com.tekyerema

import com.tekyerema.language.TwiText
import com.tekyerema.voice.Lang
import com.tekyerema.voice.LanguagePipeline
import com.tekyerema.voice.Lid
import com.tekyerema.voice.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** X-06/B-05/B-20: Twi spelling variants, numerals (variants and compounds), English amounts in Twi speech, and
 *  code-switch-aware LID. English behaviour is pinned unchanged. */
class TwiTextTest {

    private fun twi(s: String) = Normalizer.normalize(s, Lang.TWI)

    // ---------------------------------------------------------------- TwiText

    @Test fun look_alikes_and_tone_marks_become_the_standard_spelling() {
        assertEquals("Frɛ Kofi", TwiText.canonical("Frε Kofi"))              // Greek epsilon
        assertEquals("kɔ fie", TwiText.canonical("kͻ fie"))                  // reversed lunate sigma
        assertEquals("Mɛpɛ sɛ", TwiText.canonical("Mɛ́pɛ̀ sɛ"))   // tone marks dropped
        assertEquals("Frɛ Kofi", TwiText.canonical(TwiText.canonical("Frε Kofi")))  // idempotent
        assertEquals("mmiensa", TwiText.fold("Mmiɛnsa"))
        assertEquals("oha", TwiText.fold("ɔha"))
    }

    @Test fun the_english_voice_gets_plain_vowels_and_english_text_is_untouched() {
        assertEquals("Merefre Kwabena.", TwiText.forEnglishVoice("Merefrɛ Kwabɛna."))
        assertEquals("Okyena", TwiText.forEnglishVoice("Ɔkyena"))
        assertEquals("Ngo", TwiText.forEnglishVoice("Ŋo"))
        val english = "Meet me at the café at six."
        assertSame("no Akan letters → same instance, accents kept", english, TwiText.forEnglishVoice(english))
    }

    // ---------------------------------------------------------------- Twi numerals

    @Test fun plain_latin_and_alternative_spellings_find_their_numeral() {
        assertEquals("3", twi("mmiensa"))
        assertEquals("100", twi("oha"))
        assertEquals("8", twi("nwotwe"))
        assertEquals("2 4 5", twi("mienu ɛnan enum"))
        assertEquals("cedi 20.", twi("Sidi aduonu."))                            // existing behaviour kept
    }

    @Test fun compound_numerals_add_up_only_without_a_pause_between() {
        assertEquals("25", twi("aduonu anum"))
        assertEquals("cedi 21", twi("sidi aduonu baako"))
        assertEquals("20, 5", twi("aduonu, anum"))                              // a list, not 25
        assertEquals("1 2 3", twi("baako mmienu mmiɛnsa"))                      // spoken digits stay digits
        assertEquals("101", twi("ɔha ne baako"))
        assertEquals("cedi 125", twi("sidi ɔha ne aduonu anum"))
        assertEquals("1120", twi("apem ne ɔha ne aduonu"))
        assertEquals("Frɛ Kofi ne Ama", twi("Frɛ Kofi ne Ama"))                  // "ne" between names is untouched
        assertTrue(twi("du").contains("du"))                                    // bare "du" still the ordinary word
    }

    @Test fun english_amounts_inside_twi_speech_are_normalized_too() {
        assertEquals("Soma 5 cedis kɔ Kofi", twi("Soma five cedis kɔ Kofi"))
        assertEquals("Soma 150 cedis", twi("Soma one hundred and fifty cedis"))
    }

    @Test fun twi_canonical_goal_is_respelled_but_raw_is_kept() {
        val tr = LanguagePipeline.process("Frέ Kofi", Lang.TWI, forceLanguage = Lang.TWI)
        assertEquals("Frɛ Kofi", tr.canonical)
        assertEquals("Frέ Kofi", tr.raw)
    }

    @Test fun english_normalization_is_unchanged() {
        assertEquals("Send 25 cedis to Kofi", Normalizer.normalize("Send twenty five cedis to Kofi", Lang.GH_EN))
        assertEquals("café at 6 30", Normalizer.normalize("café at six thirty", Lang.GH_EN))
    }

    // ---------------------------------------------------------------- LID + code-switching

    @Test fun lid_counts_look_alike_letters_and_plain_latin_twi() {
        assertEquals(Lang.TWI, Lid.detect("Frε me nua").lang)
        assertEquals(Lang.TWI, Lid.detect("mepe se me fre Kofi").lang)
        // folds that are English words ("den", "eye") are not Twi evidence without the Twi letter
        assertEquals(Lang.GH_EN, Lid.detect("I see the den").lang)
        assertEquals(0, Lid.detect("an eye for an eye").twiTok)
    }

    @Test fun code_switched_request_resolves_to_the_dominant_language() {
        val (lang, reason) = Lid.resolve("Mepɛ sɛ me call Kofi", sessionDefault = Lang.GH_EN)
        assertEquals(Lang.TWI, lang)
        assertTrue(reason, reason.startsWith("code-switch"))
        assertEquals(Lang.GH_EN, Lid.resolve("Please call Kofi for me", sessionDefault = Lang.TWI).first)
    }
}
