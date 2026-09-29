package com.tekyerema.mcp

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The MCP transport: newline-delimited JSON-RPC 2.0 (MCP stdio framing, UTF-8) over an ABSTRACT unix socket named
 * [SOCKET_NAME] — no TCP port on the phone, so no other app can reach it over localhost (research note §1.4, "Local
 * Mess"). A computer reaches it through adb:
 *
 *     adb forward tcp:8765 localabstract:tekyerema-mcp
 *
 * Connection establishment (documented, as MCP asks of custom transports):
 *  1. The phone checks who opened the socket: only adbd (uid 2000, [PeerCheck]) — a computer the user has already
 *     authorised for USB debugging. Anything else (another app on the phone) is closed at once.
 *  2. The phone writes `TEKYEREMA-MCP/1 PAIR` and, unless a code is already active, SPEAKS and SHOWS a 6-digit code.
 *  3. The client writes `PAIR <code>` within [PAIR_TIMEOUT_MS]. The phone answers `OK`, or `DENIED` and closes
 *     ([Pairing]: 3 wrong tries retire the code).
 *  4. After `OK`, each line either way is one JSON-RPC message ([McpSession]). EOF ends the connection.
 *
 * Only while the user has switched the computer connection on ([RemoteControl]); it turns itself off when unused.
 */
class McpServer(
    private val pairing: Pairing,
    private val backend: RemoteBackend,
    private val events: Events,
    private val serverVersion: String
) {
    interface Events {
        /** A code to say and show now. */
        fun announceCode(code: String)
        /** A computer passed both checks. */
        fun connected(client: String)
        /** A connection was refused ([RemoteAudit.REFUSED_APP] / [RemoteAudit.REFUSED_CODE]). */
        fun refused(why: String)
        /** Every JSON-RPC request, for the audit. */
        fun call(c: RemoteCall)
    }

    @Volatile private var running = false
    @Volatile private var server: LocalServerSocket? = null
    private val open = AtomicInteger(0)
    /** Live connections, so turning the feature off cuts every paired computer off at once. */
    private val live = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<LocalSocket, Boolean>())
    /** Last time a computer sent anything (for the idle turn-off). */
    @Volatile var lastActivityMs: Long = System.currentTimeMillis(); private set

    val isRunning: Boolean get() = running

    @Synchronized fun start(): Boolean {
        if (running) return true
        val s = try { LocalServerSocket(SOCKET_NAME) } catch (e: IOException) {
            Log.w(TAG, "[MCP] can't open the socket: ${e.message}"); return false
        }
        server = s
        running = true
        lastActivityMs = System.currentTimeMillis()
        Thread({ acceptLoop(s) }, "tekyerema-mcp-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "[MCP] listening on localabstract:$SOCKET_NAME")
        return true
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
        val s = server; server = null
        try { s?.close() } catch (_: Throwable) {}
        for (c in live.toList()) try { c.shutdownInput(); c.close() } catch (_: Throwable) {}
        live.clear()
        // A LocalServerSocket blocked in accept() may not notice close(): knock once so the loop sees running=false.
        try { LocalSocket().apply { connect(LocalSocketAddress(SOCKET_NAME)); close() } } catch (_: Throwable) {}
        Log.i(TAG, "[MCP] stopped")
    }

    private fun acceptLoop(s: LocalServerSocket) {
        while (running) {
            val sock = try { s.accept() } catch (e: IOException) { break }
            if (!running) { try { sock.close() } catch (_: Throwable) {}; break }
            Thread({ serve(sock) }, "tekyerema-mcp-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(sock: LocalSocket) {
        val uid = try { sock.peerCredentials.uid } catch (_: Throwable) { -1 }
        if (!PeerCheck.accept(uid)) {
            events.refused(RemoteAudit.REFUSED_APP)
            try { sock.close() } catch (_: Throwable) {}
            return
        }
        if (open.incrementAndGet() > MAX_CONNECTIONS) {
            try { writeLine(sock.outputStream, "DENIED busy") } catch (_: Throwable) {}
            open.decrementAndGet(); try { sock.close() } catch (_: Throwable) {}
            return
        }
        live.add(sock)
        try {
            val input = BufferedInputStream(sock.inputStream)
            val out = sock.outputStream
            pairing.onConnect()?.let { events.announceCode(it) }
            writeLine(out, BANNER)
            sock.soTimeout = PAIR_TIMEOUT_MS
            val first = readLine(input, 200) ?: return
            if (!first.startsWith("PAIR") || pairing.verify(first.removePrefix("PAIR")) != Pairing.Verdict.OK) {
                writeLine(out, "DENIED")
                events.refused(RemoteAudit.REFUSED_CODE)
                return
            }
            writeLine(out, "OK")
            sock.soTimeout = 0
            lastActivityMs = System.currentTimeMillis()
            val session = McpSession(backend, onCall = events::call, serverVersion = serverVersion)
            var announced = false
            val flood = RateLimiter(MAX_LINES_PER_10S, 10_000L)
            while (running) {
                val line = try { readLine(input, MAX_LINE_BYTES) } catch (e: LineTooLong) {
                    writeLine(out, McpSession.errorLine(null, McpSession.INVALID_REQUEST, "Message longer than $MAX_LINE_BYTES bytes"))
                    break
                } ?: break
                if (!running) break              // turned off while this line was on its way: never served
                lastActivityMs = System.currentTimeMillis()
                if (!flood.tryAcquire()) break   // a client flooding the phone is cut off
                for (reply in session.handle(line)) writeLine(out, reply)
                // After the first message (initialize / server/discover usually carries the client's name).
                if (!announced) { announced = true; events.connected(session.clientName) }
            }
        } catch (_: IOException) {
        } catch (t: Throwable) {
            Log.w(TAG, "[MCP] connection error: ${t.javaClass.simpleName}")
        } finally {
            live.remove(sock)
            open.decrementAndGet()
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private class LineTooLong : IOException()

    companion object {
        const val SOCKET_NAME = "tekyerema-mcp"
        const val BANNER = "TEKYEREMA-MCP/1 PAIR"
        const val PAIR_TIMEOUT_MS = 120_000
        const val MAX_LINE_BYTES = 256 * 1024
        const val MAX_LINES_PER_10S = 200
        const val MAX_CONNECTIONS = 2
        private const val TAG = "Tekyerema"

        fun writeLine(out: OutputStream, line: String) {
            synchronized(out) { out.write((line + "\n").toByteArray(Charsets.UTF_8)); out.flush() }
        }

        /** One UTF-8 line without its "\n" (a trailing "\r" is dropped), or null at end of stream. */
        internal fun readLine(input: InputStream, max: Int): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8").trimEnd('\r')
                if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                if (buf.size() >= max) throw LineTooLong()
                buf.write(b)
            }
        }
    }
}
