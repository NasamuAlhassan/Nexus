package com.tekyerema.tools

import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * Tɛkyerɛma's own camera answering the user's question about something PHYSICAL in front of them — "what am I holding",
 * "how much money is this", "is the stove on", "what does this medicine box say", "when does this milk expire" — in any
 * wording, including Twi after translation (owner, 20 Oct 2026: "the app should be smart and understand the request"). The
 * camera screen answers out loud by itself (camera/VisionPrompts) and takes follow-up questions on the same photo. Opening
 * a camera view is reversible and nothing is sent until a frame is sharp → TIER1.
 */
object LookWithCameraTool : Tool {
    override val name = "look_with_camera"
    override val description = "Open Tɛkyerɛma's own camera to answer a question about something physical in front of the user " +
        "(an object, money, a label, a colour, a sign, light). It speaks the answer itself. Not for the phone's screen."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("question", "string", "The user's question, in English, e.g. 'how much money am I holding'.")
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val q = args.str("question")?.trim().orEmpty().ifEmpty { "What is in front of me?" }
        val mode = if (com.tekyerema.camera.VisionPrompts.isMoneyQuestion(q)) com.tekyerema.camera.CameraReadActivity.MODE_CURRENCY
            else com.tekyerema.camera.CameraReadActivity.MODE_DESCRIBE
        return try {
            ctx.appContext.startActivity(com.tekyerema.camera.CameraReadActivity.intent(ctx.appContext, mode, q))
            ToolResult(true, "The camera is open and answers out loud by itself. Finish now with one word, like 'Okay.'", channel = "native")
        } catch (t: Throwable) {
            ToolResult(false, "I couldn't open the camera.", recoverable = false)
        }
    }
}
