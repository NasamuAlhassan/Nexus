package com.tekyerema.core

import com.tekyerema.accessibility.UiSnapshot

/**
 * "Where am I?" (MASTER_STATUS #35, 2 Nov 2026): one short sentence about the current screen, built offline from the
 * accessibility snapshot — no model. Lost focus and silent refreshes are the top documented frustration of screen-reader
 * users, so the sentence says the place first (the app, then the screen's title) and then the one thing that matters
 * most right now: a private screen, the box the keyboard types into, a pop-up, or how long the list is.
 *
 * WHY only those facts: labels are screen-derived (untrusted, possibly private). The sentence never says message text,
 * amounts or a field's contents — only the app's name, a short title without long numbers, and a box's name (its hint),
 * each with codes hidden ([Redact.secrets]). A private screen (credentials, FLAG_SECURE) says so and nothing else.
 *
 *  - [sentence] (snapshot): on demand ("which app am I in?", the fast path's WHERE_ON_SCREEN).
 *  - [sentence] (before, after): after an action; null when nothing a listener needs changed, so the caller stays quiet.
 * Pure Kotlin, unit-tested (ScreenSummaryTest).
 */
object ScreenSummary {
    private const val TITLE_MAX = 40
    private val LONG_NUMBER = Regex("\\d[\\d ,.]{2,}\\d")
    private val LIST_ROWS = Regex("^list (\\d+)")

    /** "You're in Settings, Wi-Fi. There's a list of 12 items." — never empty. */
    fun sentence(s: UiSnapshot?): String {
        if (s == null || (s.appPackage == null && s.appName == null && s.elements.isEmpty()))
            return "I can't see the screen right now."
        val name = nameOf(s)
        if (isPrivate(s)) return "You're ${prep(name)} $name, on a private screen."
        val title = titleOf(s, name)
        val head = "You're ${prep(name)} $name" + (title?.let { ", $it" } ?: "") + "."
        return detailOf(s)?.let { "$head $it" } ?: head
    }

    /** What changed for the listener after an action, in one sentence; null = nothing worth saying. */
    fun sentence(before: UiSnapshot?, after: UiSnapshot?): String? {
        if (after == null) return null
        if (before == null) return sentence(after)
        val name = nameOf(after)
        if (isPrivate(after)) return if (isPrivate(before) && name == nameOf(before)) null else "Now on a private screen in $name."
        val title = titleOf(after, name)
        val detail = detailOf(after)
        if (after.appPackage != before.appPackage || name != nameOf(before))
            return "Now ${prep(name)} $name" + (title?.let { ", $it" } ?: "") + "." + (detail?.let { " $it" } ?: "")
        if (title != null && title != titleOf(before, nameOf(before)))
            return "Now on $title." + (detail?.let { " $it" } ?: "")
        // Same place: only what is new and matters — a pop-up, or the keyboard now on a box.
        if (popup(after) && !popup(before)) return "A pop-up opened."
        val box = focusedBox(after)
        if (box != null && box != focusedBox(before)) return keyboardLine(box)
        return null
    }

    /** The screen's short title for the after-switch note ("In WhatsApp, Chats."); null on a private screen. */
    fun title(s: UiSnapshot?): String? = s?.takeUnless { isPrivate(it) }?.let { titleOf(it, nameOf(it)) }

    // ------------------------------------------------------------------ pieces

    private fun isPrivate(s: UiSnapshot): Boolean =
        s.secureWindow || try { com.tekyerema.policy.TargetSemantics.isCredentialScreen(s) } catch (_: Throwable) { false }

    /** What a person calls the app; never a package id ("com.whatsapp"). */
    private fun nameOf(s: UiSnapshot): String = s.appName?.trim()?.takeIf { it.isNotEmpty() } ?: "an app"

    private fun prep(name: String) = if (name.startsWith("the ", ignoreCase = true)) "on" else "in"

    /** The screen's title when it adds something: short, not the app's name again, no long numbers, no hidden codes. */
    private fun titleOf(s: UiSnapshot, name: String): String? {
        val t = s.screenTitle?.trim()?.trimEnd('.', ':', ',')?.replace(Regex("\\s+"), " ") ?: return null
        if (t.length !in 2..TITLE_MAX || t.equals(name, ignoreCase = true)) return null
        if (LONG_NUMBER.containsMatchIn(t) || Redact.secrets(t) != t) return null
        return t
    }

    /** The one extra fact, in order of what a listener needs first. */
    private fun detailOf(s: UiSnapshot): String? {
        if (s.webContentMissing) return "The page hasn't loaded yet."
        focusedBox(s)?.let { return keyboardLine(it) }
        if (popup(s)) return "A pop-up is open."
        val rows = s.elements.firstNotNullOfOrNull { e -> e.collection?.let { LIST_ROWS.find(it)?.groupValues?.get(1)?.toIntOrNull() } }
        if (rows != null && rows >= 2) return "There's a list of $rows items."
        return null
    }

    /** The name of the box the keyboard types into ("Message"), "" for a box with no name, null when none is focused. */
    private fun focusedBox(s: UiSnapshot?): String? {
        val e = s?.elements?.firstOrNull { it.editable && it.focused } ?: return null
        if (e.password) return "password"
        val hint = e.hint?.trim()?.trimEnd('.', '…', ':')?.takeIf { it.length in 1..30 && Redact.secrets(it) == it && !LONG_NUMBER.containsMatchIn(it) }
        return hint ?: ""
    }

    private fun keyboardLine(box: String) = if (box.isEmpty()) "The keyboard is on a text box." else "The keyboard is on the $box box."

    private fun popup(s: UiSnapshot?): Boolean =
        s != null && (s.windowType == "system" && s.appName == null || s.elements.any { it.role.contains("Dialog", ignoreCase = true) })
}
