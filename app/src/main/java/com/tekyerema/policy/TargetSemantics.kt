package com.tekyerema.policy

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot

/**
 * Classifies the RESOLVED TARGET of a primitive — not the primitive's name (plan §13.2a).
 *
 * Whatever primitive the model uses (tap, long_press, swipe, drag, type+submit…), once the target
 * resolves to an element or a requested label, its semantics decide the risk. This closes the
 * class of bug where an enumerated list of "risky tool names" misses a new way to reach the same
 * control (the real round-6 set_text bypass). Keyword classes are generic UI vocabulary — no
 * app, package or task names.
 */
object TargetSemantics {

    /** Controls whose activation leaves the device, spends, destroys, or is hard to undo. (A plain tap on "Install" is
     *  confirmed by the consent classes, whose read-back says what installing means.) */
    val consequential = Regex(
        "\\b(send|pay|transfer|delete|remove|confirm|call|buy|purchase|checkout|submit|post|share|forward|" +
            "archive|block|report|unsubscribe|sign out|log out|logout|reset|format|erase|uninstall|accept|agree|allow|" +
            "shutter|capture|take (?:a )?(?:photo|picture|selfie)|record)\\b",
        RegexOption.IGNORE_CASE
    )

    private val PHOTO_SHUTTER = Regex("(?i)^(?:camera |photo )?(?:shutter(?: button)?|capture|take (?:a )?(?:photo|picture|selfie|pic))$")
    private val ASKED_FOR_PHOTO = Regex("(?i)\\b(?:take|snap|capture|shoot)\\b.{0,24}\\b(?:selfie|photo|picture|pic)s?\\b|\\bselfie\\b")

    /** The photo shutter pressed for a request that itself says to take a photo or selfie: the user's own words are the
     *  approval, so it isn't asked again (Infinix, 26 Oct 2026: "take a selfie and send it to Prince on telegram" asked
     *  "Tap Shutter?" — the one question belongs at the Send). Never video ("record"), never a goal that doesn't ask for a picture. */
    fun photoShutterAskedFor(label: String?, goal: String?): Boolean =
        label != null && goal != null && PHOTO_SHUTTER.matches(label.trim()) && ASKED_FOR_PHOTO.containsMatchIn(goal)

    /**
     * J-02: a button that COMMITS what was typed into a form — a new contact, an event, an edited profile. Found by the
     * device suite (A20e, 2026-09-28): asked to "save a new contact", the model went through the Contacts app's own
     * screens (Create contact → type the name → Save) instead of the confirmed create_contact tool, and "Save" was not a
     * consequential word — the same write would have gone through unconfirmed. Exact captions only, and only when the
     * screen holds a filled field ([filledFields]), so an ordinary "Save" in Settings doesn't ask.
     */
    val commitCaption = Regex("^(save|save contact|save changes|create|create contact|update)$", RegexOption.IGNORE_CASE)

    /** Editable fields holding typed content (not a hint, not a search box). A password field's value is never read. */
    fun filledFields(screen: UiSnapshot?): List<UiElement> = screen?.elements.orEmpty().filter { e ->
        e.editable && !e.password && !isQueryBox(e) && !e.label.isNullOrBlank() && !e.label.equals(e.hint, ignoreCase = true)
    }

    private val dialerPackage = Regex("(?i)dialer|\\.phone|contacts|incall|telecom|\\.call")
    private val dialControl = Regex("(?i)^(call|dial|voice call|video call|call button|make a call)$")

    /** A keypad that can place a call: a dialer/phone/contacts app, or a Call/Dial control on the same screen. */
    fun looksLikeDialer(screen: UiSnapshot?): Boolean {
        screen ?: return true   // unknown screen: stay careful
        if (screen.appPackage?.let { dialerPackage.containsMatchIn(it) } == true) return true
        return screen.elements.any { it.clickable && it.label?.trim()?.let { l -> dialControl.matches(l) } == true }
    }

    /** Fields whose IME action can BE a send (message compose boxes and the like). */
    val communicationField = Regex("\\b(message|reply|chat|comment|caption|post|tweet|status)\\b", RegexOption.IGNORE_CASE)

