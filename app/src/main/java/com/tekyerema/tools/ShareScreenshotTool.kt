package com.tekyerema.tools

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.tekyerema.android.AppResolver
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject
import java.io.File

/**
 * "Take a screenshot of this and send it to Richmond on Telegram." The screenshot KEY only saves a file to the Gallery —
 * getting that file into a chat meant driving a gallery picker by hand, which is where the research → screenshot →
 * Telegram task died (A20e audit 2026-10-01, E2E1/E2E2: seven screenshots saved, none ever attached).
 *
 * This captures what is on the screen now and hands the picture straight to the named app with Android's own share
 * intent. The app then shows its "send to…" list; choosing the chat and pressing Send are separate steps, and the Send
 * is confirmed like any other. Nothing leaves the phone here. The picture lives in the app's private cache (the same
 * place the user's own data export uses) and is handed over with a one-off read grant; older ones are deleted.
 */
object ShareScreenshotTool : Tool {
    override val name = "share_screenshot"
    override val description =
        "Capture the screen NOW and open an app (WhatsApp, Telegram…) with that picture ready to send. Pick the chat there; " +
            "nothing is sent until Send is confirmed."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER1
    override val params = listOf(
        ToolParam("app", "string", "App to share with (omit for the share sheet).", required = false),
        ToolParam("caption", "string", "Text to go with it.", required = false)
    )

    private const val KEEP = 3

    /** The outcome is checked here, not guessed from the screen: the target app (or the share sheet) is in front. Without
     *  this the engine took "no element named WhatsApp" as a failed step and pressed Back out of the share screen
     *  (A20e retest 2026-10-01). */
    override val verifiesOutcome = true
    override val isAccelerator = true

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: com.tekyerema.accessibility.UiSnapshot?,
                                after: com.tekyerema.accessibility.UiSnapshot?, result: ToolResult): com.tekyerema.verifier.VerificationResult {
        if (!result.success) return com.tekyerema.verifier.VerificationResult(false, result.message)
        val pkg = result.data?.optString("package")?.takeIf { it.isNotBlank() }
            ?: return com.tekyerema.verifier.VerificationResult(true, "the share sheet was opened with the picture", authoritative = true)
        val (ok, fg) = com.tekyerema.verifier.Verify.pollLaunched(ctx.controller, pkg, before?.appPackage)
        return if (ok && fg?.contains(pkg, ignoreCase = true) == true)
            com.tekyerema.verifier.VerificationResult(true, "the app is in front with the picture ready to send", authoritative = true)
        else com.tekyerema.verifier.VerificationResult(false, "the app did not come to the front (foreground is ${fg ?: "unknown"})")
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val context = ctx.appContext
        val controller = ctx.controller ?: return ToolResult(false, "I can't see the screen right now, so I couldn't capture it.")
        val caption = args.str("caption")?.trim()?.takeIf { it.isNotEmpty() }
        // Resolve the app BEFORE capturing: a name that matches nothing must not leave a stray picture behind.
        val (target, refusal) = resolveTarget(context, args.str("app"))
        refusal?.let { return it }

        val bitmap = try { controller.captureScreenshot() } catch (t: Throwable) { null }
            ?: return ToolResult(false, "I couldn't capture the screen — this screen may be protected.")
        val file = try { save(context.cacheDir, bitmap) } catch (t: Throwable) {
            return ToolResult(false, "I captured the screen but couldn't save the picture: ${t.message}")
        } finally { try { bitmap.recycle() } catch (_: Throwable) {} }
        return handOver(context, file, target, caption, "captured the screen")
    }

    /** The app named [appName] to hand a picture to (null = the share sheet), or the reply to give instead: several apps
     *  match, none does, or it is an app Tɛkyerɛma never drives. Shared with send_photo. */
    internal fun resolveTarget(context: android.content.Context, appName: String?): Pair<AppResolver.AppInfo?, ToolResult?> {
        val name = appName?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val target = when (val m = AppResolver.resolveDetailed(context, name)) {
            is AppResolver.Match.One -> m.app
            is AppResolver.Match.Ambiguous -> return null to ToolResult(
                false, "More than one app matches '$name': ${m.options.joinToString(", ") { it.label }}. Which one?",
                needsDisambiguation = true)
            AppResolver.Match.None -> return null to ToolResult(false, "I couldn't find an app called '$name'. I can open the share sheet instead.")
        }
        launchRefusal(target.packageName, target.label)?.let { return null to ToolResult(false, it, recoverable = false) }
        return target to null
    }

    /** Hand [file] (a JPEG in cache/exports) to [target], or to the share sheet, with Android's share intent and a one-off
     *  read grant. [did] starts the reply: "captured the screen", "took the photo". Shared with send_photo. */
    internal fun handOver(context: android.content.Context, file: File, target: AppResolver.AppInfo?, caption: String?, did: String): ToolResult {
        val uri = try { FileProvider.getUriForFile(context, "${context.packageName}.exports", file) } catch (t: Throwable) {
            return ToolResult(false, "I couldn't prepare the picture for sharing: ${t.message}")
        }
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("picture", uri)
        if (caption != null) send.putExtra(Intent.EXTRA_TEXT, caption)

        if (target == null) {
            return try {
                context.startActivity(Intent.createChooser(send, "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                ToolResult(true, "I $did and opened the share sheet — pick an app. Nothing is sent until you tap Send.", channel = "intent")
            } catch (t: Throwable) { ToolResult(false, "Couldn't open the share sheet: ${t.message}") }
        }
        send.setPackage(target.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (send.resolveActivity(context.packageManager) == null)
            return ToolResult(false, "${target.label} can't receive a shared picture. I can open the share sheet instead.")
        return try {
            context.startActivity(send)
            ToolResult(true,
                "I $did and opened ${target.label} with the picture ready" + (if (caption != null) " and your caption" else "") +
                    ". ${target.label} is asking who to send it to — choose the chat there. Nothing is sent until Send is confirmed.",
                data = JSONObject().put("package", target.packageName), channel = "intent")
        } catch (t: Throwable) { ToolResult(false, "Couldn't open ${target.label}: ${t.message}") }
    }

    /** JPEG in cache/exports (the only folder the FileProvider may hand out); keeps the newest [KEEP] screenshots. */
    internal fun save(cacheDir: File, bitmap: Bitmap, now: Long = System.currentTimeMillis()): File {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        prune(dir, KEEP - 1)
        val file = File(dir, "screenshot-$now.jpg")
        file.outputStream().use { out -> if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw java.io.IOException("could not encode the picture") }
        return file
    }

    /** Delete all but the newest [keep] pictures with [prefix] this tool (or send_photo) made — never the user's own export. */
    internal fun prune(dir: File, keep: Int, prefix: String = "screenshot-"): Int {
        val old = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".jpg") }
            ?.sortedByDescending { it.lastModified() }?.drop(keep.coerceAtLeast(0)) ?: return 0
        return old.count { it.delete() }
    }

    override fun confirmationPrompt(args: JSONObject): String =
        args.str("app")?.let { "Capture this screen and open $it to share it?" } ?: "Capture this screen and share it?"
}
