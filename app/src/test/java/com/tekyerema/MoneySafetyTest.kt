package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.NumberCorrection
import com.tekyerema.agent.NumberSpeech
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.TargetSemantics
import com.tekyerema.tools.MoMoSendTool
import com.tekyerema.tools.MobileMoneyTool
import com.tekyerema.tools.MtnConfirm
import com.tekyerema.voice.ConfirmTemplates
import com.tekyerema.voice.Confirmation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Money safety (2026-09-29): MTN's own confirmation is read and checked before the PIN; numbers are read back in 3-3-4
 * groups with their network; one group can be corrected during a confirmation; a USSD payment screen is a credential
 * screen whatever its prompt says; the task ends at the Mobile Money step.
 */
class MoneySafetyTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    /** The final *170# screen of the owner's live 10-pesewa send (A20e, 2026-09-29), as the tool reads it (labels + "\n"). */
    private val REAL = "Transfer to AMA MENSAH for GHS 0.1 with Reference: Payment. Fee is GHS 0.00, Tax amount is GHS 0.00. " +
        "Total Amount is GHS 0.10. Enter MM # for next\nCancel"
    private fun bd(s: String) = BigDecimal(s)

    // ---------------------------------------------------------------- MtnConfirm.parse

    @Test fun parses_the_real_mtn_confirmation() {
        for (text in listOf(REAL, REAL.replace("\n", " | "))) {
            val s = MtnConfirm.parse(text)
            assertEquals("AMA MENSAH", s.name)
            assertEquals(0, s.amount!!.compareTo(bd("0.1")))
            assertEquals(0, s.fee!!.compareTo(BigDecimal.ZERO))
            assertEquals(0, s.tax!!.compareTo(BigDecimal.ZERO))
            assertEquals(0, s.total!!.compareTo(bd("0.10")))
            assertEquals("Payment", s.reference)
            assertNull(s.number)
        }
    }

    @Test fun parses_wording_variants() {
        val a = MtnConfirm.parse("Send GHS 0.10 to AMA MENSAH (0553238011). Fee: GHS 0.00. Total: GHS 0.10. Enter PIN")
        assertEquals("AMA MENSAH", a.name); assertEquals("0553238011", a.number)
        assertEquals(0, a.amount!!.compareTo(bd("0.10"))); assertEquals(0, a.total!!.compareTo(bd("0.10")))
        val b = MtnConfirm.parse("You are transferring 5.00 GHS to 233553238011 KOFI MENSAH. E-Levy GHS 0.05. Total Amount GHS 5.05. Enter MM PIN")
        assertEquals("KOFI MENSAH", b.name); assertEquals("0553238011", b.number)
        assertEquals(0, b.amount!!.compareTo(bd("5"))); assertEquals(0, b.tax!!.compareTo(bd("0.05"))); assertEquals(0, b.total!!.compareTo(bd("5.05")))
        // Currency written GH₵ with a thousands comma; a charge; a name that contains a label word never labels the amount.
        val c = MtnConfirm.parse("Transfer to FEE MENSAH for GH₵1,000.00. Charges GH₵ 5.00. Total GH₵1,005.00. Enter PIN")
        assertEquals("FEE MENSAH", c.name)
        assertEquals(0, c.amount!!.compareTo(bd("1000"))); assertEquals(0, c.fee!!.compareTo(bd("5"))); assertEquals(0, c.total!!.compareTo(bd("1005")))
        // No name at all.
        val d = MtnConfirm.parse("Transfer GHS 0.10. Total Amount is GHS 0.10. Enter MM PIN")
        assertNull(d.name); assertEquals(0, d.amount!!.compareTo(bd("0.10")))
        // "Reply 1 to confirm" is not a person; a balance figure is never the amount.
        val e = MtnConfirm.parse("Your balance is GHS 12.00. Reply 1 to confirm")
        assertNull(e.name); assertNull(e.amount)
    }

    // ---------------------------------------------------------------- MtnConfirm.verdict

    @Test fun the_real_screen_is_spoken_facts_first_before_the_pin() {
        val v = MtnConfirm.verdict(REAL, "0.1", "0553238011", pinScreen = true)
        assertTrue(v is MtnConfirm.Verdict.HandOff)
        assertEquals("MTN says this sends 0.10 cedis to Zelia Awini, no fee, total 0.10 cedis — if that's right, enter your PIN; " +
            "if not, press Cancel.", (v as MtnConfirm.Verdict.HandOff).spoken)
        assertEquals("AMA MENSAH", MtnConfirm.data(v.screen).getString("registeredName"))
        // Spoken, it is natural cedi wording — and one sentence, so "brief" speech keeps the instruction.
        val said = com.tekyerema.voice.Verbalize.apply(v.spoken)
        // "MTN" is said letter by letter since 2 October 2026 (the voice ran the three letters together).
        assertTrue(said, said.startsWith("M T N says this sends ten pesewas to Zelia Awini, no fee, total ten pesewas"))
        assertEquals(v.spoken, com.tekyerema.voice.SpeechLint.firstSentence(v.spoken))
        assertFalse(Regex("(?i)\\bstep\\b").containsMatchIn(v.spoken))
    }

    @Test fun a_different_amount_is_cancelled_not_handed_off() {
        val v = MtnConfirm.verdict(REAL.replace("GHS 0.1 ", "GHS 10.00 ").replace("GHS 0.10", "GHS 10.00"), "0.1", "0553238011", true)
        assertTrue(v is MtnConfirm.Verdict.Cancel)
        val why = (v as MtnConfirm.Verdict.Cancel).why
        assertTrue(why, why.contains("10 cedis to Zelia Awini") && why.contains("you asked for 0.10 cedis"))
        assertEquals("$why, so I pressed Cancel. Nothing was sent.", MtnConfirm.cancelledLine(why, pressed = true))
        assertTrue(MtnConfirm.cancelledLine(why, pressed = false).contains("Don't enter your PIN"))
    }

    @Test fun no_amount_another_number_or_not_the_pin_screen_is_cancelled() {
        val noAmount = MtnConfirm.verdict("Transfer to AMA MENSAH. Enter MM # for next", "0.1", "0553238011", true)
        assertTrue(noAmount is MtnConfirm.Verdict.Cancel && noAmount.why.contains("couldn't find the amount"))
        val otherNumber = MtnConfirm.verdict("Send GHS 0.10 to AMA (0243238011). Total GHS 0.10. Enter PIN", "0.1", "0553238011", true)
        assertTrue(otherNumber is MtnConfirm.Verdict.Cancel && otherNumber.why.contains("0 2 4, 3 2 3, 8 0 1 1"))
        val sameNumberIntl = MtnConfirm.verdict("Send GHS 0.10 to AMA (233553238011). Total GHS 0.10. Enter PIN", "0.1", "0553238011", true)
        assertTrue(sameNumberIntl is MtnConfirm.Verdict.HandOff)
        val notPin = MtnConfirm.verdict("Transfer to AMA MENSAH for GHS 0.1. 1. Confirm 2. Cancel", "0.1", "0553238011", pinScreen = false)
        assertTrue(notPin is MtnConfirm.Verdict.Cancel && notPin.why.contains("isn't the PIN screen"))
        val badRequest = MtnConfirm.verdict(REAL, "ten", "0553238011", true)
        assertTrue(badRequest is MtnConfirm.Verdict.Cancel)
    }

    @Test fun no_name_still_hands_off_but_says_to_check_the_number() {
        val v = MtnConfirm.verdict("Transfer GHS 0.10. Fee is GHS 0.50, Total Amount is GHS 0.60. Enter MM PIN", "0.10", "0553238011", true)
        assertTrue(v is MtnConfirm.Verdict.HandOff)
        val line = (v as MtnConfirm.Verdict.HandOff).spoken
        assertTrue(line, line.contains("fee 0.50 cedis, total 0.60 cedis, but it didn't show a name, so check the number: 0 5 5, 3 2 3, 8 0 1 1"))
        assertTrue(line.contains("enter your PIN"))
        assertEquals("5 cedis", MtnConfirm.cedis(bd("5.00"))); assertEquals("1 cedi", MtnConfirm.cedis(bd("1")))
        assertEquals("Kofi Mensah-Boateng", MtnConfirm.speakName("KOFI MENSAH-BOATENG")); assertEquals("Ama", MtnConfirm.speakName("Ama"))
    }

    // ---------------------------------------------------------------- NumberSpeech

    @Test fun numbers_are_grouped_3_3_4_and_plus_233_folds() {
        assertEquals(listOf("055", "323", "8011"), NumberSpeech.groups("0553238011"))
        assertEquals("0 5 5, 3 2 3, 8 0 1 1", NumberSpeech.spoken("0553238011"))
        assertEquals("0 5 5, 3 2 3, 8 0 1 1", NumberSpeech.spoken("+233 55 323 8011"))
        assertEquals("0 5 5, 3 2 3, 8 0 1 1", NumberSpeech.spoken("233553238011"))
        assertEquals(listOf("112"), NumberSpeech.groups("112"))
        assertEquals(listOf("055", "3238"), NumberSpeech.groups("0553238"))          // 7: a lone last digit joins the group
        assertEquals(listOf("055", "332", "380", "11"), NumberSpeech.groups("05533238011"))
        assertEquals("plus 4 4 7, 9 1 1, 1 2 3, 4 5 6", NumberSpeech.spoken("+447911123456"))
        assertEquals("", NumberSpeech.spoken(null))
    }

    @Test fun ghana_networks_from_the_prefix_table() {
        assertEquals("MTN", NumberSpeech.ghanaNetwork("0553238011"))
        for (p in listOf("024", "054", "055", "059", "025", "053")) assertEquals(p, "MTN", NumberSpeech.ghanaNetwork(p + "1234567"))
        for (p in listOf("020", "050")) assertEquals(p, "Telecel", NumberSpeech.ghanaNetwork(p + "1234567"))
        for (p in listOf("026", "027", "056", "057")) assertEquals(p, "AirtelTigo", NumberSpeech.ghanaNetwork(p + "1234567"))
        assertNull(NumberSpeech.ghanaNetwork("0123456789")); assertNull(NumberSpeech.ghanaNetwork("055323801"))
        assertEquals("MTN", NumberSpeech.ghanaNetwork("+233 24 412 3456"))
        assertEquals("an MTN number", NumberSpeech.networkPhrase("MTN"))
        assertEquals("a Telecel number", NumberSpeech.networkPhrase("Telecel"))
        assertEquals("an AirtelTigo number", NumberSpeech.networkPhrase("AirtelTigo"))
    }

    @Test fun shipped_prefix_table_matches_the_code_default_and_is_config_driven() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        AppConfig.loadFrom(dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { "config/${it.name}" to it.readText() })
        assertEquals(mapOf("mtn" to listOf("024", "025", "053", "054", "055", "059"), "telecel" to listOf("020", "050"),
            "airteltigo" to listOf("026", "027", "056", "057")), AppConfig.stringListMap("connectors.mobileMoney.prefixes"))
        assertEquals("MTN", NumberSpeech.ghanaNetwork("0253238011")); assertEquals("Telecel", NumberSpeech.ghanaNetwork("0503238011"))
        assertEquals(400, AppConfig.int("tools.resultLimits.momo_send", 0))   // MTN's line + the name survive history truncation
        // A config change moves a prefix (the table is data, not code).
        AppConfig.loadFrom(listOf("c" to """{"connectors":{"mobileMoney":{"prefixes":{"glo":["023"]}}}}"""))
        assertEquals("Glo", NumberSpeech.ghanaNetwork("0233238011")); assertNull(NumberSpeech.ghanaNetwork("0553238011"))
    }

    @Test fun money_read_back_names_the_network_and_flags_what_to_check() {
        assertEquals("0 5 5, 3 2 3, 8 0 1 1, an MTN number" to null, NumberSpeech.moneyReadBack("0553238011", "mtn"))
        val other = NumberSpeech.moneyReadBack("0203238011", "mtn")
        assertEquals("0 2 0, 3 2 3, 8 0 1 1, a Telecel number", other.first)
        assertEquals("Your SIM is on MTN, so check the number before you send.", other.second)
        assertTrue(NumberSpeech.moneyReadBack("0123238011").second!!.contains("doesn't start like an MTN, Telecel or AirtelTigo number"))
        assertNull(NumberSpeech.moneyReadBack("0273238011", null).second)            // unknown SIM: no comparison
    }

    // ---------------------------------------------------------------- read-backs use the grouping

    @Test fun momo_send_read_back_is_grouped_with_its_network_and_one_question() {
        val a = JSONObject().put("number", "0553238011").put("amount", "0.1").put(MoMoSendTool.ARG_SIM, "mtn")
        val p = MoMoSendTool.confirmationPrompt(a)
        assertEquals("Send 0.1 cedis to 0 5 5, 3 2 3, 8 0 1 1, an MTN number, with Mobile Money?\n" +
            "I'll go through the menu and stop at the PIN for you to enter.", p)
        val q = Confirmation.question(p)
        assertEquals(1, q.count { it == '?' })
        assertTrue(q, q.startsWith("You want me to send 0.1 cedis to 0 5 5, 3 2 3, 8 0 1 1, an MTN number"))
        assertNull(MoMoSendTool.networkWarning(a))
        val telecel = JSONObject().put("number", "0503238011").put("amount", "5").put(MoMoSendTool.ARG_SIM, "mtn")
        assertTrue(MoMoSendTool.confirmationPrompt(telecel).contains("a Telecel number, with Mobile Money?\nYour SIM is on MTN"))
        assertNotNull(MoMoSendTool.networkWarning(telecel))
        // The misheard-number message reads the number in groups too.
        assertTrue(MoMoSendTool.numberProblem("0553238")!!.contains("0 5 5, 3 2 3 8 — that's 7 digits"))
    }

    @Test fun other_read_backs_group_the_number() {
        val mm = MobileMoneyTool.confirmationPrompt(JSONObject().put("_kind", "send_money").put("_amount", "5").put("_number", "0553238011"))
        assertTrue(mm, mm.startsWith("Send 5 cedis to 0 5 5, 3 2 3, 8 0 1 1, an MTN number, with Mobile Money?"))
        val named = MobileMoneyTool.confirmationPrompt(JSONObject().put("_kind", "send_money").put("_amount", "5").put("_recipient", "Ama"))
        assertEquals("Send 5 cedis to Ama with Mobile Money? I'll go through the menu; you'll enter your PIN.", named)
        assertTrue(MobileMoneyTool.confirmationPrompt(JSONObject().put("_kind", "send_money").put("_number", "0123238011")).contains("\nThat number doesn't start like"))
        assertEquals("Call 0 5 5, 3 2 3, 8 0 1 1?", com.tekyerema.tools.MakeCallTool.confirmationPrompt(JSONObject().put("number", "0553238011")))
        assertEquals("zero anum anum, mmiɛnsa mmienu mmiɛnsa, nwɔtwe zero baako baako", ConfirmTemplates.twiNumber("0553238011"))
        // LocalBrain's misheard number: grouped, and the network it starts like.
        val d = kotlinx.coroutines.runBlocking { LocalBrain().decide(com.tekyerema.agent.BrainContext("send 5 cedis to 055323801",
            JSONObject(), emptyList(), org.json.JSONArray(), org.json.JSONArray())) }
        assertTrue("$d", d is AgentDecision.Done && d.summary.contains("0 5 5, 3 2 3, 8 0 1") && d.summary.contains("It starts like an MTN number"))
    }

    // ---------------------------------------------------------------- NumberCorrection

    private val N = "0553238011"

    @Test fun one_group_is_corrected_by_position() {
        assertEquals("0554568011", NumberCorrection.apply(N, "the middle is 4 5 6"))
        assertEquals("0554568011", NumberCorrection.apply(N, "The middle part should be four five six."))
        assertEquals("0553238012", NumberCorrection.apply(N, "the last four is 8 0 1 2"))
        assertEquals("0553238012", NumberCorrection.apply(N, "no, the last four are eight oh one two"))
        assertEquals("0543238011", NumberCorrection.apply(N, "the first part is 054"))
        assertEquals("0543238011", NumberCorrection.apply(N, "the first one is oh five four"))
        assertEquals("0553234567", NumberCorrection.apply(N, "the last 4 5 6 7"))              // not a count: the group's own digits
        assertEquals("0553238111", NumberCorrection.apply(N, "the last three is triple one"))
        assertEquals("0553238022", NumberCorrection.apply(N, "it ends with 8 0 2 2"))
        assertEquals("0243238011", NumberCorrection.apply(N, "it starts with 024"))
        assertEquals("0553558011", NumberCorrection.apply(N, "the second group is 3 double five"))
    }

    @Test fun a_change_or_a_whole_new_number() {
        assertEquals("0553328011", NumberCorrection.apply(N, "change 323 to 332"))
        assertEquals("0553328011", NumberCorrection.apply(N, "not 323, 332"))
        assertEquals("0553328011", NumberCorrection.apply(N, "332 not 323"))
        assertEquals("0553328011", NumberCorrection.apply(N, "323 should be 332"))
        assertEquals("0553238012", NumberCorrection.apply(N, "it's 0 5 5 3 2 3 8 0 1 2"))
        assertEquals("0553238012", NumberCorrection.apply(N, "no, it's oh double five, three two three, eight oh one two"))
        assertEquals("0553238012", NumberCorrection.apply("+233553238011", "+233 55 323 8012"))
        assertEquals(N, NumberCorrection.apply(N, "yes 0553238011"))                            // repeated: unchanged
    }

    @Test fun anything_that_isnt_an_exact_fix_goes_to_the_planner() {
        assertNull(NumberCorrection.apply(N, "the middle is 4 5"))                 // wrong length for the group
        assertNull(NumberCorrection.apply(N, "change 5 to 6"))                     // one digit: ambiguous
        assertEquals("0663238011", NumberCorrection.apply(N, "change 55 to 66"))    // two digits, found once: applied
        assertNull(NumberCorrection.apply("0553553011", "change 55 to 66"))        // "55" occurs twice: ambiguous
        assertNull(NumberCorrection.apply(N, "change 999 to 111"))                 // not in the number
        assertNull(NumberCorrection.apply(N, "make it 5 cedis"))                   // the amount
        assertNull(NumberCorrection.apply(N, "change the message to call me on 0244123456"))
        assertNull(NumberCorrection.apply(N, "send it to Kofi instead"))
        assertNull(NumberCorrection.apply(N, "no"))
        assertNull(NumberCorrection.apply("112", "the last is 3"))                 // not a phone number
        assertEquals("055 , 323 , 8011", NumberCorrection.digitize("0 5 5, 3 2 3, 8 0 1 1"))
        assertEquals("oh , the middle is 456", NumberCorrection.digitize("Oh, the middle is four five six"))
    }

    @Test fun a_digit_fix_is_a_revision_even_after_no_and_a_stop_stays_a_decline() {
        assertTrue(Confirmation.looksLikeRevision("no, the last four is 8 0 1 2"))
        assertTrue(Confirmation.looksLikeRevision("the middle is 4 5 6"))
        assertTrue(Confirmation.looksLikeRevision("no it's 0 5 5 3 2 3 8 0 1 2"))
        assertTrue(Confirmation.looksLikeRevision("change 323 to 332"))
        assertFalse(Confirmation.looksLikeRevision("no"))
        assertFalse(Confirmation.looksLikeRevision("yes"))
        assertFalse(Confirmation.looksLikeRevision("no, cancel the middle 456"))
        assertFalse(Confirmation.looksLikeRevision("yes send 5 cedis"))
        assertEquals(Confirmation.Answer.NO, Confirmation.interpret("no, the last four is 8 0 1 2"))   // interpret itself unchanged
    }

    @Test fun revise_rebuilds_the_pending_action_with_the_new_number() {
        val momo = JSONObject().put("number", N).put("amount", "0.1").put(MoMoSendTool.ARG_SIM, "mtn")
        val r = NumberCorrection.revise("momo_send", momo, "the middle is 4 5 6")!!
        assertEquals("0554568011", r.number); assertTrue(r.changed)
        assertEquals("0554568011", r.args.getString("number")); assertEquals("0.1", r.args.getString("amount"))
        assertEquals(N, momo.getString("number"))                                  // the original proposal is untouched
        // The SMS read-back text derived from the old number is dropped, so preflight rebuilds it for the new one.
        val sms = JSONObject().put("number", N).put("message", "hi").put("_to", "0 5 5, 3 2 3, 8 0 1 1")
        assertFalse(NumberCorrection.revise("send_sms", sms, "the last four is 8012")!!.args.has("_to"))
        assertEquals("0554568011", NumberCorrection.revise("mobile_money", JSONObject().put("_number", N), "the middle is 456")!!.number)
        assertNull(NumberCorrection.revise("make_call", JSONObject().put("contact", "Ama"), "the middle is 456"))   // no number to fix
        assertNull(NumberCorrection.revise("open_app", JSONObject().put("app", "x"), "the middle is 456"))
        assertFalse(NumberCorrection.revise("make_call", JSONObject().put("number", N), "it's 0553238011")!!.changed)
    }

    // ---------------------------------------------------------------- fail-closed credential screen

    private fun el(id: Int, label: String?, editable: Boolean = false, clickable: Boolean = !editable) =
        UiElement(id, if (editable) "EditText" else "TextView", label, Box(0, id * 100, 500, id * 100 + 80),
            editable = editable, clickable = clickable)
    private fun snap(pkg: String, vararg e: UiElement) = UiSnapshot(pkg, null, e.toList(), 0L, PerceptionRoute("tree", "test"))

    @Test fun a_ussd_payment_screen_is_a_credential_screen_whatever_its_prompt_says() {
        // The real screen (its "Enter MM" was already a credential word).
        val real = snap("com.android.phone", el(1, REAL.substringBefore("\n"), clickable = false), el(2, null, editable = true),
            el(3, "Cancel"), el(4, "Send"))
        assertTrue(TargetSemantics.isCredentialScreen(real))
        assertEquals("momo_pin", TargetSemantics.handoffReason(real, dialerLike = true))
        // Reworded with no PIN / "Enter MM" at all — still credential (a GHS figure + total).
        val reworded = snap("com.android.phone", el(1, "Transfer to AMA MENSAH for GHS 0.1. Total Amount is GHS 0.10. Reply to continue",
            clickable = false), el(2, null, editable = true), el(3, "Cancel"), el(4, "Send"))
        assertTrue(TargetSemantics.isCredentialScreen(reworded))
        assertEquals("momo_pin", TargetSemantics.handoffReason(reworded, dialerLike = true))
        // Found by the dialog's shape in an unknown package (one input + Send + Cancel).
        val shaped = snap("com.vendor.ussd", el(1, "Pay GHS 5.00 to KOFI. Fee GHS 0.00", clickable = false), el(2, null, editable = true),
            el(3, "Cancel"), el(4, "OK"))
        assertTrue(TargetSemantics.isCredentialScreen(shaped))
    }

    @Test fun ussd_menus_that_mention_money_are_not_credential_screens() {
        // MTN's first menu mentions "Transfer Money" and has an input box — it must stay operable.
        val menu = snap("com.android.phone", el(1, "1) Transfer Money\n2) MoMoPay & Pay Bill\n3) Airtime & Bundles\n4) Allow Cash Out\n" +
            "5) Financial Services\n6) Wallet", clickable = false), el(2, null, editable = true), el(3, "Cancel"), el(4, "Send"))
        assertFalse(TargetSemantics.isCredentialScreen(menu))
        // Amount entry; a bundle price list (figures, but no total / fee / PIN).
        assertFalse(TargetSemantics.isCredentialScreen(snap("com.android.phone", el(1, "Enter amount", clickable = false), el(2, null, editable = true))))
        assertFalse(TargetSemantics.isCredentialScreen(snap("com.android.phone", el(1, "1) GHS 1 = 50MB\n2) GHS 5 = 350MB", clickable = false),
            el(2, null, editable = true), el(3, "Cancel"), el(4, "Send"))))
        // A shop's checkout with a notes box is not a USSD dialog.
        assertFalse(TargetSemantics.isCredentialScreen(snap("com.shop.app", el(1, "Total GHS 50.00", clickable = false),
            el(2, "Delivery notes", editable = true), el(3, "Place order"))))
        assertFalse(TargetSemantics.isMoneyConfirmationText("1) Transfer Money 2) MoMoPay"))
        assertTrue(TargetSemantics.isMoneyConfirmationText("Total Amount is GHS 0.10"))
    }

    // ---------------------------------------------------------------- the task ends at the money step

    @Test fun the_task_ends_at_the_mobile_money_step() {
        val ok = "momo_send({number=0553238011, amount=0.1}) → success [native]; verified=false (no expectation given); " +
            "${com.tekyerema.agent.AgentEngine.UNTRUSTED_MARK} MTN says this sends 0.10 cedis to Zelia Awini, no fee, total 0.10 cedis — " +
            "if that's right, enter your PIN; if not, press Cancel."
        val end = RoutingBrain.moneyEnd(listOf("open_app({app=x}) → success", ok, "PLAN: subgoal 1 done"))!!
        assertEquals(AgentStatus.COMPLETED, end.first)
        assertTrue(end.second, end.second.startsWith("MTN says this sends 0.10 cedis to Zelia Awini") && end.second.endsWith("press Cancel."))
        val cancelled = "momo_send({number=0553238011}) → fail [native]; verified=false (x); MTN's screen says it would send 10 cedis " +
            "to Zelia Awini, but you asked for 0.10 cedis, so I pressed Cancel. Nothing was sent. — this route is a genuine dead-end (environment-blocked); only call cannot_do"
        val c = RoutingBrain.moneyEnd(listOf(cancelled))!!
        assertEquals(AgentStatus.CANNOT_DO, c.first)
        assertTrue(c.second, c.second.endsWith("so I pressed Cancel. Nothing was sent."))
        // A preflight refusal (misheard number) is not an end: the planner asks again. Another tool last: not an end.
        assertNull(RoutingBrain.moneyEnd(listOf("momo_send({number=0553238}) → fail [preflight]; verified=false (not attempted); I heard the number …")))
        assertNull(RoutingBrain.moneyEnd(listOf(ok, "ask_user(\"Which one?\") → the user answered: \"the first\"")))
        assertNull(RoutingBrain.moneyEnd(emptyList()))
    }
}
