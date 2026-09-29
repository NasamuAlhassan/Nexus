package com.tekyerema.audit

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The activity log's hash chain (pure, unit-tested). Every entry carries the hash of the entry before it ([AuditEntry.prev])
 * and its own hash over a canonical text of all its fields, so changing, removing, inserting or reordering any entry breaks
 * [verify] from that point on. The first kept entry links to an ANCHOR: [GENESIS] for a fresh log, or the hash of the last
 * entry removed by the retention limit.
 *
 * The two fields that can hold personal words — [AuditEntry.text] and [AuditEntry.reason] (a reason may quote an on-screen
 * label such as a payee) — enter the hash only through a salted digest,
 * sha256(nonce + "\n" + len(text) + ":" + text + "\n" + len(reason) + ":" + reason), so an export with the words
 * redacted still verifies, and the digest can't be guessed back without the per-entry nonce, which is exported only
 * together with the words. In a redacted export the replacement words are marked `"redacted": true` and are not covered.
 *
 * Limits, stated plainly: the chain shows an edit, a removal from the middle, an insertion or a reordering. Someone who
 * recomputes every later hash, or cuts entries off the end, is caught only by comparing with the head the phone recorded
 * when the file was exported (an "exported your history" entry in the phone's own log carries it).
 *
 * Canonical form (v2), one "name=value" per line, in this order, values with "\" → "\\" and newline → "\n":
 *   v, t, task, step, source, kind, tool, tier, scope, policy, provenance, decidedBy, confirmSeq, approved, verified,
 *   outcome, content (= the digest), prev
 * hash = lowercase hex sha256 of that UTF-8 text. What this is NOT: protection against the app itself or someone holding
 * the phone's key — it makes edits to a stored or exported log detectable.
 */
object AuditChain {
    val GENESIS = "0".repeat(64)
    private val rng = SecureRandom()

    fun newNonce(): String = ByteArray(16).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Each part length-prefixed, so words can't be moved between text and reason without changing the digest. */
    fun contentDigest(nonce: String, text: String, reason: String): String =
        sha256(nonce + "\n" + text.length + ":" + text + "\n" + reason.length + ":" + reason)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")

    fun canonical(e: AuditEntry, contentDigest: String): String = listOf(
        "v=2", "t=${e.t}", "task=${e.task}", "step=${e.step}", "source=${esc(e.source)}", "kind=${esc(e.kind)}",
        "tool=${esc(e.tool)}", "tier=${esc(e.tier)}", "scope=${esc(e.scope)}", "policy=${esc(e.policy)}",
        "provenance=${esc(e.provenance)}", "decidedBy=${esc(e.decidedBy)}",
        "confirmSeq=${e.confirmSeq}", "approved=${e.approved ?: ""}", "verified=${e.verified ?: ""}",
        "outcome=${esc(e.outcome)}", "content=$contentDigest", "prev=${e.prev}"
    ).joinToString("\n")

    fun digestOf(e: AuditEntry): String = contentDigest(e.nonce, e.text, e.reason)

    fun hashOf(e: AuditEntry, contentDigest: String = digestOf(e)): String = sha256(canonical(e, contentDigest))

    /** [e] linked after [prev] and sealed with its own hash. */
    fun seal(e: AuditEntry, prev: String): AuditEntry {
        val linked = e.copy(prev = prev)
        return linked.copy(hash = hashOf(linked))
    }

    /** The result of checking a chain. [firstBad] is the index of the first entry that doesn't check out. */
    data class Check(
        val ok: Boolean,
        val checked: Int,
        val firstBad: Int? = null,
        val problem: String? = null,
        val head: String = GENESIS,
        /** Stored lines that could not even be decrypted (only for the on-phone log). */
        val unreadable: Int = 0
    ) {
        val intact: Boolean get() = ok && unreadable == 0
    }

    /** Walk [entries] from [anchor]: every link and every hash must match. */
    fun verify(entries: List<AuditEntry>, anchor: String): Check =
        walk(entries.size, anchor, { entries[it].prev }, { entries[it].hash }, { hashOf(entries[it]) })

    private fun walk(n: Int, anchor: String, prev: (Int) -> String, stored: (Int) -> String, computed: (Int) -> String): Check {
        var expected = anchor
        for (i in 0 until n) {
            if (prev(i) != expected) return Check(false, i, i, "entry ${i + 1} doesn't follow the one before it (an entry was removed, added or moved)", expected)
            val h = computed(i)
            if (h != stored(i)) return Check(false, i, i, "entry ${i + 1} was changed after it was written", expected)
            expected = h
        }
        return Check(true, n, head = expected)
    }

    /**
     * Verify an EXPORTED log (the "audit" object of an [AuditExport] document): entries as JSON carrying their
     * contentDigest; where the words and nonce are included, the digest must match them too.
     */
    fun verifyExport(audit: JSONObject): Check {
        val arr = audit.optJSONArray("entries") ?: JSONArray()
        val anchor = audit.optJSONObject("chain")?.optString("anchor")?.ifEmpty { null } ?: GENESIS
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
        for ((i, o) in list.withIndex()) {
            val redacted = o.optBoolean("redacted", false)
            // Words shown without their seal (the nonce removed) and not marked as redacted: they can't be checked, so fail.
            if (!redacted && !o.has("nonce") && (o.optString("text").isNotEmpty() || o.optString("reason").isNotEmpty()))
                return Check(false, i, i, "entry ${i + 1}'s words can't be checked (their seal was removed)")
            if (o.has("nonce") && contentDigest(o.getString("nonce"), o.optString("text"), o.optString("reason")) != o.optString("contentDigest"))
                return Check(false, i, i, "entry ${i + 1}'s words don't match what was recorded")
        }
        return walk(list.size, anchor, { list[it].optString("prev") }, { list[it].optString("hash") },
            { hashOf(AuditJson.fromJson(list[it]), list[it].optString("contentDigest")) })
    }
}

/** Entry ↔ JSON, shared by the on-phone log and the export. Short keys keep the stored log small. */
internal object AuditJson {
    /** [includeContent] = false leaves out the words (text, reason) and their nonce. */
    fun toJson(e: AuditEntry, includeContent: Boolean = true): JSONObject {
        val o = JSONObject().put("t", e.t).put("task", e.task).put("src", e.source).put("kind", e.kind)
        if (e.step != 0) o.put("step", e.step)
        if (e.tool.isNotEmpty()) o.put("tool", e.tool)
        if (e.tier.isNotEmpty()) o.put("tier", e.tier)
        if (e.scope.isNotEmpty()) o.put("scope", e.scope)
        if (e.policy.isNotEmpty()) o.put("policy", e.policy)
        if (e.provenance.isNotEmpty()) o.put("prov", e.provenance)
        if (e.decidedBy.isNotEmpty()) o.put("by", e.decidedBy)
        if (e.confirmSeq != 0L) o.put("seq", e.confirmSeq)
        e.approved?.let { o.put("approved", it) }
        e.verified?.let { o.put("verified", it) }
        if (e.outcome.isNotEmpty()) o.put("out", e.outcome)
        if (includeContent) { o.put("text", e.text); o.put("reason", e.reason); o.put("nonce", e.nonce) }
        o.put("prev", e.prev).put("hash", e.hash)
        return o
    }

    fun fromJson(o: JSONObject): AuditEntry = AuditEntry(
        t = o.optLong("t"), task = o.optLong("task"), source = o.optString("src"), kind = o.optString("kind"),
        text = o.optString("text"), step = o.optInt("step", 0), tool = o.optString("tool"), tier = o.optString("tier"),
        scope = o.optString("scope"), policy = o.optString("policy"), reason = o.optString("reason"),
        provenance = o.optString("prov"), decidedBy = o.optString("by"), confirmSeq = o.optLong("seq", 0L),
        approved = if (o.has("approved")) o.optBoolean("approved") else null,
        verified = if (o.has("verified")) o.optBoolean("verified") else null,
        outcome = o.optString("out"), nonce = o.optString("nonce"), prev = o.optString("prev"), hash = o.optString("hash")
    )
}
