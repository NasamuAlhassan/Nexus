package com.tekyerema

import com.tekyerema.accessibility.TelephonyWindows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The windows whose entry boxes are never read into a snapshot — the USSD reply box holds a MoMo PIN in plain text. */
class TelephonyWindowsTest {
    @Test fun phone_windows_are_recognised() {
        for (p in listOf("com.android.phone", "com.samsung.android.app.telephonyui", "com.samsung.android.dialer",
                "com.google.android.dialer", "com.samsung.android.incallui", "com.android.server.telecom", "com.android.stk"))
            assertTrue(p, TelephonyWindows.matches(p))
    }

    @Test fun ordinary_apps_are_not() {
        for (p in listOf(null, "", "com.whatsapp", "com.mtn.momo", "com.google.android.apps.messaging", "com.samsung.android.messaging",
                "com.android.chrome", "com.tekyerema", "com.stackoverflow.app"))
            assertFalse(p.toString(), TelephonyWindows.matches(p))
    }
}
