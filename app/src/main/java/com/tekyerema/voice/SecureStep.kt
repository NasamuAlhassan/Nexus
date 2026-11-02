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

    /** S-03 (P2-04, 13 October 2026): the hand-off also raises [com.tekyerema.core.Privacy.secureStep], so while it lasts the
     *  log redacts screen content, the screen-describe / vision gate refuses, and the helper treats the step as secret —
     *  the same blindness the agent's own credential-screen wait has. Lowered by [end], by the window change, or by [MAX_MS]. */
    /** The app whose own PIN keypad this step is (money/AppPin), or null for the network's dialog. */
    @Volatile private var appPkg: String? = null

    fun begin(now: Long = System.currentTimeMillis(), appPackage: String? = null) {
        since = now; appPkg = appPackage; com.tekyerema.core.Privacy.secureStep = true
        com.tekyerema.money.MoneyMoment.hold(com.tekyerema.money.MoneyMoment.PIN)   // "this is money" through the PIN
        // The screen is covered while a NETWORK PIN is entered (demo step 4); in an app the pad uses the app's own keypad, uncovered
        // (owner, 28 Oct 2026: "instead of a full coverage, use the buttons directly").
        if (appPackage == null) com.tekyerema.phone.PinCurtain.show()
    }
    fun end() {
        since = 0L; appPkg = null; com.tekyerema.core.Privacy.secureStep = false
        com.tekyerema.money.MoneyMoment.release(com.tekyerema.money.MoneyMoment.PIN)
        com.tekyerema.phone.PinCurtain.hideWhenDialogGone()   // …and until the network's answer (a balance) has left the screen
    }
    fun active(now: Long = System.currentTimeMillis()): Boolean {
        if (since == 0L) return false
        if (now - since !in 0..MAX_MS) { end(); return false }   // timed out: the flag must not outlive the step
        return true
    }

    /** The screen in front changed: once it's no longer a phone window (a few seconds after the hand-off, so the
     *  hand-off's own window change doesn't end it), the PIN step is over. */
    fun onForeground(pkg: String?, now: Long = System.currentTimeMillis()) {
        if (since == 0L || now - since < 3_000L) return
        if (!com.tekyerema.accessibility.TelephonyWindows.matches(pkg) && pkg != "com.tekyerema" && (appPkg == null || pkg != appPkg)) end()
    }
}
