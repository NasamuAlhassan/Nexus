package com.tekyerema.core

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * N-04 — one connection pool for every cloud call. Each brain used to build its own OkHttpClient per task, so every
 * task paid a fresh DNS + TCP + TLS handshake (hundreds of ms on a Ghanaian mobile network) before the first token.
 * Clients now derive from one shared client (same pool and dispatcher, own timeouts), and [prewarm] opens the TLS
 * connection while the user is still speaking.
 */
object Http {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // A slow resolver (the A20e's Private DNS took 5–11 s a lookup on the campus Wi-Fi) must not stall every new
            // connection: known addresses are reused, and a slow lookup falls back to the last good one ([CachingDns]).
            .dns(CachingDns.shared)
            // Idle connections are kept only a minute: the HTTP/2 pings below would otherwise keep the mobile radio
            // awake for the full idle time, and [prewarm] reopens the connection when the mic opens anyway.
            .connectionPool(ConnectionPool(5, com.tekyerema.core.config.AppConfig.long("router.network.poolKeepAliveSec", 60L), TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            // N-10 / N-15: a model call gets no bytes back until its answer is ready, so a dead link and a slow model look
            // the same — silence until the call timeout; and a connection a Wi-Fi/NAT silently dropped stays "healthy" in
            // the pool and hangs every later call (A20e, 2026-09-28: seven Groq timeouts in a row while Gemini answered in
            // 4 s). An HTTP/2 PING is answered by the server's network stack, not the model: a missing pong fails the call
            // after two intervals instead of the timeout, and keeps the NAT mapping alive. Idle connections are dropped
            // after 60 s, so the pings don't keep the radio awake. 0 turns it off.
            // 15 s, not 5: on EDGE / a congested 3G cell (or a ping queued behind a large request body) a pong can take
            // longer than 5 s, and OkHttp would fail a working connection — the FallbackBrain watchdog (8 s + a probe)
            // already catches a truly dead link sooner.
            .pingInterval(com.tekyerema.core.config.AppConfig.long("router.network.h2PingSec", 15L), TimeUnit.SECONDS)
            .build()
    }

    fun client(connectSec: Long, callSec: Long): OkHttpClient = shared.newBuilder()
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(callSec, TimeUnit.SECONDS)
        .callTimeout(callSec, TimeUnit.SECONDS)
        .build()

    /**
     * Run [call] without blocking a thread, and CANCEL it when the coroutine is cancelled — a "stop" (or a barge-in)
     * aborts the request in flight instead of waiting out a 35-second model call. Returns (HTTP code, body text,
     * response headers); throws IOException on a network failure.
     *
     * @param observe feed the outcome to [LinkHealth] (N-10): any HTTP answer proves the link works; a network failure
     *   (not a user cancel) counts against it. Off for the probe itself, which records its own result.
     */
    suspend fun execute(call: Call, observe: Boolean = true): Triple<Int, String, okhttp3.Headers> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val t0 = System.currentTimeMillis()
            cont.invokeOnCancellation { try { call.cancel() } catch (_: Throwable) {} }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (observe && !call.isCanceled()) LinkHealth.failure(System.currentTimeMillis() - t0, e.message)
                    // Refused / no route: the cached address may be stale — look it up again next time.
                    if (e is java.net.ConnectException || e is java.net.NoRouteToHostException) CachingDns.shared.forget(call.request().url.host)
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (observe) LinkHealth.success(System.currentTimeMillis() - t0)
                    val r = try { response.use { Result.success(Triple(it.code, it.body?.string().orEmpty(), it.headers)) } }
                        catch (e: IOException) { Result.failure(e) }
                    if (cont.isActive) cont.resumeWith(r)
                }
            })
        }

    @Volatile private var lastPrewarmMs = 0L

    /** Bounded: over a stalled link an unbounded HEAD would hang on DNS/TCP for as long as the OS lets it. */
    private val prewarmClient: OkHttpClient by lazy {
        shared.newBuilder()
            .callTimeout(com.tekyerema.core.config.AppConfig.long("router.network.prewarmTimeoutSec", 8L), TimeUnit.SECONDS)
            .build()
    }

    /** Open (or refresh) a pooled connection to [url]'s host without waiting for the answer. Debounced.
     *  Its outcome is also a free link check (N-10), taken while the user is still speaking. */
    fun prewarm(url: String) {
        val now = System.currentTimeMillis()
        if (url.isBlank() || now - lastPrewarmMs < 45_000L) return
        lastPrewarmMs = now
        try {
            LinkHealth.tracker.warmStarted(now)
            prewarmClient.newCall(Request.Builder().url(url).head().build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    LinkHealth.tracker.warmEnded()
                    if (!call.isCanceled()) LinkHealth.failure(System.currentTimeMillis() - now, "prewarm: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    LinkHealth.tracker.warmEnded()
                    LinkHealth.success(System.currentTimeMillis() - now)
                    response.close()
                }
            })
            Log.i("Tekyerema", "[NET] prewarming ${url.substringBefore("/v1").take(60)}")
        } catch (_: Throwable) {}
    }
}
