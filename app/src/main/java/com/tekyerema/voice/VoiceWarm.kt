package com.tekyerema.voice

/**
 * Which phone voice to load at start-up and when the app is idle-ready. The A20e has 2.7 GB of RAM and 580–760 MB free with the
 * app running (phone log, 24 Oct 2026): at start-up BOTH voices were loaded at once whatever the language — in parallel, so
 * 17 s each instead of 8.7 — and the first English recognition then released them again ([AsrMemory.onRelease]). Now only the
 * voice of the language in force is loaded, and only with memory clearly to spare; otherwise it loads with the first reply, as
 * it does after any release. `speech.warm.minAvailMb` (same limit [VoiceEngine.getReady] uses). Pure → VoiceWarmTest.
 */
object VoiceWarm {
    /** The language whose phone voice to load now ([Lang.GH_EN] or [Lang.TWI]), or null for none. */
    fun voiceToWarm(forcedLanguage: String?, sessionLanguage: String?, availMb: Long, minAvailMb: Long): String? {
        if (availMb < minAvailMb) return null
        return when (forcedLanguage ?: sessionLanguage) {
            Lang.TWI -> Lang.TWI
            Lang.KUS -> null          // Kusaal speaks through the Space; the phone's English/Twi voices are not its voice
            else -> Lang.GH_EN
        }
    }
}
