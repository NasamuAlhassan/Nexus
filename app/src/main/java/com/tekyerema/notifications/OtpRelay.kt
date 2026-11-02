package com.tekyerema.notifications

import android.content.Context

/**
 * One-time code to the waiting field (MASTER_STATUS #37, 2 Nov 2026). Fetching an OTP with a screen reader means leaving
 * the form, finding the SMS, memorising six digits and coming back. Here: while Nexus's keyboard has a code-like box
 * focused in another app, a code that arrives is OFFERED — "A 6-digit code arrived from Google. Say 'enter the code' to
 * type it into Chrome." — and typed into that box only when the user says so.
 *
 * Privacy rules (agreed with the brain session for the money rules):
 *  - the code lives only in memory, for [TTL_MS], wiped once typed, expired or replaced; never stored, logged, shown in
 *    history or sent to a model (capture keeps hiding codes through core/Redact; this reads the raw text once, here);
 *  - on the loudspeaker only the digit COUNT is said; the digits only with headphones ([PrivateReading.privateOutputConnected]);
 *  - never a PIN, and never a code that approves money ([MONEY]): Mobile Money / bank approvals are the user's to type;
 *  - typed only into the box the keyboard reported as code-like, still focused, in the app named in the offer.
 * The detector and the lines are pure (OtpRelayTest in SpeechShieldNoisyTest's file); the keyboard registers the box
 * through [focus] / [blur] (ime/, session t3kyer3ma-11).
 */
object OtpRelay {
    const val TTL_MS = 5 * 60_000L

    private val CONTEXT = Regex("(?i)\\b(?:otp|one[- ]?time|verification|verify|security code|auth(?:entication)? code|login code|" +
        "sign[- ]?in code|confirmation code|your code|code is|code:|is your code)\\b|\\bcode\\b")
    /** A PIN or password GIVEN in the message ("your new PIN is 1234"); "never share your PIN" is only advice. */
    private val PIN = Regex("(?i)\\b(?:pin|password|passcode)\\b\\D{0,15}\\d{4,8}")
    /** Codes that approve money: never relayed (the user types them, as at the MoMo PIN). */
    private val MONEY = Regex("(?i)\\b(?:momo|mobile money|transaction|transfer|payment|withdraw(?:al)?|cash ?out|debit|approve|authori[sz]e)\\b|\\bgh(?:s|c|₵)\\b|₵")
    private val CODE = Regex("(?<![\\d.,])\\d{4,8}(?![\\d]|[.,]\\d)")
    private val SPLIT = Regex("(?<!\\d)(\\d{3})[- ](\\d{3})(?!\\d)")

    /** The one code in [text], or null: code words present, exactly one 4–8 digit code, no PIN, no money approval. Pure. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank() || !CONTEXT.containsMatchIn(text) || PIN.containsMatchIn(text) || MONEY.containsMatchIn(text)) return null
        val joined = SPLIT.replace(text) { it.groupValues[1] + it.groupValues[2] }
        val codes = CODE.findAll(joined).map { it.value }.distinct().toList()
        return codes.singleOrNull()
    }

    /** The spoken offer. Pure. */
    fun offer(digits: Int, from: String?, intoApp: String?, sayDigits: String?): String {
        val src = from?.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""
        val head = if (sayDigits != null) "A code arrived$src: ${sayDigits.toList().joinToString(" ")}." else "A $digits-digit code arrived$src."
        val target = intoApp?.takeIf { it.isNotBlank() }?.let { " into $it" } ?: ""
        return "$head Say \"enter the code\" to type it$target."
    }

    private val ENTER = Regex("^(?:please )?(?:enter|type|put in|fill in|paste|use)(?: in)? (?:the|that|my) (?:code|otp|verification code)(?: for me)?(?: please)?$|" +
        "^(?:enter|type) it(?: in)?(?: please)?$")

