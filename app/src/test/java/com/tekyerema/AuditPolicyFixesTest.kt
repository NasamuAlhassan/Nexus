package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.ActionSemantics
import com.tekyerema.policy.RiskTier
import com.tekyerema.policy.TargetSemantics
import com.tekyerema.policy.TargetSemantics.TapEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A20e black-box audit, 2026-10-01 — the policy half of fixes 1 and 5:
 *  1. Secure Input Mode fired on an ordinary Telegram chat because an old message mentioned a PIN (E2E2, E2E3);
 *  5. "Clear", "Close all", "Start Bot", "Show MoMo balance" and the Wi-Fi tile were tapped with no confirmation
 *     (S23, S20, C10, F04), and read-backs doubled the caption ('Delete “Delete”?').
 */
class AuditPolicyFixesTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    private fun box(id: Int) = Box(0, id * 100, 600, id * 100 + 80)
    private fun text(id: Int, label: String, clickable: Boolean = false) = UiElement(id, "TextView", label, box(id), clickable = clickable)
    private fun btn(id: Int, label: String, checkable: Boolean = false) =
        UiElement(id, "Button", label, box(id), clickable = true, checkable = checkable)
    private fun field(id: Int, label: String? = null, hint: String? = null, viewId: String? = null, focused: Boolean = false,
                      password: Boolean = false) =
        UiElement(id, "EditText", label, box(id), editable = true, focused = focused, password = password, viewId = viewId, hint = hint)
    private fun snap(pkg: String, vararg e: UiElement, title: String? = null, appName: String? = null, secure: Boolean = false) =
        UiSnapshot(pkg, title, e.toList(), 0L, PerceptionRoute("tree", "test"), secureWindow = secure, appName = appName)

    private fun secure(s: UiSnapshot) = TargetSemantics.isCredentialScreen(s)
    private fun tier(label: String, screen: UiSnapshot? = null, primitive: String = "tap", target: UiElement? = btn(1, label)) =
        TargetSemantics.classify(primitive, target, null, false, screen).tier

    private val PIN_MESSAGE = "3. App Interface: The myMTN app is already there, so go through it, examine the interface and " +
        "button layouts, and code around it if possible. When the flow reaches the PIN stage, stop and let the user interact " +
        "directly to enter their PIN."

    // ------------------------------------------------------------------ fix 1: what is NOT a credential screen

    @Test fun a_chat_that_mentions_a_pin_is_not_a_credential_screen() {
        // The screen from E2E2: the real Richmond chat, an old message about "the PIN stage", and the message box.
        val telegram = snap("org.telegram.messenger", text(1, "Richmond Apenensu"), text(2, PIN_MESSAGE, clickable = true),
            text(3, "Send me the OTP"), text(4, "my password is hunter2", clickable = true), text(5, "PIN"),
            field(6, hint = "Message"), btn(7, "Send"), title = "Richmond Apenensu")
        assertFalse(secure(telegram))
        // Telegram draws its own views: no hint, no resource id, and the box holds what is being typed.
        assertFalse(secure(snap("org.telegram.messenger", text(1, "Enter your PIN"), field(2, label = "what's your pin", focused = true))))
        // WhatsApp replying to a message shows Cancel + Send round ONE input — the shape of a prompt dialog, but it is a chat.
        assertFalse(secure(snap("com.whatsapp", text(1, "Please enter your PIN to confirm"), field(2, hint = "Message"),
            btn(3, "Cancel"), btn(4, "Send"))))
        // SMS, mail and social apps alike.
        for (pkg in listOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.google.android.gm",
                "com.facebook.orca", "com.instagram.android"))
            assertFalse(pkg, secure(snap(pkg, text(1, "Your OTP is 482913. Enter this password to continue."), field(2, hint = "Text message"))))
    }

    @Test fun a_chat_app_that_is_not_on_the_list_is_recognised_by_its_message_box() {
        val unknown = snap("com.example.chatty", text(1, "Kofi"), text(2, "Enter your PIN"), text(3, PIN_MESSAGE),
            field(4, hint = "Type a message"), btn(5, "Send"))
        assertFalse(secure(unknown))
        assertFalse(secure(snap("com.example.forum", text(1, "Password"), field(2, viewId = "com.example.forum:id/comment_input"))))
        assertTrue(TargetSemantics.isComposer(field(1, hint = "Reply")))
        assertFalse(TargetSemantics.isComposer(field(1, hint = "Search messages")))
    }

    @Test fun web_pages_and_search_boxes_are_not_credential_screens_on_text_alone() {
        val article = snap("com.android.chrome", text(1, "How to reset your password"), text(2, "Enter your PIN"),
            field(3, label = "example.com/help", viewId = "com.android.chrome:id/url_bar"), field(4, hint = "Add a comment"),
            title = "Reset your password – Help")
        assertFalse(secure(article))
        // Settings' own search: the only input is a search box, the rows merely name PINs and passwords.
        val settings = snap("com.android.settings", field(1, hint = "Search", viewId = "com.android.settings:id/search_src_text"),
            text(2, "Passwords and autofill", clickable = true), text(3, "Change PIN"), title = "Search")
        assertFalse(secure(settings))
        // A password manager's search box is still a search box.
        assertFalse(secure(snap("com.example.vault", field(1, hint = "Search passwords"))))
    }

    @Test fun typed_text_ids_and_long_content_do_not_look_like_a_credential_field() {
        // A note being written, in an app with no hint on its field.
        assertFalse(secure(snap("com.example.notes", field(1, label = "the wifi password is on the fridge door", focused = true))))
        // "spinner" and "opinion" contain "pin" but are not PIN fields.
        assertFalse(secure(snap("com.example.app", field(1, viewId = "com.example.app:id/spinner_input"))))
        assertFalse(secure(snap("com.example.app", field(1, viewId = "com.example.app:id/opinionBox"))))
        // A tappable row of someone's words next to an ordinary field is content.
        assertFalse(secure(snap("com.example.app", text(1, "Kofi: the OTP for our group is 5512, don't share it", clickable = true),
            field(2, hint = "Name"))))
        // No input at all → never a credential screen.
        assertFalse(secure(snap("com.example.bank", text(1, "Enter your PIN"))))
    }

    // ------------------------------------------------------------------ fix 1: what still IS one

    @Test fun real_credential_prompts_are_still_detected() {
        // The network's dialog — short or one long text, tappable or not.
        assertTrue(secure(snap("com.android.phone", text(1, "Enter MoMo PIN"), field(2), btn(3, "Cancel"), btn(4, "Send"))))
        assertTrue(secure(snap("com.android.phone", text(1, "You are about to buy a bundle for 0552093690 from your wallet. It expires in " +
            "30 days and renews itself. Reply with your PIN", clickable = true), field(2), btn(3, "Cancel"), btn(4, "Send"))))
        assertTrue(secure(snap("com.samsung.android.dialer", text(1, "1. Transfer\n4. Change PIN"), field(2))))
        // A lone input with OK + Cancel in an unknown app.
        assertTrue(secure(snap("com.vendor.wallet", text(1, "Please authorise this with your secret PIN, then press OK to go on", clickable = true),
            field(2), btn(3, "Cancel"), btn(4, "OK"))))
        // A bank app: the field says what it takes — by hint, by id, or by its own short name.
        assertTrue(secure(snap("com.example.bank", text(1, "Welcome back"), field(2, hint = "Enter PIN"))))
        assertTrue(secure(snap("com.example.bank", field(1, hint = "Username"), field(2, hint = "Password"))))
        assertTrue(secure(snap("com.example.bank", field(1, hint = "One-time code (OTP)"))))
        assertTrue(secure(snap("com.example.bank", field(1, viewId = "com.example.bank:id/otpInput"))))
        assertTrue(secure(snap("com.example.bank", field(1, viewId = "com.example.bank:id/pin_entry"))))
        assertTrue(secure(snap("com.example.bank", field(1, label = "PIN"))))
        // …or the words beside it do: a static label, a caption-sized tappable one, the title, a sentence that asks.
        assertTrue(secure(snap("com.example.bank", text(1, "Enter your PIN"), field(2))))
        assertTrue(secure(snap("com.example.bank", text(1, "MoMo PIN", clickable = true), field(2))))
        assertTrue(secure(snap("com.example.bank", field(1), title = "Enter passcode")))
        assertTrue(secure(snap("com.example.bank", text(1, "We sent a code to 055 209 3690 by text message. Enter the OTP below to " +
            "continue with your transfer", clickable = true), field(2))))
        // Masked fields and secure windows count everywhere — a chat app's own two-step password, a web login.
        assertTrue(secure(snap("org.telegram.messenger", text(1, "Two-Step Verification"), field(2, password = true))))
        assertTrue(secure(snap("com.android.chrome", field(1, label = "accounts.example.com", viewId = "com.android.chrome:id/url_bar"),
            field(2, password = true))))
        assertTrue(secure(snap("com.whatsapp", field(1, hint = "Message"), secure = true)))
        // A field's own hint counts in a browser or a chat app too (a web OTP box, an in-app PIN box).
        assertTrue(secure(snap("com.android.chrome", text(1, "Verify it's you"), field(2, hint = "Enter OTP"))))
        assertTrue(secure(snap("com.whatsapp", text(1, "Two-step verification"), field(2, hint = "Enter your PIN"))))
    }

    @Test fun the_payment_screen_rule_and_the_hand_off_reason_are_unchanged() {
        val pay = snap("com.android.phone", text(1, "Transfer to AMA MENSAH for GHS 0.1. Total Amount is GHS 0.10. Reply to continue"),
            field(2), btn(3, "Cancel"), btn(4, "Send"))
        assertTrue(secure(pay))
        assertEquals("momo_pin", TargetSemantics.handoffReason(pay, dialerLike = true))
        assertFalse(secure(snap("com.android.phone", text(1, "Enter amount"), field(2))))
        assertFalse(secure(snap("com.android.phone", text(1, "1) Transfer Money\n2) MoMoPay & Pay Bill\n6) Wallet"), field(2),
            btn(3, "Cancel"), btn(4, "Send"))))
    }

    @Test fun the_content_app_lists_come_from_config() {
        assertTrue(TargetSemantics.isChatPackage("org.telegram.messenger"))
        assertTrue(TargetSemantics.isChatPackage("org.telegram.messenger.web"))      // a flavour of a listed app
        assertFalse(TargetSemantics.isChatPackage("org.telegram.messengerx"))
        assertTrue(TargetSemantics.isContentPackage("com.android.chrome"))
        assertFalse(TargetSemantics.isContentPackage("com.android.phone"))
        assertFalse(TargetSemantics.isContentPackage(null))
        val prompt = arrayOf(text(1, "Enter your PIN"), field(2))
        assertTrue(secure(snap("com.example.newchat", *prompt)))
        AppConfig.loadFrom(listOf("policy.json" to """{"policy":{"secureInput":{"chatPackages":["com.example.newchat"]}}}"""))
        assertFalse(secure(snap("com.example.newchat", *prompt)))
        assertFalse("the configured list replaces the built-in one", TargetSemantics.isChatPackage("org.telegram.messenger"))
    }

    @Test fun a_long_message_that_mentions_a_captcha_is_not_a_human_check() {
        assertTrue(TargetSemantics.isHumanCheckScreen(snap("com.android.chrome", text(1, "I'm not a robot"))))
        assertTrue(TargetSemantics.isHumanCheckScreen(snap("com.android.chrome", text(1, "Verify that you are a human by ticking the box"))))
        assertFalse(TargetSemantics.isHumanCheckScreen(snap("com.whatsapp", text(1, "Ama: the site kept showing me a captcha every time I " +
            "tried to log in yesterday evening, so I gave up and went to the branch in person this morning instead."))))
    }

    // ------------------------------------------------------------------ fix 5: taps that ask first

    private val shade = snap("com.android.systemui", text(1, "Telegram"), text(2, "YouTube"), btn(3, "Notification settings"), btn(4, "Clear"),
        appName = "System UI")
    private val recents = snap("com.sec.android.app.launcher", text(1, "Calculator", clickable = true), btn(2, "Close all"), appName = "One UI Home")
    private val telegramBot = snap("org.telegram.messenger", text(1, "Richmond"), text(2, "bot"), text(3, "What can this bot do?"),
        btn(4, "Start Bot"), title = "Richmond", appName = "Telegram")
    private val myMtn = snap("com.mtngh.mymtn", text(1, "MoMo Balance"), btn(2, "Show MoMo balance"), text(3, "GHS ****"), btn(4, "Buy"),
        appName = "myMTN Beta")
    private val quickSettings = snap("com.android.systemui", btn(1, "Wi-Fi,PRINCE's HP,On.,Button"), btn(2, "Mobile,data,On.,Button"),
        btn(3, "Flight,mode,Off.,Button"), btn(4, "Torch,Off.,Button"), btn(5, "Bluetooth,Off.,Button"), appName = "System UI")

    @Test fun clearing_everything_and_closing_everything_ask_first() {
        // S23: the model asked for "Clear all"; the shade's button reads "Clear".
        val c = TargetSemantics.classify("tap", btn(4, "Clear"), "Clear all", false, shade)
        assertEquals(RiskTier.TIER2, c.tier); assertNull(c.block)
        assertEquals(TapEffect.WIPE, TargetSemantics.tapEffect("Clear", btn(4, "Clear"), shade))
        for (l in listOf("Clear all", "CLEAR ALL", "Clear all notifications", "Clear history", "Clear chat", "Dismiss all",
                "Delete all", "Remove all", "Empty trash", "Clear all."))
            assertEquals(l, RiskTier.TIER2, tier(l, snap("com.example.app")))
        // A model-requested label with no resolved element, and any primitive.
        assertEquals(RiskTier.TIER2, TargetSemantics.classify("tap", null, "Clear all", false, shade).tier)
        assertEquals(RiskTier.TIER2, tier("Clear", shade, primitive = "long_press"))
        // S20: Recents' "Close all".
        assertEquals(RiskTier.TIER2, tier("Close all", recents))
        assertEquals(RiskTier.TIER2, tier("Close all apps"))
        assertEquals(RiskTier.TIER2, tier("Close all tabs", snap("com.android.chrome")))
        assertEquals("Clear all your notifications? They can't be brought back.",
            ActionSemantics.confirmation("tap", btn(4, "Clear"), "Clear all", shade))
        assertEquals("Close all your open apps? Anything unsaved in them may be lost.",
            ActionSemantics.confirmation("tap", btn(2, "Close all"), null, recents))
        val history = ActionSemantics.confirmation("tap", btn(1, "Clear history"), null, snap("com.android.chrome", appName = "Chrome"))!!
        assertTrue(history, history.startsWith("Tap “Clear history” in Chrome?") && history.contains("can't be undone"))
    }

    @Test fun clearing_a_field_closing_a_dialog_and_ordinary_taps_stay_ungated() {
        for (l in listOf("Clear search field", "Clear input", "Clear text", "Clear query", "Close", "Close tab", "Close app",
                "Start typing", "Start", "Join", "Follow", "Following", "Show more", "Balance", "Balances", "Wi-Fi", "Mobile data",
                "Wi-Fi settings", "Notifications", "Notification settings", "Clear cache now please"))
            assertNull(l, tier(l, snap("com.example.app")))
        // "Clear" beside a field being edited empties the field; on a keypad it is the C key.
        val searching = snap("com.example.app", field(1, label = "kakum", hint = "Search", focused = true), btn(2, "Clear"))
        assertNull(tier("Clear", searching))
        val calculator = snap("com.sec.android.app.popupcalculator", *(0..9).map { btn(it + 10, it.toString()) }.toTypedArray(), btn(30, "Clear"))
        assertNull(tier("Clear", calculator))
        assertNull(ActionSemantics.confirmation("tap", btn(2, "Clear"), null, searching))
        // The torch and Bluetooth tiles are not connections the user can be cut off by.
        assertNull(tier("Torch,Off.,Button", quickSettings))
        assertNull(tier("Bluetooth,Off.,Button", quickSettings))
    }

    @Test fun starting_a_bot_joining_and_following_ask_first_in_chat_apps() {
        assertEquals(RiskTier.TIER2, tier("Start Bot", telegramBot))                       // C10
        assertEquals(RiskTier.TIER2, tier("START BOT", null))                              // unambiguous anywhere
        assertEquals(RiskTier.TIER2, tier("Start", telegramBot))
        assertEquals(RiskTier.TIER2, tier("Restart bot", telegramBot))
        for (l in listOf("Join", "Join group", "Join channel", "Follow"))
            assertEquals(l, RiskTier.TIER2, tier(l, snap("com.whatsapp")))
        assertEquals(RiskTier.TIER2, tier("Join group", snap("com.example.app")))
        assertEquals("subscribing stays a spend", RiskTier.TIER3, tier("Subscribe", telegramBot))
        // Outside a chat app a bare Start / Join / Follow is an ordinary button (a timer, a meeting, a wizard).
        for (l in listOf("Start", "Join", "Follow", "Restart"))
            assertNull(l, tier(l, snap("com.sec.android.app.clockpackage")))
        val rb = ActionSemantics.confirmation("tap", btn(4, "Start Bot"), null, telegramBot)!!
        assertTrue(rb, rb.startsWith("Tap “Start Bot” in Telegram?") && rb.contains("bot") && rb.contains("not a message to a person you know"))
    }

    @Test fun showing_a_balance_asks_first() {
        for (l in listOf("Show MoMo balance", "Show balance", "View balance", "Check balance", "Show my account balance", "Reveal balance",
                "Show Mobile Money balance"))
            assertEquals(l, RiskTier.TIER2, tier(l, myMtn))                                // F04
        assertEquals(RiskTier.TIER2, TargetSemantics.classify("tap", null, "Show MoMo balance", false, null).tier)
        assertEquals("Tap “Show MoMo balance” in myMTN Beta? This shows your balance on the screen.",
            ActionSemantics.confirmation("tap", btn(2, "Show MoMo balance"), null, myMtn))
    }

    @Test fun connection_tiles_and_switches_ask_first_when_tapped() {
        for (l in listOf("Wi-Fi,PRINCE's HP,On.,Button", "Wi-Fi", "WiFi", "Mobile,data,On.,Button", "Flight,mode,Off.,Button",
                "Airplane mode", "Mobile Hotspot,Off.,Button"))
            assertEquals(l, RiskTier.TIER2, tier(l, quickSettings))                        // F04
        // A long press opens the tile's settings page; only the tap switches it.
        assertNull(tier("Wi-Fi,PRINCE's HP,On.,Button", quickSettings, primitive = "long_press"))
        // In Settings a real switch counts; the "Wi-Fi" row that opens the Wi-Fi page does not.
        val settings = snap("com.android.settings", text(1, "Connections"))
        assertEquals(RiskTier.TIER2, tier("Wi-Fi", settings, target = btn(1, "Wi-Fi", checkable = true)))
        assertEquals(RiskTier.TIER2, tier("Mobile data", settings, target = btn(1, "Mobile data", checkable = true)))
        assertNull(tier("Wi-Fi", settings))
        assertNull("unchanged: no screen, not a switch", TargetSemantics.classify("tap", btn(1, "Wi-Fi"), null, false).tier)
        assertEquals("Switch Wi-Fi on or off? You may lose the connection you are using.",
            ActionSemantics.confirmation("tap", btn(1, "Wi-Fi,PRINCE's HP,On.,Button"), null, quickSettings))
        assertEquals("Switch mobile data on or off? You may lose the connection you are using.",
            ActionSemantics.confirmation("tap", btn(2, "Mobile,data,On.,Button"), null, quickSettings))
    }

    @Test fun the_new_reasons_do_not_read_as_a_message_or_a_call() {
        // operator/Capabilities maps a reason containing "send" to Messages and "dial" / "place a call" to Calls.
        val reasons = listOf(
            TargetSemantics.classify("tap", btn(4, "Clear"), null, false, shade),
            TargetSemantics.classify("tap", btn(2, "Close all"), null, false, recents),
            TargetSemantics.classify("tap", btn(4, "Start Bot"), null, false, telegramBot),
            TargetSemantics.classify("tap", btn(2, "Show MoMo balance"), null, false, myMtn),
            TargetSemantics.classify("tap", btn(1, "Wi-Fi,On.,Button"), null, false, quickSettings)).map { it.reason.lowercase() }
        assertEquals(5, reasons.toSet().size)
        for (r in reasons) assertFalse(r, "send" in r || "dial" in r || "place a call" in r)
    }

    // ------------------------------------------------------------------ fix 5: read-backs a listener can judge

    @Test fun a_verb_button_is_named_once_with_where_and_what() {
        // S32: 'Delete “Delete”?' on the Clock's selection screen.
        val clock = snap("com.sec.android.app.clockpackage", text(1, "1 selected"), text(2, "06:00"), btn(3, "Turn on"), btn(4, "Delete"),
            appName = "Clock")
        assertEquals("Tap “Delete” in Clock? 1 selected. This may not be undone.",
            ActionSemantics.confirmation("tap", btn(4, "Delete"), null, clock))
        // CAM03: 'Take picture “Take picture”?'
        val camera = snap("com.sec.android.app.camera", btn(1, "Take picture"), appName = "Camera")
        assertEquals("Tap “Take picture” in Camera? This takes a photo or starts recording.",
            ActionSemantics.confirmation("tap", btn(1, "Take picture"), null, camera))
        // L06: 'Buy “Buy”?'
        assertEquals("Tap “Buy” in myMTN Beta? This may charge you money.", ActionSemantics.confirmation("tap", btn(4, "Buy"), null, myMtn))
        // A longer caption that leads with the verb is not doubled either.
        val chat = snap("com.example.app", appName = "Notes")
        assertEquals("Tap “Delete conversation” in Notes? This may not be undone.",
            ActionSemantics.confirmation("tap", btn(1, "Delete conversation"), null, chat))
        // A caption that does not lead with its verb keeps the old form; sends and calls keep theirs.
        assertEquals("Delete “Yes, delete”?", ActionSemantics.confirmation("tap", btn(1, "Yes, delete"), null, chat))
        assertEquals("Call Ama?", ActionSemantics.confirmation("tap", btn(1, "Call Ama"), null, snap("com.example.app")))
        assertEquals(RiskTier.TIER2, tier("Delete", clock))
    }
}
