package com.tekyerema.tools

import android.content.Context
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * Shared mechanics for the real-world app-task accelerators (install_app, ask_app, google_sign_in, take_photo, the
 * send_sms composer path). Each of those drives ANOTHER app's UI for a few deterministic steps after one decision
 * (and, where the action leaves the device, one confirmation). The reading of those screens is pure and unit-tested
 * here; only the polling touches the device.
 *
 * Why poll instead of one read: agents mostly fail these tasks on TIMING — the store page, the chat or the camera
 * is still loading when the next step looks (AndroidWorld / SPA-Bench), so every wait below is "until the screen
 * shows X, bounded", never a fixed sleep followed by a single look.
 */

/** Pure label helpers over a [UiSnapshot] — generic UI vocabulary, no app ids. */
internal object AppUi {
    /** Trimmed, lower-case, single-spaced. */
    fun norm(s: String?): String = s?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()

    /** Elements whose whole label is one of [labels] (case/space-insensitive) — "Install", never "Uninstall". */
    fun exact(snap: UiSnapshot, vararg labels: String): List<UiElement> {
        val want = labels.map { norm(it) }.toSet()
        return snap.elements.filter { !it.editable && norm(it.label) in want }
    }

    fun has(snap: UiSnapshot, vararg labels: String): Boolean = exact(snap, *labels).isNotEmpty()

    /** True if any visible label (or the title) matches [re]. */
    fun anyLabel(snap: UiSnapshot, re: Regex): Boolean =
        snap.elements.any { e -> e.label?.let(re::containsMatchIn) == true } || snap.screenTitle?.let(re::containsMatchIn) == true

    /** The clickable one first — a caption TextView inside a button reports the same label as the button. */
    fun preferClickable(els: List<UiElement>): UiElement? = els.firstOrNull { it.clickable && it.enabled } ?: els.firstOrNull()

    /** Every label on screen, top to bottom — for matching page titles and reading answers. */
    fun texts(snap: UiSnapshot): List<String> =
        snap.elements.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).mapNotNull { it.label?.trim()?.takeIf { l -> l.isNotEmpty() } }

    /** Significant lower-case words of a spoken name ("the Claude app" → [claude]). */
    fun words(name: String): List<String> = norm(name).split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 && it !in NAME_FILLER }

    private val NAME_FILLER = setOf("the", "app", "apps", "application", "my", "an", "for", "me", "please", "from", "on",
        "play", "store", "google", "download", "install")

    /** Every significant word of [name] starts a word of [label] ("jiji" ⊂ "Jiji Ghana: Buy & Sell"). */
    fun namesThing(label: String?, name: String): Boolean {
        val ws = words(name).ifEmpty { return false }
        val lw = norm(label).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return ws.all { w -> lw.any { it.startsWith(w) } }
    }
}

/**
 * E-03 / E-04 — reading a message composer (Samsung Messages, WhatsApp, an assistant's chat box) so a CONFIRMED send
 * can press the app's own Send button and prove the message left the box. Pure → unit-tested (AppTasksTest).
 */
object ComposerSend {
    /** Send controls by caption: "Send", "Send message", "Send SMS", "Send prompt", "Send (SIM 1)"… (not "Send money"). */
    private val SEND_LABEL = Regex(
        "^(?:send|submit)(?:\\s+(?:message|sms|mms|text|now|prompt))?(?:[\\s,(]*sim\\s*\\d\\)?)?$", RegexOption.IGNORE_CASE)
    /** …or by resource id when the button is an unlabelled icon ("send", "send_button", "btn_send", "send_msg"). */
    private val SEND_ID = Regex("(?:^|[_.:/-])(?:btn_?)?send(?:[_-]?(?:button|btn|icon|msg|message|fab))?$", RegexOption.IGNORE_CASE)

    private fun squash(s: String?): String = s.orEmpty().replace(Regex("\\s+"), " ").trim()

    /** The field text IS the message we were asked to send (whitespace-insensitive) — nothing more, nothing less. */
    fun textMatches(fieldText: String?, message: String): Boolean {
        val a = squash(fieldText); val b = squash(message)
        return b.isNotEmpty() && a.equals(b, ignoreCase = false)
    }

    /** The editable box holding exactly [message], or null. */
    fun composerWith(snap: UiSnapshot, message: String): UiElement? =
        snap.elements.firstOrNull { it.editable && !it.password && textMatches(it.label, message) }

    /** The main editable (a chat box) when it holds no text yet — its label is empty or just its placeholder. */
    fun emptyComposer(snap: UiSnapshot): UiElement? =
        snap.elements.filter { it.editable && !it.password }.maxByOrNull { it.bounds.top }
            ?.takeIf { it.label.isNullOrBlank() || it.label == it.hint }

    fun isSendControl(e: UiElement): Boolean {
        if (e.editable) return false
        val label = squash(e.label)
        if (label.isNotEmpty()) return SEND_LABEL.matches(label)
        val id = e.viewId?.substringAfter(":id/") ?: return false
        return SEND_ID.containsMatchIn(id)
    }

    /**
     * The Send button for [composer]: a send control on the composer's row (vertical overlap) wins, then the lowest one
     * on screen (chat apps keep Send at the bottom, next to the box).
     */
    fun sendButton(snap: UiSnapshot, composer: UiElement?): UiElement? {
        val cands = snap.elements.filter { isSendControl(it) && it.enabled }
        if (cands.isEmpty()) return null
        if (composer != null) {
            cands.filter { it.bounds.top < composer.bounds.bottom && composer.bounds.top < it.bounds.bottom }
                .maxByOrNull { it.bounds.left }?.let { return it }
        }
        return cands.maxByOrNull { it.bounds.bottom }
    }

