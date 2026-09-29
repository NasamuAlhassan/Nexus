package com.tekyerema.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.launch

/** Online/offline/degraded state (directive §32) — decided by the OS, not the user. */
enum class Connectivity { ONLINE, OFFLINE, DEGRADED }

/** Why the cloud can't be used right now — so the spoken reply can say the true thing. */
enum class OfflineReason {
    /** The cloud looks usable. */
    NONE,
    /** No network at all (airplane mode, no Wi-Fi, data off). */
    NO_NETWORK,
    /** Connected, but traffic isn't getting through (a stalled Wi-Fi, a captive portal) — see [LinkHealth]. */
    STALLED
}

/**
 * Process-wide connectivity observer. Registered once in TekyeremaApp; the brain router reads [isOnline]
 * to decide local-vs-cloud, and no cloud path is attempted when offline. Uses the default-network callback
 * (API 24+) plus an on-demand recompute so state is fresh even if a callback was missed.
 *
 * N-10: [isOnline] also consults [LinkHealth] — a network the OS calls connected but whose traffic is timing out is
 * treated as offline for a minute (and re-checked), so ASR, the planner, web search and the camera all take their
 * on-phone path at once instead of each waiting out its own timeout.
 */
object NetworkMonitor {
    @Volatile private var cm: ConnectivityManager? = null
    @Volatile private var cached: Connectivity = Connectivity.ONLINE   // optimistic until first read
    @Volatile private var currentNet: Network? = null
    @Volatile private var appContext: Context? = null
    private val reportScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** The application context, for plumbing that must act when the network returns (the offline queue). */
    val context: Context? get() = appContext

    fun init(context: Context) {
        val mgr = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm = mgr
        appContext = context.applicationContext
        LinkProbe.available = true
        // Resolve the few hosts the app talks to now, off the request path (see CachingDns).
        CachingDns.shared.prefetch(com.tekyerema.core.config.AppConfig.stringList("router.network.dns.prefetch",
            listOf("api.groq.com", "generativelanguage.googleapis.com", "connectivitycheck.gstatic.com")))
        // VALIDATED is only re-checked now and then (AOSP re-probes on DNS-timeout streaks, not on a timer), so tell
        // Android what our traffic saw: it re-validates the network and, with the user's "switch to mobile data"
        // setting, may move to a network that works. A hint only — needs no permission beyond network state.
        // Only once a probe agrees: a slow-but-working link (EDGE, a congested 3G cell) must not be reported as broken,
        // which can push the phone off a free Wi-Fi onto paid mobile data.
        LinkHealth.onStalled {
            val net = currentNet ?: return@onStalled
            reportScope.launch {
                if (LinkProbe.check() == false && net == currentNet)
                    try { cm?.reportNetworkConnectivity(net, false) } catch (_: Throwable) {}
            }
        }
        recompute()
        try {
            mgr?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val was = cached
                    // A different network is a fresh start: what the old Wi-Fi did says nothing about mobile data.
                    if (network != currentNet) { currentNet = network; LinkHealth.reset() }
                    recompute()
                    if (was == Connectivity.OFFLINE && cached != Connectivity.OFFLINE) LinkHealth.fireRestored()
                }
                override fun onLost(network: Network) { cached = Connectivity.OFFLINE; currentNet = null; LinkHealth.reset() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // Fires often (every bandwidth-estimate change): recompute only — never reset the stall evidence here.
                    val was = cached
                    recompute()
                    if (was != Connectivity.ONLINE && cached == Connectivity.ONLINE) LinkHealth.fireRestored()
                }
            })
        } catch (_: Throwable) { /* registration can throw on odd OEM states — fall back to on-demand recompute */ }
    }

    /** Fresh state; recomputes from the active network so a missed callback can't leave us stale. */
    fun state(): Connectivity { recompute(); return cached }

    /**
     * True only when the cloud is worth trying: a network the OS considers internet-capable (DEGRADED counts as
     * usable-ish) AND whose traffic isn't known to be stalling. While stalled, each check nudges a background probe
     * (rate-limited) so the circuit closes soon after the link recovers.
     */
    fun isOnline(): Boolean {
        if (state() == Connectivity.OFFLINE) return false
        if (LinkHealth.stalled()) { LinkProbe.kick(); return false }
        return true
    }

    /** Why [isOnline] is false (or [OfflineReason.NONE] when it isn't). */
    fun offlineReason(): OfflineReason = when {
        state() == Connectivity.OFFLINE -> OfflineReason.NO_NETWORK
        LinkHealth.stalled() -> OfflineReason.STALLED
        else -> OfflineReason.NONE
    }

    @Volatile private var downKbps: Int = 0

    /**
     * N-10: a usable but WEAK connection — unvalidated, the OS's downstream estimate is under `router.network.weakKbps`
     * (default 400 kbps, roughly 2G/EDGE), or our own last probe was slow. The router then keeps work on the device and
     * uses the smaller cloud model.
     */
    fun isWeak(): Boolean {
        val s = state()
        if (s == Connectivity.OFFLINE) return false
        if (s == Connectivity.DEGRADED) return true
        if (LinkHealth.tracker.slowProbe()) return true
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
