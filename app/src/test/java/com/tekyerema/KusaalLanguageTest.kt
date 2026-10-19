package com.tekyerema

import com.tekyerema.language.LanguageSession
import com.tekyerema.voice.KusaalYes
import com.tekyerema.voice.Lang
import com.tekyerema.voice.LanguagePipeline
import com.tekyerema.voice.PrefetchTts
import com.tekyerema.voice.RoutingTts
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.TwiVoiceStatus
import com.tekyerema.voice.io.Heard
import com.tekyerema.voice.io.InputCaps
import com.tekyerema.voice.io.LanguageRoutedInput
import com.tekyerema.voice.io.ListenEvents
import com.tekyerema.voice.io.ListenPurpose
import com.tekyerema.voice.io.ListenSpec
import com.tekyerema.voice.io.VoiceInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 19 October 2026: "Make it selectable, it will use the online spaces" — Kusaal as an app language (heard, translated
 * and voiced by our language Space), and "Is it possible you add his voice?" — English replies in Kafui Dey's voice from the
 * Space first, the English chain behind it.
 */
class KusaalLanguageTest {
    private class Voice(override val name: String, var accept: Set<String>) : TtsProvider {
        val said = ArrayList<Pair<String, String>>()
        override suspend fun speak(text: String, language: String): Boolean { said.add(text to language); return language in accept }
        override fun stop() {}
        override fun shutdown() {}
    }

    /** A network voice: [fetchMs] to fetch a clip (null = it can't), [playOk] whether playback runs to the end. */
    private class NetVoice(override val name: String, var fetchMs: Long = 0, var can: Boolean = true, var playOk: Boolean = true) : PrefetchTts {
        val fetched = ArrayList<String>()
        val played = ArrayList<String>()
        override suspend fun fetch(text: String, language: String): List<ByteArray>? {
            delay(fetchMs)
            if (!can) return null
            fetched += text
            return listOf(text.toByteArray())
        }
        override suspend fun play(clips: List<ByteArray>): Boolean { clips.forEach { played += String(it) }; return playOk }
        override suspend fun speak(text: String, language: String): Boolean = fetch(text, language)?.let { play(it) } ?: false
        override fun stop() {}
        override fun shutdown() {}
    }

    private val status = TwiVoiceStatus(clock = { 0L })

    // ---------------------------------------------------------------- selection and session

    @Test fun kusaalIsALanguageOfItsOwn() {
        assertEquals("kus", Lang.KUS)
        assertEquals("Kusaal", Lang.label(Lang.KUS))
    }

    @Test fun theSessionRemembersKusaal() {
        var saved = Lang.GH_EN
        val s = LanguageSession({ saved }, { saved = it }, clock = { 0L }, conversationTtlMs = { 60_000L }, switchAfterTurns = { 1 })
        s.onTurn(Lang.KUS)
        assertEquals(Lang.KUS, s.current())
        assertEquals("the habitual language follows after the streak", Lang.KUS, saved)
    }

    @Test fun kusaalSpeechGoesOnAsHeard() {
        // The English/Twi repairs (numbers, network and money words, contact names by sound) never touch Kusaal words.
        val t = LanguagePipeline.process("  Fʋ  pʋʋsim  ten cedis ", Lang.GH_EN, Lang.KUS)
        assertEquals(Lang.KUS, t.language)
        assertEquals("Fʋ pʋʋsim ten cedis", t.canonical)
        assertEquals("explicit", t.reason)
    }

    @Test fun aRememberedKusaalSessionIsNeverAGuess() {
        // Kusaal is only ever chosen: with no forced language, a remembered Kusaal session falls back to English, not Kusaal.
        val t = LanguagePipeline.process("ok", Lang.KUS, null)
        assertEquals(Lang.GH_EN, t.language)
    }

    @Test fun saySpeakKusaalToSwitch() {
        val c = { s: String -> com.tekyerema.agent.FastIntentClassifier.classify(s) }
        for (s in listOf("speak kusaal", "speak kusal", "switch to kusaal", "use kusaal", "kusaal mode", "talk to me in kusasi"))
            assertEquals(s, com.tekyerema.agent.FastIntent.Preference("language", "kus"), c(s))
        assertEquals(com.tekyerema.agent.FastIntent.Preference("language", "english"), c("speak english"))
    }

    // ---------------------------------------------------------------- hearing