    /** Credential inputs the operator must never type into, whatever the model says. */
    // "enter mm": MTN's Mobile Money PIN prompt reads "… Total Amount is GHS 0.10. Enter MM # for next" — no word "PIN"
    // (A20e 2026-09-29, a live send), so Secure Input never engaged on it.
    val credentialField = Regex(
        "\\b(pin|otp|password|passcode|passphrase|cvv|verification code|one[- ]time|security code|enter mm)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Fields where a submit is a harmless query, not a send — the only case where type+submit is waved
     *  through without a confirmation (§1.14). Everything else that submits is treated as a possible send. */
    val queryField = Regex(
        "\\b(search|find|query|filter|url|address|web|browser|www|go to|look ?up|zoom)\\b",
        RegexOption.IGNORE_CASE
    )

    /** A label that is essentially a phone number — tapping it in a dialer/recents can place a call (§1.16). */
    private val phoneNumberLabel = Regex("^[+(]?[0-9][0-9\\s().\\-]{5,}$")
    private val singleDigit = Regex("[0-9]")

    data class Classification(
        /** A tier to escalate to (only ever raises the tool's static tier), or null for no change. */
        val tier: RiskTier?,
        /** Non-null = hard block, with the reason. */
        val block: String?,
        val reason: String
    ) {
        companion object { val NONE = Classification(null, null, "no risky target semantics") }
    }

    /**
     * True when the CURRENT SCREEN is a credential-entry surface (MoMo PIN, password, OTP, passcode) —
     * the trigger for Secure Input Mode, where Tɛkyerɛma must go blind and NEVER hand the screen to the
     * LLM / OCR / logs. Reuses the same [credentialField] vocabulary as the never-type guard. A FLAG_SECURE window or
     * a masked field always counts. Beyond that it is the FIELD or its PROMPT that must name the credential — never
     * the content of the screen: a Telegram chat whose old message said "…PIN stage… enter their PIN" was taken for a
     * MoMo PIN prompt and the agent went blind in it (A20e audit 2026-10-01, E2E2 / E2E3).
     *  - the field's own hint or resource id names one ("Enter PIN", `otp_input`) — in any app;
     *  - the network's dialog (USSD / dialer / SIM toolkit): any wording that names one, as before;
     *  - a lone input with a Send/OK + Cancel pair outside chat/web apps: likewise;
     *  - any other app: the window title, a short static label ("Enter your PIN"), or a sentence that ASKS for one;
     *  - a chat, mail or web app ([chatPackages], [browserPackages]), a screen with a message composer, or one whose
     *    only inputs are search boxes: text alone never counts — those words are someone else's.
     */
    /**
     * @param confirmationAsked Tɛkyerɛma has just asked the user, by voice, whether to press a USSD payment confirmation's
     * own confirm key ("Reply 1 to confirm" — money/ussd/MenuBrain, 2 October 2026): that screen asks for no secret, and
     * going blind on it swallowed the user's answer. Every screen that asks for a PIN or password still counts.
     */
    fun isCredentialScreen(screen: UiSnapshot, confirmationAsked: Boolean = false): Boolean {
        if (screen.secureWindow) return true                       // FLAG_SECURE (system PIN/lock/keyguard-like)
        if (screen.elements.any { it.password }) return true       // a masked password/PIN field
        val inputs = screen.elements.filter { it.editable }
        if (inputs.isEmpty()) return false
        val pkg = screen.appPackage.orEmpty()
        val chat = isChatPackage(pkg)
        if (inputs.any { fieldNamesCredential(it, chat) }) return true
        if (promptNamesCredential(screen, inputs, pkg)) return true
        return !confirmationAsked && isUssdMoneyConfirmation(screen)
    }

    /** Apps whose screens are mostly OTHER PEOPLE'S words. `policy.secureInput.chatPackages` replaces the list. */
    private val DEFAULT_CHAT_PACKAGES = listOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram", "com.facebook.orca",
        "com.facebook.mlite", "com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.instagram.lite",
        "com.twitter.android", "com.snapchat.android", "org.thoughtcrime.securesms", "com.viber.voip", "com.discord",
        "com.Slack", "com.linkedin.android", "com.zhiliaoapp.musically", "com.google.android.youtube",
        "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms", "com.android.messaging",
        "com.google.android.gm", "com.samsung.android.email.provider", "com.microsoft.office.outlook"
    )
    /** Browsers and search apps: the page is the web's words. `policy.secureInput.browserPackages` replaces the list. */
    private val DEFAULT_BROWSER_PACKAGES = listOf(
        "com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.opera.browser",
        "com.opera.mini.native", "com.microsoft.emmx", "com.brave.browser", "com.duckduckgo.mobile.android",
        "com.transsion.phoenix", "com.google.android.googlequicksearchbox"
    )
    fun chatPackages(): List<String> =
        com.tekyerema.core.config.AppConfig.stringList("policy.secureInput.chatPackages", DEFAULT_CHAT_PACKAGES)
    fun browserPackages(): List<String> =
        com.tekyerema.core.config.AppConfig.stringList("policy.secureInput.browserPackages", DEFAULT_BROWSER_PACKAGES)

