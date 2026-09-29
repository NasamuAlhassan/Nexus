package com.tekyerema.core

import com.tekyerema.core.config.AppConfig
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * DNS that doesn't make every new connection wait on a slow resolver.
 *
 * On the A20e (2026-09-29) the phone's Private DNS (DNS-over-TLS to a public ad-blocking resolver) took 5–11 s per
 * lookup on the campus Wi-Fi while ping and the PC on the same network were fine. Every fresh connection — each model
 * call after the pool dropped a stalled one, each link probe — waited on it, and the watchdog called a working link
 * dead. The handful of hosts the app talks to change addresses rarely, so:
 *  - an answer younger than [freshMs] is reused with no lookup;
 *  - otherwise the system lookup gets [budgetMs]; if it's slower and a previous answer (younger than [staleMs]) is
 *    known, that answer is used now and the lookup finishes in the background to refresh it;
 *  - with nothing known, the lookup is simply waited for (as before).
 * [forget] drops a host's answer after a connection to it failed, so a moved server is looked up again.
 */
class CachingDns(
    private val system: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val freshMs: () -> Long = { AppConfig.long("router.network.dns.freshMs", 5 * 60_000L) },
    private val staleMs: () -> Long = { AppConfig.long("router.network.dns.staleMs", 6 * 3_600_000L) },
    private val budgetMs: () -> Long = { AppConfig.long("router.network.dns.budgetMs", 1_500L) },
    /** With nothing known, how long a request waits for the lookup before failing (the lookup goes on and fills the
     *  cache for the next request). OkHttp's call timeout can't interrupt a lookup, so this is what bounds it. */
    private val maxWaitMs: () -> Long = { AppConfig.long("router.network.dns.maxWaitMs", 12_000L) },
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "tekyerema-dns").apply { isDaemon = true } }
) : Dns {
    private class Entry(val addresses: List<InetAddress>, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inflight = HashMap<String, Future<List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()
        val now = clock()
        val known = cache[host]
        if (known != null && now - known.at <= freshMs()) return known.addresses
        // One lookup per host at a time; a finished one is replaced (never reused — its answer is already cached).
        val job = synchronized(inflight) {
            inflight[host]?.takeIf { !it.isDone } ?: pool.submit<List<InetAddress>> {
                system(host).also { if (it.isNotEmpty()) cache[host] = Entry(it, clock()) }
            }.also { inflight[host] = it }
        }
        val usable = known?.takeIf { now - it.at <= staleMs() }
        return try {
            job.get(if (usable != null) budgetMs() else maxWaitMs(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            if (usable == null) throw UnknownHostException("$hostname: DNS lookup timed out after ${maxWaitMs()}ms")
            android.util.Log.i("Tekyerema", "[NET] DNS for $host slower than ${budgetMs()}ms — using its last known address")
            usable.addresses
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            if (usable != null) usable.addresses
            else throw (cause as? UnknownHostException ?: UnknownHostException(hostname).apply { initCause(cause) })
        }
    }

    /** Resolve [hosts] in the background now (at start-up, before the first request needs them). */
    fun prefetch(hosts: Collection<String>) {
        for (h in hosts) pool.execute { try { lookup(h) } catch (_: Throwable) {} }
    }

    /** A connection to [hostname] failed: don't hand out its old address again without looking it up. */
    fun forget(hostname: String) { cache.remove(hostname.lowercase()) }

    companion object {
        val shared by lazy { CachingDns() }
    }
}
