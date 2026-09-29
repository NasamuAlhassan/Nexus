package com.tekyerema.mcp

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The second lock on the computer connection (pure, unit-tested). The first is the kernel: only adbd — a computer the
 * user already authorised for USB debugging — can reach the socket ([PeerCheck]). But `adb forward` opens a TCP port on
 * that computer that ANY process on it can connect to, so the phone also speaks and shows a 6-digit code when a
 * computer connects, and the client must send it back (research note §1.4, "Pairing").
 *
 * One code per session: it stays valid while the connection feature is on, so a client can reconnect with it, and is
 * replaced after [MAX_FAILURES] wrong tries (a guesser gets 3 of a million) or when the feature turns off. A new code is
 * announced at most every [ANNOUNCE_GAP_MS], so a flood of connections can't make the phone talk non-stop.
 */
class Pairing(
    private val clock: () -> Long = System::currentTimeMillis,
    private val nextCode: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) }
) {
    enum class Verdict { OK, WRONG, NO_CODE }

    private var code: String? = null
    private var failures = 0
    private var announcedAt = Long.MIN_VALUE / 2

    /**
     * A computer has connected. Returns a fresh code to speak and show now, or null when the current code is still valid
     * (the person already heard it) or a new one was announced moments ago ([ANNOUNCE_GAP_MS]).
     */
    @Synchronized fun onConnect(): String? {
        if (code != null) return null
        val now = clock()
        if (now - announcedAt < ANNOUNCE_GAP_MS) return null
        announcedAt = now
        failures = 0
        return nextCode().also { code = it }
    }

    /** Check the code a client presented (constant-time). Three wrong tries retire the code. */
    @Synchronized fun verify(presented: String?): Verdict {
        val c = code ?: return Verdict.NO_CODE
        val p = presented?.trim().orEmpty()
        if (MessageDigest.isEqual(c.toByteArray(), p.toByteArray())) return Verdict.OK
        if (++failures >= MAX_FAILURES) code = null
        return Verdict.WRONG
    }

    /** The connection feature turned off: the code dies with it. */
    @Synchronized fun reset() { code = null; failures = 0 }

    @Synchronized fun active(): Boolean = code != null

    companion object {
        const val MAX_FAILURES = 3
        const val ANNOUNCE_GAP_MS = 20_000L

        /** "4 8 1 2 0 3" — a code spoken digit by digit. */
        fun spoken(code: String): String = code.toList().joinToString(" ")
    }
}

/** The kernel-level check on who opened the socket: only adbd (the shell user, uid 2000 — `Process.SHELL_UID`). */
object PeerCheck {
    /** android.os.Process.SHELL_UID (public since API 29; the literal keeps this pure and minSdk-26-safe). */
    const val SHELL_UID = 2000

    fun accept(uid: Int): Boolean = uid == SHELL_UID
}

/** A sliding-window limit (pure): at most [max] events per [windowMs]. */
class RateLimiter(private val max: Int, private val windowMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val hits = ArrayDeque<Long>()

    @Synchronized fun tryAcquire(): Boolean {
        val now = clock()
        while (hits.isNotEmpty() && now - hits.first() >= windowMs) hits.removeFirst()
        if (hits.size >= max) return false
        hits.addLast(now)
        return true
    }

    /** How long until the next event would be allowed (0 = now). */
    @Synchronized fun retryAfterMs(): Long {
        val now = clock()
        while (hits.isNotEmpty() && now - hits.first() >= windowMs) hits.removeFirst()
        return if (hits.size < max) 0L else (windowMs - (now - hits.first())).coerceAtLeast(0L)
    }
}