    private fun inPackages(pkg: String?, list: List<String>): Boolean =
        !pkg.isNullOrBlank() && list.any { p -> p.isNotBlank() && (pkg.equals(p, ignoreCase = true) || pkg.startsWith("$p.", ignoreCase = true)) }
    fun isChatPackage(pkg: String?): Boolean = inPackages(pkg, chatPackages())
    /** A chat, mail, social, browser or search app — where on-screen text is content, not a prompt. */
    fun isContentPackage(pkg: String?): Boolean = isChatPackage(pkg) || inPackages(pkg, browserPackages())

    /** A resource id as words: `com.bank:id/otpInput` → "otp input", `pin_entry` → "pin entry" (never "spinner" → pin). */
    private fun idWords(viewId: String?): String = viewId.orEmpty().substringAfter(":id/")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace(Regex("[^A-Za-z0-9]+"), " ").trim().lowercase()

    /** The box a message is written in, by its hint or resource id ("Message", "Reply", `comment_input`). */
    fun isComposer(e: UiElement): Boolean {
        if (!e.editable || isQueryBox(e)) return false
        return (e.hint != null && communicationField.containsMatchIn(e.hint)) || communicationField.containsMatchIn(idWords(e.viewId))
    }

    /**
     * The input ITSELF says it takes a credential: its placeholder hint or its resource id. Its label counts only as its
     * name — when it has no hint, is short, and this is not a chat app or a composer (there, a label is what was typed:
     * "my pin?" in a message box is a message).
     */
    private fun fieldNamesCredential(e: UiElement, chatApp: Boolean): Boolean {
        if (isQueryBox(e)) return false                                    // "Search passwords" is a search
        val hint = e.hint?.trim().orEmpty()
        if (hint.isNotEmpty() && credentialField.containsMatchIn(hint) && !communicationField.containsMatchIn(hint)) return true
        if (credentialField.containsMatchIn(idWords(e.viewId))) return true
        val label = e.label?.trim().orEmpty()
        if (label.isEmpty() || hint.isNotEmpty() || chatApp || isComposer(e)) return false
        return label.length <= 30 && label.split(Regex("\\s+")).size <= 4 && credentialField.containsMatchIn(label)
    }

    /** A prompt is short; a message, an article or a notification is not. */
    private const val PROMPT_MAX = 60
    /** A sentence that ASKS for a credential ("Enter the OTP we sent to 055…", "Type your PIN to continue"). */
    private val credentialAsk = Regex(
        "\\b(?:enter|type|input|provide|key in|put in|insert|confirm|re-?enter)\\b[^\\n.?!]{0,50}" +
            "\\b(?:pin|otp|password|passcode|passphrase|cvv|verification code|one[- ]time|security code)\\b|\\benter mm\\b",
        RegexOption.IGNORE_CASE
    )

    /** The words AROUND the input name a credential — judged by where the screen is (see [isCredentialScreen]). */
    private fun promptNamesCredential(screen: UiSnapshot, inputs: List<UiElement>, pkg: String): Boolean {
        fun names(s: String?) = s != null && credentialField.containsMatchIn(s)
        val others = screen.elements.filter { !it.editable }
        fun anyText() = names(screen.screenTitle) || others.any { names(it.label) }
        // The network's own dialog: whatever it says (USSD prompts are one long text; fail closed).
        if (ussdPackage.containsMatchIn(pkg)) return anyText()
        // Someone else's words (a chat, mail, a web page), a message being written, or nothing to type a credential into.
        if (isContentPackage(pkg) || inputs.any { isComposer(it) } || inputs.all { isQueryBox(it) }) return false
        // A lone input with Send/OK + Cancel is a prompt dialog whatever app shows it.
        val buttons = others.filter { it.clickable }.mapNotNull { it.label?.trim()?.lowercase() }
        if (inputs.size == 1 && buttons.any { it in USSD_SUBMIT } && buttons.any { it == "cancel" }) return anyText()
        if (screen.screenTitle?.trim()?.let { it.length <= PROMPT_MAX && names(it) } == true) return true
        // A prompt: a short static label ("Enter your PIN"), a caption-sized one even if it is tappable ("MoMo PIN"), or a
        // sentence that asks for the credential. A tappable row of text ("Kofi: your OTP is…") is content.
        return others.any { e ->
            val l = e.label?.trim() ?: return@any false
            names(l) && ((l.length <= PROMPT_MAX && (!e.clickable || l.split(Regex("\\s+")).size <= 4)) ||
                (l.length <= 200 && credentialAsk.containsMatchIn(l)))
        }
    }

