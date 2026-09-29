package com.tekyerema

import com.tekyerema.operator.WeakModelPolicy
import com.tekyerema.phonecare.PhoneCheck
import com.tekyerema.phonecare.PhoneCheck.Facts
import com.tekyerema.phonecare.PhoneCheck.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Check my phone" (phonecare/PhoneCheck): the facts → problems → one spoken line, pure. The A20e session of 2026-09-29
 * (Private DNS stall, unbound accessibility service, spent AI quota) is the headline case.
 */
class PhoneCheckTest {
    private fun say(f: Facts) = PhoneCheck.summarise(PhoneCheck.findings(f), f.batteryPct, f.charging)

    /** Owner rules: no step numbers, no counts ("I found three problems"), no emoji. */
    private fun assertSpeakable(line: String) {
        assertFalse(line, Regex("(?i)\\bstep\\b|\\b(?:one|two|three|four|five|\\d+) (?:problems?|issues?|things?|checks?)\\b").containsMatchIn(line))
        assertFalse(line, line.any { Character.getType(it) == Character.OTHER_SYMBOL.toInt() && it.code > 0x2000 && it != '₵' })
        assertFalse("an unfilled template slot", line.contains("{"))
    }

    @Test fun a_healthy_phone_says_so_in_one_line() {
        assertEquals(emptyList<PhoneCheck.Finding>(), PhoneCheck.findings(Facts()))
        assertEquals("Everything I need is working.", say(Facts()))
        assertEquals("Everything I need is working. Your battery is at 80 percent.", say(Facts(batteryPct = 80)))
        assertEquals("Everything I need is working. Your battery is at 42 percent and charging.", say(Facts(batteryPct = 42, charging = true)))
    }

    @Test fun the_judge_session_of_2026_09_29_is_explained_most_important_first() {
        val f = Facts(screenControlBound = false, network = Net.NOT_ANSWERING, privateDnsMode = "hostname",
            privateDnsHost = "dns.adguard.com", mainModelWaitMs = 3 * 3_600_000L, weakPolicy = WeakModelPolicy.ALLOW)
        val found = PhoneCheck.findings(f)
        assertEquals(listOf("screen_control", "internet", "private_dns", "ai_quota"), found.map { it.key })
        val line = say(f)
        assertSpeakable(line)
        assertTrue(line, line.startsWith("Screen control is switched on but isn't running"))
        assertTrue(line, line.contains("Your Private DNS is set to dns.adguard.com, which can slow me down. " +
            "To fix it: Settings, Connections, More connection settings, Private DNS, Automatic."))
        assertTrue(line, line.contains("My main AI has used up its allowance for now, so a smaller backup AI is doing bigger tasks"))
        assertTrue(line, line.contains("in about 3 hours"))
        // The internet line leaves the fix to the DNS line that follows it (the DNS is the likely cause).
        assertFalse(line, line.contains("switch Wi-Fi off and on again"))
        assertTrue(line, line.endsWith("Everything else is working."))
    }

    @Test fun private_dns_alone_is_named_with_its_fix() {
        val f = Facts(privateDnsMode = "hostname", privateDnsHost = "dns.adguard.com")
        assertEquals("Your Private DNS is set to dns.adguard.com, which can slow me down. To fix it: Settings, Connections, " +
            "More connection settings, Private DNS, Automatic. Everything else is working.", say(f))
        // Automatic (opportunistic) or off is fine; a hostname mode with no host is not reported.
        assertEquals("Everything I need is working.", say(Facts(privateDnsMode = "opportunistic", privateDnsHost = "dns.adguard.com")))
        assertEquals("Everything I need is working.", say(Facts(privateDnsMode = "off")))
        assertEquals("Everything I need is working.", say(Facts(privateDnsMode = "hostname", privateDnsHost = " ")))
        // Stock Android's path on a non-Samsung phone.
        assertTrue(say(f.copy(samsung = false)).contains("To fix it: Settings, Network and internet, Private DNS, Automatic."))
    }

