package com.tekyerema

import com.tekyerema.voice.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline recogniser's model directory descriptor (2026-09-30 offline English bake-off). */
class ModelSpecTest {

    @Test fun a_parakeet_transducer_is_described_by_its_model_json() {
        val s = ModelSpec.parse("""{"type":"nemo_transducer","label":"parakeet-110m-ghen","encoder":"encoder.int8.onnx",
            "decoder":"decoder.int8.onnx","joiner":"joiner.int8.onnx","tokens":"tokens.txt","bpeVocab":"bpe.vocab","threads":4}""")!!
        assertEquals(ModelSpec.NEMO_TRANSDUCER, s.type)
        assertEquals("parakeet-110m-ghen", s.label)
        assertEquals(4, s.threads)
        assertFalse("hotwords are opt-in", s.hotwords)
        assertFalse("extracted unless it says so", s.fromAssets)
        assertTrue(ModelSpec.parse("""{"type":"nemo_ctc","model":"m.onnx","fromAssets":true}""")!!.fromAssets)
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), s.required())
        val hw = s.copy(hotwords = true)
        assertTrue("with hotwords the bpe.vocab is needed too", "bpe.vocab" in hw.required())
    }

    @Test fun a_ctc_model_needs_one_file() {
        val s = ModelSpec.parse("""{"type":"nemo_ctc","model":"model.int8.onnx"}""")!!
        assertEquals(mapOf("model" to "model.int8.onnx"), s.files)
        assertEquals("tokens.txt", s.tokens)
        assertEquals("nemo_ctc", s.label)
    }

    @Test fun an_unknown_type_or_a_missing_file_is_no_model() {
        assertNull(ModelSpec.parse("""{"type":"mystery","model":"m.onnx"}"""))
        assertNull("a transducer without its joiner", ModelSpec.parse("""{"type":"nemo_transducer","encoder":"e.onnx","decoder":"d.onnx"}"""))
    }

    @Test fun the_old_whisper_tiny_layout_still_loads() {
        val w = ModelSpec.LEGACY_WHISPER
        assertEquals(ModelSpec.WHISPER, w.type)
        assertEquals(listOf("tiny-encoder.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt"), w.required())
    }

    @Test fun hotwords_are_clean_short_phrases_separated_by_slashes() {
        val line = ModelSpec.hotwordLine(listOf("Ama Serwaa", "ama serwaa", "Kofi/Snr", "WhatsApp", "x", "Dr. Kwame Nkrumah Osei Tutu Jr", "Yaw: Boateng"))
        assertEquals("Ama Serwaa/Kofi Snr/WhatsApp/Yaw Boateng", line)
        assertEquals(2, ModelSpec.hotwordLine(List(10) { "Name$it" }, max = 2).split('/').size)
        assertEquals("", ModelSpec.hotwordLine(emptyList()))
    }
}
