package com.tekyerema.tools

import com.tekyerema.camera.PhotoCapture
import com.tekyerema.camera.PhotoCaptureActivity
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * "Take a selfie and send it to Ama on Telegram." take_photo saves to the Gallery through the phone's camera app, and
 * nothing could get that photo into a chat: the owner's request (26 Oct 2026) only opened Telegram. This takes the photo
 * with Tɛkyerɛma's own camera ([PhotoCaptureActivity]: the chosen camera, a beep countdown) and hands it straight to the
 * named app's share target — the same hand-over as share_screenshot. Choosing the chat and pressing Send are the next
 * steps, and the Send is confirmed like any other; nothing leaves the phone here → TIER1.
 */
object SendPhotoTool : Tool {
    override val name = "send_photo"
    override val description = "The way to SEND a new photo or selfie: call it FIRST, before opening any app. Takes it with " +
        "Nexus's camera (camera=front for a selfie; beep countdown) and opens ANY app that takes shared pictures with it " +
        "ready. Pick the person there; nothing is sent until Send is confirmed."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val verifiesOutcome = true
    override val params = listOf(
        ToolParam("camera", "string", "Default back.", required = false, enumValues = listOf("front", "back")),
        ToolParam("app", "string", "App to send with (omit for the share sheet).", required = false),
        ToolParam("caption", "string", "Text to go with it.", required = false)
    )

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: com.tekyerema.accessibility.UiSnapshot?,
                                after: com.tekyerema.accessibility.UiSnapshot?, result: ToolResult): com.tekyerema.verifier.VerificationResult =
        ShareScreenshotTool.verify(args, ctx, before, after, result)

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val context = ctx.appContext
        val front = args.str("camera")?.trim()?.lowercase() in setOf("front", "selfie", "user")
        val caption = args.str("caption")?.trim()?.takeIf { it.isNotEmpty() }
        // The app first: a name that matches nothing must not cost the user a photo they then can't send.
        val (target, refusal) = ShareScreenshotTool.resolveTarget(context, args.str("app"))
        refusal?.let { return it }

        val answer = PhotoCapture.begin()
        try { context.startActivity(PhotoCaptureActivity.intent(context, front, args.intOrNull("countdown") ?: -1)) }
        catch (t: Throwable) { PhotoCapture.done(PhotoCapture.Shot(null, "camera")); return ToolResult(false, "I couldn't open the camera.", recoverable = false) }
        val shot = withTimeoutOrNull(AppConfig.long("camera.sendPhoto.timeoutMs", 30_000L)) { answer.await() }
        val file = shot?.file ?: return ToolResult(false, when (shot?.why) {
            "permission" -> "I need the camera permission to take the photo. Allow the camera for Nexus and ask again."
            "closed" -> "The camera closed before the photo was taken."
            null -> "The camera didn't take the photo in time."
            else -> "I couldn't take the photo."
        }, JSONObject().put("taken", false), recoverable = false)
        val side = if (front) "selfie" else "photo"
        return ShareScreenshotTool.handOver(context, file, target, caption, "took the $side")
    }

    override fun confirmationPrompt(args: JSONObject): String =
        args.str("app")?.let { "Take a photo and open $it to send it?" } ?: "Take a photo and share it?"
}
