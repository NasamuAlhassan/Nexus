package com.tekyerema.accessibility

/**
 * Windows owned by the phone itself — the USSD dialog (*170# Mobile Money, bank codes), the dialer, the in-call
 * screen, SIM toolkit menus. Their entry boxes can hold a Mobile Money PIN in plain text (the USSD reply box has no
 * password flag on Android 11), so the snapshot never carries what is typed in them. Pure, so it is unit-tested.
 */
object TelephonyWindows {
    private val EXACT = setOf("com.android.phone", "com.samsung.android.app.telephonyui", "com.android.server.telecom",
        "com.samsung.android.incallui", "com.android.stk", "com.samsung.android.stk")

    fun matches(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return p in EXACT || p.contains("dialer") || p.contains("incall") || p.contains("telecom") || p.endsWith(".stk") ||
            p.contains(".stk.") || p.contains("telephonyui")
    }
}
