package com.tekyerema.ussdsim

import android.app.Activity
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log

/**
 * Twin-emulator rehearsal (owner, 15 Oct 2026: "use multiple emulators … something that can connect them … like the real
 * world"): the OTHER phone (Kofi's emulator) sends a real SMS to the user's emulator, through the emulators' own modem
 * network — `adb -s emulator-5556 shell am start -n com.tekyerema.ussdsim/.SmsSendActivity --es to 5554 --es body "…"`.
 * No screen; it sends and closes. Debug test harness only (this app is never shipped).
 */
class SmsSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val to = intent.getStringExtra("to")?.trim().orEmpty()
        val body = intent.getStringExtra("body").orEmpty()
        if (to.isNotEmpty() && body.isNotEmpty()) try {
            @Suppress("DEPRECATION") val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(body)
            if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, null, null) else sms.sendTextMessage(to, null, body, null, null)
            Log.i("TwinSms", "sent ${parts.size} part(s) to $to")
        } catch (t: Throwable) { Log.w("TwinSms", "not sent: ${t.javaClass.simpleName}") }
        finish()
    }
}
