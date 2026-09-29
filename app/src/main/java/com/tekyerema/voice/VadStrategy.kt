package com.tekyerema.voice

import com.tekyerema.core.config.AppConfig

/**
 * B-03 — how patiently a capture waits. [hangoverMs] is the silence that ends a turn once speech has started,
 * [maxUtteranceMs] caps one utterance, [noSpeechMs] gives up when nothing is said at all.
 *  - quick:   yes/no answers and continuations
 *  - normal:  a request (the previous fixed behaviour)
 *  - patient: users who pause mid-sentence (older users, first-time users) — B-09 "hold on" also switches to it
 * Values come from `speech.vad.strategies.<name>` when present; `speech.vad.strategy` picks the default.
 */
data class VadStrategy(val name: String, val hangoverMs: Int, val maxUtteranceMs: Int, val noSpeechMs: Int) {
    companion object {
        val QUICK = VadStrategy("quick", 350, 10_000, 4_000)
        // Normal: ~1.2 s of silence ends a request (900 ms here + Silero's own 300 ms). It was ~0.7 s, and people were cut
        // off mid-request — between the groups of a phone number ("send money to 055 … 323 …"), or while finding the next
        // word. The owner (A20e 2026-09-29, after a judge's session): "it stops listening early … fix the time of
        // listening and the silence period so it doesn't miss the complete command". Speculative recognition starts
        // inside this silence, so the wait mostly overlaps the recogniser's own time.
        // A request may run 30 s (was 15 s: a long WhatsApp message dictated in one go was cut), and the mic waits 10 s
        // for the first word (was 8 s).
        val NORMAL = VadStrategy("normal", 900, 30_000, 10_000)
        /** Answers to a question (a phone number, an amount) and "Patient" in Settings: ~1.9 s of silence, 15 s to begin. */
        val PATIENT = VadStrategy("patient", 1_600, 30_000, 15_000)
        /** B-02: the rest of a request that paused mid-phrase. 5 s to start again (it was 2.5 s: the judge's "Send money
         *  to" was acted on while they gathered the number); a false alarm costs only that wait. */
        val CONTINUE = VadStrategy("continue", 900, 20_000, 5_000)

        fun named(name: String?): VadStrategy {
            val base = when (name?.trim()?.lowercase()) {
                "quick" -> QUICK; "patient" -> PATIENT; "continue" -> CONTINUE; else -> NORMAL
            }
            val o = AppConfig.obj("speech.vad.strategies.${base.name}") ?: return base
            return VadStrategy(
                base.name,
                o.optInt("hangoverMs", base.hangoverMs).coerceIn(150, 3_000),
                o.optInt("maxUtteranceMs", base.maxUtteranceMs).coerceIn(3_000, 30_000),
                o.optInt("noSpeechMs", base.noSpeechMs).coerceIn(1_500, 30_000)
            )
        }

        /** The configured default for a new request. */
        fun default(): VadStrategy = named(AppConfig.string("speech.vad.strategy", "normal"))
    }
}
