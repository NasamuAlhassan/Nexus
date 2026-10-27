package com.tekyerema

import com.tekyerema.money.UssdDialog
import com.tekyerema.money.ussd.Navigator
import com.tekyerema.money.ussd.Navigator.Companion.Retry
import com.tekyerema.money.ussd.TxIntent
import org.junit.Assert.assertEquals
import org.junit.Test

/** A press that got no answer is retried from the menu still in front — for a balance check only, never for money. */
class NavigatorRetryTest {
    private val menu = "1) Check Balance 2) Reset PIN"
    private fun screen(text: String, input: Boolean = true, masked: Boolean = false) =
        UssdDialog.Screen(text, if (input) 1 else null, if (input) 2 else null, 3, masked)

    @Test fun `balance check presses the same menu once more`() =
        assertEquals(Retry.PRESS_AGAIN, Navigator.retryAction(TxIntent.CHECK_BALANCE, menu, screen(menu)))

    @Test fun `a late different screen is simply taken`() =
        assertEquals(Retry.TAKE_NOW, Navigator.retryAction(TxIntent.CHECK_BALANCE, menu, screen("Enter MM PIN")))

    @Test fun `no dialog means give up`() =
        assertEquals(Retry.GIVE_UP, Navigator.retryAction(TxIntent.CHECK_BALANCE, menu, null))

    @Test fun `a masked prompt is never pressed again`() =
        assertEquals(Retry.GIVE_UP, Navigator.retryAction(TxIntent.CHECK_BALANCE, menu, screen(menu, masked = true)))

    @Test fun `money moving intents are never repeated`() {
        for (i in listOf(TxIntent.SEND_MONEY, TxIntent.BUY_AIRTIME, TxIntent.SEND_AIRTIME, TxIntent.GOAL))
            assertEquals(Retry.GIVE_UP, Navigator.retryAction(i, menu, screen(menu)))
    }
}
