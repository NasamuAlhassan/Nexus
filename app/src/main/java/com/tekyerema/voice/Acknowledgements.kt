package com.tekyerema.voice

import kotlin.random.Random

/**
 * Short, natural acknowledgements shown (and announced to TalkBack) when Tɛkyerɛma starts
 * listening. Rotates through a pool so the same phrase is never repeated back-to-back; Twi phrases
 * are used when the user has chosen Twi. Pure Kotlin.
 */
object Acknowledgements {
    private val english = listOf("I'm listening.", "Go ahead.", "Uh-huh.", "What do you need?", "Yes?", "I'm here.")
    // Twi: "I'm listening." / "Say it." / "Okay." / "What do you need?" / "I'm here."
    private val twi = listOf("Meretie.", "Ka.", "Yoo.", "Ɛdeɛn na wohia?", "Mewɔ ha.")

    private var lastEn = -1
    private var lastTw = -1

    fun next(language: String, rnd: Random = Random.Default): String {
        val pool = if (language == Lang.TWI) twi else english
        val last = if (language == Lang.TWI) lastTw else lastEn
        var i = rnd.nextInt(pool.size)
        if (pool.size > 1 && i == last) i = (i + 1 + rnd.nextInt(pool.size - 1)) % pool.size
        if (language == Lang.TWI) lastTw = i else lastEn = i
        return pool[i]
    }
}