    /**
     * Fail closed (money safety, 2026-09-29): a USSD / phone dialog with an input box that shows a money figure AND the
     * wording of a final payment screen — a total, a fee, a tax or levy, "Enter MM", a PIN, a confirmation — is a
     * credential screen WHATEVER its prompt says. MTN's PIN prompt said "Enter MM # for next", no "PIN" (A20e, live send);
     * the next wording change must not slip past either. A menu that merely mentions money ("1) Transfer Money
     * 2) MoMoPay …", "Enter amount") has no figure + final-screen cue and stays operable. Dialog found by structure:
     * the phone / dialer / SIM-toolkit process, or a lone input box with a Send/OK + Cancel pair.
     */
    fun isUssdMoneyConfirmation(screen: UiSnapshot): Boolean {
        val inputs = screen.elements.count { it.editable }
        if (inputs == 0) return false
        val pkg = screen.appPackage.orEmpty()
        val buttons = screen.elements.filter { it.clickable && !it.editable }.mapNotNull { it.label?.trim()?.lowercase() }
        val dialogShape = inputs == 1 && buttons.any { it in USSD_SUBMIT } && buttons.any { it == "cancel" }
        if (!ussdPackage.containsMatchIn(pkg) && !dialogShape) return false
        val text = (screen.elements.filter { !it.editable }.mapNotNull { it.label } + listOfNotNull(screen.screenTitle)).joinToString("\n")
        return isMoneyConfirmationText(text)
    }

    /** The words of a final payment screen: a money figure plus a total / fee / tax / "Enter MM" / PIN / confirm cue. */
    fun isMoneyConfirmationText(text: String): Boolean = moneyFigure.containsMatchIn(text) && finalMoneyCue.containsMatchIn(text)

    private val ussdPackage = Regex("(?i)^com\\.android\\.phone$|dialer|\\.phone$|telephony|\\.stk\\b|simtoolkit|incallui")
    private val USSD_SUBMIT = setOf("send", "ok", "reply", "submit", "answer")
    /** "GHS 0.10", "GH₵5", "0.10 GHS", "5 cedis". */
    private val moneyFigure = Regex("(?i)(?:GH\\s?₵|GH\\s?¢|GHS|GHC|₵|¢)\\s*\\d|\\d(?:[\\d,]*\\.?\\d*)?\\s*(?:GHS|GHC|cedis?)\\b")
    /** A final payment screen's words — never "transfer" or "send money" alone (those are menu items too). */
    private val finalMoneyCue = Regex("(?i)\\btotal\\b|\\bfees?\\b|\\btax\\b|\\blevy\\b|\\bcharges?\\b|\\benter mm\\b|\\bmm pin\\b|" +
        "\\bpin\\b|\\bconfirm\\b|\\bauthori[sz]e\\b")

    /** G-13: a "prove you're human" check. The agent never solves these; the user takes over, like a PIN screen. */
    private val humanCheck = Regex(
        "\\b(captcha|recaptcha|hcaptcha|i'?m not a robot|verify (?:that )?you(?:'re| are) (?:a )?human|are you a robot|" +
            "select all (?:images|squares)|security check)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isHumanCheckScreen(screen: UiSnapshot): Boolean {
        // A check's own wording is short ("I'm not a robot"); a long message or article that mentions a captcha is content.
        val texts = screen.elements.mapNotNull { it.label } + listOfNotNull(screen.screenTitle)
        return texts.any { it.length <= HUMAN_CHECK_MAX && humanCheck.containsMatchIn(it) }
    }
    private const val HUMAN_CHECK_MAX = 120

    /**
     * G-13: what the user is being asked to do on a hand-off screen, from structure and wording only — so the spoken
     * instruction names it ("your MoMo PIN", "the one-time code", "the robot check") instead of always "MoMo PIN".
     */
    fun handoffReason(screen: UiSnapshot, dialerLike: Boolean): String {
        val texts = (screen.elements.mapNotNull { it.label } + listOfNotNull(screen.screenTitle)).joinToString(" ").lowercase()
        return when {
            humanCheck.containsMatchIn(texts) -> "captcha"
            Regex("\\b(otp|one[- ]time|verification code|security code)\\b").containsMatchIn(texts) -> "otp"
            Regex("\\b(momo|mobile money)\\b").containsMatchIn(texts) || (dialerLike && Regex("\\bpin\\b").containsMatchIn(texts)) -> "momo_pin"
            // MTN's own prompt ("… Enter MM # for next") or any USSD payment screen: it is the Mobile Money PIN, not "your password".
            Regex("\\benter mm\\b|\\bmm pin\\b").containsMatchIn(texts) || isUssdMoneyConfirmation(screen) -> "momo_pin"
            Regex("\\b(pin|passcode)\\b").containsMatchIn(texts) -> "pin"
            else -> "password"
        }
    }

