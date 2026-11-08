package com.tekyerema.agent

import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiSnapshot

/**
 * The screen read out word for word, in reading order, a few sentences at a time — offline (owner, 2026-09-28: "for
 * the reading of screens, I don't know why it doesn't work offline"). The old local reader only summarised: it dropped
 * every text longer than 40 characters, so a message or an article was never actually read.
 *
 * Tree elements come in tree order (the order a screen reader uses); OCR elements, when the tree had nothing, top to
 * bottom and left to right. A password field is never read, not even its label. A row whose text repeats the item
 * just read (a list row and its own title) is read once. Pure, unit-tested; [ScreenReadCursor] keeps the rest for
 * "continue".
 */
object ScreenReadout {
    // No quote marks: the engine's grounding check (§C.3) took “continue” for a quotation that isn't on the screen and
    // marked every multi-part reading "not confirmed" (A20e, 2026-09-28). Spoken, the quotes added nothing.
    const val MORE = " There's more — say continue."
    const val NOTHING = "I don't see any text on this screen."

    /** "Profile picture, double tap to open Google Account" → "Profile picture": a spoken hint is how to operate it, not what it says. */
    private val HINT = Regex("(?i)[,.;:]\\s*(?:double[- ]?tap|tap twice|tap|long[- ]?press|press and hold|touch and hold)\\s+(?:to|and hold)\\b.{0,50}$")
    /** A picture described as a picture, and controls that are chrome rather than content, are not part of "what it says". */
    private val DECORATIVE = Regex("(?i)^illustration(?: of\\b.*)?$|^(?:icon|image|graphic|photo|picture)$|^(?:navigate up|more options|back|close|"
        + "profile picture|menu|overflow menu|show navigation drawer|open navigation drawer|pdf view|web view|document view)$")

    fun clean(raw: String?): String {
        val one = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val noHint = HINT.replace(one, "").trim().trimEnd(',', ';', ':').trim()
        return noHint
    }

    fun isChrome(label: String) = DECORATIVE.matches(label)

    /** What is read, one item per entry, in reading order. */
    fun items(snap: UiSnapshot): List<String> {
        val tree = snap.elements.filter { it.source != ElementSource.VISION }
        val vision = snap.elements.filter { it.source == ElementSource.VISION }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val out = ArrayList<String>()
        val chat = com.tekyerema.accessibility.ChatBubbles.isChatScreen(snap)
        val other = if (chat) try { com.tekyerema.agent.journal.EffectKeys.chatTitle(snap) } catch (_: Throwable) { null } else null
        for (e in tree + vision) {
            if (e.password) continue
            // A chat read aloud says who said each bubble ("You: …", "Richmond: …") — A20e 8 Nov 2026.
            val label = clean((if (chat) com.tekyerema.accessibility.ChatBubbles.promptLabel(e, snap, other) else null) ?: e.label)
            if (label.isEmpty() || isChrome(label)) continue
            val prev = out.lastOrNull()
            if (out.any { it.equals(label, ignoreCase = true) }) continue
            if (prev != null && prev.contains(label, ignoreCase = true)) continue   // a row's own title, already read
            out += when {
                e.editable -> "Text box: $label"
                e.checkable -> "$label: ${if (e.checked) "on" else "off"}"
                else -> label
            }
        }
        return out
    }

    /** The reading in parts of about [maxChars]; every part but the last ends with [MORE]. */
    fun compose(snap: UiSnapshot, maxChars: Int = 480): List<String> {
        // "You're in Settings. Settings." (A20e): the page's own title repeats the app name — say it once.
        val items = items(snap).dropWhile { snap.appName != null && it.equals(snap.appName, ignoreCase = true) }
        val where = snap.appName?.takeIf { it.isNotBlank() }?.let { name ->
            // The title is named only when the reading doesn't start with it anyway.
            // Chrome titles its window "Chrome: <page title>" — drop the app-name prefix, and skip the title when the
            // reading starts with it anyway ("You're in Chrome, on Chrome: Community Notice. Community Notice.").
            val title = snap.screenTitle?.removePrefix("$name: ")?.removePrefix("$name - ")?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(name, true) && it.length <= 60 && !it.equals(items.firstOrNull(), true) }
            (if (name.startsWith("the ")) "You're on $name" else "You're in $name") + (title?.let { ", on $it" } ?: "") + "."
        }
        val sentences = items.flatMap { split(sentence(it), maxChars) }
        if (sentences.isEmpty()) return listOf(listOfNotNull(where, NOTHING).joinToString(" "))
        val parts = ArrayList<String>()
        var cur = StringBuilder(where.orEmpty())
        for (s0 in sentences) {
            var s = s0
            if (parts.isEmpty() && cur.length < maxChars / 2 && cur.length + 1 + s.length > maxChars) {
                // Fill the first part with the start of this sentence, at a word; the rest begins the next part.
                val room = maxChars - cur.length - 1
                val cut = s.lastIndexOf(' ', room).takeIf { it > 0 } ?: room.coerceAtMost(s.length)
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s.substring(0, cut).trim())
                s = s.substring(cut).trim()
                if (s.isEmpty()) continue
            }
            if (cur.isNotEmpty() && cur.length + 1 + s.length > maxChars) { parts += cur.toString(); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) parts += cur.toString()
        return parts.mapIndexed { i, p -> if (i < parts.lastIndex) p + MORE else p }
    }

    private fun sentence(s: String) = if (s.last() in ".!?:;…") s else "$s."

    /** A text longer than one part is cut between words. */
    private fun split(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (w in s.split(Regex("\\s+"))) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > max) { out += cur.toString(); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

/**
 * The rest of a reading, for "continue". In memory only — never written anywhere — and only for the same screen and a
 * couple of minutes; a different screen starts the reading over.
 */
object ScreenReadCursor {
    private var rest: List<String> = emptyList()
    private var screen: String? = null
    private var until = 0L

    @Synchronized fun start(parts: List<String>, screenKey: String, now: Long = System.currentTimeMillis()): String {
        rest = parts.drop(1); screen = screenKey; until = now + 120_000L
        return parts.firstOrNull() ?: ScreenReadout.NOTHING
    }

    /** The next part when the reading continues on the same screen, else null (start over). */
    @Synchronized fun next(screenKey: String, now: Long = System.currentTimeMillis()): String? {
        if (rest.isEmpty() || screenKey != screen || now > until) return null
        val n = rest.first(); rest = rest.drop(1); until = now + 120_000L
        return n
    }

    val pending: Boolean @Synchronized get() = rest.isNotEmpty() && System.currentTimeMillis() <= until

    @Synchronized fun clear() { rest = emptyList(); screen = null; until = 0L }

    fun keyOf(snap: UiSnapshot): String = "${snap.appPackage}|${snap.screenTitle}"
}
