package com.tekyerema.tools

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.MediaStore
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * E-21 — reading a camera app's viewfinder controls (shutter, front/rear switch) by caption or id. Pure, unit-tested.
 * Front-camera intent extras are undocumented and apps needn't honour them (AOSP Camera2 reads CAMERA_FACING, Open
 * Camera reads LENS_FACING_FRONT / USE_FRONT_CAMERA; nothing is published for Samsung Camera), so the tool sends them
 * all and then CHECKS the switch button's caption, flipping the camera itself when it is on the wrong side.
 */
object CameraUi {
    private val SHUTTER = Regex("^(?:take (?:a )?(?:picture|photo|pic|selfie)|capture|shutter(?: button)?|camera shutter|" +
        "take pictures?|photo shutter|shoot|camera button)$", RegexOption.IGNORE_CASE)
    private val SHUTTER_ID = Regex("shutter|capture_?button|btn_?capture|camera_?capture", RegexOption.IGNORE_CASE)
    /** A switch captioned by where it GOES: "Switch to front camera" means the rear camera is on now. A bare "Front
     *  camera" caption is NOT used — it could name the current side or the destination. */
    private val GOES_FRONT = Regex("\\bswitch to (?:the )?(?:front|selfie)\\b", RegexOption.IGNORE_CASE)
    private val GOES_BACK = Regex("\\bswitch to (?:the )?(?:rear|back|main)\\b", RegexOption.IGNORE_CASE)
    private val SWITCH_ANY = Regex("^(?:switch|flip|change|swap) (?:the )?cameras?$|^camera switch$|switch (?:between )?front and (?:rear|back)",
        RegexOption.IGNORE_CASE)

    enum class Facing { FRONT, BACK, UNKNOWN }

    fun shutter(snap: UiSnapshot): UiElement? {
        val byLabel = snap.elements.filter { !it.editable && it.label?.trim()?.let(SHUTTER::matches) == true }
        val byId = snap.elements.filter { !it.editable && it.viewId?.substringAfter(":id/")?.let(SHUTTER_ID::containsMatchIn) == true }
        return AppUi.preferClickable(byLabel) ?: AppUi.preferClickable(byId)
    }

    /** The camera that is on now, from the switch's caption; UNKNOWN when the caption doesn't say. */
    fun facing(snap: UiSnapshot): Facing {
        val labels = snap.elements.filter { it.clickable }.mapNotNull { it.label?.trim() }
        return when {
            labels.any { GOES_FRONT.containsMatchIn(it) && !GOES_BACK.containsMatchIn(it) } -> Facing.BACK
            labels.any { GOES_BACK.containsMatchIn(it) && !GOES_FRONT.containsMatchIn(it) } -> Facing.FRONT
            else -> Facing.UNKNOWN
        }
    }

    /** The control that flips the camera to [want], or null. A caption-less "Switch camera" is only used when the
     *  current side is KNOWN to be wrong — never as a coin flip. */
    fun switchTo(snap: UiSnapshot, want: Facing): UiElement? {
        val goes = if (want == Facing.FRONT) GOES_FRONT else GOES_BACK
        val other = if (want == Facing.FRONT) GOES_BACK else GOES_FRONT
        return snap.elements.firstOrNull { it.clickable && it.label?.let { l -> goes.containsMatchIn(l) && !other.containsMatchIn(l) } == true }
            ?: snap.elements.firstOrNull { it.clickable && it.label?.trim()?.let(SWITCH_ANY::containsMatchIn) == true }
                ?.takeIf { facing(snap).let { f -> f != Facing.UNKNOWN && f != want } }
    }

    /** Seconds to count down before the shutter: a selfie needs time to hold the phone up. Clamped 0–10. */
    fun countdown(requested: Int?, front: Boolean): Int = (requested ?: if (front) 3 else 2).coerceIn(0, 10)
}

/**
 * E-21 take_photo — take a photo (or a selfie with the front camera) in the phone's own camera app, so it lands in the
 * Gallery like any photo the user takes. Opens the camera in photo mode, makes sure the requested camera is on,
 * counts down with short beeps (the user can't see a timer), presses the shutter and checks the screen reacted.
 * Local, reversible (a photo can be deleted), nothing leaves the phone → TIER1. No new permission: the camera app
 * takes the picture.
 */
