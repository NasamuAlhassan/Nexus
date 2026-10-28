package com.tekyerema

import com.tekyerema.accessibility.A11yWatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yWatchTest {
    private val ours = "com.tekyerema/com.tekyerema.accessibility.TekyeremaAccessibilityService"
    private val talkback = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
    private val samsung = "com.samsung.android.accessibility.talkback/com.samsung.android.marvin.talkback.TalkBackService"

    @Test fun ourServiceAndTalkBackAreToldApart() {
        assertTrue(A11yWatch.isOurs("$ours:$talkback", "com.tekyerema"))
        assertFalse(A11yWatch.isOurs(talkback, "com.tekyerema"))
        assertFalse(A11yWatch.isOurs("", "com.tekyerema"))
        assertTrue(A11yWatch.isTalkBack("$ours:$talkback"))
        assertTrue(A11yWatch.isTalkBack(samsung))
        assertFalse(A11yWatch.isTalkBack(ours))
        assertFalse(A11yWatch.isOurs("com.tekyeremax/Other", "com.tekyerema"))
    }
}
