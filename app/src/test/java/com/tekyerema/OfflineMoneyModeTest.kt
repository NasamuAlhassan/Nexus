package com.tekyerema

import com.tekyerema.camera.CediClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "How much am I holding?" with no online vision uses the note model on the phone (25 Oct 2026). */
class OfflineMoneyModeTest {
    @Test fun offlineMoneyQuestionUsesThePhonesModel() = assertTrue(CediClassifier.useOffline(true, true, false, true))
    @Test fun onlineKeepsTheVisionModels() = assertFalse(CediClassifier.useOffline(true, true, true, true))
    @Test fun noModelInTheBuildChangesNothing() = assertFalse(CediClassifier.useOffline(true, true, false, false))
    @Test fun aPlainDescribeIsNotMoney() = assertFalse(CediClassifier.useOffline(true, false, false, true))
    @Test fun otherModesAreLeftAlone() = assertFalse(CediClassifier.useOffline(false, true, false, true))
}
