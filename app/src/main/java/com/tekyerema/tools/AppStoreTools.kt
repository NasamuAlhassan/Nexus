package com.tekyerema.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.android.AppResolver
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import org.json.JSONObject

/**
 * E-01 — the Play Store, read as data. Pure (unit-tested in AppTasksTest): which package a spoken name means, and what
 * state an app's store page is in (free to install, already installed, paid, downloading…).
 *
 * Why an accelerator at all (A20e traces 2026-09-28): driven through the UI, "install calculator" took 13 steps — the
 * search bar wasn't editable until tapped, typing the name raised a needless "submit?" confirmation, and the Install
 * tap itself was never confirmed; "install YouTube Music" mis-tapped Spotify via vision and opened an "Uninstall this
 * app?" dialog. A details-page deep link lands on the one right page, and the page's single Install button is read
 * by its exact caption.
 */
object PlayStore {
    const val PKG = "com.android.vending"

    data class Listing(val title: String, val pkg: String)

    /**
     * Spoken names → Play listing. Package ids verified on play.google.com where marked in the D report (Claude
     * com.anthropic.claude, Jiji Ghana com.olx.ssa.gh, ChatGPT, YouTube Music, Telegram); the rest are long-stable
     * ids. A wrong or withdrawn id is harmless: its page says "not found" and the tool falls back to a search.
     * `os.store.packages` ({"spoken name": "package.id"}) adds or overrides entries.
     */
    private val CATALOG: List<Pair<Listing, List<String>>> = listOf(
        Listing("Claude", "com.anthropic.claude") to listOf("claude", "claude ai", "cloud ai", "clawed ai", "claude by anthropic", "anthropic"),
        Listing("ChatGPT", "com.openai.chatgpt") to listOf("chatgpt", "chat gpt", "chatgbt", "open ai chatgpt"),
        Listing("Jiji Ghana", "com.olx.ssa.gh") to listOf("jiji", "jiji ghana", "jiji gh"),
        Listing("WhatsApp", "com.whatsapp") to listOf("whatsapp", "whats app", "watsapp"),
        Listing("WhatsApp Business", "com.whatsapp.w4b") to listOf("whatsapp business"),
        Listing("Telegram", "org.telegram.messenger") to listOf("telegram"),
        Listing("YouTube Music", "com.google.android.apps.youtube.music") to listOf("youtube music", "yt music", "ytm"),
        Listing("YouTube", "com.google.android.youtube") to listOf("youtube", "you tube"),
        Listing("Gemini", "com.google.android.apps.bard") to listOf("gemini", "google gemini"),
        Listing("Microsoft Copilot", "com.microsoft.copilot") to listOf("copilot", "microsoft copilot"),
        Listing("Perplexity", "ai.perplexity.app.android") to listOf("perplexity"),
        Listing("Calculator", "com.google.android.calculator") to listOf("calculator", "google calculator"),
        Listing("Spotify", "com.spotify.music") to listOf("spotify"),
        Listing("Boomplay", "com.afmobi.boomplayer") to listOf("boomplay", "boom play"),
        Listing("Audiomack", "com.audiomack") to listOf("audiomack", "audio mack"),
        Listing("Gmail", "com.google.android.gm") to listOf("gmail"),
        Listing("Google Maps", "com.google.android.apps.maps") to listOf("maps", "google maps"),
        Listing("Chrome", "com.android.chrome") to listOf("chrome", "google chrome"),
        Listing("Google Translate", "com.google.android.apps.translate") to listOf("translate", "google translate"),
        Listing("Google Lens", "com.google.ar.lens") to listOf("lens", "google lens"),
        Listing("Facebook", "com.facebook.katana") to listOf("facebook"),
        Listing("Messenger", "com.facebook.orca") to listOf("messenger", "facebook messenger"),
        Listing("Instagram", "com.instagram.android") to listOf("instagram"),
        Listing("TikTok", "com.zhiliaoapp.musically") to listOf("tiktok", "tik tok"),
        Listing("Snapchat", "com.snapchat.android") to listOf("snapchat"),
        Listing("Zoom", "us.zoom.videomeetings") to listOf("zoom"),
        Listing("Bolt", "ee.mtakso.client") to listOf("bolt"),
        Listing("Uber", "com.ubercab") to listOf("uber"),
        Listing("Be My Eyes", "com.bemyeyes.bemyeyes") to listOf("be my eyes"),
        Listing("Lookout", "com.google.android.apps.accessibility.reveal") to listOf("lookout", "google lookout"),
        Listing("Seeing AI", "com.microsoft.seeingai") to listOf("seeing ai")
    )