    @Test fun at_most_four_problems_are_spoken_and_the_rest_is_not_claimed_working() {
        val f = Facts(micGranted = false, screenControlOn = false, network = Net.OFFLINE, notificationAccess = false,
            callsGranted = false, smsGranted = false, batteryUnrestricted = false)
        val found = PhoneCheck.findings(f)
        assertEquals(7, found.size)
        val line = say(f)
        assertSpeakable(line)
        assertTrue(line, line.startsWith("I can't use the microphone, so I can't hear you. To fix it: in Tɛkyerɛma, open Settings, Permissions, Microphone."))
        assertTrue(line.contains("Screen control is off") && line.contains("You're offline") && line.contains("I can't see your notifications"))
        assertFalse("the fifth and later are not spoken", line.contains("phone calls") || line.contains("text messages") || line.contains("sleep"))
        assertFalse("with problems left unsaid, nothing claims the rest works", line.contains("Everything else"))
    }

    @Test fun every_problem_has_a_one_line_fix_or_none_and_is_spoken_plainly() {
        val all = Facts(micGranted = false, screenControlOn = false, a11yShortcut = false, wakeWordOn = false, floatingButtonOn = false,
            notificationAccess = false, callsGranted = false, contactsGranted = false, smsGranted = false, batteryUnrestricted = false,
            network = Net.NOT_ANSWERING, privateDnsMode = "hostname", privateDnsHost = "one.one.one.one", budgetSpent = true,
            speechIn = false, speechOut = false, speakAloud = false, batteryPct = 9)
        val found = PhoneCheck.findings(all)
        for (x in found) {
            assertSpeakable(x.spoken)
            assertTrue(x.key, x.problem.endsWith("."))
            x.fix?.let { assertTrue(x.key, it.endsWith(".")); assertFalse(x.key, it.contains("\n")) }
        }
        assertEquals("ordered most important first", found.sortedByDescending { it.rank }, found)
        assertEquals("mic", found.first().key)
    }

    @Test fun screen_control_off_and_not_running_are_told_apart() {
        assertTrue(say(Facts(screenControlOn = false)).startsWith("Screen control is off, so I can't open apps or tap for you. " +
            "To fix it: Settings, Accessibility, Installed apps, Tɛkyerɛma, and switch it on."))
        assertTrue(say(Facts(screenControlBound = false)).contains("switch it off, then on again."))
        assertTrue(say(Facts(notificationListenerBound = false)).startsWith("Notification access is on, but it isn't connected. To fix it: restart the phone."))
    }

    @Test fun the_ai_lines_follow_the_users_own_choices() {
        // A per-minute wait refills by itself: not a problem.
        assertEquals("Everything I need is working.", say(Facts(mainModelWaitMs = 30_000L)))
        assertTrue(say(Facts(mainModelWaitMs = 40 * 60_000L, weakPolicy = WeakModelPolicy.NEVER))
            .contains("so I'll pause bigger tasks until it's back. Nothing to fix: it comes back by itself in about 40 minutes."))
        assertTrue(say(Facts(mainModelWaitMs = 2 * 3_600_000L)).contains("so I'll ask before a smaller backup AI takes over a task."))
        // The user's own switches: cloud off and a reached budget are said as such (and outrank the quota line).
        assertTrue(say(Facts(cloudAllowed = false, mainModelWaitMs = 3_600_000L)).startsWith("The cloud is off"))
        assertFalse(say(Facts(cloudAllowed = false, mainModelWaitMs = 3_600_000L)).contains("allowance"))
        assertTrue(say(Facts(budgetSpent = true)).startsWith("You've reached today's online AI limit"))
        assertTrue(say(Facts(onlineAi = false)).startsWith("No online AI is set up"))
    }

