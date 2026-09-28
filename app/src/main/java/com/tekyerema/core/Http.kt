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
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    fun client(connectSec: Long, callSec: Long): OkHttpClient = shared.newBuilder()
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(callSec, TimeUnit.SECONDS)
        .callTimeout(callSec, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastPrewarmMs = 0L

    /** Open (or refresh) a pooled connection to [url]'s host without waiting for the answer. Debounced. */
    fun prewarm(url: String) {
        val now = System.currentTimeMillis()
        if (url.isBlank() || now - lastPrewarmMs < 45_000L) return
        lastPrewarmMs = now
        try {
            shared.newCall(Request.Builder().url(url).head().build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
            Log.i("Tekyerema", "[NET] prewarming ${url.substringBefore("/v1").take(60)}")
        } catch (_: Throwable) {}
    }
}
