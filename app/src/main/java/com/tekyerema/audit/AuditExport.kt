package com.tekyerema.audit

import android.content.Context
import android.content.Intent
import com.tekyerema.history.HistoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WS-C — the user's own copy of their history and activity log, as one JSON file handed to the Android share sheet
 * (Tɛkyerɛma never uploads it). By default personal words are REDACTED by the same rules as the trace files
 * ([com.tekyerema.core.TraceRedact]): the user's own words and the confirmation read-backs keep only their length;
 * everything else loses quoted text, digits (numbers, amounts, codes) and e-mail addresses. The user can choose
 * "include message text". Either way the activity log's hash chain verifies ([AuditChain.verifyExport]): redaction drops
 * only the words and their nonce, and the chain covers their salted digest.
 */
object AuditExport {
    const val DIR = "exports"
    const val FORMAT = "tekyerema-export"

    private val DOUBLE_QUOTED = Regex("[\"“]([^\"”]{1,200})[\"”]")
    /** 'like this' — but not the apostrophe in "don't" (the quote must open after a space and close before one). */
    private val SINGLE_QUOTED = Regex("(^|\\s)[‘']([^’']{1,200})[’'](?=[\\s.,?!;:]|$)")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
    private val DIGITS = Regex("\\+?\\d[\\d\\s-]*\\d|\\d")

    /** Kinds whose text is the user's own words or a read-back of what they were asked (length only, like TraceRedact). */
    private val WORDS_KINDS = setOf(AuditLog.TASK_START, AuditLog.APPROVED, AuditLog.DECLINED)

    /** Quoted text, e-mail addresses and digits out; the shape stays ("tapped “…”", "sent # cedis"). */
    fun redact(text: String): String {
        if (text.isBlank()) return text
        var t = DOUBLE_QUOTED.replace(text, "“…”")
        t = SINGLE_QUOTED.replace(t) { m -> m.groupValues[1] + "“…”" }
        t = EMAIL.replace(t, "…@…")
        return DIGITS.replace(t, "#")
    }

    /** How an entry's words appear in a redacted export. */
    fun redactEntryText(kind: String, text: String): String =
        if (kind in WORDS_KINDS && text.isNotEmpty()) "[${text.length} chars]" else redact(text)

    /** The export document (pure). [operator] is the snapshot of the user's choices (no keys). */
    fun build(
        entries: List<AuditEntry>,
        anchor: String,
        history: List<HistoryEntry>,
        includeText: Boolean,
        operator: JSONObject?,
        now: Long
    ): JSONObject {
        val check = AuditChain.verify(entries, anchor)
        val arr = JSONArray()
        for (e in entries) {
            val o = AuditJson.toJson(e, includeContent = includeText)
            o.put("time", iso(e.t)).put("contentDigest", AuditChain.digestOf(e))
            if (!includeText) o.put("text", redactEntryText(e.kind, e.text)).put("reason", redact(e.reason)).put("redacted", true)
            arr.put(o)
        }
        val hist = JSONArray()
        for (h in history) hist.put(JSONObject().put("time", iso(h.timeMs)).put("t", h.timeMs)
            .put("title", if (includeText) h.title else "[${h.title.length} chars]")
            .put("kind", kindOf(h.title)).put("outcome", h.outcome.name))
        return JSONObject()
            .put("format", FORMAT).put("version", 1)
            .put("exportedAt", iso(now))
            .put("includesMessageText", includeText)
            .put("note", if (includeText) "Includes the words of your requests and messages. Keep this file private."
                else "Your own words are shown only as their length; quoted text, numbers and e-mail addresses are replaced.")
            .put("audit", JSONObject()
                .put("chain", JSONObject()
                    .put("algorithm", "sha256")
                    .put("canonical", "one name=value line each, in order: v=2,t,task,step,source,kind,tool,tier,scope,policy," +
                        "provenance,decidedBy,confirmSeq,approved,verified,outcome,content=contentDigest,prev; values escape \\ and newline")
                    .put("contentDigest", "sha256(nonce + \"\\n\" + text + \"\\n\" + reason); the nonce is exported only with the words")
                    .put("anchor", anchor).put("head", check.head).put("entries", entries.size)
                    .put("verifiedOnExport", check.ok))
                .put("entries", arr))
            .put("history", hist)
            .apply { if (operator != null) put("settings", operator) }
    }

    /** A coarse kind for a history title, so a redacted export keeps its shape. */
    fun kindOf(title: String): String {
        val t = title.lowercase()
        fun has(vararg w: String) = w.any { Regex("\\b$it").containsMatchIn(t) }
        return when {
            has("call", "phone", "dial") -> "call"
            has("message", "text", "sms", "whatsapp", "send", "sent", "repl", "email") -> "message"
            has("money", "momo", "pay", "cedi", "transfer") -> "money"
            has("alarm", "timer", "remind") -> "alarm"
            has("wi-fi", "wifi", "bluetooth", "volume", "bright", "setting", "mode") -> "setting"
            has("search", "what", "who", "when", "how", "why") -> "question"
            has("open", "launch") -> "app"
            else -> "other"
        }
    }

    private fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /**
     * Write the export to the app's cache (older exports removed) and open the share sheet. False when the file couldn't
     * be written or no app can receive it.
     */
    fun share(ctx: Context, includeText: Boolean): Boolean {
        val app = ctx.applicationContext
        return try {
            val doc = build(AuditLog.all(), AuditLog.anchor(), com.tekyerema.history.HistoryStore.entries.value, includeText,
                com.tekyerema.operator.OperatorPolicy.toJson(), System.currentTimeMillis())
            val dir = File(app.cacheDir, DIR).apply { deleteRecursively(); mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
            val file = File(dir, "tekyerema-history-$stamp.json").apply { writeText(doc.toString(2)) }
            val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".exports", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Tɛkyerɛma history and activity log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share your Tɛkyerɛma history").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(chooser)
            AuditLog.record("user", AuditLog.DONE, if (includeText) "exported your history with message text" else "exported your history",
                AuditLog.newTask())
            true
        } catch (_: Throwable) { false }
    }
}
