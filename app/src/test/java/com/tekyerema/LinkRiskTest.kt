package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.fraud.LinkRisk
import com.tekyerema.money.fraud.ScamScore
import com.tekyerema.money.fraud.ScamWarning
import com.tekyerema.phone.CallShield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fraud step 2 (MASTER_STATUS #13, 2 Nov 2026): warnings on LINKS and on a call followed by money. All made-up examples. */
class LinkRiskTest {
    @After fun reset() { AppConfig.loadFrom(emptyList()) }

    private fun cue(text: String) = ScamScore.cues(text).firstOrNull()

    @Test fun lookalike_brand_links_warn_on_their_own() {
        assertEquals(ScamScore.Cue.LINK_LOOKALIKE, cue("Your MoMo wallet is ready, see http://mtn-gh-reward.xyz/login"))
        assertEquals(ScamScore.Cue.LINK_LOOKALIKE, cue("Hello, details here rnomo-verify.com"))          // rn read as m
        assertEquals(ScamScore.Cue.LINK_LOOKALIKE, cue("Dear customer see ecobank.secure-login.top for your statement"))
        assertEquals(ScamScore.Cue.LINK_LOOKALIKE, cue("New offer at mtnghana.online today"))
        assertEquals(ScamScore.Cue.LINK_LOOKALIKE, cue("Join us: whatsapp-gold.app"))
        assertEquals("MTN", LinkRisk.parse("https://mtn-gh-reward.xyz")?.brand)
    }

    @Test fun disguised_addresses_warn() {
        assertEquals(ScamScore.Cue.LINK_UNSAFE, cue("Open http://192.168.4.20/momo now"))
        assertEquals(ScamScore.Cue.LINK_UNSAFE, cue("https://mtn.com.gh@evil.site/claim"))         // the real host is after the @
        assertEquals(ScamScore.Cue.LINK_UNSAFE, cue("See xn--80ak6aa92e.com"))
        assertEquals(ScamScore.Cue.LINK_LOOKALIKE, cue("See xn--mtn-x9a.com"))          // a brand inside punycode: look-alike first
    }

    @Test fun shortened_and_weak_links_warn_only_when_asked_to_act() {
        assertEquals(ScamScore.Cue.LINK_HIDDEN, cue("Your account is suspended. Go to bit.ly/3xYz12 to reactivate"))
        assertNull(cue("Listen to this song https://bit.ly/abc123"))
        assertEquals(ScamScore.Cue.LINK_ACTION, cue("Claim your bonus now at http://free-data.click/gh"))
        assertNull(cue("Our church photos are at http://ourparish.xyz/gallery"))
    }

    @Test fun real_sites_and_ordinary_links_stay_quiet() {
        assertNull(cue("Your bundle is active. Visit https://mtn.com.gh/bundles for more."))
        assertNull(cue("Verify your SIM registration at www.mtn.com.gh"))          // the brand's own site, even with "verify"
        assertNull(cue("Pay your bills online at https://www.ecobank.com/gh"))
        assertNull(cue("See you at grace-church.org on Sunday"))
        assertNull(cue("Notes are in https://docs.google.com/document/d/abc"))
        assertNull(cue("bogus.com has funny videos"))
        assertTrue(LinkRisk.links("no links here at all").isEmpty())
        assertTrue(LinkRisk.parse("https://chat.whatsapp.com/xyz")!!.official)
    }

    @Test fun trusted_domains_can_be_added_by_config() {
        AppConfig.loadFrom(listOf("t.json" to """{"fraud":{"links":{"trustedDomains":["mtnfoundation.org"]}}}"""))
        assertFalse(LinkRisk.parse("https://www.mtnfoundation.org/news")!!.signals.contains(LinkRisk.Signal.LOOKALIKE))
    }

    @Test fun a_call_then_money_message_warns() {
        val t0 = 1_000_000L
        assertTrue(CallShield.withinCallWindow(t0, t0 + 10 * 60_000L))
        assertFalse(CallShield.withinCallWindow(t0, t0 + 31 * 60_000L))
        assertFalse(CallShield.withinCallWindow(0L, t0))
        assertTrue(ScamWarning.callThenMoney(true, "I sent GHS 200 to you by mistake, please send it back"))
        assertTrue(ScamWarning.callThenMoney(true, "You have received 150.00 cedis from 024xxxxxxx"))
        assertFalse(ScamWarning.callThenMoney(true, "Are we still meeting at 4?"))
        assertFalse(ScamWarning.callThenMoney(false, "I sent GHS 200 to you by mistake"))
    }
}