    /** A search / address box, recognised by its resource id or widget class — NOT its label: once text is in it,
     *  the label IS that text (Chrome's omnibox showed "kakum national park" and a new search was gated as a
     *  possible SEND). A box that ever receives a message has a comms id (compose, message, reply…), not these. */
    fun isQueryBox(e: UiElement?): Boolean {
        if (e == null || !e.editable) return false
        val id = (e.viewId ?: "").substringAfter(":id/").lowercase()
        return Regex("search|url|omnibox|query|location_bar").containsMatchIn(id) ||
            Regex("(?i)SearchView|SearchAutoComplete").containsMatchIn(e.role) ||
            // a web page's search input has no resource id; its placeholder says what it is ("Search", "Search Google…")
            (e.hint != null && queryField.containsMatchIn(e.hint) && !communicationField.containsMatchIn(e.hint))
    }

    fun classify(
        primitive: String,
        target: UiElement?,
        requestedLabel: String?,
        submit: Boolean,
        screen: UiSnapshot? = null
    ): Classification {
        val label = target?.label ?: requestedLabel
        val lower = label?.lowercase()

        if (primitive == "type") {
            if (target?.password == true) return Classification(null, "credential field: typing into a password field is never permitted", "isPassword")
            if (lower != null && credentialField.containsMatchIn(lower))
                return Classification(null, "credential field ('$label'): PINs, OTPs and passwords are never entered by the operator", "credential label")
            // §1.14 — a submit fires the field's IME action, which in a compose/chat box SENDS. The field
            // label is often blank or an icon, so we can't rely on it saying "message". Default: any submit
            // needs confirmation; only wave it through when the field is CLEARLY a query/search box.
            if (submit) {
                val benign = (lower != null && queryField.containsMatchIn(lower)) || isQueryBox(target)
                if (!benign) return Classification(
                    RiskTier.TIER2, null,
                    "type+submit fires the field's action (can send) on '${label ?: "an unlabelled field"}'"
                )
            }
            return Classification.NONE
        }

        // ---- tap/long_press/drag/swipe on a resolved element ----
        val secure = screen?.secureWindow == true
        val digitKeys = screen?.elements?.count { it.clickable && it.label?.trim()?.matches(singleDigit) == true } ?: 0
        val onNumericKeypad = digitKeys >= 8
        val targetIsDigit = lower?.trim()?.matches(singleDigit) == true

        // §1.17 — a numeric keypad on a FLAG_SECURE screen is a PIN/passcode/lock pad: never tap it.
        if (onNumericKeypad && targetIsDigit && secure)
            return Classification(null, "PIN/passcode pad: entering a device or app passcode is never permitted", "pin pad on secure screen")

        // §1.16 — tapping a digit key on a (non-secure) numeric keypad, or a phone-number row, can place a
        // call outright on many dialers; require confirmation.
        // Only a DIALER's keypad can dial: every Calculator digit asked for a yes (device suite, A20e, 2026-09-28 — the
        // runner declined, so "work out 12 × 13" failed). A dialer = a phone/dialer/contacts app, or a Call/Dial control on
        // the screen. The PIN-pad block above stays unconditional.
        if (onNumericKeypad && targetIsDigit && looksLikeDialer(screen))
            return Classification(RiskTier.TIER2, null, "dialer keypad key ('$label') — a tap here can dial")
        if (primitive == "tap" && lower != null && phoneNumberLabel.matches(label!!.trim()))
            return Classification(RiskTier.TIER2, null, "target '$label' is a phone number — tapping it can place a call")

        // §1.15 — an unlabelled icon tapped WHILE a message is being composed is very likely the Send
        // button. Scope it to a compose context so ordinary icon taps elsewhere don't all demand a yes.
        // A focused SEARCH/URL box is not a message composer (see [isQueryBox]). Seen on-device: tapping a
        // Settings-search result asked "Tap element 5?".
        val composing = screen?.elements?.any { f ->
            f.editable && (f.focused || communicationField.containsMatchIn(f.label ?: "")) && !isQueryBox(f)
        } == true
        if (primitive == "tap" && label.isNullOrBlank() && target?.clickable == true && composing)
            return Classification(RiskTier.TIER2, null, "an unlabelled control tapped while composing a message may send it")

        // J-02: committing a filled form (Save / Create / Update) is a write the user confirms, like create_contact.
        if (primitive == "tap" && lower != null && commitCaption.matches(lower.trim()) && filledFields(screen).isNotEmpty())
            return Classification(RiskTier.TIER2, null, "target '$label' saves what was typed into this form")

        // J-03: a control that SPENDS money is TIER3 — its own read-back class, above an ordinary send. A real button
        // whose caption STARTS with a spend verb counts even when long ("Pay GH₵ 50.00 to Kwame Mensah").
        // MTN's app (28 Oct 2026): "Continue" after the number, amount and reference went straight to the PIN with nothing read back.
        if (lower != null && mtnCommit(screen, label!!))
            return Classification(RiskTier.TIER3, null, "target '$label' commits a filled money form in MTN's app — the PIN comes next")
        if (lower != null && isSpendCaption(label!!, target) && !mtnAppMenu(screen, label))
            return Classification(RiskTier.TIER3, null, "target '$label' spends money")

        // Audit 2026-10-01: a tap that wipes a list, closes everything, starts a bot / joins a group, shows a balance or
        // switches a connection — none of them carries a consequential WORD, so each went through unasked.
        tapEffect(label, target, screen)?.takeIf { it != TapEffect.CONNECTIVITY || primitive == "tap" }?.let { fx ->
            return Classification(RiskTier.TIER2, null, "target '$label' ${effectReason(fx)}")
        }

        if (lower != null && consequential.containsMatchIn(lower) && looksLikeControlCaption(label!!) && !mtnAppMenu(screen, label)) {
            return Classification(RiskTier.TIER2, null, "target '$label' is a consequential control")
        }
        // Consent: signing in / up, SSO and OAuth ("Continue as Ama Mensah"), installing, granting a permission.
        if (lower != null) consentKind(label!!, target, anchoredOk = target == null || (target.clickable && !target.editable))?.let { k ->
            return Classification(RiskTier.TIER2, null, "target '$label' gives consent (${k.name.lowercase()})")
        }
        return Classification.NONE
    }

