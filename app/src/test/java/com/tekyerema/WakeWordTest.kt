package com.tekyerema

import com.tekyerema.voice.WakeWordService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake-word matcher runs the bundled Gh-English tiny model's transcript of each short speech segment
 * against sound-alike forms of "Tɛkyerɛma". The tiny model is approximate — observed on-device it renders
 * "Tɛkyerɛma" as "the drama" / "dear kwama" — so the matcher targets those forms (with leading filler like
 * "hey"/"the"/"dear" stripped) while rejecting ordinary, longer commands. Tune FORMS from real logs.
 */
class WakeWordTest {

    @Test fun plausible_transcriptions_of_the_wake_word_match() {
        listOf(
            "tekyerema", "Tɛkyerɛma", "kyerema", "hey tekyerema", "cherema", "tekyerema please",
            "hey dear kwama", "hit the drama", "the drama", "dear kwama"   // ← observed on-device forms
        ).forEach { assertTrue("should wake on '$it'", WakeWordService.matchesWake(it)) }
    }

    @Test fun ordinary_speech_does_not_match() {
        listOf(
            "", "hi", "open whatsapp", "what's the weather in bawku", "call kwame",
            "send a message to ama", "read my notifications", "turn on bluetooth"
        ).forEach { assertFalse("must NOT wake on '$it'", WakeWordService.matchesWake(it)) }
    }
}
