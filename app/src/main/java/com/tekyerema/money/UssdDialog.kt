package com.tekyerema.money

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.TelephonyWindows
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    /** Close [s] (its Cancel / OK, else Back) and check it went. Closing a USSD session never moves money. */
    suspend fun close(ctrl: AccessibilityController, s: Screen): Boolean {
        try { s.cancelId?.let { ctrl.clickElement(it) } ?: ctrl.globalAction("back") } catch (_: Throwable) {}
        delay(900)
        var now = read(ctrl)
        if (now != null && now.text == s.text) {
            try { ctrl.globalAction("back") } catch (_: Throwable) {}
            delay(900)
            now = read(ctrl)
        }
        return now == null || now.text != s.text
    }

    /**
     * A dialog left on the screen from an earlier session is closed BEFORE a new code is dialled — otherwise its old
     * screen is read as the new menu's first and an answer goes into the wrong session (A20e, 2 October 2026: a Mashup
     * dialog left open was taken for the next walk's *567# menu). The old text is returned, so the wait for the new
     * dialog can skip it if it lingers; null when nothing was open.
     */
    suspend fun clearStale(ctrl: AccessibilityController): String? {
        val old = read(ctrl) ?: return null
        android.util.Log.i("Tekyerema", "[USSD] an earlier dialog was still open — closing it before dialling")
        close(ctrl, old)
        read(ctrl)?.let { close(ctrl, it) }
        return old.text
    }

    /** "Connection problem or invalid MMI code." — the network failed the request before any menu (nothing was chosen):
     *  one redial is safe. */
    val NEVER_STARTED = Regex("(?i)connection problem|invalid mmi|mmi code|ussd code (?:failed|cancelled)|network (?:busy|not available)")

    /**
     * Dial [code] and return its FIRST screen (null = nothing came in [timeoutMs]). Any dialog still open is closed first
     * ([clearStale]); a first screen saying the request never started ("Connection problem or invalid MMI code", seen on
     * the A20e on 2 October 2026 — the retry a minute later went through) is closed and the code dialled ONCE more —
     * nothing ran, so nothing can be repeated. Throws when the dialer can't be started.
     */
    suspend fun dial(app: android.content.Context, ctrl: AccessibilityController, code: String, timeoutMs: Long = 25_000L): Screen? {
        var prev = clearStale(ctrl) ?: ""
        repeat(2) { attempt ->
            app.startActivity(android.content.Intent(android.content.Intent.ACTION_CALL,
                android.net.Uri.parse("tel:" + android.net.Uri.encode(code))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            // MTN's "Connection problem or invalid MMI code" can come AFTER its own ~30 s: a short grace past the wait
            // catches it, so it is closed and redialled instead of popping up behind "didn't open in time" (A20e,
            // 3 October 2026: 2 of 3 dials in one batch; also 2 October). A late screen that is a real menu is used.
            val first = awaitNext(ctrl, prev, timeoutMs) ?: awaitNext(ctrl, prev, LATE_GRACE_MS)
                ?: run { sweepLate(ctrl); return null }
            if (attempt == 1 || first.hasInput || !NEVER_STARTED.containsMatchIn(first.text)) return first
            // The phone did send it: MTN held "USSD code running…" for ~30 s and then failed it (A20e, 9 October 2026) —
            // still nothing was chosen, so one redial is safe. The old words, "never started", sent the reader the wrong way.
            android.util.Log.i("Tekyerema", "[USSD] the network failed the request (connection problem / invalid MMI) — nothing was chosen; dialling once more")
            close(ctrl, first)
            prev = first.text
            delay(2_500)
        }
        return null
    }

    /** How much longer than the wait a first screen may take before the dial is given up. */
    const val LATE_GRACE_MS = 10_000L

    /** The dial was given up: for a while longer, a network error that turns up late is closed, so the user isn't left
     *  with a popup they can't see over the home screen. Never touches a dialog with an entry box or a menu. */
    private fun sweepLate(ctrl: AccessibilityController) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val deadline = android.os.SystemClock.uptimeMillis() + 20_000L
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                delay(1_000)
                val s = read(ctrl) ?: continue
                if (s.hasInput || !NEVER_STARTED.containsMatchIn(s.text)) return@launch
                android.util.Log.i("Tekyerema", "[USSD] the network's late error was closed (gone=${close(ctrl, s)})")
                return@launch
            }
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
