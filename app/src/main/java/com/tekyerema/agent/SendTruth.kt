package com.tekyerema.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.journal.EffectKeys
import com.tekyerema.tools.SendEvidence

/**
 * Ground truth for a send before the task gives up with "I couldn't see the result on the screen" (owner, 8 Nov 2026: "most
 * of the time the action is done, yet it says it couldn't confirm it" — an SMS had gone, the carrier's popup covered the chat,
 * and the reply was that sentence).
 *
 *  - An SMS: the phone's own sent box is the witness (the system writes the row when the text leaves; Nexus is not the
 *    default SMS app, so it never writes there itself).
 *  - Telegram / WhatsApp / Messages on screen: a bubble holding the words with the app's own "sent" marker ("Sent at 08:06",
 *    "Delivered", a tick) — the text left the box and the app says it went.
 *
 * Returns the plain line to say ("Sent to Prince.") or null when nothing confirms it — then the honest sentence stands.
 * The screen part is pure; the SMS part reads the provider.
 */
object SendTruth {
    private val SMS_APPS = setOf("com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms", "com.android.messaging")
    private val SENT_MARK = Regex("(?i)\\bsent at \\d{1,2}:\\d{2}|\\b(?:sent|delivered|read|seen|not seen)\\b\\s*$|\\bdelivered\\b")

    fun confirm(ctx: Context, sent: SendReport.Sent?, typed: String?, screen: UiSnapshot?, now: Long = System.currentTimeMillis()): String? {
        val text = (sent?.text ?: typed)?.trim()?.takeIf { it.length >= 2 } ?: return null
        val who = sent?.who ?: EffectKeys.chatTitle(screen)
        val pkg = screen?.appPackage.orEmpty()
        if (pkg in SMS_APPS || sent?.app?.contains("messages", ignoreCase = true) == true) {
            if (sentSmsSince(ctx, text, now - 90_000L)) return line(who)
        }
        screen ?: return null
        if (onScreen(screen, text)) return line(who)
        return null
    }

    /** A bubble on [screen] holding [text] together with the app's own sent marker (pure). */
    internal fun onScreen(screen: UiSnapshot, text: String): Boolean {
        val want = SendEvidence.norm(text).lowercase().trimEnd('.', '!')
        if (want.isEmpty()) return false
        if (SendEvidence.sentOnScreen(SendEvidence.check(null, screen, text))) return true
        return screen.elements.any { e ->
            !e.editable && e.label?.let { l -> SendEvidence.norm(l).lowercase().contains(want) && SENT_MARK.containsMatchIn(l) } == true
        }
    }

    private fun line(who: String?) = if (who.isNullOrBlank()) "Sent." else "Sent to $who."

    /** The text is in the phone's sent / outbox since [since] (body compared without case, spacing or final punctuation). */
    fun sentSmsSince(ctx: Context, body: String, since: Long): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return false
        val want = key(body)
        return try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getInt(1)
                    if (type != Telephony.Sms.MESSAGE_TYPE_SENT && type != Telephony.Sms.MESSAGE_TYPE_OUTBOX && type != Telephony.Sms.MESSAGE_TYPE_QUEUED) continue
                    if (key(c.getString(0).orEmpty()) == want) return true
                }
                false
            } ?: false
        } catch (_: Throwable) { false }
    }

    private fun key(s: String) = s.lowercase().replace(Regex("[’']"), "'").replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim()
}