    private fun key(s: String) = AppUi.words(s).joinToString("").filter { it.isLetterOrDigit() }

    /** The listing a spoken name means ("the Claude app", "cloud AI", "Jiji"), or null → search the store. */
    fun lookup(spoken: String): Listing? {
        val k = key(spoken).ifEmpty { return null }
        AppConfig.obj("os.store.packages")?.let { o ->
            for (name in o.keys()) if (key(name) == k) o.optString(name).trim().takeIf { it.isNotEmpty() }?.let { return Listing(spoken.trim(), it) }
        }
        return CATALOG.firstOrNull { (_, names) -> names.any { key(it) == k } }?.first
    }

    /** The official store link (developer.android.com "Linking to Google Play"); opened with the Play Store package. */
    fun detailsUrl(pkg: String) = "https://play.google.com/store/apps/details?id=$pkg"
    fun searchUrl(query: String) = "https://play.google.com/store/search?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8") + "&c=apps"

    enum class State { INSTALLABLE, INSTALLED, IN_PROGRESS, PAID, SIGN_IN, UNAVAILABLE, UNKNOWN }

    data class Page(val state: State, val button: UiElement? = null, val price: String? = null)

    /** A price where Install would be: "GH₵ 12.00", "$1.99", "Buy GHS 5" (never tapped — a purchase is not an install). */
    private val PRICE = Regex("^(?:buy(?: for)?\\s+)?(?:gh₵|ghs|ghc|₵|us\\$|\\$|€|£|₦|ngn|ksh|zar)\\s?\\d[\\d.,]*$|^\\d[\\d.,]*\\s?(?:gh₵|ghs|₵|€|£)$",
        RegexOption.IGNORE_CASE)
    private val PROGRESS = Regex("(?i)\\b(pending|installing|downloading|waiting for (?:download|wi-?fi|network)|verifying|optimi[sz]ing)\\b|\\d{1,3}\\s?%")
    private val UNAVAILABLE = Regex("(?i)item not found|isn'?t available|not available (?:in|for|on)|device isn'?t compatible|" +
        "not compatible with|pre-?register|coming soon|couldn'?t find|we couldn'?t find")

    /**
     * What an app's details page offers. The page is Jetpack Compose with no resource ids (Appium users report id
     * locators fail), so it is read by EXACT captions: "Install" is never confused with "Uninstall", and a page with
     * more than two Install buttons is a results list, not one app's page.
     */
    fun page(snap: UiSnapshot): Page {
        val installs = AppUi.exact(snap, "install", "install on phone", "install on this device")
        // Order matters: while downloading, newer Play pages show "Cancel" next to a greyed "Open" — that is
        // progress, not "installed". Installed = "Uninstall", or Open/Play/Update with no Cancel and no Install.
        if (installs.isEmpty() && AppUi.has(snap, "cancel")) return Page(State.IN_PROGRESS)
        if (AppUi.has(snap, "uninstall")) return Page(State.INSTALLED)
        if (installs.isEmpty() && AppUi.anyLabel(snap, PROGRESS)) return Page(State.IN_PROGRESS)
        if (installs.isEmpty() && AppUi.has(snap, "open", "play", "update")) return Page(State.INSTALLED)
        if (installs.size in 1..2) return Page(State.INSTALLABLE, AppUi.preferClickable(installs))
        snap.elements.firstOrNull { !it.editable && it.label?.trim()?.let(PRICE::matches) == true }
            ?.let { return Page(State.PAID, it, it.label!!.trim()) }
        if (installs.isEmpty() && AppUi.has(snap, "sign in")) return Page(State.SIGN_IN)
        if (AppUi.anyLabel(snap, UNAVAILABLE)) return Page(State.UNAVAILABLE)
        return Page(State.UNKNOWN)
    }

    /** The page is about [name]: a label ABOVE the Install button names it (the title sits at the top). */
    fun pageNames(snap: UiSnapshot, name: String, button: UiElement?): Boolean {
        val limit = button?.bounds?.top ?: Int.MAX_VALUE
        return snap.elements.any { !it.editable && it.bounds.top <= limit && AppUi.namesThing(it.label, name) }
    }

    private val AD = Regex("(?i)^(?:ad|ads|sponsored)\\b|\\bsponsored\\b|(?:^|\\s)ad\\s*[·•:]")

    private val RESULT_ROW = Regex("(?i)star rating|\\bdownload(?:s|ed)\\b|\\d\\s?mb\\b|\\binstalled\\b")

    /**
     * The search result to open for [name]: the top-most result row (rating / size text) whose caption STARTS with the
     * name; else the top-most caption that starts with it and says more than the bare query (the results page's search
     * bar echoes the query — tapping that would reopen the search). Only STARTS-with: a title that merely mentions the
     * name ("…a Jiji alternative") is a look-alike. Ads are skipped when their caption says so. Null when nothing is
     * clearly that app — the caller asks the user rather than installing a look-alike.
     */
    fun pickResult(snap: UiSnapshot, name: String): UiElement? {
        val first = AppUi.words(name).firstOrNull() ?: return null
        val starts = snap.elements.filter { e ->
            val l = e.label?.trim().orEmpty()
            !e.editable && l.length > 2 && !AD.containsMatchIn(l) && AppUi.namesThing(l, name) && AppUi.norm(l).startsWith(first) &&
                !Regex("(?i)^(install|open|update|uninstall)$").matches(l)
        }
        return starts.filter { RESULT_ROW.containsMatchIn(it.label!!) }.minByOrNull { it.bounds.top }
            ?: starts.filter { AppUi.norm(it.label) != AppUi.norm(name) }.minByOrNull { it.bounds.top }
    }

    /** Result-row captions for a "which one?" question: the app names only (the part before rating/size text). */
    fun resultNames(snap: UiSnapshot, max: Int = 3): List<String> = snap.elements
        .filter { it.clickable && !it.editable && (it.label?.length ?: 0) > 12 && !AD.containsMatchIn(it.label!!) &&
            Regex("(?i)star rating|downloads?|\\bmb\\b|installed").containsMatchIn(it.label!!) }
        .sortedBy { it.bounds.top }
        .map { it.label!!.split(Regex("(?i)\\s+(?:star rating|\\d+(?:\\.\\d+)?\\s?mb|downloaded|contains ads)")).first().trim().take(50) }
        .distinct().take(max)
}

