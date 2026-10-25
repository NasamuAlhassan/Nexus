package com.tekyerema

import com.tekyerema.core.DeviceLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assistant works only on an unlocked phone (owner, 25 Oct 2026). */
class DeviceLockTest {
    @Test fun aLockedPhoneIsRefused() = assertTrue(DeviceLock.refuses(locked = true, requireUnlocked = true))
    @Test fun anUnlockedPhoneIsServed() = assertFalse(DeviceLock.refuses(locked = false, requireUnlocked = true))
    @Test fun theSettingCanBeTurnedOff() = assertFalse(DeviceLock.refuses(locked = true, requireUnlocked = false))
    @Test fun theLineAsksForAnUnlockAndNothingElse() {
        assertEquals("Your phone is locked. Please unlock it, then ask me again.", DeviceLock.LOCKED_LINE)
    }
}
