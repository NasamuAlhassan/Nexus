package com.tekyerema.voice

/**
 * The user is typing a PIN in the phone's own dialog (a Mobile Money hand-off). TalkBack speaks the keys a blind user
 * touches, and the USSD box is plain text — so while this lasts Tɛkyerɛma does not open the microphone by itself: no
 * follow-up listening after the hand-off line, and a wake-word detection is ignored (a detection could be TalkBack's
 * voice). A tap on the orb still opens it — that is the user's own choice. Ends when the phone's dialog leaves the
 * screen, or after [MAX_MS] (USSD sessions time out well before). Research 2026-09-29,
 * docs/agent/research/secure_payment_auth.md.
 */
object SecureStep {
    const val MAX_MS = 150_000L
    @Volatile private var since = 0L

    fun begin(now: Long = System.currentTimeMillis()) { since = now }
    fun end() { since = 0L }
    fun active(now: Long = System.currentTimeMillis()): Boolean = since != 0L && now - since in 0..MAX_MS

    /** The screen in front changed: once it's no longer a phone window (a few seconds after the hand-off, so the
     *  hand-off's own window change doesn't end it), the PIN step is over. */
    fun onForeground(pkg: String?, now: Long = System.currentTimeMillis()) {
        if (since == 0L || now - since < 3_000L) return
        if (!com.tekyerema.accessibility.TelephonyWindows.matches(pkg) && pkg != "com.tekyerema") end()
    }
}
