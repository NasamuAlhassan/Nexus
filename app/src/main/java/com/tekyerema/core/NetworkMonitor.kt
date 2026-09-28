package com.tekyerema.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Online/offline/degraded state (directive §32) — decided by the OS, not the user. */
enum class Connectivity { ONLINE, OFFLINE, DEGRADED }

/**
 * Process-wide connectivity observer. Registered once in TekyeremaApp; the brain router reads [isOnline]
 * to decide local-vs-cloud, and no cloud path is attempted when offline. Uses the default-network callback
 * (API 24+) plus an on-demand recompute so state is fresh even if a callback was missed.
 */
object NetworkMonitor {
    @Volatile private var cm: ConnectivityManager? = null
    @Volatile private var cached: Connectivity = Connectivity.ONLINE   // optimistic until first read

    fun init(context: Context) {
        val mgr = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm = mgr
        recompute()
        try {
            mgr?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = recompute()
                override fun onLost(network: Network) { cached = Connectivity.OFFLINE }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recompute()
            })
        } catch (_: Throwable) { /* registration can throw on odd OEM states — fall back to on-demand recompute */ }
    }

    /** Fresh state; recomputes from the active network so a missed callback can't leave us stale. */
    fun state(): Connectivity { recompute(); return cached }

    /** True only when a validated internet-capable network is present (DEGRADED counts as usable-ish). */
    fun isOnline(): Boolean = state() != Connectivity.OFFLINE

    @Volatile private var downKbps: Int = 0

    /**
     * N-10: a usable but WEAK connection — unvalidated, or the OS's downstream estimate is under `router.network.weakKbps`
     * (default 400 kbps, roughly 2G/EDGE). The router then keeps work on the device and uses the smaller cloud model.
     */
    fun isWeak(): Boolean {
        val s = state()
        if (s == Connectivity.OFFLINE) return false
        if (s == Connectivity.DEGRADED) return true
        return isWeakLink(downKbps, com.tekyerema.core.config.AppConfig.int("router.network.weakKbps", 400))
    }

    /** Pure: 0 means the OS gave no estimate — not weak. */
    fun isWeakLink(downKbps: Int, weakKbps: Int): Boolean = downKbps in 1 until weakKbps

    private fun recompute() {
        val mgr = cm ?: return
        cached = try {
            val net = mgr.activeNetwork
            val caps = if (net != null) mgr.getNetworkCapabilities(net) else null
            downKbps = try { caps?.linkDownstreamBandwidthKbps ?: 0 } catch (_: Throwable) { 0 }
            when {
                caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Connectivity.OFFLINE
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Connectivity.ONLINE
                else -> Connectivity.DEGRADED
            }
        } catch (_: Throwable) { Connectivity.OFFLINE }
    }
}
