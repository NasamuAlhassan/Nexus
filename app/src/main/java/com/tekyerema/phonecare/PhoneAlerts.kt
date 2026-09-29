package com.tekyerema.phonecare

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.tekyerema.agent.triggers.QuietHours
import com.tekyerema.agent.triggers.TriggerGate
import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import com.tekyerema.notifications.NotificationRecord
import com.tekyerema.settings.Settings
import java.util.concurrent.Executors

/**
 * Event alerts — money received, a missed call from a contact, low battery — spoken on the phone with NO model
 * (openclaw.md §5.2: event nudges instead of an LLM heartbeat, which would cost ~100k tokens a turn against a daily cap
 * that ran out on 2026-09-29). Every alert type is OFF until the user switches it on (Settings › Phone care, or "tell me
 * when I receive money"), and every one follows the unattended-trigger rules of [TriggerGate] / [QuietHours]:
 *  - never during a task, a voice turn or a call — it waits (config `alerts.busyRetryMs` × `alerts.busyRetries`) and is
 *    then kept for "read my alerts" instead of being lost;
 *  - never in quiet hours (kept for "read my alerts" until an hour after they end);
 *  - each one ONCE (signatures kept across restarts, so a re-posted SMS after a reboot is not news; the same payment
 *    from the MoMo app and by SMS, or a dialer's summary and its child, is said once), and not too often
 *    (`alerts.minGapMs` per type, `alerts.maxPerHour` in all);
 *  - privacy on the loudspeaker: with "private alerts" on and no headset or Bluetooth audio, a money or missed-call
 *    alert only says "You have a money alert. Say 'read it' to hear it." ([AlertPrivacy]);
 *  - money and missed-call alerts come from notifications, so the user's "Message alerts" switch in Settings › Control
 *    (OperatorPolicy.messageAlertsEnabled, WS-C) silences them too; nothing here ever acts on a message.
 * Only lines built from parsed fields are spoken — never a message's raw text, a code, an OTP or (unless the user asked,
 * checked again when the line is spoken or read) a balance. Logs carry the type only.
 */
object PhoneAlerts {
    const val MONEY = "money"
    const val MISSED_CALL = "missed_call"
    const val BATTERY = "battery"
    /** Catalog-style ids for the flags (config `flags.PC-0n.enabled`); PC-01 is "check my phone". */
    const val FEATURE_CHECK = "PC-01"
    const val FEATURE_MONEY = "PC-02"
    const val FEATURE_MISSED_CALL = "PC-03"
    const val FEATURE_BATTERY = "PC-04"

    private const val TAG = "Tekyerema"
    private const val PREFS = "tekyerema_phonecare"
    private const val KEY_SEEN = "seen_signatures"
    private const val KEY_BATTERY = "battery_announced"
    private const val REPEAT_WINDOW_MS = 10 * 60_000L

    /** One alert ready to go: [full] is the whole line (for a credit, rebuilt from [money] with the balance choice at
     *  speaking time); [canBePrivate] = hidden on the loudspeaker when the user wants that; [keep] = kept for "read my
     *  alerts" when it can't be spoken now. */
    internal data class Alert(val type: String, val full: String, val signature: String, val canBePrivate: Boolean,
                              val keep: Boolean = true, val money: MoneyAlerts.Event.Received? = null)

    val gate = AlertGate()
    val inbox = AlertInbox()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tekyerema-alerts").apply { isDaemon = true } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var seenRestored = false
    @Volatile private var batteryReceiver: BroadcastReceiver? = null

    // ---------------------------------------------------------------- start-up

    /** TekyeremaApp.onCreate: listen to the battery (a dynamic receiver — the battery broadcast can't be declared in the
     *  manifest). Cheap: it only reads two numbers and writes a preference when a threshold changes. */
    fun init(ctx: Context) {
        if (batteryReceiver != null) return
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { if (i != null) try { onBattery(app, i) } catch (t: Throwable) { Log.w(TAG, "[ALERT] battery: ${t.message}") } }
        }
        try {
            ContextCompat.registerReceiver(app, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            batteryReceiver = r
        } catch (t: Throwable) { Log.w(TAG, "[ALERT] battery receiver not registered: ${t.message}") }
    }

    // ---------------------------------------------------------------- notifications (money, missed calls)

