package com.tekyerema.debug

import android.content.Context
import android.util.Log
import com.tekyerema.voice.SherpaTts
import com.tekyerema.voice.VoiceEngine
import java.io.File

/**
 * Listening comparison for the reading pace and pauses: the same passages read the way the app reads them under several
 * settings, one WAV each in <external files>/tts_ab/ (pull them with adb and listen). Log lines `[TTS-AB]`.
 *   adb ... --es tts_ab 1
 */
object TtsAbDebug {
    val PASSAGES = linkedMapOf(
        "news" to "The Ministry of Health says 1,250 new cases were recorded on 12 Oct 2026, up 8.5% from last week. Dr. Mensah, speaking at 10:30am in Accra, urged the public to wear masks in crowded places; however, many people ignored the call and went on as before, which worried the ministry. He said the next update will come on Friday.",
        "message" to "You have a message from Ama on WhatsApp. It says: ok, see you at 5pm, please bring the money, GHS 250.50, and don't forget the change. Then she wrote: I will wait at the station.",
        "bill" to "Your electricity bill for October is 120 cedis. It is due on the fifth of November, so please pay before then. If you pay late, there is a penalty of ten percent, and the power may be cut off. You can pay by mobile money, at the bank, or at any ECG office near you.",
        "settings" to "You're in Settings. Network and internet. Wi-Fi. Connected to HomeNet. Bluetooth. Off. Display. Brightness level: 60%. Sound. Battery. 82%. Apps. Storage. 14 GB used."
    )

    val VARIANTS = listOf(
        SherpaTts.ReadingVariant("A_now"),
        SherpaTts.ReadingVariant("H_breath_1_3", breathScale = 1.3),
        SherpaTts.ReadingVariant("I_breath_1_6", breathScale = 1.6),
        SherpaTts.ReadingVariant("J_breath_2_0", breathScale = 2.0)
    )

    fun run(context: Context) {
        val voice = VoiceEngine.onDeviceVoice("eng") as? SherpaTts
        if (voice == null) { Log.w("Tekyerema", "[TTS-AB] no on-device English voice"); return }
        val out = File(context.getExternalFilesDir(null), "tts_ab")
        for ((tag, text) in PASSAGES) voice.renderReadings(text, VARIANTS, out, tag)
    }
}
