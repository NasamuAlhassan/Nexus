package com.tekyerema.voice.io

import com.tekyerema.voice.Lang

/**
 * The ONE switch for the voice layer: `speech.voiceLayer.profile` (assets/config/speech.json, overridable through the
 * AppConfig local override). Read only by VoiceEngine.init.
 *
 *  - `builtin_en` (default, testing): Android's SpeechRecognizer + the system TextToSpeech, English only. The on-device
 *    Whisper-tiny (SherpaAsr) and the UG HCI Lab language layer are NOT built — no model load, no warm-up CPU, no
 *    HTTP clients, no quota ledger.
 *  - `ghana`: today's stack — MicCapture + HybridAsr (sherpa Whisper-tiny + UG HCI Lab ASR) + RoutingTts (HCI Lab Twi
 *    TTS) and the session language (Twi / Ghanaian English).
 * Switching back is a one-line config change; the agent core never learns which one is live.
 */
object VoiceLayer {
    const val CONFIG_KEY = "speech.voiceLayer.profile"
    const val BUILTIN_EN = "builtin_en"
    const val GHANA = "ghana"
    /** English → the phone's recognizer + system voice; Twi → UG HCI Lab ASR/TTS (on-device Twi Whisper as fallback).
     *  The owner's choice for the hackathon, 2026-09-29 ([LanguageRoutedInput]). */
    const val GH_MIXED = "gh_mixed"

    enum class InputKind { SYSTEM_SPEECH, CAPTURED_AUDIO, MIXED }

    /** What a profile builds. [pinnedLanguage] non-null = the session language is fixed (no LID, no Twi routing). */
    data class Plan(
        val profile: String,
        val input: InputKind,
        val buildOnDeviceAsr: Boolean,
        val buildLanguageLayer: Boolean,
        val pinnedLanguage: String?
    )

    /** An unknown or blank value falls back to the default, [BUILTIN_EN]. */
    fun profileOf(raw: String?): String = when (raw?.trim()?.lowercase()) {
        GHANA -> GHANA
        BUILTIN_EN -> BUILTIN_EN
        GH_MIXED -> GH_MIXED
        else -> BUILTIN_EN
    }

    fun plan(raw: String?): Plan = when (profileOf(raw)) {
        GHANA -> Plan(GHANA, InputKind.CAPTURED_AUDIO, buildOnDeviceAsr = true, buildLanguageLayer = true, pinnedLanguage = null)
        GH_MIXED -> Plan(GH_MIXED, InputKind.MIXED, buildOnDeviceAsr = true, buildLanguageLayer = true, pinnedLanguage = null)
        else -> Plan(BUILTIN_EN, InputKind.SYSTEM_SPEECH, buildOnDeviceAsr = false, buildLanguageLayer = false, pinnedLanguage = Lang.GH_EN)
    }

    /** Builders for the pieces a profile may need; the heavy ones are only called when [plan] asks for them. */
    interface Factory<A, L> {
        /** The on-device recogniser (SherpaAsr, ~hundreds of MB of model). */
        fun onDeviceAsr(): A
        /** The UG HCI Lab / REST language layer (LanguageLayer.build). */
        fun languageLayer(): L
        fun systemInput(): VoiceInput
        fun capturedInput(onDevice: A, layer: L): VoiceInput
        /** [InputKind.MIXED]: one recognizer per language. The default routes everything to English (tests). */
        fun mixedInput(english: VoiceInput, twi: VoiceInput): VoiceInput = LanguageRoutedInput(english, twi) { Lang.GH_EN }
    }

    class Built<A, L>(val plan: Plan, val input: VoiceInput, val onDevice: A?, val layer: L?)

    fun <A, L> build(plan: Plan, f: Factory<A, L>): Built<A, L> = when (plan.input) {
        InputKind.SYSTEM_SPEECH -> Built(plan, f.systemInput(), null, null)
        InputKind.CAPTURED_AUDIO -> {
            val onDevice = f.onDeviceAsr()
            val layer = f.languageLayer()
            Built(plan, f.capturedInput(onDevice, layer), onDevice, layer)
        }
        InputKind.MIXED -> {
            val onDevice = f.onDeviceAsr()
            val layer = f.languageLayer()
            // The captured (Twi) side is built first: it sets up the language session the router reads.
            val twi = f.capturedInput(onDevice, layer)
            Built(plan, f.mixedInput(f.systemInput(), twi), onDevice, layer)
        }
    }
}
