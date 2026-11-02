package com.tekyerema.memory

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.TelephonyWindows
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Who an unlabelled control is, without any screen text in the clear (#38 label memory).
 *  - [viewId]: the control's resource id, when it is the ONLY element on screen with that id (then nothing else is used).
 *  - otherwise [role] + a coarse place ([hBand]/[vBand], thirds of the screen) + [anchor] (the nearest named neighbour as
 *    "left:/right:/above:/below:" + a hash of its words) + [ordinal]/[groupSize] (its place among same-class unlabelled
 *    controls in its row). With no neighbour the screen title ([screen], hashed) stands in for the anchor.
 * Neighbour words and the title are stored as short hashes: what the screen said (a contact's name, a message) is not
 * kept, only enough to tell the same control again.
 */
data class LabelSig(
    val pkg: String,
    val viewId: String?,
    val role: String,
    val hBand: Int,
    val vBand: Int,
    val anchor: String?,
    val ordinal: Int,
    val groupSize: Int,
    val screen: String?
)

/**
 * Pure logic for label memory: which controls may be named, their [LabelSig], whether two signatures are the same control,
 * and applying remembered names to a snapshot. No Android. Rules: when unsure, don't apply — a control that cannot be told
 * from its neighbours, a screen that is private, a list row, anything on a scrolling surface without a resource id.
 */
object LabelSignature {
    private val DIGIT_KEY = Regex("""^\d$""")
    private val NEVER_PKG = setOf("com.android.systemui", "com.tekyerema")
    private const val MAX_GROUP = 6

    /** A short stable hash of [text] (lower case, trimmed): 8 hex characters. */
    fun hash(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.trim().lowercase().toByteArray(Charsets.UTF_8))
        return d.take(4).joinToString("") { "%02x".format(it) }
    }

    /** May this screen carry learned names at all? Not a private screen, a phone/USSD window, a keypad, the keyboard or a pop-up window. */
    fun usable(snap: UiSnapshot): Boolean {
        val pkg = snap.appPackage ?: return false
        if (snap.secureWindow || snap.windowType != null || snap.webContentMissing) return false
        if (pkg in NEVER_PKG || TelephonyWindows.matches(pkg)) return false
        if (snap.elements.any { it.password }) return false
        if (snap.elements.count { it.clickable && it.label?.trim()?.matches(DIGIT_KEY) == true } >= 8) return false
        return try { !com.tekyerema.policy.TargetSemantics.isCredentialScreen(snap) } catch (_: Throwable) { false }
    }

    /** Without a name of its own: no label, or one that memory put there. */
    internal fun isUnnamed(e: UiElement): Boolean = e.label.isNullOrBlank() || e.learned

    private fun inside(a: Box, b: Box) = a.left >= b.left && a.top >= b.top && a.right <= b.right && a.bottom <= b.bottom

    private fun sameRow(a: Box, b: Box): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlap > 0 && overlap * 2 >= minOf(a.height, b.height)
    }

    /** Horizontal gap between two boxes, or -1 when they overlap horizontally. */
    private fun hGap(a: Box, b: Box): Int = when {
        a.right <= b.left -> b.left - a.right
        b.right <= a.left -> a.left - b.right
        else -> -1
    }

    /** Vertical gap between two boxes, or -1 when they overlap vertically. */
    private fun vGap(a: Box, b: Box): Int = when {
        a.bottom <= b.top -> b.top - a.bottom
        b.bottom <= a.top -> a.top - b.bottom
        else -> -1
    }

    /** The extent of the screen as the tree shows it (the snapshot carries no display size). */
    fun extent(snap: UiSnapshot): Pair<Int, Int> {
        val tree = snap.elements.filter { it.source == ElementSource.TREE }
        return maxOf(tree.maxOfOrNull { it.bounds.right } ?: 0, 1) to maxOf(tree.maxOfOrNull { it.bounds.bottom } ?: 0, 1)
    }

    /** Top to bottom by row, left to right within a row: the order a person would count buttons in. */
    fun readingOrder(list: List<UiElement>): List<UiElement> {
        val rows = ArrayList<MutableList<UiElement>>()
        for (e in list.sortedBy { it.bounds.centerY }) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row[0].bounds, e.bounds)) row.add(e) else rows.add(mutableListOf(e))
        }
        return rows.flatMap { r -> r.sortedBy { it.bounds.left } }
    }

    private fun isCandidate(snap: UiSnapshot, e: UiElement, w: Int, h: Int, folded: Set<Int>): Boolean {
        if (e.source != ElementSource.TREE || e.pkg != null) return false
        if (!isUnnamed(e)) return false
        if (!(e.clickable || e.longClickable)) return false
        if (e.editable || e.password || e.checkable || e.scrollable || e.range != null || !e.enabled) return false
        if (e.unseen != null || e.bounds.isEmpty() || e.id in folded) return false
        // a full-width bar or a big tappable layer is a surface, not an icon button
        if (e.bounds.width * 2 > w || e.bounds.height * 4 > h) return false
        // list rows come and go with scrolling: never named
        if (snap.elements.any { c -> c.id != e.id && c.collection != null && inside(e.bounds, c.bounds) }) return false
        return true
    }

    /** Every control on [snap] that could be given a name, in reading order (empty on a screen that may not carry names). */
    fun candidates(snap: UiSnapshot): List<UiElement> {
        if (!usable(snap)) return emptyList()
        val (w, h) = extent(snap)
        val folded = snap.foldedRowParts()
        return readingOrder(snap.elements.filter { isCandidate(snap, it, w, h, folded) })
    }

    /** The candidates that have no name at all right now (what "the third unnamed button" counts), in reading order. */
    fun nameless(snap: UiSnapshot): List<UiElement> = candidates(snap).filter { it.label.isNullOrBlank() && !it.learned }

    private fun screenKey(snap: UiSnapshot): String? {
        val t = snap.screenTitle?.trim()?.lowercase()?.replace(Regex("""\s+"""), " ") ?: return null
        if (t.length !in 2..40 || t.any { it.isDigit() }) return null
        return hash(t)
    }

    /** The words of a neighbour that is part of the app's chrome (a box's hint, a button or heading caption); null for anything else. */
    private fun anchorText(snap: UiSnapshot, a: UiElement): String? {
        if (a.source != ElementSource.TREE || a.pkg != null || a.password || a.learned || a.unseen != null) return null
        val raw = when {
            a.editable -> a.hint
            a.clickable || a.heading -> a.label
            else -> null
        } ?: return null
        val t = raw.trim().lowercase().replace(Regex("""\s+"""), " ")
        if (t.length !in 2..24 || t.any { it.isDigit() } || t.split(' ').size > 3) return null
        if (snap.elements.any { c -> c.id != a.id && c.collection != null && inside(a.bounds, c.bounds) }) return null
        return t
    }

    private fun anchorOf(snap: UiSnapshot, e: UiElement, h: Int): String? {
        val pool = snap.elements.filter { it.id != e.id }.mapNotNull { a -> anchorText(snap, a)?.let { a to it } }
        // 1. the nearest neighbour in the same row; 2. the nearest above or below it. A tie between different words is no anchor.
        val row = pool.filter { (a, _) -> sameRow(a.bounds, e.bounds) && hGap(a.bounds, e.bounds) >= 0 }
        pickNearest(row) { (a, _) -> hGap(a.bounds, e.bounds) }?.let { (a, t) ->
            return (if (a.bounds.right <= e.bounds.left) "left:" else "right:") + hash(t)
        }
        if (row.isNotEmpty()) return null
        val col = pool.filter { (a, _) -> a.bounds.left < e.bounds.right && e.bounds.left < a.bounds.right && vGap(a.bounds, e.bounds) in 0..(h / 4) }
        pickNearest(col) { (a, _) -> vGap(a.bounds, e.bounds) }?.let { (a, t) ->
            return (if (a.bounds.bottom <= e.bounds.top) "above:" else "below:") + hash(t)
        }
        return null
    }

    private fun pickNearest(list: List<Pair<UiElement, String>>, gap: (Pair<UiElement, String>) -> Int): Pair<UiElement, String>? {
        if (list.isEmpty()) return null
        val best = list.minOf(gap)
        val tied = list.filter { gap(it) == best }
        return if (tied.map { it.second }.distinct().size == 1) tied[0] else null
    }

    /**
     * The signature of each control that may be named and can be told from the rest of its screen, by element id. A control is
     * left out when unsure: no resource id and no named neighbour or stable title, a crowded row (more than 6 alike), a
     * scrolling surface without a resource id, or another control on the same screen has the same signature.
     */
    fun signatures(snap: UiSnapshot): Map<Int, LabelSig> {
        val pkg = snap.appPackage ?: return emptyMap()
        val cands = candidates(snap)
        if (cands.isEmpty()) return emptyMap()
        val (w, h) = extent(snap)
        val screen = screenKey(snap)
        val idCount = snap.elements.mapNotNull { it.viewId?.takeIf { v -> v.isNotBlank() } }.groupingBy { it }.eachCount()
        val out = LinkedHashMap<Int, LabelSig>()
        for (e in cands) {
            val hb = (e.bounds.centerX * 3 / w).coerceIn(0, 2)
            val vb = (e.bounds.centerY * 3 / h).coerceIn(0, 2)
            val vid = e.viewId?.takeIf { it.isNotBlank() && idCount[it] == 1 }
            if (vid != null) { out[e.id] = LabelSig(pkg, vid, e.role, hb, vb, null, 0, 0, null); continue }
            if (snap.elements.any { it.scrollable && it.id != e.id && inside(e.bounds, it.bounds) }) continue
            val group = readingOrder(cands.filter { it.role == e.role && sameRow(it.bounds, e.bounds) })
            if (group.size > MAX_GROUP) continue
            val anchor = anchorOf(snap, e, h)
            val scr = if (anchor == null) screen else null
            if (anchor == null && scr == null) continue
            out[e.id] = LabelSig(pkg, null, e.role, hb, vb, anchor, group.indexOfFirst { it.id == e.id }, group.size, scr)
        }
        val ids = out.keys.toList()
        val drop = HashSet<Int>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            if (matches(out.getValue(ids[i]), out.getValue(ids[j]))) { drop.add(ids[i]); drop.add(ids[j]) }
        }
        drop.forEach { out.remove(it) }
        return out
    }

    /**
     * Is [live] the control [stored] was made for? With a resource id: the same id and class. Without: the same class, neighbour
     * (or title), place in its row and size of that row; the thirds of the screen may differ by one when a neighbour anchors it
     * (small layout changes), not otherwise.
     */
    fun matches(stored: LabelSig, live: LabelSig): Boolean {
        if (stored.pkg != live.pkg || stored.role != live.role) return false
        if (stored.viewId != null || live.viewId != null) return stored.viewId == live.viewId
        if (stored.anchor != live.anchor || stored.screen != live.screen) return false
        if (stored.ordinal != live.ordinal || stored.groupSize != live.groupSize) return false
        val tol = if (stored.anchor != null) 1 else 0
        return abs(stored.hBand - live.hBand) <= tol && abs(stored.vBand - live.vBand) <= tol
    }

    /**
     * [snap] with the remembered names put on the controls they belong to: `label` filled and `learned = true`. A control gets a
     * name only when exactly one name fits it and no other control on the screen fits the same entry. Otherwise unchanged.
     */
    fun apply(snap: UiSnapshot, entries: List<LabelEntry>): UiSnapshot {
        val pkg = snap.appPackage ?: return snap
        val mine = entries.filter { it.sig.pkg == pkg }
        if (mine.isEmpty() || snap.elements.isEmpty()) return snap
        val sigs = signatures(snap)
        if (sigs.isEmpty()) return snap
        val hits = HashMap<Int, List<LabelEntry>>()
        for ((id, sig) in sigs) {
            val m = mine.filter { matches(it.sig, sig) }
            if (m.isNotEmpty() && m.map { it.name.lowercase() }.distinct().size == 1) hits[id] = m
        }
        if (hits.isEmpty()) return snap
        val perEntry = HashMap<LabelEntry, MutableList<Int>>()
        for ((id, m) in hits) for (en in m) perEntry.getOrPut(en) { mutableListOf() }.add(id)
        val ambiguous = perEntry.values.filter { it.size > 1 }.flatten().toSet()
        val names = hits.filterKeys { it !in ambiguous }.mapValues { it.value.first().name }
        if (names.isEmpty()) return snap
        return snap.copy(elements = snap.elements.map { e ->
            val n = names[e.id]
            if (n != null && isUnnamed(e)) e.copy(label = n, learned = true) else e
        })
    }

    /**
     * The one nameless control beside the control whose box or caption has [anchorWords] ("message" for "the message box"), on
     * [side] ("left"/"right" of it) or either side when null. Null when there is no such neighbour or it is not clear which.
     */
    fun beside(snap: UiSnapshot, anchorWords: String, side: String?): UiElement? {
        val want = anchorWords.trim().lowercase().replace(Regex("""\s+"""), " ")
        if (want.length < 2) return null
        val cands = nameless(snap)
        if (cands.isEmpty()) return null
        val anchors = snap.elements.filter { a ->
            a.source == ElementSource.TREE && !a.password && !a.learned &&
                (if (a.editable) a.hint else a.label)?.lowercase()?.contains(want) == true
        }
        var best: UiElement? = null
        var bestGap = Int.MAX_VALUE
        var tie = false
        for (a in anchors) for (c in cands) {
            if (c.id == a.id || !sameRow(a.bounds, c.bounds)) continue
            val gap = hGap(a.bounds, c.bounds)
            if (gap < 0) continue
            val cIsRight = c.bounds.left >= a.bounds.right
            if (side == "right" && !cIsRight) continue
            if (side == "left" && cIsRight) continue
            if (gap < bestGap) { best = c; bestGap = gap; tie = false }
            else if (gap == bestGap && best?.id != c.id) tie = true
        }
        return if (tie) null else best
    }
}