    private class Rec(override val id: String, val heard: MutableList<String>) : VoiceInput {
        override val caps = InputCaps(false, false, false, emptySet(), secureListen = false, patientEndpointing = false)
        override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard { heard += "$id/${spec.language}/${spec.purpose}"; return Heard.NoSpeech }
    }

    @Test fun kusaalIsHeardByTheSpaceAndYesNoByThePhone() = runTest {
        val heard = mutableListOf<String>()
        val routed = LanguageRoutedInput(english = Rec("phone", heard), twi = Rec("captured", heard), language = { Lang.KUS }, isOnline = { true })
        routed.listen(ListenSpec(ListenPurpose.REQUEST), object : ListenEvents {})
        routed.listen(ListenSpec(ListenPurpose.YES_NO), object : ListenEvents {})
        routed.listen(ListenSpec(ListenPurpose.ANSWER, digits = true), object : ListenEvents {})
        routed.listen(ListenSpec(ListenPurpose.ANSWER), object : ListenEvents {})
        assertEquals(listOf("captured/kus/REQUEST", "phone/gh_en/YES_NO", "phone/gh_en/ANSWER", "captured/kus/ANSWER"), heard)
    }

    @Test fun offlineKusaalNeverReachesThePhonesEnglishRecogniser() = runTest {
        val heard = mutableListOf<String>()
        val routed = LanguageRoutedInput(english = Rec("phone", heard), twi = Rec("captured", heard), language = { Lang.KUS }, isOnline = { false })
        routed.listen(ListenSpec(ListenPurpose.YES_NO), object : ListenEvents {})
        assertEquals(listOf("captured/kus/YES_NO"), heard)
    }

    // ---------------------------------------------------------------- the money step's yes

    @Test fun onlyAPlainEnglishYesApprovesInKusaal() {
        for (s in listOf("yes", "Yes.", "yes please", "Yes, go ahead", "go ahead")) assertTrue(s, KusaalYes.strict(s))
        for (s in listOf("okay", "ok", "yeah", "sure", "yes no", "a yay", "yes but no", "", "no")) assertFalse(s, KusaalYes.strict(s))
    }

    // ---------------------------------------------------------------- speaking Kusaal

