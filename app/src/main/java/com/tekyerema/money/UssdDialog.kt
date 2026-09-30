package com.tekyerema.money

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.TelephonyWindows
import kotlinx.coroutines.delay

/**
 * Reading and answering the phone's own USSD dialog, for the guided bank menu ([BankUssdTool]). The same approach as the
 * live-tested *170# driver (tools/MoMoSendTool: wait for the old dialog to clear, then the next one; type, then press the
 * dialog's own Send), kept separate so the MoMo driver is untouched. The entry box's contents never reach the snapshot
 * (TelephonyWindows redaction), so nothing typed — least of all a PIN — is read back.
 */
internal object UssdDialog {
    data class Screen(val text: String, val inputId: Int?, val sendId: Int?, val cancelId: Int?, val masked: Boolean) {
        val hasInput: Boolean get() = inputId != null
    }

    private val SUBMIT = Regex("(?i)^(send|ok|submit|reply|answer)$")
    private val CANCEL = Regex("(?i)^(cancel|dismiss|close)$")
    private val CLOSE_OK = Regex("(?i)^(ok|dismiss|close|done)$")
    /** The dialer's own "USSD code running…" progress — the gap between two screens, not a screen. */
    private val RUNNING = Regex("(?i)ussd code running|running ussd|please wait|sending request")

    /** The USSD dialog on screen now, or null (none, the progress spinner, or not a phone window). */
    suspend fun read(ctrl: AccessibilityController): Screen? {
        val snap = try { ctrl.buildSnapshot() } catch (_: Throwable) { return null }
        // Only the phone app's own window is the USSD dialog — the app underneath must never be read (or typed
        // into) as if it were the bank's menu (audit 2026-09-30, half 2 #2).
        if (!TelephonyWindows.matches(snap.appPackage)) {
            if (com.tekyerema.BuildConfig.DEBUG) android.util.Log.d("Tekyerema", "[USSD] not a phone window: ${snap.appPackage}")
            return null
        }
        val labels = ArrayList<String>()
        snap.screenTitle?.takeIf { it.isNotBlank() }?.let { labels += it }
        var inputId: Int? = null
        var sendId: Int? = null
        var cancelId: Int? = null
        var okOnly: Int? = null
        for (e in snap.elements) {
            val l = e.label?.trim().orEmpty()
            when {
                e.editable -> if (inputId == null) inputId = e.id
                e.clickable && SUBMIT.matches(l) -> { if (sendId == null) sendId = e.id; if (CLOSE_OK.matches(l) && okOnly == null) okOnly = e.id }
                e.clickable && CANCEL.matches(l) -> { if (cancelId == null) cancelId = e.id; if (CLOSE_OK.matches(l) && okOnly == null) okOnly = e.id }
                l.isNotBlank() -> labels += l
            }
        }
        val text = labels.joinToString("\n").trim()
        if (text.isBlank()) return null
        val masked = snap.elements.any { it.password }
        if (inputId != null) return Screen(text, inputId, sendId, cancelId, masked)
        // No entry box: only the phone's own window with an OK/Close button is a last message ("Request received"); the
        // launcher or the running spinner is not.
        if (!TelephonyWindows.matches(snap.appPackage) || okOnly == null || RUNNING.containsMatchIn(text)) return null
        return Screen(text, null, null, cancelId ?: okOnly, masked)
    }

    /** The first dialog after [prevText]'s cleared (or with new words), within [timeoutMs]; null = nothing came. */
    suspend fun awaitNext(ctrl: AccessibilityController, prevText: String, timeoutMs: Long): Screen? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var sawGap = prevText.isBlank()
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            delay(500)
            val s = read(ctrl)
            if (s == null) { sawGap = true; continue }
            if (sawGap || s.text != prevText) return s
        }
        return null
    }

    /** The dialog as it stands, waiting briefly for it — one immediate read misses it while the phone app is still
     *  settling (A20e 2026-09-30: the guided walk's reply read once, saw another window, and wrongly said the menu
     *  had closed). Null only after [timeoutMs] of genuinely no dialog. */
    suspend fun awaitCurrent(ctrl: AccessibilityController, timeoutMs: Long = 5_000L): Screen? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            read(ctrl)?.let { return it }
            if (android.os.SystemClock.uptimeMillis() >= deadline) return null
            delay(400)
        }
    }

    /** Type [value] into the dialog's box and press its Send (or the box's own submit). False when it has no box. */
    suspend fun answer(ctrl: AccessibilityController, s: Screen, value: String): Boolean {
        val id = s.inputId ?: return false
        val send = s.sendId
        return if (send != null) { ctrl.setText(id, value, submit = false); delay(250); ctrl.clickElement(send) }
        else ctrl.setText(id, value, submit = true)
    }
}