    /**
     * What a tap does that its caption has no consequential word for (A20e audit, 2026-10-01):
     *  WIPE — "Clear" / "Clear all" emptied the whole notification shade when the user said "close notifications" (S23);
     *  CLOSE_ALL — "Close all" in Recents closed every app when the user said "close this app" (S20);
     *  JOIN — "Start Bot" started a chat with a stranger's bot (C10); joining a group or following a channel is the same kind;
     *  REVEAL_BALANCE — "Show MoMo balance" was tapped with no question (F04);
     *  CONNECTIVITY — the Wi-Fi tile was tapped unasked (F04): the user can lose the connection they are using.
     * Exact captions only, so "Clear search field", "Close" and "Start typing" stay ordinary taps.
     */
    enum class TapEffect { WIPE, CLOSE_ALL, JOIN, REVEAL_BALANCE, CONNECTIVITY }

    private val wipeCaption = Regex("^(?:clear all(?: notifications| history)?|clear notifications|" +
        "clear (?:history|chat|chats|conversation|recents|call log)|dismiss all|delete all|remove all|" +
        "empty (?:trash|bin|recycle bin))$")
    private val closeAllCaption = Regex("^close all(?: apps| tabs| windows)?$")
    private val joinAnywhere = Regex("^(?:start bot|restart bot|join (?:group|channel|chat|community))$")
    private val joinInChatApp = Regex("^(?:start|restart|join|follow)$")
    private val revealBalanceCaption = Regex("^(?:show|view|reveal|unhide|see|check|display) (?:my |your |the )?(?:[a-z]+ ){0,2}balances?$")
    private const val CONNECTIONS = "(?:wi-?fi|mobile data|flight mode|airplane mode|aeroplane mode|mobile hotspot|hotspot)"
    private val connectivityName = Regex("^$CONNECTIONS\\b")
    /** The control's name alone, or a tile's "name[ network] on|off" — not a notification that mentions Wi-Fi. */
    private val connectivityCaption = Regex("^$CONNECTIONS$|^$CONNECTIONS\\b.{0,60} (?:on|off)$")
    private const val SYSTEM_UI = "com.android.systemui"

    /** A caption as plain words: a tile reads "Wi-Fi,On.,Button"; trailing punctuation and the word "button" are dropped. */
    internal fun captionWords(label: String): String =
        label.replace(Regex("[\\s,]+"), " ").trim().lowercase().removeSuffix(" button").trimEnd('.', '!', '?', '…').trim()

    /** The connection a [TapEffect.CONNECTIVITY] control switches ("wi-fi", "mobile data"), for the read-back. */
    internal fun connectionNamed(label: String): String? = connectivityName.find(captionWords(label))?.value

    fun tapEffect(label: String?, target: UiElement?, screen: UiSnapshot?): TapEffect? {
        val t = captionWords(label ?: return null)
        if (t.isEmpty() || t.length > 80) return null      // a caption (a tile may carry a long network name), not a sentence
        if (wipeCaption.matches(t)) return TapEffect.WIPE
        if (t == "clear") {
            // A bare "Clear" next to a field being edited empties that field, and on a keypad it is the C key: both harmless.
            val editing = screen?.elements?.any { it.editable && (it.focused || (!it.label.isNullOrBlank() && !it.label.equals(it.hint, true))) } == true
            val keypad = (screen?.elements?.count { it.clickable && it.label?.trim()?.matches(singleDigit) == true } ?: 0) >= 8
            return if (editing || keypad) null else TapEffect.WIPE
        }
        if (closeAllCaption.matches(t)) return TapEffect.CLOSE_ALL
        if (joinAnywhere.matches(t) || (joinInChatApp.matches(t) && isChatPackage(screen?.appPackage))) return TapEffect.JOIN
        if (revealBalanceCaption.matches(t)) return TapEffect.REVEAL_BALANCE
        // A Quick Settings tile, or a real switch in Settings (a "Wi-Fi" ROW only opens the Wi-Fi page).
        if (connectivityCaption.matches(t) && (screen?.appPackage == SYSTEM_UI || target?.checkable == true)) return TapEffect.CONNECTIVITY
        return null
    }

