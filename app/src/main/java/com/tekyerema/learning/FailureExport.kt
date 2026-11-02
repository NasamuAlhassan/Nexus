package com.tekyerema.learning

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Share my mistakes for training": the redacted notes as JSONL (one training-pool task per line, [FailureCore.toTask]) written to
 * the app's files dir (files/learning/mistakes-<time>.jsonl), and the Android share sheet opened on it. Nothing is sent by the app:
 * the user picks where the file goes. [FailureCommands] reads out what the file holds BEFORE asking for the yes.
 *
 * The share sheet reads through the app's FileProvider, which serves cache/exports only (res/xml/export_paths.xml, shared with
 * the history export) — so a copy of the file is placed there. Both copies go with "forget my mistakes" and "delete everything".
 */
object FailureExport {
    private const val FILES_DIR = "learning"
    private const val SHARE_PREFIX = "nexus-mistakes-"

    /** The share-sheet intent for the notes, or null when there are none or the file couldn't be written. */
    fun prepare(ctx: Context, cases: List<FailureCase> = FailureStore.all()): Intent? {
        if (cases.isEmpty()) return null
        val app = ctx.applicationContext
        return try {
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
            val dir = File(app.filesDir, FILES_DIR).apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("mistakes-") }?.forEach { it.delete() }
            val file = File(dir, "mistakes-$stamp.jsonl").apply { writeText(FailureCore.toJsonl(cases)) }
            val shareDir = File(app.cacheDir, com.tekyerema.audit.AuditExport.DIR).apply { mkdirs() }
            shareDir.listFiles { f -> f.name.startsWith(SHARE_PREFIX) }?.forEach { it.delete() }
            val copy = File(shareDir, "$SHARE_PREFIX$stamp.jsonl").also { file.copyTo(it, overwrite = true) }
            val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".exports", copy)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Nexus: tasks that went wrong (for training)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, "Share my mistakes for training").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Throwable) { null }
    }

    /** Opens the share sheet. False when the file couldn't be made or nothing can receive it. */
    fun share(ctx: Context): Boolean {
        val intent = prepare(ctx) ?: return false
        return try { ctx.applicationContext.startActivity(intent); true } catch (_: Throwable) { false }
    }

    /** Both export copies, gone. */
    fun deleteFiles(ctx: Context) {
        val app = ctx.applicationContext
        try { File(app.filesDir, FILES_DIR).listFiles { f -> f.name.startsWith("mistakes-") }?.forEach { it.delete() } } catch (_: Throwable) {}
        try { File(app.cacheDir, com.tekyerema.audit.AuditExport.DIR).listFiles { f -> f.name.startsWith(SHARE_PREFIX) }?.forEach { it.delete() } } catch (_: Throwable) {}
    }
}