/**
 * E-01 install_app — install a FREE app from the Google Play Store after the user says yes (TIER2). Known names go
 * straight to the app's store page; others are searched and the result whose title names the app is opened. It taps
 * ONLY a caption that is exactly "Install" — never a price, never "Update", never "Uninstall" — then waits for the
 * download to start and (bounded) to finish. Honest about every stop: paid app, not found, sign-in needed, still
 * downloading.
 */
object InstallAppTool : Tool {
    override val name = "install_app"
    override val description = "Install a free app from the Play Store and wait for it (asks the user first). Only when the " +
        "user asked to install an app."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER2
    override val isAccelerator = true
    override val verifiesOutcome = true
    override val params = listOf(ToolParam("app", "string", "App name, or its exact Play Store title."))

    /** Filled by [preflight] so the confirmation names the real listing ("Install Jiji Ghana…?"). */
    private const val ARG_TITLE = "_title"

    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        val spoken = args.str("app")?.trim().orEmpty()
        if (AppUi.words(spoken).isEmpty()) return ToolResult(false, "Which app should I install?")
        if (!isInstalled(ctx.appContext, PlayStore.PKG))
            return ToolResult(false, "The Google Play Store isn't on this phone, so I can't install apps.", recoverable = false)
        driveRefusal(PlayStore.PKG)?.let { return ToolResult(false, "I can't install apps: the Play Store — $it.", recoverable = false) }
        val listing = PlayStore.lookup(spoken)
        val installedAs = listing?.takeIf { isInstalled(ctx.appContext, it.pkg) }?.title
            ?: (try { AppResolver.match(AppResolver.launchable(ctx.appContext, fresh = true), spoken, tolerant = false) }
                catch (_: Throwable) { null } as? AppResolver.Match.One)?.app?.label?.takeIf { AppUi.namesThing(it, spoken) }
        if (installedAs != null)
            return ToolResult(false, "$installedAs is already installed on this phone — open it with open_app.",
                data = JSONObject().put("installed", true), recoverable = false)
        args.put(ARG_TITLE, listing?.title ?: spokenAppName(spoken))
        return null
    }

    override fun confirmationPrompt(args: JSONObject): String {
        val title = args.str(ARG_TITLE) ?: args.str("app")?.let { spokenAppName(it) } ?: "this app"
        return "Install $title from the Play Store? I'll only install it if it's free."
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val spoken = args.str("app")?.trim().orEmpty()
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE, recoverable = false)
        val listing = PlayStore.lookup(spoken)
        val title = listing?.title ?: spokenAppName(spoken)
        val before = try { AppResolver.launchable(ctx.appContext, fresh = true).map { it.packageName }.toSet() } catch (_: Throwable) { emptySet() }

        // 1) The store page: straight to it for a known package; otherwise search and open the result that names it.
        //    Waits are upper bounds (the check passes the moment the page shows): 25 s, because on the campus Wi-Fi
        //    (A20e, 2026-09-29) a details page took longer than the old 12 s and a free app was reported "not found".
        var page: UiSnapshot? = null
        if (listing != null && openStore(ctx, PlayStore.detailsUrl(listing.pkg))) {
            // The page must NAME the app: for a moment the Play Store can still show whatever page it was left on
            // (another app's Install button), and that must never be the one pressed.
            page = awaitScreen(ctrl, AppConfig.long("os.store.pageWaitMs", 25_000L)) { s ->
                val p = PlayStore.page(s)
                s.appPackage == PlayStore.PKG && p.state != PlayStore.State.UNKNOWN &&
                    (p.state == PlayStore.State.UNAVAILABLE || PlayStore.pageNames(s, listing.title, p.button))
            }
            // A withdrawn / wrong id shows "not found": search by name instead of stopping.
            if (page != null && PlayStore.page(page).state == PlayStore.State.UNAVAILABLE) page = null
        }
        if (page == null) {
            if (!openStore(ctx, PlayStore.searchUrl(title)))
                return ToolResult(false, "I couldn't open the Play Store.", recoverable = true)
            val results = awaitScreen(ctrl, AppConfig.long("os.store.pageWaitMs", 25_000L)) { s ->
                s.appPackage == PlayStore.PKG && PlayStore.pickResult(s, title) != null
            }
            if (results == null) {
                val seen = try { PlayStore.resultNames(ctrl.buildSnapshot()) }
                    catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { emptyList() }
                return ToolResult(false, "I searched the Play Store for $title but no result is clearly that app. " +
                    (if (seen.isNotEmpty()) "I can see: ${seen.joinToString(", ")}. " else "") +
                    "Ask the user which one they mean, then call install_app with its exact title.",
                    needsDisambiguation = true, channel = "a11y")
            }
            val row = PlayStore.pickResult(results, title)!!
            ctrl.clickElement(row.id)
            // The details page, not the results list still on screen: the tapped row must be gone from where it was (a
            // results list has inline Install buttons of its own — pressing one could install a neighbouring app). By
            // position, not caption: the details page repeats the app's title.
            page = awaitScreen(ctrl, AppConfig.long("os.store.pageWaitMs", 25_000L)) { s ->
                s.appPackage == PlayStore.PKG && s.elements.none { it.label == row.label && it.bounds == row.bounds } &&
                    PlayStore.page(s).state != PlayStore.State.UNKNOWN && AppUi.exact(s, "install").size <= 2
            } ?: return ToolResult(false, "I opened ${row.label?.take(40)} in the Play Store, but its page didn't load.", channel = "a11y")
            // Never install a look-alike: the page's title must name what the user asked for.
            if (!PlayStore.pageNames(page, title, PlayStore.page(page).button))
                return ToolResult(false, "The Play Store page that opened doesn't look like $title. Ask the user which app " +
                    "they mean, then call install_app with its exact title.", needsDisambiguation = true, channel = "a11y")
        }

        // 2) Act on the page's state — only a free "Install" is ever pressed.
        val state = PlayStore.page(page)
        val data = JSONObject().put("app", title).apply { listing?.let { put("package", it.pkg) } }
        when (state.state) {
            PlayStore.State.INSTALLED -> return ToolResult(true, "$title is already installed.", data.put("installed", true), channel = "a11y")
            PlayStore.State.IN_PROGRESS -> return ToolResult(true, "$title is already downloading in the Play Store.",
                data.put("inProgress", true), channel = "a11y")
            PlayStore.State.PAID -> return ToolResult(false, "$title costs ${state.price} — I only install free apps, and I never buy " +
                "anything. The user can buy it themselves in the Play Store.", data, recoverable = false, channel = "a11y")
            PlayStore.State.SIGN_IN -> return ToolResult(false, "The Play Store needs a Google account signed in before it can " +
                "install apps. The user needs to sign in to the Play Store first.", data, recoverable = false, channel = "a11y")
            PlayStore.State.UNAVAILABLE -> return ToolResult(false, "The Play Store says $title isn't available for this phone.",
                data, recoverable = false, channel = "a11y")
            PlayStore.State.UNKNOWN -> return ToolResult(false, "The Play Store page for $title didn't show an Install button.", data, channel = "a11y")
            PlayStore.State.INSTALLABLE -> Unit
        }
        val started = pressElement(ctrl, state.button!!, page, onlyNewControlsCount = true) {
            awaitScreen(ctrl, 6_000L) { s -> PlayStore.page(s).state.let { it == PlayStore.State.IN_PROGRESS || it == PlayStore.State.INSTALLED } } != null ||
                (listing != null && isInstalled(ctx.appContext, listing.pkg))
        }
        if (!started) {
            val now = try { ctrl.buildSnapshot() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { null }
            val said = now?.let { AppUi.texts(it).take(6).joinToString("; ") }.orEmpty()
            return ToolResult(false, "I pressed Install for $title but the download didn't start. The screen now shows: $said",
                data, channel = "a11y")
        }

        // 3) Wait (bounded) for the install to finish, so "open it" works right after.
        val waitMs = AppConfig.long("os.store.installWaitMs", 60_000L)
        val done = awaitScreen(ctrl, waitMs, intervalMs = 1_500L) { s ->
            (listing != null && isInstalled(ctx.appContext, listing.pkg)) || PlayStore.page(s).state == PlayStore.State.INSTALLED
        } != null
        if (!done) return ToolResult(true, "$title is downloading now — it will finish installing on its own.",
            data.put("inProgress", true), channel = "a11y")
        val newPkg = listing?.pkg ?: try {
            AppResolver.launchable(ctx.appContext, fresh = true).map { it.packageName }.firstOrNull { it !in before }
        } catch (_: Throwable) { null }
        newPkg?.let { data.put("package", it) }
        return ToolResult(true, "Installed $title. It's ready to open.", data.put("installed", true), channel = "a11y")
    }

    /** Open a Play link in the Play Store app itself (no browser chooser). */
    private fun openStore(ctx: ToolContext, url: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(PlayStore.PKG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try { ctx.appContext.startActivity(i); true }
        catch (_: ActivityNotFoundException) {
            // Older Play Store builds only answer market:// links.
            try {
                val m = url.replace("https://play.google.com/store/apps/", "market://").replace("https://play.google.com/store/", "market://")
                ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m)).setPackage(PlayStore.PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Throwable) { false }
        } catch (_: Throwable) { false }
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        val d = result.data
        val ok = result.success && d != null && (d.optBoolean("installed") || d.optBoolean("inProgress"))
        return VerificationResult(ok, result.message)
    }
}
