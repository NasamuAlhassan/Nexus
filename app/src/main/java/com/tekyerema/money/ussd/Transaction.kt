package com.tekyerema.money.ussd

/**
 * What the user asked for, carried through the whole USSD walk (owner's navigator brief, 2026-09-30 §3): the
 * NAVIGATOR owns execution; screens are matched against this intent, slots answer the network's questions, and the
 * one consolidated confirmation + the PIN boundary close it. Pure data — nothing here touches Android.
 */
enum class TxIntent {
    SEND_MONEY, BUY_AIRTIME, BUY_DATA, SEND_AIRTIME, SEND_DATA, CHECK_BALANCE, PAY_BILL;

    /** Spoken name for the one confirmation and honest errors. */
    val spoken: String get() = when (this) {
        SEND_MONEY -> "send money"; BUY_AIRTIME -> "buy airtime"; BUY_DATA -> "buy data"
        SEND_AIRTIME -> "send airtime"; SEND_DATA -> "send a data bundle"; CHECK_BALANCE -> "check the balance"
        PAY_BILL -> "pay a bill"
    }
}

/**
 * The transaction as the navigator knows it. [recipient] is the number AS CONFIRMED by the user's read-back — it is
 * entered verbatim (normalized once, safely: "+233…"/"233…" fold to the 0-form, nothing else is ever transformed).
 * [confirmedUpFront] records that the user already approved intent+recipient+amount in ONE read-back, so the walk
 * asks nothing more until the network itself presents something new (the pre-authorization screen).
 */
data class TxContext(
    val intent: TxIntent,
    val provider: String?,            // "mtn" | "telecel" | "airteltigo" | null (unknown → never guess a code)
    val recipient: String? = null,    // digits, 0-form
    val recipientName: String? = null,// only ever a name the NETWORK or the user's own contacts provided — never invented
    val amount: String? = null,       // plain cedi figure, e.g. "50" or "0.50"
    val product: String? = null,      // a bundle/offer the user named ("the 1 cedi mashup")
    val reference: String? = null,
    val confirmedUpFront: Boolean = false
) {
    /** The value for a slot the network asks for, or null (then the user is asked ONCE). */
    fun slot(kind: SlotKind): String? = when (kind) {
        SlotKind.PHONE_NUMBER, SlotKind.CONFIRM_NUMBER -> recipient
        SlotKind.AMOUNT -> amount
        // Never invented: a reference the user didn't give is ASKED at the network's reference screen (owner, 5 October
        // 2026: "it got to a reference part, it didn't pause … which is wrong"). It used to be typed as "Payment".
        SlotKind.REFERENCE -> reference
        SlotKind.PRODUCT, SlotKind.OTHER -> null
    }
}

/** What an input screen is asking for, read from its own prompt words. */
enum class SlotKind { PHONE_NUMBER, CONFIRM_NUMBER, AMOUNT, REFERENCE, PRODUCT, OTHER }

/** One step of the structured trace (§15) — for logs and tests, never spoken; values are masked by the logger. */
data class TraceStep(val state: String, val detail: String, val at: Long = System.currentTimeMillis())
