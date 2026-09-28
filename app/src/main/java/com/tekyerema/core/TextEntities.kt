package com.tekyerema.core

import android.content.Context
import android.os.Build

/**
 * E-23 — phone numbers, web links, e-mail addresses, street addresses and dates inside a piece of text (a message,
 * a notification, a web answer), so the next request can act on them: "call that number", "open that link",
 * "when is it?". On Android 9+ the system's on-device [android.view.textclassifier.TextClassifier] finds them
 * (nothing leaves the phone); below that, or if it fails, a small pattern set does ([detectPatterns], unit-tested).
 * Never call on the main thread — the system classifier can block.
 */
object TextEntities {
    data class Entity(val type: String, val text: String)

    const val PHONE = "phone"; const val URL = "url"; const val EMAIL = "email"; const val ADDRESS = "address"; const val DATE = "date"

    private const val MAX_CHARS = 2000

    fun detect(ctx: Context?, text: String): List<Entity> {
        val t = text.take(MAX_CHARS)
        if (t.isBlank()) return emptyList()
        if (ctx != null && Build.VERSION.SDK_INT >= 28 &&
            com.tekyerema.core.config.AppConfig.bool("os.textClassifier.enabled", true)) {
            try { return system(ctx, t) } catch (_: Throwable) { /* fall back to patterns */ }
        }
        return detectPatterns(t)
    }

    @androidx.annotation.RequiresApi(28)
    private fun system(ctx: Context, text: String): List<Entity> {
        val tcm = ctx.getSystemService(android.view.textclassifier.TextClassificationManager::class.java)
            ?: return detectPatterns(text)
        val links = tcm.textClassifier.generateLinks(android.view.textclassifier.TextLinks.Request.Builder(text).build())
        val out = ArrayList<Entity>()
        for (link in links.links) {
            if (link.entityCount == 0) continue
            val type = when (link.getEntity(0)) {
                android.view.textclassifier.TextClassifier.TYPE_PHONE -> PHONE
                android.view.textclassifier.TextClassifier.TYPE_URL -> URL
                android.view.textclassifier.TextClassifier.TYPE_EMAIL -> EMAIL
                android.view.textclassifier.TextClassifier.TYPE_ADDRESS -> ADDRESS
                android.view.textclassifier.TextClassifier.TYPE_DATE, android.view.textclassifier.TextClassifier.TYPE_DATE_TIME -> DATE
                else -> null
            } ?: continue
            val s = text.substring(link.start.coerceIn(0, text.length), link.end.coerceIn(0, text.length)).trim()
            if (s.isNotEmpty()) out.add(Entity(type, s))
        }
        // The system classifier's date and phone coverage varies by OEM; patterns fill what it missed.
        return (out + detectPatterns(text)).distinctBy { it.type + "|" + norm(it) }
    }

    private val PHONE_RX = Regex("(?<![\\d])(?:\\+?233[\\s-]?|0)(?:\\d[\\s-]?){8,9}\\d(?![\\d])")
    private val URL_RX = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>()\"']+|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|gov|edu|gh|co\\.uk|io)(?:/[^\\s<>()\"']*)?\\b")
    private val EMAIL_RX = Regex("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b")
    private val DATE_RX = Regex("(?i)\\b(?:(?:mon|tues|wednes|thurs|fri|satur|sun)day|today|tomorrow|tonight)\\b(?:\\s+(?:at\\s+)?\\d{1,2}(?:[:.]\\d{2})?\\s*(?:[ap]\\.?m\\.?)?)?|" +
        "\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\b(?:\\s+\\d{4})?|" +
        "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\s+\\d{1,2}(?:st|nd|rd|th)?\\b(?:,?\\s+\\d{4})?|" +
        "\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b")

    /** Pure pattern fallback: Ghana phone numbers, links, e-mails and common date phrases, in text order. */
    fun detectPatterns(text: String): List<Entity> {
        val found = ArrayList<Pair<Int, Entity>>()
        val emails = EMAIL_RX.findAll(text).toList()
        emails.forEach { found.add(it.range.first to Entity(EMAIL, it.value)) }
        PHONE_RX.findAll(text).forEach { found.add(it.range.first to Entity(PHONE, it.value.trim())) }
        URL_RX.findAll(text).filter { m -> emails.none { e -> m.range.first in e.range } }
            .forEach { found.add(it.range.first to Entity(URL, it.value.trimEnd('.', ',', ';', ':'))) }
        DATE_RX.findAll(text).forEach { found.add(it.range.first to Entity(DATE, it.value.trim())) }
        return found.sortedBy { it.first }.map { it.second }.distinctBy { it.type + "|" + norm(it) }
    }

    private fun norm(e: Entity) = if (e.type == PHONE) e.text.filter { it.isDigit() } else e.text.lowercase()

    /** One short line for the planner's history ("found: phone 0244123456; link graphic.com.gh"), or "" if none. */
    fun summary(entities: List<Entity>, max: Int = 4): String {
        if (entities.isEmpty()) return ""
        return "found: " + entities.take(max).joinToString("; ") { e ->
            val label = when (e.type) { URL -> "link"; else -> e.type }
            "$label " + (if (e.type == PHONE) e.text.filter { it.isDigit() || it == '+' } else e.text.take(60))
        }
    }
}
