package com.tekyerema.agent

import org.json.JSONObject

/**
 * What the request names is ALREADY on the screen: say so to the brain, so it taps it instead of searching or scrolling
 * for it again (owner, 4 Nov 2026: "the person is sometimes on the screen but it will search again instead of just opening
 * the chat … not just chats"; A20e 3 Nov 07:41 — Telegram opened straight into Richmond Apenensu's chat, the brain pressed
 * Go back and searched).
 *
 * Perception, not a plan: the brain still decides, so this only points. Two matches:
 *  - a PERSON, in chat, calling and contacts apps — the same test as [Invariants] rule 3 ([TaskFacts]: said, by sound, or
 *    found by a look-up), so a row named here is never one the invariants then stop; channels, groups and bots are never
 *    named ([Invariants.looksGroupish]);
 *  - ANY item, in any app — a song, a setting, a video, a file, a place: every content word of the item's name was said in
 *    this task ("play Adonai by Sarkodie" → the row "Adonai"; "turn on Bluetooth" → the row "Bluetooth").
 * The apps' own controls (Search, Send, Back, tabs…) and the app's own name are never pointed at. Screen text is quoted
 * with the listing's escaping. Pure → SeenTargetTest.
 */
object SeenTarget {
    private const val MAX = 3

    /** The name part of a row's label: "Richmond Apenensu, Ok see you, 10:42" → "Richmond Apenensu"; "Adonai • Sarkodie" → "Adonai". */
    internal fun nameOf(label: String): String =
        label.lines().first().split(',', '·', '•', '|', '—').first().trim().trimEnd(':', '.')

    /** Words that say nothing about WHICH item: requests' glue and the apps' own controls. */
    private val NOT_CONTENT = setOf(
        "the", "a", "an", "to", "on", "in", "of", "my", "me", "for", "and", "with", "is", "it", "its", "this", "that", "please",
        "i", "you", "can", "could", "what", "how", "from", "at", "by", "up", "down", "now", "just", "app", "some", "any", "all",
        "open", "play", "send", "call", "show", "find", "go", "tap", "turn", "set", "start", "stop", "check", "read", "tell",
        "message", "messages", "chat", "chats", "text", "search", "new", "more", "options", "back", "navigate", "home", "menu",
        "settings", "ok", "done", "cancel", "close", "edit", "share", "save", "add", "delete", "next", "previous", "more",
        "video", "videos", "song", "songs", "music", "file", "files", "photo", "photos", "page", "list", "button", "tab", "item")

    private fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    private fun content(s: String): List<String> = words(s).filter { it !in NOT_CONTENT }

    private fun peopleApp(pkg: String?, isChatApp: (String?) -> Boolean): Boolean =
        isChatApp(pkg) || (pkg != null && listOf("contacts", "dialer", "telecom", ".phone", "incallui").any { pkg.contains(it, true) })

    /** One line for the brain, or null when nothing the request named is on this screen. */
    fun hint(goal: String, history: List<String>, screen: JSONObject?, isChatApp: (String?) -> Boolean = defaultChatApp): String? {
        if (screen == null) return null
        val pkg = screen.optString("app").takeIf { it.isNotBlank() }
        val facts = TaskFacts(goal, history, "")
        val said = content(facts.said).toSet()
        val appWords = (words(screen.optString("appName")) + words(pkg.orEmpty())).toSet()
        val people = peopleApp(pkg, isChatApp)

        fun match(label: String): String? {
            if (label.isBlank()) return null
            val n = nameOf(label).takeIf { it.length in 2..80 } ?: return null
            if (people && Invariants.looksGroupish(label)) return null
            val c = content(n)
            if (c.isNotEmpty() && c.all { it in appWords }) return null           // "Telegram" inside Telegram
            if (people && Invariants.looksLikeName(n) && facts.personGrounded(n)) return n
            if (c.isEmpty() || c.sumOf { it.length } < 3) return null
            return n.takeIf { c.all { it in said } }
        }

        // Already there: the screen's / window's title is the thing (inside Richmond's chat, on the Bluetooth page).
        for (key in listOf("screen", "window")) {
            val t = screen.optString(key).takeIf { it.isNotBlank() } ?: continue
            match(t)?.let { return "SEEN (the app's own check): this screen is already ${ScreenListing.quote(it)}, which the request " +
                "names — work here; don't go back or search for it again." }
        }
        val els = screen.optJSONArray("elements") ?: return null
        val hits = mutableListOf<Pair<Int, String>>()
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            if (!e.has("id") || e.optString("role").contains("Edit", true)) continue     // a typed-in search box is not the item
            val acts = e.optJSONArray("actions")
            if (acts != null && (0 until acts.length()).none { acts.optString(it) == "tap" }) continue
            val label = e.optString("label")
            match(label) ?: continue
            // the row as the user would tell it apart ("Gospel • Joe Mettle" vs "Gospel • MOG"), not only its name
            val row = label.lines().first().trim().take(60)
            if (hits.none { it.second.equals(row, ignoreCase = true) }) hits += e.optInt("id") to row
            if (hits.size > MAX) return null      // the request's words are everywhere here: pointing would only mislead
        }
        val list = hits.joinToString(", ") { "[${it.first}] ${ScreenListing.quote(it.second)}" }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> "SEEN (the app's own check): $list on this screen is what the request names — tap it if it is " +
                "what the user means, instead of searching or scrolling for it."
            people -> "SEEN (the app's own check): more than one fits the person in the request: $list — ask_user which one, then tap it."
            else -> "SEEN (the app's own check): these on this screen match the request: $list — tap the one the user means " +
                "instead of searching; if it is unclear which, ask."
        }
    }

    private val defaultChatApp: (String?) -> Boolean = { p ->
        try { com.tekyerema.policy.TargetSemantics.isChatPackage(p) } catch (_: Throwable) { false }
    }
}
