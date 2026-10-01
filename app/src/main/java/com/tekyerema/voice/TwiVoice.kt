package com.tekyerema.voice

/** A voice that can say, without speaking, why it can't be used right now (null = usable as far as it knows). */
interface VoiceAvailability {
    fun whyUnavailable(): String?
}

/**
 * Whether the Twi voice is working, and when the user must be told it isn't (audit 2026-10-01, L16/L17: in Twi mode
 * the lab voice answered HTTP 401 and the reply fell back to the English voice with no explanation — to a blind Twi
 * speaker that is a stranger's accent reading their language, or what sounds like nothing).
 *
 * The rule: the first time a Twi reply has to be read by the English voice the user hears [NOTICE] once, then the
 * reply. Not again in the same session — a "session" ends when the Twi voice works again, or when no Twi reply has
 * needed the fallback for [sessionGapMs] (the process lives for days under the accessibility service).
 *
 * Pure (clock injected) → unit-tested. [shared] is the app's instance; the phone check reads [status] / [problem].
 */
class TwiVoiceStatus(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sessionGapMs: () -> Long = {
        try { com.tekyerema.core.config.AppConfig.long("speech.tts.twiNoticeGapMs", 30 * 60_000L) } catch (_: Throwable) { 30 * 60_000L }
    }
) {
    enum class State { UNTRIED, OK, UNAVAILABLE }

    @Volatile var state: State = State.UNTRIED; private set
    /** Why it is unavailable ("key rejected", "quota used up", …); null = no Twi voice at all on this phone. */
    @Volatile var reason: String? = null; private set
    private var lastFallbackAt = 0L
    private var noticed = false

    /** A Twi reply was spoken by a Twi voice. */
    @Synchronized fun onSpoke() { state = State.OK; reason = null; noticed = false }

    /** A Twi reply is about to be read by the English voice. True when the user should hear [NOTICE] first. */
    @Synchronized fun onUnavailable(why: String?): Boolean {
        val now = clock()
        val due = !noticed || now - lastFallbackAt > sessionGapMs()
        state = State.UNAVAILABLE
        reason = why?.trim()?.ifEmpty { null }
        lastFallbackAt = now
        noticed = true
        return due
    }

    /** One log/diagnostic line: "twi voice: unavailable (key rejected)". Never contains a secret. */
    fun status(): String = when (state) {
        State.UNTRIED -> "twi voice: not used yet"
        State.OK -> "twi voice: ok"
        State.UNAVAILABLE -> "twi voice: unavailable (${reason ?: NO_VOICE})"
    }

    /** A sentence for the spoken phone check, or null when there is nothing wrong (or it hasn't been tried). */
    fun problem(): String? = if (state != State.UNAVAILABLE) null else
        "The Twi voice isn't available (${reason ?: NO_VOICE}), so Twi replies are read with the English voice."

    companion object {
        const val NOTICE = "The Twi voice isn't available right now, so I'll read with the English voice."
        const val NO_VOICE = "no Twi voice on this phone"
        /** The app's Twi voice status (RoutingTts writes it; the phone check and the debug ping read it). */
        val shared = TwiVoiceStatus()
    }
}
