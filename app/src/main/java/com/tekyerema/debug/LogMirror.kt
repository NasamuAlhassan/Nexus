package com.tekyerema.debug

import android.content.Context
import android.util.Log
import com.tekyerema.core.config.AppConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds: the app's own log lines kept on the phone (owner, 28 Oct 2026: "save the logs on the Infinix so every test we do,
 * you can read them"). The Infinix log buffer is 256 KB and rolls over in minutes; this copies the app's lines (tag Tekyerema, plus
 * its crashes) to rolling files that survive restarts and reinstalls:
 *
 *   /sdcard/Android/data/com.tekyerema/files/logs/tk-<start time>-<n>.log     (adb pull, no run-as needed)
 *
 * An app may read its own process's log, so this is `logcat` run by the app itself, from the moment this process started (no lines
 * of the previous process twice). Files of `debug.logMirror.fileMb` (4) each, the oldest dropped past `debug.logMirror.keepMb` (200).
 * Off in release builds; `debug.logMirror.enabled` switches it in a debug build. The lines are what logcat already shows (debug
 * builds log request text), so the folder is as private as the debug log itself.
 */
object LogMirror {
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started || !com.tekyerema.BuildConfig.DEV_HOOKS || !AppConfig.bool("debug.logMirror.enabled", true)) return
        started = true
        val app = ctx.applicationContext
        Thread({ run(app) }, "log-mirror").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    fun dir(ctx: Context): File? = ctx.getExternalFilesDir("logs")

    private fun run(app: Context) {
        val dir = dir(app)?.apply { mkdirs() } ?: return
        val fileBytes = AppConfig.long("debug.logMirror.fileMb", 4L).coerceIn(1L, 32L) * 1024 * 1024
        val keepBytes = AppConfig.long("debug.logMirror.keepMb", 200L).coerceIn(8L, 2048L) * 1024 * 1024
        // From this process's start (less a second): the start-up lines are kept, the previous process's lines are not repeated.
        val startedWall = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) - 1000
        val since = String.format(Locale.US, "%d.%03d", startedWall / 1000, startedWall % 1000)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedWall))
        var proc: Process? = null
        try {
            proc = ProcessBuilder("logcat", "-v", "threadtime", "-T", since, "-s", "Tekyerema:V", "AndroidRuntime:E", "TekyeremaCrash:V")
                .redirectErrorStream(true).start()
            var n = 0
            var file = File(dir, "tk-$stamp-$n.log")
            var out = file.outputStream().buffered()
            var size = 0L
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val bytes = (line + "\n").toByteArray()
                    out.write(bytes)
                    size += bytes.size
                    if (line.contains("[INPUT]") || line.contains("FATAL")) out.flush()
                    if (size >= fileBytes) {
                        out.close()
                        prune(dir, keepBytes)
                        n++
                        file = File(dir, "tk-$stamp-$n.log")
                        out = file.outputStream().buffered()
                        size = 0
                    } else if (size % 8192 < bytes.size) out.flush()
                }
            }
            out.close()
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[LOGMIRROR] stopped: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try { proc?.destroy() } catch (_: Throwable) {}
        }
    }

    private fun prune(dir: File, keep: Long) {
        val files = dir.listFiles { f -> f.name.startsWith("tk-") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= keep) break; total -= f.length(); f.delete() }
    }
}
