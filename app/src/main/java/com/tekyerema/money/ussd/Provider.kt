package com.tekyerema.money.ussd

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.tekyerema.tools.GhanaMobileMoney

/**
 * Which SIM the walk will really dial on, and what network it is (navigator brief §16) — never assumed from a
 * hard-coded provider. ACTION_CALL dials on the phone's DEFAULT VOICE subscription, so that is the one identified
 * here (SubscriptionManager where available, the plain TelephonyManager otherwise). On a dual-SIM phone whose
 * default voice SIM is NOT the wallet SIM, the honest answer is to SAY so, not to dial the wrong network's code —
 * per-call SIM selection needs a PhoneAccount picker and is a device-test item, documented, not faked.
 */
object Provider {
    data class Sim(val provider: String?, val carrier: String?, val slot: Int?, val dualSim: Boolean)

    fun current(ctx: Context): Sim {
        // Debug builds with the USSD simulator on: the SIM the simulator pretends to be (the emulator's fake carrier has no
        // Mobile Money code, so nothing would ever be dialled).
        UssdSim.carrier()?.let { c -> return Sim(GhanaMobileMoney.network(c), c, null, dualSim = false) }
        // Two SIMs and the user has said which one this task uses: that is the SIM the code is dialled on (phone/SimChoice).
        com.tekyerema.phone.SimChoice.current(ctx)?.let { c -> return Sim(c.network, c.carrier, c.slot, dualSim = true) }
        try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val active = try { sm?.activeSubscriptionInfoList.orEmpty() } catch (_: SecurityException) { emptyList() }
            val voiceId = SubscriptionManager.getDefaultVoiceSubscriptionId()
            val voice = active.firstOrNull { it.subscriptionId == voiceId } ?: active.firstOrNull()
            if (voice != null) {
                val carrier = voice.carrierName?.toString()?.ifBlank { null }
                return Sim(GhanaMobileMoney.network(carrier), carrier, voice.simSlotIndex, active.size > 1)
            }
        } catch (_: Throwable) {}
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val op = tm?.simOperatorName?.ifBlank { tm.simOperator } ?: tm?.simOperator
        return Sim(GhanaMobileMoney.network(op), op, null, dualSim = false)
    }

    /** The Mobile-Money root code of [network] ("mtn" → *170#), as a set — empty when the network isn't known. */
    fun momoRoots(network: String?): Set<String> =
        try { network?.let { GhanaMobileMoney.rootUssd(it) }?.let { setOf(it) } ?: emptySet() } catch (_: Throwable) { emptySet() }

    /** The provider's Mobile-Money root code for this SIM, or null (then nothing is ever guessed). */
    fun momoRoot(ctx: Context): String? {
        val sim = current(ctx)
        return sim.provider?.let { p ->
            // GhanaMobileMoney's table is keyed by operator words; ask it with the carrier we actually found.
            GhanaMobileMoney.rootUssd(sim.carrier ?: p)
        }
    }
}