    /** From TekyeremaNotificationListener.onNotificationPosted (live posts only, never the reconnect backfill). Returns at
     *  once: the work (an SMS / call-log read, a Keystore decrypt for Relations) runs on this object's own thread.
     *  [n] = the raw notification, for its group-summary flag and its `when` (a missed call's time). */
    fun onPosted(ctx: Context, rec: NotificationRecord, n: Notification? = null) {
        val app = ctx.applicationContext
        // A group summary ("2 new messages", a dialer's bundle) repeats its children: the children are the news.
        if (n != null && (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val whenMs = n?.`when` ?: 0L
        worker.execute { try { handle(app, rec, whenMs) } catch (t: Throwable) { Log.w(TAG, "[ALERT] notification: ${t.message}") } }
    }

    private fun handle(app: Context, rec: NotificationRecord, whenMs: Long) {
        if (rec.packageName == app.packageName) return                       // never our own notifications
        val s = Settings(app)
        if (!s.alertMoney && !s.alertMissedCalls) return
        if (!com.tekyerema.operator.OperatorPolicy.messageAlertsEnabled) return   // WS-C: the user's switch for message-driven alerts
        if (s.alertMoney && FeatureFlags.isEnabled(FEATURE_MONEY, true)) money(app, rec)?.let { deliver(app, it); return }
        if (s.alertMissedCalls && FeatureFlags.isEnabled(FEATURE_MISSED_CALL, true)) missedCall(app, rec, whenMs)?.let { deliver(app, it) }
    }

    private fun money(app: Context, rec: NotificationRecord): Alert? {
        val pkg = rec.packageName
        val fromApp = MoneyAlerts.isMoneyApp(pkg)
        if (!fromApp && pkg !in MoneyAlerts.smsApps()) return null
        // An SMS app's title is the sender; a Mobile Money app may put "Payment received" in the title.
        val body = if (fromApp) listOfNotNull(rec.title, rec.text).joinToString(". ") else rec.text.orEmpty()
        if (body.isBlank()) return null
        // Trust the SMS's real sender address (READ_SMS, when already granted), not the name a notification shows: a
        // scammer's number saved as "Mobile Money" must not pass. Without the permission, the shown name is all there is.
        val shown = rec.sender ?: rec.title
        val senderId = if (fromApp) null else smsAddress(app, body) ?: shown
        val trusted = fromApp || MoneyAlerts.isTrustedSender(senderId)
        val event = MoneyAlerts.classify(body, trusted, fromNumber = !fromApp && MoneyAlerts.looksLikeNumber(senderId)) ?: return null
        val now = System.currentTimeMillis()
        val sig = "m|" + body.lowercase().filter { it.isLetterOrDigit() }.hashCode()
        return when (event) {
            is MoneyAlerts.Event.Received -> {
                // The MoMo app's push and the SMS for the same payment: said once.
                if (gate.repeatWithin("money|${event.amount}|${event.from.orEmpty().lowercase()}", now, REPEAT_WINDOW_MS)) return null
                Alert(MONEY, MoneyAlerts.receivedLine(event, withBalance = false), sig, canBePrivate = true, money = event)
            }
            // Warnings name nobody, but "someone asked for money" is still private on a loudspeaker: same rule as a credit.
            MoneyAlerts.Event.MistakeClaim -> Alert(MONEY, MoneyAlerts.mistakeLine(), sig, canBePrivate = true)
            MoneyAlerts.Event.UntrustedCredit -> Alert(MONEY, MoneyAlerts.untrustedLine(), sig, canBePrivate = true)
        }
    }

    /** The sender address of the newest inbox SMS (last 10 minutes) holding [body]; null without READ_SMS or no match. */
    private fun smsAddress(app: Context, body: String): String? {
        if (app.checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            app.contentResolver.query(android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY),
                "${android.provider.Telephony.Sms.DATE} > ?", arrayOf((System.currentTimeMillis() - 10 * 60_000L).toString()),
                "${android.provider.Telephony.Sms.DATE} DESC")?.use { c ->
                var found: String? = null
                var rows = 0
                while (found == null && rows++ < 20 && c.moveToNext()) {
                    if (MoneyAlerts.sameMessage(c.getString(1), body)) found = c.getString(0)
                }
                found
            }
        } catch (_: Throwable) { null }
    }

    private fun missedCall(app: Context, rec: NotificationRecord, whenMs: Long): Alert? {
        if (rec.packageName !in MissedCalls.packages()) return null
        val m = MissedCalls.parse(rec.title, rec.text) ?: return null
        var callers = m.callers.filter { MissedCalls.isSavedName(it) }
        // The call's own time (the notification's `when`), so a re-post after a reboot is the same event.
        var at = (if (whenMs > 0) whenMs else rec.postTime) / 60_000L
        // The call log (only when READ_CALL_LOG is already granted) is the better judge of WHO: its saved name, else the
        // number looked up in Contacts (when READ_CONTACTS is granted) — a number found in neither is a stranger. A blank
        // cached name alone proves nothing (Android doesn't always fill it), so the dialer's name is kept then.
        lastMissedFromLog(app)?.let { (name, number, date) ->
            at = date / 60_000L
            callers = when {
                !name.isNullOrBlank() -> listOf(name)
                else -> when (val c = contactName(app, number)) {
                    null -> callers
                    "" -> emptyList()
                    else -> listOf(c)
                }
            }
        }
        if (callers.isEmpty()) return null
        val who = callers.joinToString(",") { it.lowercase() }
        if (gate.repeatWithin("call|$who|$at", System.currentTimeMillis(), REPEAT_WINDOW_MS)) return null
        com.tekyerema.android.Relations.ensureLoaded(app)
        val line = MissedCalls.line(callers, com.tekyerema.android.Relations.all(), several = m.count > 1)
        return Alert(MISSED_CALL, line, "c|" + ("$who|${m.count}|$at").hashCode(), canBePrivate = true)
    }

    /** The newest missed call of the last few minutes: (cached contact name or null, number, date). */
    private fun lastMissedFromLog(app: Context): Triple<String?, String, Long>? {
        if (app.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val since = System.currentTimeMillis() - AppConfig.long("alerts.missedCall.logWindowMs", 5 * 60_000L)
        return try {
            app.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
                "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.DATE} > ?",
                arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
                "${android.provider.CallLog.Calls.DATE} DESC")?.use { c ->
                if (!c.moveToFirst()) null else Triple(c.getString(0), c.getString(1).orEmpty(), c.getLong(2))
            }
        } catch (_: Throwable) { null }
    }

    /** The contact name saved for [number]: the name, "" when it isn't a contact, null when Contacts can't be read. */
    private fun contactName(app: Context, number: String): String? {
        if (number.isBlank() || app.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            app.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }
        } catch (_: Throwable) { null }
    }