object TakePhotoTool : Tool {
    override val name = "take_photo"
    override val description = "Take a photo in the camera app after a beep countdown (camera=front for a selfie); saved to Gallery."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val verifiesOutcome = true
    override val params = listOf(
        ToolParam("camera", "string", "Default back.", required = false, enumValues = listOf("front", "back")),
        ToolParam("countdown", "integer", "Seconds.", required = false)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val front = args.str("camera")?.trim()?.lowercase() in setOf("front", "selfie", "user")
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE, recoverable = false)
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            // Undocumented facing hints — harmless where ignored; the switch caption is checked below either way.
            putExtra("android.intent.extras.CAMERA_FACING", if (front) 1 else 0)
            putExtra("android.intent.extras.LENS_FACING_FRONT", if (front) 1 else 0)
            putExtra("android.intent.extras.LENS_FACING_BACK", if (front) 0 else 1)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", front)
            putExtra("camerafacing", if (front) "front" else "rear")
        }
        val cameraPkg = try { intent.resolveActivity(ctx.appContext.packageManager)?.packageName } catch (_: Throwable) { null }
            ?: return ToolResult(false, "This phone has no camera app I can open.", recoverable = false)
        driveRefusal(cameraPkg)?.let { return ToolResult(false, "I can't take the photo: the camera — $it.", recoverable = false) }
        try { ctx.appContext.startActivity(intent) } catch (t: Throwable) {
            return ToolResult(false, "Couldn't open the camera: ${t.javaClass.simpleName}", recoverable = true)
        }
        val side = if (front) "front camera" else "back camera"
        var screen = awaitScreen(ctrl, AppConfig.long("os.camera.readyWaitMs", 7_000L)) { s ->
            s.appPackage == cameraPkg && CameraUi.shutter(s) != null
        } ?: return ToolResult(false, "The camera opened, but I couldn't find its shutter button.",
            JSONObject().put("taken", false), recoverable = true, channel = "intent")

        // Right side? Flip once if the switch's caption says we're on the other camera.
        val want = if (front) CameraUi.Facing.FRONT else CameraUi.Facing.BACK
        var facingNote = ""
        CameraUi.switchTo(screen, want)?.takeIf { CameraUi.facing(screen) != want }?.let { sw ->
            fun onWantedSide(s: UiSnapshot) = s.appPackage == cameraPkg && CameraUi.facing(s) == want && CameraUi.shutter(s) != null
            ctrl.clickElement(sw.id)
            var flipped = awaitScreen(ctrl, 1_500L) { s -> onWantedSide(s) }
            if (flipped == null) {
                // Samsung Camera (A20e, 2026-09-29) ignores the node click on its switch. Tap it once — only while the SAME
                // switch is still there, still saying it goes to the other side (so a slow flip is never flipped back).
                val still = try { ctrl.buildSnapshot() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { null }
                val again = still?.let { CameraUi.switchTo(it, want) }?.takeIf {
                    CameraUi.facing(still) != want && it.label == sw.label && it.bounds.overlaps(sw.bounds) && !it.bounds.isEmpty()
                }
                if (again != null) ctrl.tap(again.bounds.centerX, again.bounds.centerY)
                flipped = awaitScreen(ctrl, 3_000L) { s -> onWantedSide(s) }
            }
            screen = flipped
                ?: return ToolResult(false, "I couldn't switch to the $side.", JSONObject().put("taken", false), recoverable = true, channel = "a11y")
        }
        if (CameraUi.facing(screen) == CameraUi.Facing.UNKNOWN) facingNote = " I couldn't confirm which camera was on."

        beepCountdown(CameraUi.countdown(args.intOrNull("countdown"), front))
        val shot = awaitScreen(ctrl, 1_500L) { s -> s.appPackage == cameraPkg && CameraUi.shutter(s) != null }
            ?: return ToolResult(false, "The camera closed before the photo was taken.", JSONObject().put("taken", false), channel = "a11y")
        val button = CameraUi.shutter(shot)!!
        // ONE press only (no blind re-tap — that could take a second photo). Its effect is a new thumbnail / changed
        // controls or an announcement; an unchanged screen is reported as "couldn't tell", not as a photo.
        suspend fun reaction() = awaitScreen(ctrl, 2_500L, intervalMs = 300L) { s ->
            s.signature() != shot.signature() || s.announcements.isNotEmpty()
        } != null
        val pressed = ctrl.clickElement(button.id) || (!button.bounds.isEmpty() && ctrl.tap(button.bounds.centerX, button.bounds.centerY))
        var reacted = pressed && reaction()
        // Samsung Camera can ignore the node click (its camera switch did, A20e 2026-09-29): with no reaction at all, one
        // gesture tap on the same shutter. The worst case is a second photo, never anything sent or changed.
        if (!reacted && !button.bounds.isEmpty()) reacted = ctrl.tap(button.bounds.centerX, button.bounds.centerY) && reaction()
        return if (reacted) ToolResult(true, "Took the photo with the $side — it's saved in your Gallery.$facingNote",
            JSONObject().put("taken", true).put("camera", if (front) "front" else "back"), channel = "a11y")
        else ToolResult(false, "I pressed the shutter but couldn't tell whether a photo was taken — check the Gallery.",
            JSONObject().put("taken", false), recoverable = false, channel = "a11y")
    }

    /** One short beep per second, a higher one at zero — a countdown the user can hear. */
    private suspend fun beepCountdown(seconds: Int) {
        if (seconds <= 0) return
        val tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (_: Throwable) { null }
        try {
            repeat(seconds) { i ->
                tone?.startTone(if (i == seconds - 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 150)
                delay(1_000L)
            }
        } finally { tone?.release() }
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult =
        VerificationResult(result.success && result.data?.optBoolean("taken") == true, result.message)
}