    /** After pressing Send: no box still holds the text, and the text now shows as a message on screen. */
    fun looksSent(after: UiSnapshot, message: String): Boolean {
        val b = squash(message)
        if (b.isEmpty()) return false
        if (after.elements.any { it.editable && squash(it.label).contains(b) }) return false
        return after.elements.any { !it.editable && squash(it.label).contains(b.take(60)) }
    }

    /** WhatsApp's "this number isn't on WhatsApp" dialog (wording varies; keyed on the phrase, not an id). */
    private val NOT_ON_WHATSAPP = Regex("(?i)(isn'?t|is not|not) on whatsapp|not a whatsapp user|isn'?t registered|invalid phone number")
    fun notOnWhatsApp(snap: UiSnapshot): Boolean = AppUi.anyLabel(snap, NOT_ON_WHATSAPP)

    // The app's own "no connection" dialog (A20e 2026-09-29: WhatsApp showed "Couldn't connect. Please try again later.").
    private val CANT_CONNECT = Regex("(?i)couldn'?t connect|could not connect|no internet connection|check your (?:internet|phone'?s) connection|" +
        "you'?re offline|unable to connect")
    fun cantConnect(snap: UiSnapshot): Boolean = AppUi.anyLabel(snap, CANT_CONNECT)
}

/**
 * J-11 for accelerators that drive another app's screen: the engine refuses grounded taps inside a denied or
 * guide-only app, and these tools press buttons there too — so they honour the same lists. Null = may drive [pkg].
 */
internal fun driveRefusal(pkg: String?): String? {
    if (pkg == null) return null
    return when (pkg) {
        in com.tekyerema.policy.PolicyConfig.denyApps() -> "that app is on your list of apps I must not operate"
        in com.tekyerema.policy.PolicyConfig.guideOnlyApps() -> "that app is guide-only — I can tell you what to tap, but not tap it"
        else -> null
    }
}

/** Is [pkg] installed? (QUERY_ALL_PACKAGES is declared, so visibility is not the limit on Android 11+.) */
internal fun isInstalled(context: Context, pkg: String): Boolean = try {
    @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, 0); true
} catch (_: Throwable) { false }

/**
 * Re-read the screen every [intervalMs] until [accept] passes or [timeoutMs] runs out; returns the accepted snapshot
 * or null. Cancellation (the user said "stop") propagates — it is never swallowed as a failed read.
 */
internal suspend fun awaitScreen(
    ctrl: AccessibilityController, timeoutMs: Long, intervalMs: Long = 500L, accept: (UiSnapshot) -> Boolean
): UiSnapshot? {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        val s = try { ctrl.buildSnapshot() } catch (c: CancellationException) { throw c } catch (_: Throwable) { null }
        if (s != null && accept(s)) return s
        if (System.currentTimeMillis() >= end) return null
        delay(intervalMs)
    }
}

/** Wait for one of [pkgs] to be the app in front. */
internal suspend fun awaitForeground(ctrl: AccessibilityController, pkgs: Set<String>, timeoutMs: Long): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (ctrl.foregroundPackage() in pkgs) return true
        if (System.currentTimeMillis() >= end) return false
        delay(250L)
    }
}

/**
 * Press [e]: its node's click first; if the screen doesn't react, ONE gesture tap on its bounds (Compose and WhatsApp
 * controls sometimes accept ACTION_CLICK and do nothing). The re-tap only happens while the SAME control is still
 * there: if it has changed (Install → Cancel, a sent box emptied) the first press worked, and a second tap would undo
 * it or act twice.
 */
internal suspend fun pressElement(ctrl: AccessibilityController, e: UiElement, on: UiSnapshot, onlyNewControlsCount: Boolean = false,
                                   reacted: suspend () -> Boolean): Boolean {
    if (ctrl.clickElement(e.id) && reacted()) return true
    val now = try { ctrl.buildSnapshot() } catch (c: CancellationException) { throw c } catch (_: Throwable) { null } ?: return false
    // Anything new on screen (a dialog, a sheet) means the press DID something — never tap blindly through it.
    if (!onlyNewControlsCount) { if (now.signature() != on.signature()) return reacted() }
    else {
        // [onlyNewControlsCount] (a page whose carousel or images keep changing, like the Play Store's Install sheet —
        // A20e 2026-09-29: the click never reached the Compose button and the signature check skipped the gesture tap
        // that would have): only a NEW tappable control counts as a reaction. Never for a Send button — a sent message
        // may add nothing tappable, and a second tap would send twice.
        val before = on.elements.mapNotNullTo(HashSet()) { if (it.clickable) it.label?.trim()?.lowercase() else null }
        if (now.elements.any { it.clickable && !it.label.isNullOrBlank() && it.label.trim().lowercase() !in before }) return reacted()
    }
    val same = now.elements.firstOrNull {
        !it.editable && it.label == e.label && it.viewId == e.viewId && it.bounds.overlaps(e.bounds)
    } ?: return reacted()
    if (same.bounds.isEmpty()) return false
    return ctrl.tap(same.bounds.centerX, same.bounds.centerY) && reacted()
}
