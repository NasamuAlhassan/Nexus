package com.tekyerema.voice

/**
 * The Twi lines the app says on their own, word for word, made into the phone voice's cache at start (VoiceEngine.prerenderTwi), so
 * the first time each is said offline it plays at once. A sentence inside a longer reply is cut into other pieces, so only whole
 * lines help here; the demo's own lines (a read-back with the names and amounts of the day) go in `speech.tts.prerender.twi` or the
 * `tts_prerender` debug hook after a rehearsal.
 */
object TwiPrerender {
    val LINES: List<String> = listOf(
        "Yoo, mɛsesa. Seesei mɛkasa Twi. Sɛ mante wo yie a, ka bio anaa twerɛ. Sɛ wopɛ Borɔfo a, ka sɛ speak English.",
        com.tekyerema.money.ussd.Boundary.TWI_BALANCE_PIN,
        com.tekyerema.camera.MoneyItems.UNSURE_TW,
        Closers.TWI_NOT_HEARD,
        TWI_NEEDS_INTERNET_TWI,
        OFFLINE_TWI_LIMIT_TWI,
        "Mia bio na magyae.",
        "Yoo.",
        "Ka aane anaa daabi."
    )
}