    @Test fun small_things_rank_below_what_stops_work() {
        val line = say(Facts(batteryPct = 12, weakNetwork = true, network = Net.ONLINE))
        assertEquals("Your battery is at 12 percent. Plug in your charger soon. Your connection is slow, so online answers " +
            "will take longer. Everything else is working.", line)
        // Charging at 12 %: not a problem.
        assertEquals("Everything I need is working. Your battery is at 12 percent and charging.", say(Facts(batteryPct = 12, charging = true)))
        // No quick start at all is only said when the wake word and the floating button are both off too.
        assertEquals("Everything I need is working.", say(Facts(a11yShortcut = false)))
        assertTrue(say(Facts(a11yShortcut = false, wakeWordOn = false, floatingButtonOn = false)).startsWith("There's no quick way to start me"))
        // Couldn't tell (no probe): never a false alarm.
        assertEquals("Everything I need is working.", say(Facts(network = Net.UNKNOWN, a11yShortcut = null, speechIn = null, speechOut = null)))
    }

    @Test fun speech_problems_name_the_right_thing() {
        assertTrue(say(Facts(speechIn = false)).startsWith("Your phone's speech recognition isn't available"))
        assertTrue(say(Facts(speechIn = false, onDeviceSpeech = true)).startsWith("My on-device speech model isn't on this phone"))
        assertTrue(say(Facts(speechOut = false)).startsWith("Your phone has no text-to-speech voice"))
    }

    @Test fun a_personal_dns_host_is_not_read_out() {
        assertEquals("dns.adguard.com", PhoneCheck.spokenDnsHost("dns.adguard.com"))
        assertEquals("dns.google", PhoneCheck.spokenDnsHost("DNS.Google."))
        // AdGuard / NextDNS personal hosts carry an account or device id.
        assertEquals("a custom server", PhoneCheck.spokenDnsHost("abc123.d.adguard-dns.com"))
        assertEquals("a custom server", PhoneCheck.spokenDnsHost("5f2a1b.dns.nextdns.io"))
        val line = say(Facts(privateDnsMode = "hostname", privateDnsHost = "abc123.d.adguard-dns.com"))
        assertTrue(line, line.startsWith("Your Private DNS is set to a custom server, which can slow me down."))
        assertFalse(line, line.contains("abc123"))
        // The screen still shows it in full.
        assertTrue("Private DNS: abc123.d.adguard-dns.com" in PhoneCheck.details(Facts(privateDnsMode = "hostname", privateDnsHost = "abc123.d.adguard-dns.com")))
    }

    @Test fun spoken_waits_are_rounded_for_the_ear() {
        assertEquals("in a minute or two", PhoneCheck.spokenWait(95_000L))
        assertEquals("in about 7 minutes", PhoneCheck.spokenWait(6 * 60_000L + 30_000L))
        assertEquals("in about an hour", PhoneCheck.spokenWait(70 * 60_000L))
        assertEquals("in about 3 hours", PhoneCheck.spokenWait(3 * 3_600_000L - 60_000L))
    }

    @Test fun details_are_for_the_screen_and_name_the_model() {
        val d = PhoneCheck.details(Facts(privateDnsMode = "hostname", privateDnsHost = "dns.adguard.com",
            lastModel = "groq/openai/gpt-oss-20b", aiCallsToday = 34, batteryPct = 51))
        assertTrue(d.toString(), "Private DNS: dns.adguard.com" in d)
        assertTrue(d.toString(), "Online AI: gpt-oss-120b" in d)
        assertTrue(d.toString(), "Last answer from: gpt-oss-20b" in d)
        assertTrue(d.toString(), "Online AI calls today: 34" in d)
        assertTrue(d.toString(), "Battery: 51%" in d)
        assertEquals("gemini", PhoneCheck.shortModel("gemini"))
    }

    @Test fun the_brain_cooling_read_needs_no_network_and_no_keys() {
        assertEquals(0L, com.tekyerema.agent.OpenAiCompatBrain.coolingLeftMs("openai/gpt-oss-120b", emptyList()))
        assertEquals(0L, com.tekyerema.agent.OpenAiCompatBrain.coolingLeftMs("openai/gpt-oss-120b", listOf("k-never-used")))
    }
}
