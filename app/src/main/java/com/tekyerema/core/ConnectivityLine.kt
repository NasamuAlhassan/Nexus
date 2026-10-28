package com.tekyerema.core

/**
 * "Check my internet connection" / "why is the internet slow?" (owner, 28 Oct 2026): what the user hears, from what the phone knows —
 * the network Android has (Wi-Fi / mobile data / none), whether Android validated it as reaching the internet, and how the app's own
 * recent internet requests went ([LinkHealth]: failed or stalled, the last probe's time). Signal strength alone is never called a good
 * connection. Pure → ConnectivityLineTest.
 */
object ConnectivityLine {
    enum class Health { UNKNOWN, GOOD, SLOW, FAILING }

    fun health(verdict: LinkHealthTracker.Verdict, lastProbeMs: Long, slowMs: Long): Health = when {
        verdict == LinkHealthTracker.Verdict.STALLED -> Health.FAILING
        verdict == LinkHealthTracker.Verdict.SUSPECT -> Health.SLOW
        lastProbeMs >= slowMs -> Health.SLOW
        verdict == LinkHealthTracker.Verdict.GOOD -> Health.GOOD
        else -> Health.UNKNOWN
    }

    /** [via] = "Wi-Fi" / "mobile data" / "the network", or null with no network at all. */
    fun describe(via: String?, validated: Boolean, health: Health, lastProbeMs: Long): String = when {
        via == null -> "You're offline. Wi-Fi and mobile data are both off or have no network. I can still do what works offline, " +
            "like the MoMo menu, the time, reading your messages, and the camera."
        !validated -> "You're connected to $via, but it isn't reaching the internet. The Wi-Fi may need a login page, or the data bundle may be finished."
        health == Health.FAILING -> "You're connected to $via, but my internet requests are failing right now, so online tasks will be slow or fail. " +
            "Offline things still work."
        health == Health.SLOW -> "You're connected to $via, but the internet is responding slowly" +
            (if (lastProbeMs > 0) ", about ${(lastProbeMs + 500) / 1000} seconds for a small check" else "") + ". Simple things will work; big ones may be slow."
        health == Health.GOOD -> "You're online over $via, and the internet is responding well."
        else -> "You're online over $via."
    }
}
