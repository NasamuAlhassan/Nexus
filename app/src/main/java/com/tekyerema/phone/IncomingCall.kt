package com.tekyerema.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Step 1 of the demo flow (owner, 14 Oct 2026): a call comes in → the caller is announced by name ("Kofi Demo is calling.
 * Press volume up to answer."); Volume Up answers it on the loudspeaker. "Now call him" later puts our own call on the
 * loudspeaker too. The caller becomes "him" for what follows (References).
 *
 * Watches the phone's call state through the PHONE_STATE broadcast, registered by the accessibility service (always
 * running); the caller's number comes with READ_CALL_LOG. Nothing is recorded: the number is looked up in the contacts and
 * kept in memory only while the call lasts. `phone.announceCaller` / `phone.volumeUpAnswers` / `phone.speakerOnAnswer`
 * are the switches.
 */
object IncomingCall {
    private const val TAG = "Tekyerema"
    private val main = Handler(Looper.getMainLooper())

    /** The number ringing now (null when nothing rings). */
    @Volatile var ringingNumber: String? = null
        private set
    @Volatile private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    /** The next call that goes off-hook (answered, or placed by us) is put on the loudspeaker. */
    @Volatile private var speakerNext = false
    private var receiver: BroadcastReceiver? = null

    val isRinging: Boolean get() = lastState == TelephonyManager.EXTRA_STATE_RINGING

    fun start(ctx: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                onState(c.applicationContext, i.getStringExtra(TelephonyManager.EXTRA_STATE), i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
            }
        }
        try {
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
            receiver = r
        } catch (t: Throwable) { Log.w(TAG, "[CALL] couldn't watch the call state: ${t.message}") }
    }

    fun stop(ctx: Context) {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Throwable) {} }
        receiver = null
    }

    private fun cfg(key: String) = com.tekyerema.core.config.AppConfig.bool(key, true)

    internal fun onState(ctx: Context, state: String?, number: String?) {
        val s = state ?: return
        // The broadcast comes twice per change (once without the number): act on the one that has it.
        if (s == lastState && !(s == TelephonyManager.EXTRA_STATE_RINGING && number != null && ringingNumber == null)) return
        lastState = s
        when (s) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (number.isNullOrBlank()) return
                ringingNumber = number
                val name = try { com.tekyerema.android.ContactResolver.lookupByNumber(ctx, number).firstOrNull()?.name } catch (_: Throwable) { null }
                // The caller is "him" for what comes next ("read my message", "send money to him", "call him").
                name?.let { com.tekyerema.agent.References.notePerson(it) }
                com.tekyerema.agent.References.noteNumber(number)
                Log.i(TAG, "[CALL] ringing — caller ${if (name != null) "is a contact" else "unknown"}")   // no name or number in the log
                // #36 call shield: a number never seen before is said to be one (warn-only; hashes only — phone/CallShield).
                val firstTime = try { CallShield.onRinging(ctx, number, isContact = name != null) } catch (_: Throwable) { false }
                if (cfg("phone.announceCaller")) {
                    val who = name ?: com.tekyerema.agent.NumberSpeech.spoken(com.tekyerema.agent.NumberSpeech.normalize(number)).ifBlank { "Someone" }
                    val hint = if (cfg("phone.volumeUpAnswers")) " Press volume up to answer." else ""
                    val en = com.tekyerema.language.EntityTags.EN_OPEN + who + com.tekyerema.language.EntityTags.EN_CLOSE
                    // [owner: verify the Twi] "<name> refrɛ wo. Mia volume up na gye."
                    val twi = "$en refrɛ wo." + if (hint.isNotEmpty()) " Mia volume up na gye." else ""
                    // A moment after the ring starts: the ringtone takes the audio first, then the name is said over it.
                    main.postDelayed({ if (isRinging) com.tekyerema.voice.VoiceEngine.announceAnswer("$who is calling.${if (firstTime) CallShield.FIRST_TIME else ""}$hint", twi, urgent = true) }, 900)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                ringingNumber = null
                CallShield.onOffHook()
                if (speakerNext) {
                    speakerNext = false
                    // Routing settles a moment after the call connects; set it then, and once more in case the dialler reset it.
                    main.postDelayed({ speaker(ctx, true) }, 700)
                    main.postDelayed({ speaker(ctx, true) }, 2_000)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> { ringingNumber = null; speakerNext = false; try { CallShield.onIdle(ctx) } catch (_: Throwable) {} }
        }
    }

    /**
     * Volume Up while the phone rings: answer, on the loudspeaker. Returns true when the key was used for that (the key
     * filter then consumes it, so the ringer volume doesn't move).
     */
    fun answerByKey(ctx: Context): Boolean {
        if (!isRinging || !cfg("phone.volumeUpAnswers")) return false
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            speakerNext = cfg("phone.speakerOnAnswer")
            @Suppress("DEPRECATION", "MissingPermission")
            tm.acceptRingingCall()
            Log.i(TAG, "[CALL] answered with volume up")
            true
        } catch (t: Throwable) { Log.w(TAG, "[CALL] couldn't answer: ${t.message}"); speakerNext = false; false }
    }

    /** "Now call him": the call we are about to place goes on the loudspeaker when it connects. */
    fun speakerForNextCall() { speakerNext = cfg("phone.speakerOnCall") }

    /** The loudspeaker on (or off) for the call in progress. */
    fun speaker(ctx: Context, on: Boolean) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) } else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
            Log.i(TAG, "[CALL] loudspeaker ${if (on) "on" else "off"}")
        } catch (t: Throwable) { Log.w(TAG, "[CALL] loudspeaker: ${t.message}") }
    }
}
