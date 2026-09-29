package com.tekyerema.agent.journal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.tekyerema.accessibility.UiSnapshot

/**
 * The world checks Android allows (research: agenticness_reliability_state §3 table), strongest signal first:
 *  - SMS → the SMS provider. Tɛkyerɛma is not the default SMS app, so the system itself writes what SmsManager sent into
 *    the provider ("If and only if an app is not selected as the default SMS app…", AOSP SmsManager). Matched on the
 *    body's hash and the time window (and the number's last digits when the target is a number). Needs READ_SMS.
 *  - Calls → the call log: an outgoing row to that number/name since the effect. Needs READ_CALL_LOG.
 *  - Installs → PackageManager: the package, or a launcher entry with that name.
 *  - Chat messages → the chat on screen ([ChatCheck]).
 * Anything unreadable (permission missing, provider error) is UNDETERMINED — the user is asked, never a blind retry.
 * NOT verified on the A20e yet (end-of-run device check): One UI's provider columns and timing.
 */
class AndroidWorldCheck(private val app: Context) : WorldCheck {
    private fun granted(p: String) = try {
        ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private val hhmm = java.text.SimpleDateFormat("H:mm", java.util.Locale.US)

    override fun check(effect: Effect, payload: String?, screen: UiSnapshot?): WorldResult = try {
        when (effect.kind) {
            EffectKind.SMS -> sms(effect).let { if (it.verdict == WorldVerdict.UNDETERMINED) ChatCheck.check(effect, payload, screen) else it }
            EffectKind.CALL -> call(effect)
            EffectKind.INSTALL -> install(effect)
            EffectKind.CHAT_MESSAGE -> ChatCheck.check(effect, payload, screen)
            else -> WorldResult.UNDETERMINED
        }
    } catch (_: Throwable) { WorldResult.UNDETERMINED }

    private fun digitsTail(s: String) = s.filter { it.isDigit() }.takeLast(9)

    private fun sms(e: Effect): WorldResult {
        if (!granted(Manifest.permission.READ_SMS)) return WorldResult(WorldVerdict.UNDETERMINED, "no permission to read texts")
        val since = e.at - 5_000L
        val numeric = e.target.all { it.isDigit() } && e.target.length >= 6
        app.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(2)
                if (type != Telephony.Sms.MESSAGE_TYPE_SENT && type != Telephony.Sms.MESSAGE_TYPE_OUTBOX &&
                    type != Telephony.Sms.MESSAGE_TYPE_QUEUED && type != Telephony.Sms.MESSAGE_TYPE_FAILED) continue
                if (EffectLedger.hash(c.getString(1).orEmpty()) != e.payloadHash) continue
                if (numeric && digitsTail(c.getString(0).orEmpty()) != digitsTail(e.target)) continue
                val at = hhmm.format(java.util.Date(c.getLong(3)))
                return when (type) {
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> WorldResult(WorldVerdict.NOT_DONE, "the text shows as failed in Messages")
                    Telephony.Sms.MESSAGE_TYPE_SENT -> WorldResult(WorldVerdict.DONE, "the text is in the sent messages at $at")
                    else -> WorldResult(WorldVerdict.DONE, "the text is queued to send at $at")
                }
            }
        }
        // The provider is written when the text goes: nothing there two minutes on means it didn't.
        return if (System.currentTimeMillis() - e.at > 120_000L) WorldResult(WorldVerdict.NOT_DONE, "no such text in the sent messages")
            else WorldResult(WorldVerdict.UNDETERMINED, "the text isn't in the sent messages yet")
    }

    private fun call(e: Effect): WorldResult {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return WorldResult(WorldVerdict.UNDETERMINED, "no permission to read the call log")
        val numeric = e.target.all { it.isDigit() } && e.target.length >= 6
        app.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), (e.at - 5_000L).toString()), "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                val hit = if (numeric) digitsTail(c.getString(0).orEmpty()) == digitsTail(e.target)
                    else EffectLedger.normalizeTarget(c.getString(1).orEmpty()) == e.target
                if (hit) return WorldResult(WorldVerdict.DONE, "the call is in the call log at ${hhmm.format(java.util.Date(c.getLong(2)))}")
            }
        }
        return if (System.currentTimeMillis() - e.at > 60_000L) WorldResult(WorldVerdict.NOT_DONE, "no such call in the call log")
            else WorldResult(WorldVerdict.UNDETERMINED, "the call isn't in the call log yet")
    }

    private fun install(e: Effect): WorldResult {
        val pm = app.packageManager
        if (e.target.contains('.')) {
            val installed = try { pm.getPackageInfo(e.target, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
            if (installed) return WorldResult(WorldVerdict.DONE, "the app is installed")
        }
        val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        if (launcher.any { EffectLedger.normalizeTarget(it.loadLabel(pm)?.toString().orEmpty()) == e.target || it.activityInfo?.packageName == e.target })
            return WorldResult(WorldVerdict.DONE, "the app is installed")
        // A download may still be running — installing again is the user's call, not ours.
        return WorldResult(WorldVerdict.UNDETERMINED, "the app isn't installed yet")
    }
}
