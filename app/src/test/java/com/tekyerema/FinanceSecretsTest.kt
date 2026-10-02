package com.tekyerema

import com.tekyerema.core.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finance audit (2026-10-01): the code messages really found on the test phone (shapes kept, values made up) must never
 * be read aloud, logged or sent to a model — and the network's ordinary receipts must still be readable.
 */
class FinanceSecretsTest {
    // 44 base64 characters, like the real one; not a real key.
    private val KEY = "vSH4eQnWNFN2X9PlwoZn7rGVU31N8YpjRx5fLOAitN0bRw=="
    private val MOMO_APP = "Y'ello. Please note! This confidential code gives access to your MoMo account:$KEY " +
        "Do not share it with anyone. l+/DM+Y4kqw HTx27B9+31w"
    private val MTN_ID = "Y'ello MTNner, do not share your code with any MTN employee or anyone. Your code is :\n483920175. 9XTs4SAuJcW"
    private val OTP = "<#> MTN MOMO: This OTP gives access to your wallet. Anyone asking for this code is trying to fraudulently gain " +
        "access to your funds. MTN MOMO will NEVER ask for it. Enter Code:4821 to complete your login."

    @Test fun the_momo_apps_access_key_is_hidden() {
        assertTrue(Redact.looksSecret(MOMO_APP))
        val hidden = Redact.secrets(MOMO_APP)!!
        assertFalse(hidden, hidden.contains("vSH4eQnWNFN2X9"))
        assertTrue(hidden, hidden.contains("your MoMo account:${Redact.HIDDEN}"))
    }

    @Test fun a_long_numeric_code_is_hidden_too() {
        assertTrue(Redact.looksSecret(MTN_ID))
        assertFalse(Redact.secrets(MTN_ID)!!.contains("483920175"))
    }

    @Test fun other_shapes_of_a_code_are_hidden_too() {
        val urlSafe = "This confidential code gives access to your MoMo account:vSH4eQnW-NFN2X9Plw_oZn7rGVU31N8YpjRx5f Do not share it with anyone."
        assertFalse(Redact.secrets(urlSafe)!!.contains("vSH4eQnW"))
        assertFalse(Redact.secrets("Do not share your code. Your code is - 483920175")!!.contains("483920175"))
        assertFalse(Redact.secrets("Your code is: X7K9QZ. Never share it.")!!.contains("X7K9QZ"))
        assertFalse(Redact.secrets("Your verification code is 4839201754821")!!.contains("4839201754821"))
    }

    @Test fun the_wallet_otp_stays_hidden() {
        assertTrue(Redact.looksSecret(OTP))
        assertFalse(Redact.secrets(OTP)!!.contains("4821"))
    }

    @Test fun ordinary_receipts_and_adverts_are_left_alone() {
        for (plain in listOf(
            "Payment made for GHS 5.00 to KOFI MENSAH. Current Balance: GHS 120.00. Available Balance: GHS 120.00. Reference: lunch. " +
                "Transaction ID: 76712833868. Fee charged: GHS 0.00 TAX charged: GHS 0.00.",
            "Payment received for GHS 50.00 from AMA SERWAA Current Balance: GHS 170.00 . Available Balance: GHS 170.00. Reference: rent. " +
                "Transaction ID: 76712833999. TRANSACTION FEE: 0.00",
            "Go cashless with MoMoPay. Pay any merchant using ID or QR Code and get 500MB FREE. Dial *170#, select MoMoPay or use the " +
                "MoMoApp https://mtnmymomo.onelink.me/XJOt/cVm9x",
            "Your payment of GHS 20.00 to KOFI MENSAH has failed at 2026-10-01 14:03:22. Reference: fees. Financial Transaction Id: 76712834001."
        )) {
            assertFalse(plain, Redact.looksSecret(plain))
            assertEquals(plain, Redact.secrets(plain))
        }
    }
}
