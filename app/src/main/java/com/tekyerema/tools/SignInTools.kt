package com.tekyerema.tools

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.policy.TargetSemantics
import com.tekyerema.verifier.VerificationResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * G-13 / J — the screens of "Sign in with Google", read as data. Pure, unit-tested (AppTasksTest).
 *
 * Google's branding rules fix the button texts ("Sign in with Google", "Sign up with Google", "Continue with Google",
 * possibly localised); after it comes Google's account chooser (Credential Manager bottom sheet or the classic
 * "Choose an account" list, rows carrying the address), sometimes a consent page listing what the app will see.
 */
object GoogleSignIn {
    private val BUTTON = Regex("^(?:continue|sign ?in|sign ?up|log ?in|login|register|connect|get started)\\s+(?:with|using|via)\\s+google$",
        RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val ADD_ACCOUNT = Regex("(?i)\\b(?:add|use) (?:another|an?) account\\b|\\bcreate account\\b")
    /** The buttons that carry on with the sign-in the user approved (bottom sheet, chooser, basic consent). */
    // Not "Allow"/"OK": those also caption Android's own permission dialogs, and "Allow" on Google's consent page means
    // extra access — left to the policy's own confirmation.
    private val CARRY_ON = Regex("^(?:continue|continue as .{1,40}|next|agree|i agree|agree and continue|confirm)$",
        RegexOption.IGNORE_CASE)
    /**
     * Access BEYOND "name, email address, profile picture" — Drive, Gmail, contacts, calendar, photos, YouTube…, or
     * any "see, edit, create and delete" wording. The one approval covers signing in, not these: the tool stops and
     * names them, and a later tap on "Allow" is confirmed separately by the policy.
     */
    private val EXTRA_SCOPE = Regex("(?i)\\b(?:google drive|drive files|gmail|your email messages|send email|contacts|calendar|google photos|" +
        "youtube account|fitness|location history|see, edit|edit, create|create and delete|delete|manage your|make payments)\\b")

    /** The app's own "Continue with Google" button (or an icon button whose caption / id is just Google). */
    fun button(snap: UiSnapshot): UiElement? {
        val byText = snap.elements.filter { !it.editable && it.label?.trim()?.let(BUTTON::matches) == true }
        val byIcon = snap.elements.filter { e ->
            e.clickable && !e.editable && (e.label?.trim().equals("google", ignoreCase = true) ||
                e.label.isNullOrBlank() && e.viewId?.substringAfter(":id/")?.contains("google", ignoreCase = true) == true)
        }
        return AppUi.preferClickable(byText) ?: byIcon.firstOrNull()
    }

    /** Google account rows (the address is in the caption), never "Add another account". */
    fun accounts(snap: UiSnapshot): List<UiElement> = snap.elements.filter { e ->
        !e.editable && e.label?.let { EMAIL.containsMatchIn(it) && !ADD_ACCOUNT.containsMatchIn(it) } == true &&
            (e.clickable || snap.elements.none { o -> o.clickable && o.bounds.contains(e.bounds.centerX, e.bounds.centerY) && o != e })
    }.distinctBy { EMAIL.find(it.label!!)?.value?.lowercase() }

    fun email(e: UiElement): String? = e.label?.let { EMAIL.find(it)?.value }

    /** Which row to pick: the one naming [wanted] (an address or a name), else the only account. Null = ask the user. */
    fun pick(rows: List<UiElement>, wanted: String?): UiElement? {
        val w = wanted?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (w != null) return rows.firstOrNull { it.label?.lowercase()?.contains(w) == true }
        return rows.singleOrNull()
    }

    fun carryOn(snap: UiSnapshot): UiElement? = AppUi.preferClickable(snap.elements.filter { e ->
        !e.editable && e.label?.trim()?.let(CARRY_ON::matches) == true
    })

    /** Extra access the screen asks for, as the words shown (for the user to decide), or empty. */
    fun extraAccess(snap: UiSnapshot): List<String> = snap.elements.mapNotNull { it.label?.trim() }
        .filter { it.length in 8..160 && EXTRA_SCOPE.containsMatchIn(it) }.distinct().take(4)
}

/**
 * sign-in accelerator — sign in to the app on screen with its "Continue with Google" button and the phone's own Google
 * account. It shares the user's identity with that app, so it is TIER2: ONE confirmation that names the app and what
 * it will see, then the tool presses the button, picks the account (the only one, or the one the user named) and the
 * standard Continue. It STOPS — never guesses or types — at: several accounts (asks which), a password / code / robot
 * check (Secure Input Mode takes over; passwords are the user's), and any request for access beyond the basic profile.
 */
object GoogleSignInTool : Tool {
    override val name = "google_sign_in"
    override val description = "Sign in to the app on screen via its 'Continue with Google' button with the phone's Google " +
        "account (asks first; stops at passwords)."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER2
    override val isAccelerator = true
    override val verifiesOutcome = true
    override val params = listOf(ToolParam("account", "string", "Email, if several.", required = false))

