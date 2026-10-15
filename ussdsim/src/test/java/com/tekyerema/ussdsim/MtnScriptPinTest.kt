package com.tekyerema.ussdsim

import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulator checks either its built-in PIN or a test PIN given only as a SHA-256 (the rehearsal, 14 Oct 2026). */
class MtnScriptPinTest {
    private fun sha(t: String) = java.security.MessageDigest.getInstance("SHA-256").digest(t.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun walletWith(script: MtnScript, pin: String): String {
        script.enter("6"); script.enter("1"); script.enter(pin)
        return script.shown().text
    }

    @Test fun builtInPinStillWorksWithoutAHash() {
        assertTrue(walletWith(MtnScript("*170#"), "1234").contains("Current Balance"))
        assertTrue(walletWith(MtnScript("*170#"), "9999").contains("Wrong PIN"))
    }

    @Test fun hashedTestPinIsCheckedAndTheBuiltInOneNoLongerOpens() {
        val h = sha("8642")
        assertTrue(walletWith(MtnScript("*170#", pinSha256 = h), "8642").contains("Current Balance"))
        assertTrue(walletWith(MtnScript("*170#", pinSha256 = h), "1234").contains("Wrong PIN"))
        assertTrue(walletWith(MtnScript("*170#", pinSha256 = h.uppercase()), "8642").contains("Current Balance"))
    }
}
