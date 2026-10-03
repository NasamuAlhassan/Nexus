package com.tekyerema.voice

import org.json.JSONObject

/**
 * What an on-device ASR model directory holds, from its `model.json` (see [SherpaAsr]). Lets the offline recogniser be
 * any sherpa-onnx model the bake-off picks — not only Whisper — without code changes:
 * ```
 * {"type": "nemo_transducer", "label": "parakeet-110m-ghen",
 *  "encoder": "encoder.int8.onnx", "decoder": "decoder.int8.onnx", "joiner": "joiner.int8.onnx",
 *  "tokens": "tokens.txt", "bpeVocab": "bpe.vocab", "threads": 4, "hotwords": false}
 * ```
 * A directory with no `model.json` but Whisper-tiny's files is [LEGACY_WHISPER] (what shipped before). Pure.
 */
data class ModelSpec(
    val type: String,
    /** role ("encoder", "decoder", "joiner", "model") → file name in the directory */
    val files: Map<String, String>,
    val tokens: String,
    val label: String = type,
    val bpeVocab: String? = null,
    val threads: Int? = null,
    /** Bias decoding toward the user's contact and app names (transducers with a bpe.vocab only). */
    val hotwords: Boolean = false,
    /** Read straight from the APK's assets (uncompressed): no second copy on the phone's storage — for big models (the
     *  660 MB Parakeet on a phone with ~2 GB free). */
    val fromAssets: Boolean = false,
    /** Whisper: the language token to decode with ("" = let the model detect it). Null = the app's default for the
     *  language (Swahili as Twi's proxy — what the old tiny-twi was trained through). The clean whisper-base Twi model
     *  (tekyerema-asr-twi) was trained and scored with auto-detect, so its model.json says "". */
    val language: String? = null
) {
    /** Every file this model needs. */
    fun required(): List<String> = files.values + tokens + listOfNotNull(bpeVocab.takeIf { hotwords })

    companion object {
        const val FILE = "model.json"
        const val WHISPER = "whisper"
        const val NEMO_TRANSDUCER = "nemo_transducer"
        const val NEMO_CTC = "nemo_ctc"
        /** A wav2vec2-style CTC model on raw samples — the omniASR-300M Twi fine-tune (EXP-101), run by sherpa's
         *  omnilingual runner; its tokens.txt names the blank `<blk>` and uses `|` between words. */
        const val OMNILINGUAL_CTC = "omnilingual_ctc"
        private val ROLES = mapOf(
            WHISPER to listOf("encoder", "decoder"),
            NEMO_TRANSDUCER to listOf("encoder", "decoder", "joiner"),
            NEMO_CTC to listOf("model"),
            OMNILINGUAL_CTC to listOf("model")
        )

        val LEGACY_WHISPER = ModelSpec(WHISPER, mapOf("encoder" to "tiny-encoder.onnx", "decoder" to "tiny-decoder.int8.onnx"),
            "tiny-tokens.txt", label = "whisper-tiny")

        /** A `model.json`, or null when it names an unknown type or lacks a file its type needs. */
        fun parse(json: String): ModelSpec? {
            val o = JSONObject(json)
            val type = o.optString("type").trim()
            val roles = ROLES[type] ?: return null
            val files = roles.associateWith { o.optString(it).trim() }
            if (files.values.any { it.isEmpty() }) return null
            val tokens = o.optString("tokens", "tokens.txt").trim().ifEmpty { return null }
            return ModelSpec(type, files, tokens,
                label = o.optString("label").trim().ifEmpty { type },
                bpeVocab = o.optString("bpeVocab").trim().ifEmpty { null },
                threads = if (o.has("threads")) o.optInt("threads") else null,
                hotwords = o.optBoolean("hotwords", false),
                fromAssets = o.optBoolean("fromAssets", false),
                language = if (o.has("language")) o.optString("language").trim() else null)
        }

        /**
         * The per-stream hotwords for sherpa (`createStream(hotwords)`): one phrase per entry, '/'-separated, at most
         * [max] of them, each 1–4 words and letters only (a '/' or ':' inside a name would break the list). Pure.
         */
        fun hotwordLine(names: List<String>, max: Int = 200): String =
            names.asSequence()
                .map { it.replace(Regex("[^\\p{L}\\p{N}' -]"), " ").replace(Regex("\\s+"), " ").trim() }
                .filter { it.length >= 2 && it.split(' ').size <= 4 }
                .distinctBy { it.lowercase() }
                .take(max)
                .joinToString("/")
    }
}