    /** "enter the code" / "type the code" — only while an offer is open ([pending]). Pure on the words. */
    fun isEnter(text: String): Boolean = ENTER.matches(text.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " "))

    // ------------------------------------------------------------------ state (memory only)

    private class Pending(val code: CharArray, val at: Long)
    private class Field(val pkg: String, val app: String?, val type: (CharSequence) -> Boolean)

    @Volatile private var pending: Pending? = null
    @Volatile private var field: Field? = null

    private val CODE_HINT = Regex("(?i)\\b(?:otp|one[- ]?time|verification|verify|security code|sms code|code)\\b|smsotp|otpcode")
    private val NOT_CODE = Regex("(?i)\\b(?:pin|password|passcode|cvv|cvc|card|account|phone|amount)\\b")

    /**
     * Is the focused box one a code goes into? A number box, or a text box whose hint or autofill hints say code / OTP /
     * verification — never a PIN, password, card or amount box. Pure; the keyboard asks this in onStartInputView.
     */
    fun isCodeBox(inputType: Int, hint: CharSequence?, autofillHints: Array<String>? = null): Boolean {
        val h = listOfNotNull(hint?.toString(), autofillHints?.joinToString(" ")).joinToString(" ")
        if (NOT_CODE.containsMatchIn(h)) return false
        val variation = inputType and 0x00000ff0        // InputType.TYPE_MASK_VARIATION
        // Password boxes (text 0x80/0x90/0xe0, number 0x10) are never a code box; a bare number box may be an amount, so
        // the box must SAY it wants a code (its hint or its autofill hints).
        val password = variation == 0x00000080 || variation == 0x00000090 || variation == 0x000000e0 || variation == 0x00000010
        if (password) return false
        return CODE_HINT.containsMatchIn(h)
    }

    /** The keyboard: a code-like box in [pkg] has focus; [type] commits text into it. */
    fun focus(pkg: String, app: String?, type: (CharSequence) -> Boolean) { field = Field(pkg, app, type) }

    /** The keyboard: the box lost focus (or the keyboard closed). */
    fun blur() { field = null }

    fun enabled(): Boolean = com.tekyerema.core.config.AppConfig.bool("notifications.otpRelay.enabled", true)

    /** A notification arrived (raw text, read once). Offers the code when a code box is waiting. */
    fun onPosted(ctx: Context, pkg: String, sender: String?, text: String?, now: Long = System.currentTimeMillis()) {
        if (!enabled() || com.tekyerema.core.Redact.deniedPackage(pkg)) return
        val f = field ?: return
        if (f.pkg == pkg) return   // the app's own notification about its own box: it fills that itself
        val code = extract(text) ?: return
        wipe()
        pending = Pending(code.toCharArray(), now)
        val digits = if (PrivateReading.privateOutputConnected(ctx)) code else null
        com.tekyerema.voice.VoiceEngine.announce(offer(code.length, sender, f.app, digits), listenAfter = true)
        try { android.util.Log.i("Tekyerema", "[OTP] offered (${code.length} digits)") } catch (_: Throwable) {}   // never the code
    }

    /** "Enter the code": the line to say, or null when no offer is open (the words then go on as an ordinary turn). */
    fun enter(now: Long = System.currentTimeMillis()): String? {
        val p = pending ?: return null
        if (now - p.at > TTL_MS) { wipe(); return "That code has expired. Ask for a new one." }
        val f = field ?: return "The box for the code isn't open any more. Go back to it and say \"enter the code\" again."
        val ok = try { f.type(String(p.code)) } catch (_: Throwable) { false }
        wipe()
        return if (ok) "I typed the code." else "I couldn't type the code into that box."
    }

    fun hasOffer(now: Long = System.currentTimeMillis()): Boolean = pending?.let { now - it.at <= TTL_MS } == true

    private fun wipe() { pending?.code?.fill('0'); pending = null }

    /** For tests: open an offer with [code] at [now]. */
    internal fun offerForTest(code: String, now: Long) { wipe(); pending = Pending(code.toCharArray(), now) }
}
