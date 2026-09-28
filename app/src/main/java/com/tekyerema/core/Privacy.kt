package com.tekyerema.core

import com.tekyerema.core.config.AppConfig
import java.io.File

/**
 * J-15 — data minimisation and retention, in one place:
 *  - how long each store keeps things (`privacy.retention.*Days`), with a sweep run at start-up;
 *  - what may reach logcat (release builds: no content — logcat is readable over USB debugging);
 *  - trace redaction ([TraceRedact]) so the per-task trace files keep the shape of a run without the numbers,
 *    amounts, message bodies or typed words in it.
 * Raw screenshots are never stored anywhere (they are OCR'd in memory and recycled).
 */
object Privacy {
    fun traceDays() = AppConfig.int("privacy.retention.traceDays", 7)
    fun historyDays() = AppConfig.int("privacy.retention.historyDays", 90)
    fun telemetryDays() = AppConfig.int("privacy.retention.telemetryDays", 30)
    fun trajectoryDays() = AppConfig.int("privacy.retention.trajectoryDays", 30)

    /** Full text in logcat? Debug builds yes (development); release builds only with an explicit config opt-in. */
    fun logContent(): Boolean = com.tekyerema.BuildConfig.DEBUG || AppConfig.bool("privacy.logcatContent", false)

    /**
     * Secure Input Mode is running: the user is typing a PIN / password / one-time code and the agent is blind. Set by
     * the engine for exactly that window. While it is set nothing the user says reaches logcat — not even in a debug
     * build — and speech is recognised on the phone only: the microphone is open just to hear "done", but a screen
     * reader may be speaking the digits being typed (a USSD PIN box is often not a password field), or the user may
     * murmur them. A hard boundary in code, not config.
     */
    @Volatile var secureStep: Boolean = false

    /**
     * [text] as it may appear in a log line: the words when content logging is allowed and no secure step is running,
     * otherwise only their length. Use for anything a person said, typed, or saw on screen (J-15).
     */
    fun content(text: String?, logAllowed: Boolean = logContent(), secure: Boolean = secureStep): String = when {
        text == null -> "null"
        logAllowed && !secure -> text
        else -> "(${text.length} chars)"
    }

    /** Redact trace files? Release builds always; debug builds when config asks (default: keep detail for development). */
    fun redactTraces(): Boolean = !com.tekyerema.BuildConfig.DEBUG || AppConfig.bool("privacy.retention.redactTraces", false)

    /** Delete files in [dir] older than [days] (0 or less = keep). Returns how many were removed. */
    fun sweep(dir: File, days: Int, nowMs: Long = System.currentTimeMillis()): Int {
        if (days <= 0 || !dir.isDirectory) return 0
        val cutoff = nowMs - days * 86_400_000L
        return dir.listFiles()?.count { f -> f.isFile && f.lastModified() < cutoff && f.delete() } ?: 0
    }

    /** Start-up sweep of every file-based store. */
    fun sweepAll(filesDir: File) {
        try {
            sweep(File(filesDir, "traces"), traceDays())
            sweep(File(filesDir, "trajectories"), trajectoryDays())
        } catch (_: Throwable) {}
    }
}

/**
 * Pure trace-line redaction (unit-tested). Keeps what a developer needs to follow a run — tool names, verification
 * verdicts, perception routes, reasons — and removes what identifies a person or their money: digit runs (phone
 * numbers, amounts, codes), the values of payload arguments (message, text, query, number, amount, contact), quoted
 * words, and any third-party text after the untrusted-data marker.
 */
object TraceRedact {
    private val PAYLOAD_ARG = Regex("\"(message|text|body|query|number|amount|contact|sender|recipient|reference|name|_recipient|_number|_amount)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"")
    private val QUOTED = Regex("[\"“]([^\"”]{1,200})[\"”]")
    private val DIGITS = Regex("\\+?\\d[\\d\\s-]{1,}\\d|\\d")

    fun line(kind: String, text: String, untrustedMark: String): String {
        var t = text
        val mark = t.indexOf(untrustedMark)
        if (mark >= 0) t = t.substring(0, mark) + "[third-party text removed]"
        // The user's own words and the spoken result: keep only their length.
        if (kind == "USER" || kind == "RESULT" || kind == "CONFIRM") return "[${t.length} chars]"
        t = PAYLOAD_ARG.replace(t) { m -> "\"${m.groupValues[1]}\":\"…\"" }
        t = QUOTED.replace(t, "\"…\"")
        t = DIGITS.replace(t, "#")
        return t
    }
}