    // ---------------------------------------------------------------- battery

    private fun onBattery(app: Context, i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
            i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val before = BatteryAlerts.decode(prefs.getString(KEY_BATTERY, null))
        val thresholds = BatteryAlerts.thresholds()
        val step = BatteryAlerts.step(pct, charging, before, thresholds)
        if (step.announced != before) prefs.edit().putString(KEY_BATTERY, BatteryAlerts.encode(step.announced)).apply()
        val t = step.announce ?: return
        if (!Settings(app).alertBattery || !FeatureFlags.isEnabled(FEATURE_BATTERY, true)) return
        val alert = Alert(BATTERY, BatteryAlerts.line(pct, t, thresholds.min()), "b|$t|${System.currentTimeMillis()}",
            canBePrivate = false, keep = false)
        worker.execute { try { deliver(app, alert) } catch (_: Throwable) {} }
    }

    // ---------------------------------------------------------------- delivery (the shared rules)

    private fun enabled(type: String, s: Settings): Boolean = when (type) {
        MONEY -> s.alertMoney && com.tekyerema.operator.OperatorPolicy.messageAlertsEnabled
        MISSED_CALL -> s.alertMissedCalls && com.tekyerema.operator.OperatorPolicy.messageAlertsEnabled
        BATTERY -> s.alertBattery
        else -> false
    }