    /** (No "send" / "dial" / "call" here: [com.tekyerema.operator.Capabilities] reads those words in a reason.) */
    private fun effectReason(fx: TapEffect): String = when (fx) {
        TapEffect.WIPE -> "clears a whole list and can't be undone"
        TapEffect.CLOSE_ALL -> "closes every open app"
        TapEffect.JOIN -> "starts a bot or joins a group in a chat app"
        TapEffect.REVEAL_BALANCE -> "shows a balance on the screen"
        TapEffect.CONNECTIVITY -> "switches a connection on or off"
    }

    /**
     * What a consent control does, for the read-back the user hears (see [ActionSemantics]).
     *  ACCOUNT — signs in, signs up, links a device, or shares the user's account with an app (SSO / OAuth);
     *  INSTALL — installs or activates something (an app, a device-admin, a profile);
     *  PERMISSION — grants a permission, approves a request, pairs a device;
     *  REMOVE — wipes an app's data or turns a protection off;
     *  SECURITY — sets up or changes how the phone or an account is unlocked or approved (fingerprint, face, screen
     *    lock, purchase authentication, two-step sign-in). Added 2026-09-29: looking for the Play Store's search, the
     *    fallback model tapped a "Use biometrics — fingerprint or face" card unasked.
     */
    enum class Consent { ACCOUNT, INSTALL, PERMISSION, REMOVE, SECURITY }

    /**
     * Consent vocabulary (generic UI words, no app names). An agent must hand these back to the person: Anthropic's
     * computer-use guidance asks for a human on "any tasks requiring affirmative consent, such as … agreeing to terms",
     * OpenAI's agents confirm before state-changing actions and hand logins back, and Play's AccessibilityService policy
     * forbids granting access or changing settings without the user's knowledge. "accept", "agree", "allow" and
     * "uninstall" are already [consequential]; money words ("subscribe", "upgrade", "free trial") are TIER3 spend.
     */
    private val CONSENT: List<Pair<Consent, String>> = listOf(
        Consent.ACCOUNT to "sign[- ]?in|log[- ]?in|sign[- ]?up|register|create (?:an |a new |new |your |my )?account|" +
            "continue as|continue with|use (?:this|another|my) account|link (?:a |this |new |another )?device|link (?:your |my )?account",
        Consent.INSTALL to "install|activate",
        Consent.PERMISSION to "grant|authori[sz]e|approve|permit|while using (?:the )?app|only this time|pair",
        Consent.REMOVE to "deactivate|clear (?:data|storage|all data)",
        Consent.SECURITY to "use biometrics?|biometrics?|fingerprints?|face (?:unlock|recognition|id)|screen lock|" +
            "(?:purchase|require) authentication|two[- ]step|2[- ]step|passkeys?|security key"
    )
    private val consentAny = Regex(CONSENT.joinToString("|", "\\b(?:", ")\\b") { it.second }, RegexOption.IGNORE_CASE)
    private val consentLead = Regex(CONSENT.joinToString("|", "^\\s*(?:", ")\\b") { it.second }, RegexOption.IGNORE_CASE)

    /**
     * The consent class of a control caption, or null. A short caption (≤ 4 words, ≤ 30 chars) counts when it CONTAINS a
     * consent word ("Sign in with Google", "Install"); a longer one only when it LEADS with one and [anchoredOk] — a real
     * control, or a label the model asked to tap — so "Continue as Prince Nasamu Alhassan" is caught but a message row
     * that merely mentions installing is not.
     */
    fun consentKind(label: String, target: UiElement? = null, anchoredOk: Boolean = target != null && target.clickable && !target.editable): Consent? {
        val t = label.trim()
        if (t.isEmpty()) return null
        val hit = when {
            looksLikeControlCaption(t) -> consentAny.find(t)
            anchoredOk && t.length <= 60 -> consentLead.find(t)
            else -> null
        } ?: return null
        val word = hit.value.trim()
        return CONSENT.firstOrNull { (_, words) -> Regex("^(?:$words)$", RegexOption.IGNORE_CASE).matches(word) }?.first
    }

    /** True when [text] names a consent action at all (used for app-defined node actions, G-03). */
    fun isConsent(text: String): Boolean = consentAny.containsMatchIn(text)