    @Test fun anEnglishReplyIsPutIntoKusaalAndSpokenByTheKusaalVoice() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kus = Voice("space:kus", setOf(Lang.KUS))
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), twiStatus = status, others = mapOf(Lang.KUS to kus),
            toOthers = mapOf<String, suspend (String) -> String?>(Lang.KUS to { s -> if (s.startsWith("Okay")) "Ee, m na tʋm." else null }))
        assertTrue(routing.speak("Okay, I will do it now.", Lang.KUS))
        assertEquals(listOf("Ee, m na tʋm." to Lang.KUS), kus.said)
        assertTrue(phone.said.isEmpty())
    }

    @Test fun anUntranslatableReplyIsSaidByTheEnglishVoiceNeverReadByTheKusaalVoice() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kus = Voice("space:kus", setOf(Lang.KUS))
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), twiStatus = status, others = mapOf(Lang.KUS to kus),
            toOthers = mapOf<String, suspend (String) -> String?>(Lang.KUS to { _ -> null }))
        assertTrue(routing.speak("I couldn't find that contact on your phone.", Lang.KUS))
        assertTrue(kus.said.isEmpty())
        assertEquals(1, phone.said.size)
    }

    @Test fun amountsInAKusaalLineAreSaidByTheEnglishVoice() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kus = Voice("space:kus", setOf(Lang.KUS))
        val marked = "M na tʋm " + com.tekyerema.language.EntityTags.EN_OPEN + "fifty Ghana cedis" + com.tekyerema.language.EntityTags.EN_CLOSE + " Kofi."
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), twiStatus = status, others = mapOf(Lang.KUS to kus),
            toOthers = mapOf<String, suspend (String) -> String?>(Lang.KUS to { _ -> marked }))
        assertTrue(routing.speak("I will send fifty Ghana cedis to Kofi.", Lang.KUS))
        assertEquals(listOf("fifty Ghana cedis" to Lang.GH_EN), phone.said)
        assertEquals(2, kus.said.size)
    }

    // ---------------------------------------------------------------- English in Kafui Dey's voice

    @Test fun englishIsSpokenInTheNetworkVoiceFirst() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kafui = NetVoice("http:space_tts_eng")
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), ghEnglish = kafui, twiStatus = status,
            ghEnabled = { true }, clock = { testScheduler.currentTime })
        assertTrue(routing.speak("Your balance is two hundred and thirty five Ghana cedis. Akosua and Kwame are coming to Accra tomorrow.", Lang.GH_EN))
        assertEquals(2, kafui.played.size)
        assertTrue("the phone's voice says nothing", phone.said.isEmpty())
    }

    @Test fun switchedOffOrOfflineTheEnglishChainSpeaks() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kafui = NetVoice("http:space_tts_eng")
        var on = true; var online = true
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), ghEnglish = kafui, twiStatus = status,
            isOnline = { online }, ghEnabled = { on }, clock = { testScheduler.currentTime })
        on = false
        routing.speak("The time is six.", Lang.GH_EN)
        on = true; online = false
        routing.speak("The time is seven.", Lang.GH_EN)
        assertEquals(2, phone.said.size)
        assertTrue(kafui.fetched.isEmpty())
    }

    @Test fun aSlowNetworkVoiceGivesWayAndRests() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kafui = NetVoice("http:space_tts_eng", fetchMs = 20_000)
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), ghEnglish = kafui, twiStatus = status,
            ghEnabled = { true }, ghFirstWaitMs = { 6_000L }, clock = { testScheduler.currentTime })
        assertTrue(routing.speak("The time is six.", Lang.GH_EN))
        assertEquals("the English chain spoke after the first sentence's time ran out", listOf("The time is six." to Lang.GH_EN), phone.said)
        kafui.fetchMs = 0
        routing.speak("The time is seven.", Lang.GH_EN)
        assertTrue("resting: the next reply doesn't wait on it", kafui.fetched.isEmpty())
        assertEquals(2, phone.said.size)
    }

    @Test fun aNetworkVoiceThatFailsLaterHandsTheRestOver() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kafui = object : PrefetchTts {
            override val name = "http:space_tts_eng"
            var n = 0
            override suspend fun fetch(text: String, language: String): List<ByteArray>? = if (n++ == 0) listOf(text.toByteArray()) else null
            override suspend fun play(clips: List<ByteArray>) = true
            override suspend fun speak(text: String, language: String) = false
            override fun stop() {}
            override fun shutdown() {}
        }
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), ghEnglish = kafui, twiStatus = status,
            ghEnabled = { true }, clock = { testScheduler.currentTime })
        assertTrue(routing.speak("Your balance is two hundred and thirty five Ghana cedis. Akosua and Kwame are coming to Accra tomorrow.", Lang.GH_EN))
        assertEquals(listOf("Akosua and Kwame are coming to Accra tomorrow." to Lang.GH_EN), phone.said)
    }

    @Test fun aStoppedReplyIsNotRepeatedByTheOtherVoice() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kafui = NetVoice("http:space_tts_eng", playOk = false)
        val routing = RoutingTts(english = phone, twi = Voice("lab", emptySet()), ghEnglish = kafui, twiStatus = status,
            ghEnabled = { true }, clock = { testScheduler.currentTime })
        assertFalse(routing.speak("The time is six o'clock in the evening right now.", Lang.GH_EN))
        assertTrue(phone.said.isEmpty())
    }

    @Test fun twiLinesKeepTheirEnglishPiecesOnTheEnglishChain() = runTest {
        val phone = Voice("system", setOf(Lang.GH_EN))
        val kafui = NetVoice("http:space_tts_eng")
        val routing = RoutingTts(english = phone, twi = Voice("lab", setOf(Lang.TWI)), ghEnglish = kafui, twiStatus = status,
            ghEnabled = { true }, clock = { testScheduler.currentTime })
        routing.speak("Wopɛ sɛ mesoma " + com.tekyerema.language.EntityTags.EN_OPEN + "five Ghana cedis" +
            com.tekyerema.language.EntityTags.EN_CLOSE + " ma Ama?", Lang.TWI)
        assertTrue("the demo's Twi behaviour is unchanged", kafui.fetched.isEmpty())
    }

    @Test fun aLineIsFetchedSentenceBySentence() {
        assertEquals(listOf("Okay. Your balance is two hundred cedis.", "Akosua and Kwame are coming to Accra tomorrow."),
            RoutingTts.sentences("Okay. Your balance is two hundred cedis. Akosua and Kwame are coming to Accra tomorrow."))
        assertEquals(listOf("Okay."), RoutingTts.sentences("Okay."))
        assertEquals(emptyList<String>(), RoutingTts.sentences("  "))
    }
}