    private fun deliver(app: Context, a: Alert, attempt: Int = 0) {
        if (attempt == 0) {
            restoreSeen(app)
            if (!gate.firstTime(a.signature)) return                       // spoken (or held) once already
            saveSeen(app)
        }
        val s = Settings(app)
        if (!enabled(a.type, s)) return                                  // switched off while it waited
        val now = System.currentTimeMillis()
        if (QuietHours.isQuiet(now)) { hold(a, now, "quiet hours"); return }
        if (TriggerGate.userBusy(app) ||
            !gate.mayAnnounce(a.type, now, AppConfig.long("alerts.minGapMs", 30_000L), AppConfig.int("alerts.maxPerHour", 12))) {
            if (attempt < AppConfig.int("alerts.busyRetries", 12)) {
                main.postDelayed({ worker.execute { try { deliver(app, a, attempt + 1) } catch (_: Throwable) {} } },
                    AppConfig.long("alerts.busyRetryMs", 15_000L))
            } else hold(a, now, "busy")
            return
        }
        val full = a.money?.let { MoneyAlerts.receivedLine(it, s.alertMoneyBalance) } ?: a.full   // the balance choice NOW
        val hide = AlertPrivacy.hideOnSpeaker(a.canBePrivate, s.alertsPrivate, privateAudio(app))
        val line = if (hide) AlertPrivacy.privateLine(a.type) else full
        if (com.tekyerema.core.Redact.looksSecret(full)) { Log.w(TAG, "[ALERT] ${a.type} dropped: looked like a code"); return }
        if (hide) inbox.put(AlertInbox.Item(a.type, full, now, announcedPrivately = true, expiresAt = now + inboxTtl(), money = a.money))
        Log.i(TAG, "[ALERT] ${a.type} spoken private=$hide attempt=$attempt")   // never the words
        TriggerGate.speak(app, line, post = false)
    }

    private fun inboxTtl() = AppConfig.long("alerts.inboxTtlMs", 60 * 60_000L)

    private fun hold(a: Alert, now: Long, why: String) {
        if (a.keep) inbox.put(AlertInbox.Item(a.type, a.full, now, announcedPrivately = false,
            expiresAt = AlertInbox.expiry(now, inboxTtl(), QuietHours.parse(QuietHours.configuredSpec()), java.util.TimeZone.getDefault()),
            money = a.money))
        Log.i(TAG, "[ALERT] ${a.type} not spoken ($why)${if (a.keep) " — kept for “read my alerts”" else ""}")
    }

    /** A headset, earphones or Bluetooth audio is connected — an alert can be read in full without the room hearing it.
     *  (Bluetooth counts as private per the owner's spec; a Bluetooth SPEAKER can't be told apart from earphones here.) */
    fun privateAudio(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { it.type in PRIVATE_OUTPUTS } == true
    } catch (_: Throwable) { false }

    private val PRIVATE_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
        26 /* TYPE_BLE_HEADSET, API 31 */
    )

    // ---------------------------------------------------------------- seen signatures (hashes only — never content)

    private fun restoreSeen(app: Context) {
        if (seenRestored) return
        seenRestored = true
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, null).orEmpty()
        gate.restore(saved.split('\n').filter { it.isNotBlank() })
    }

    private fun saveSeen(app: Context) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SEEN, gate.seenList().joinToString("\n")).apply()
    }

    // ---------------------------------------------------------------- "read it" / "read my alerts"

    /** The waiting alerts, oldest first, read in full (the user asked) — a credit's balance only if [withBalance] (the
     *  user's choice NOW); "There's no alert waiting." when there are none. */
    fun readWaiting(withBalance: Boolean, now: Long = System.currentTimeMillis()): String {
        val items = inbox.take(now)
        if (items.isEmpty()) return AppConfig.phrase("alerts.phrases.noneWaiting", "There's no alert waiting.")
        return items.joinToString(" ") { it.money?.let { m -> MoneyAlerts.receivedLine(m, withBalance) } ?: it.line }
    }

    /** A private alert was just announced, so a bare "read it" means it (`alerts.readItWindowMs`, 3 minutes). */
    fun readItPending(now: Long = System.currentTimeMillis()): Boolean =
        inbox.readItPending(now, AppConfig.long("alerts.readItWindowMs", 3 * 60_000L))
}

/** Loudspeaker privacy (assistive_tech_blind.md §8: "Message from Ama — say 'read it'"). Pure. */
object AlertPrivacy {
    /** Say only that there is an alert: the type allows it, the user wants it, and nothing private is plugged in. */
    fun hideOnSpeaker(canBePrivate: Boolean, privateSetting: Boolean, headsetConnected: Boolean): Boolean =
        canBePrivate && privateSetting && !headsetConnected

    fun privateLine(type: String): String = when (type) {
        PhoneAlerts.MONEY -> AppConfig.phrase("alerts.phrases.privateMoney", "You have a money alert. Say “read it” to hear it.")
        PhoneAlerts.MISSED_CALL -> AppConfig.phrase("alerts.phrases.privateMissedCall", "You have a missed call alert. Say “read it” to hear it.")
        else -> AppConfig.phrase("alerts.phrases.privateAlert", "You have an alert. Say “read it” to hear it.")
    }
}