    private const val ARG_APP = "_app"

    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        val screen = ctx.observedBefore ?: ctx.controller?.buildSnapshot()
            ?: return ToolResult(false, NO_SERVICE, recoverable = false)
        if (GoogleSignIn.button(screen) == null)
            return ToolResult(false, "I don't see a 'Continue with Google' button on this screen — open the app's sign-in page first.")
        driveRefusal(screen.appPackage)?.let { return ToolResult(false, "I can't sign in there: $it.", recoverable = false) }
        args.put(ARG_APP, screen.appName ?: "this app")
        return null
    }

    override fun confirmationPrompt(args: JSONObject): String {
        val app = args.str(ARG_APP) ?: "this app"
        return "Sign in to $app with your Google account? $app will get your name, email address and profile picture."
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE, recoverable = false)
        val appPkg = ctx.observedBefore?.appPackage
        val start = ctrl.buildSnapshot()
        // Only on the screen the user approved: if another app came to the front meanwhile, don't press anything.
        if (appPkg != null && start.appPackage != appPkg)
            return ToolResult(false, "The screen changed before I could sign in — re-observe and try again.")
        val app = args.str(ARG_APP) ?: start.appName ?: "the app"
        val button = GoogleSignIn.button(start) ?: return ToolResult(false, "The 'Continue with Google' button is gone from the screen.")
        ctrl.clickElement(button.id)

        val t0 = System.currentTimeMillis()
        val end = t0 + AppConfig.long("os.signin.waitMs", 30_000L)
        val pressedOn = HashSet<String>()   // one press per distinct screen — never hammer a button that didn't move us on
        var chose: String? = null
        var sawGoogle = false
        while (System.currentTimeMillis() < end) {
            delay(800L)
            val s = try { ctrl.buildSnapshot() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { continue }
            if (TargetSemantics.isCredentialScreen(s) || TargetSemantics.isHumanCheckScreen(s))
                return ToolResult(false, "Google is asking for a password, a code or a robot check. The user must do that step " +
                    "themselves — I never type passwords.", JSONObject().put("signedIn", false), recoverable = false, channel = "a11y")
            // Google's own screens (its account sheet in Play services, or accounts.google.com in a browser tab) — never
            // the app's own screens, where an address or a "Continue" means something else.
            // The app's own next question (Android's "Allow notifications?" dialog) counts as back in the app.
            val inApp = appPkg != null && (s.appPackage == appPkg || s.appPackage?.contains("permissioncontroller") == true)
            if (!inApp) {
                val rows = GoogleSignIn.accounts(s)
                if (rows.isNotEmpty()) {
                    sawGoogle = true
                    val row = GoogleSignIn.pick(rows, args.str("account")) ?: return ToolResult(false,
                        "Which Google account should I use: ${rows.mapNotNull { GoogleSignIn.email(it) }.joinToString(", ")}? " +
                            "Ask the user, then call google_sign_in with that account.", needsDisambiguation = true, channel = "a11y")
                    if (pressedOn.add("acct|" + s.signature())) { chose = GoogleSignIn.email(row); ctrl.clickElement(row.id) }
                    continue
                }
                val extra = GoogleSignIn.extraAccess(s)
                if (extra.isNotEmpty())
                    return ToolResult(false, "$app is also asking for access to: ${extra.joinToString("; ")}. The user approved signing " +
                        "in, not this — ask them before tapping Allow.", JSONObject().put("signedIn", false), channel = "a11y")
                val onward = GoogleSignIn.carryOn(s)
                val google = s.appPackage?.startsWith("com.google.android.gms") == true || AppUi.anyLabel(s, Regex("(?i)\\bgoogle\\b"))
                if (onward != null && google) {
                    sawGoogle = true
                    if (pressedOn.add("next|" + s.signature())) ctrl.clickElement(onward.id)
                    continue
                }
            }
            // Back in the app, its Google button gone and the screen moved on: signed in. (A one-tap sign-in can finish
            // without any Google screen we could see — then give it a moment before believing it.)
            val settled = sawGoogle || System.currentTimeMillis() - t0 > 3_000L
            if (inApp && settled && GoogleSignIn.button(s) == null && s.signature() != start.signature())
                return ToolResult(true, "Signed in to $app with your Google account${chose?.let { " ($it)" } ?: ""}.",
                    JSONObject().put("signedIn", true), channel = "a11y")
        }
        return ToolResult(false, "I pressed 'Continue with Google' for $app but couldn't confirm the sign-in finished — " +
            "read the screen to see where it stopped.", JSONObject().put("signedIn", false), channel = "a11y")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult =
        VerificationResult(result.success && result.data?.optBoolean("signedIn") == true, result.message)
}