    /** J-03: generic purchase/payment vocabulary (no app names); `policy.financialWords` can add more, never remove. */
    // "subscribe" stays: on Ghanaian networks a Subscribe button often starts an airtime-billed service. A free trial,
    // an upgrade or a renewal starts a billed subscription too (it converts unless cancelled), so it is read back as a spend.
    private const val SPEND_WORDS = "buy|buy now|purchase|checkout|check out|pay|pay now|place order|order now|book now|reserve now|" +
        "subscribe|top ?up|recharge|donate|withdraw|cash ?out|add money|send money|continue to payment|proceed to (?:pay|payment|checkout)|" +
        "confirm (?:payment|purchase|order|transfer|booking)|complete (?:purchase|order|payment)|" +
        "upgrade|renew|pre-?order|start (?:your |my |a )?(?:\\d+[- ]day )?free trial|free trial|try (?:it )?(?:for )?free|go premium|get premium"

    private fun spendRegex(anchored: Boolean): Regex {
        val extra = com.tekyerema.core.config.AppConfig.stringList("policy.financialWords", emptyList())
            .filter { it.isNotBlank() }.joinToString("") { "|" + Regex.escape(it.trim()) }
        val words = "(?:$SPEND_WORDS$extra)"
        return Regex(if (anchored) "^\\s*$words\\b" else "\\b$words\\b", RegexOption.IGNORE_CASE)
    }

    /**
     * MTN's own app at a MENU, not a payment step (finale demo, 28 Oct 2026): its home tiles say "Top Up: airtime", "Buy Bundle",
     * "Send Money" and only OPEN a form — asking "this may charge you money?" there made the user decline before anything was filled.
     * A screen with a filled field or an amount on it (the summary, the pay step) is not a menu, so its Pay / Confirm is still TIER3,
     * and the planner reads back name, number and amount before it (money/MtnApp.RULE); the PIN stays the user's.
     */
    internal fun mtnAppMenu(screen: UiSnapshot?, label: String): Boolean {
        val pkg = screen?.appPackage ?: return false
        if (!(pkg.startsWith("com.mtngh.") || pkg.startsWith("com.mtn."))) return false
        if (filledFields(screen).isNotEmpty()) return false
        return !MTN_FINAL.matches(label.trim().lowercase())
    }
    /** MTN's app, a form with something filled in, and its going-on button: the step before the PIN. */
    internal fun mtnCommit(screen: UiSnapshot?, label: String): Boolean {
        val pkg = screen?.appPackage ?: return false
        if (!(pkg.startsWith("com.mtngh.") || pkg.startsWith("com.mtn."))) return false
        if (filledFields(screen).isEmpty()) return false
        return Regex("^(?:continue|next|proceed|send|send now|confirm|pay|pay now|submit|done)$").matches(label.trim().lowercase())
    }

    /** A payment's last button: still TIER3 everywhere, MTN's app included. */
    private val MTN_FINAL = Regex("^(?:pay|pay now|buy now|buy|confirm|confirm .*|checkout|check out|complete .*|place order|proceed to pay.*|send|send now|submit|approve)$")

    internal fun isSpendCaption(label: String, target: UiElement?): Boolean {
        val t = label.trim()
        if (t.isEmpty()) return false
        if (looksLikeControlCaption(t) && spendRegex(anchored = false).containsMatchIn(t)) return true
        // Longer captions: only a real button whose text leads with the spend verb (not a message preview row).
        val isButton = target != null && target.clickable && !target.editable && Regex("(?i)button").containsMatchIn(target.role)
        return isButton && t.length <= 60 && spendRegex(anchored = true).containsMatchIn(t)
    }

    /**
     * A consequential keyword should only escalate a real control CAPTION — a button like "Send", "Pay now",
     * "Delete", "Confirm & Pay" — NOT a long text blob that merely CONTAINS the word. This is the fix for a
     * demo-breaking false positive: a conversation-list row like "Prince Melanin Can you send me 10 pesewas to
     * buy water?? 16:19" is TAPPED to OPEN and READ it, not a Send button — but it contains "send" AND "buy",
     * so it was wrongly gated with "Send this message?" and reading became impossible. Structure-only heuristic
     * (no app names): a button caption is short — at most 4 words AND 30 characters. Longer labels are
     * sentences / message previews / notifications, i.e. navigation, not a consequential action. Genuine
     * money moves go through the dedicated TIER3 tools (momo_send / mobile_money), not a generic labelled tap,
     * so this narrowing doesn't weaken the real send-money boundary.
     */
    private fun looksLikeControlCaption(label: String): Boolean {
        val t = label.trim()
        if (t.isEmpty() || t.length > 30) return false
        return t.split(Regex("\\s+")).size <= 4
    }
}
