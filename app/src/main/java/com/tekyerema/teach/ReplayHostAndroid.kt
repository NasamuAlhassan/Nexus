package com.tekyerema.teach

import android.content.Context
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.policy.PolicyEngine
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.policy.ScreenPrivacy
import com.tekyerema.policy.StepGate
import com.tekyerema.policy.TargetSemantics
import com.tekyerema.tools.ResolvedTarget
import com.tekyerema.tools.Target
import com.tekyerema.tools.TargetResolver
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolManifest
import com.tekyerema.tools.defaultTools
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Teach mode — the phone side of a replay. Every step goes through the SAME gates the agent's steps do:
 * the tool's availability and caller list ([ToolManifest]), the target classification ([TargetSemantics.classify]), the
 * policy engine (tiers, the user's execution mode, switched-off capabilities, the deny list) and the hidden-target floor
 * ([StepGate.unseenFloor]). Anything that is not a plain ALLOW is NOT done here — it comes back as
 * [StepRun.NeedsYes] / [StepRun.Blocked] and the replay hands the rest to the agent, which asks with its usual read-back.
 */
class ReplayHostAndroid(
    private val appContext: Context,
    private val controller: AccessibilityController
) : ReplayHost {

    override suspend fun snapshot(): UiSnapshot? {
        if (!controller.ready) return null
        controller.waitForSettle(1500)
        return try { controller.buildSnapshot().takeIf { it.appPackage != null } } catch (_: Throwable) { null }
    }

    override fun foregroundPackage(): String? = controller.foregroundPackage()

    override fun isPrivate(snap: UiSnapshot): Boolean = ScreenPrivacy.isPrivate(snap)

    override fun appBlocked(pkg: String?): String? = when {
        pkg == null -> null
        pkg in PolicyConfig.denyApps() -> ScreenPrivacy.DENIED
        pkg in PolicyConfig.guideOnlyApps() -> ScreenPrivacy.GUIDE_ONLY
        else -> null
    }

    private fun tool(name: String): Tool? = defaultTools().firstOrNull { it.name == name }
        ?.takeIf { ToolManifest.isAvailable(it) && ToolManifest.mayCall(it, ToolManifest.CALLER_LOCAL) }

    override suspend fun openApp(step: TeachStep): StepRun {
        val stepTool = tool("open_app") ?: return StepRun.Failed("open_app unavailable")
        for (app in listOfNotNull(step.pkg, step.appName).distinct()) {
            val args = JSONObject().put("app", app)
            val pd = PolicyEngine().evaluate(stepTool, args)
            if (pd.outcome == PolicyOutcome.CONFIRM) return StepRun.NeedsYes(pd.confirmationPrompt ?: pd.reason)
            if (pd.outcome == PolicyOutcome.BLOCK) return StepRun.Blocked(pd.reason)
            val ctx = ToolContext(appContext, controller, NotificationStore)
            val r = stepTool.execute(args, ctx)
            if (r.success) { controller.waitForSettle(3000); return StepRun.Ok }
        }
        return StepRun.Failed("couldn't open ${step.appName ?: step.pkg}")
    }

    override suspend fun act(step: TeachStep, element: UiElement?, snap: UiSnapshot): StepRun {
        val toolName = when (step.kind) {
            StepKind.CLICK -> "tap"
            StepKind.LONG_CLICK -> "long_press"
            StepKind.TYPE -> "type"
            StepKind.SCROLL -> "scroll"
            StepKind.OPEN_APP -> return openApp(step)
        }
        val stepTool = tool(toolName) ?: return StepRun.Failed("$toolName unavailable")
        val args = JSONObject()
        if (element != null && element.source == ElementSource.TREE) args.put("element_id", element.id)
        when (step.kind) {
            StepKind.TYPE -> { args.put("text", step.text ?: return StepRun.Failed("no text kept")); if (step.submit) args.put("submit", true) }
            StepKind.SCROLL -> args.put("direction", step.direction ?: "down")
            else -> {}
        }
        // ---- the gates (the same order as the engine's step)
        val label = element?.label ?: step.label
        val cls = TargetSemantics.classify(toolName, element, label, step.submit, snap)
        var pd = PolicyEngine().evaluate(stepTool, args, hasUntrustedParams = false, targetTier = cls.tier, hardBlock = cls.block,
            context = PolicyEngine.ActionContext(label, cls.reason, snap.appPackage))
        pd = StepGate.unseenFloor(element, pd)
        if (pd.outcome == PolicyOutcome.CONFIRM) return StepRun.NeedsYes(pd.confirmationPrompt ?: pd.reason)
        if (pd.outcome == PolicyOutcome.BLOCK) return StepRun.Blocked(pd.reason)
        // ---- do it
        val ctx = ToolContext(appContext, controller, NotificationStore, observedBefore = snap,
            resolvedTarget = element?.let { ResolvedTarget(it, it.bounds.centerX, it.bounds.centerY, if (it.source == ElementSource.VISION) "vision" else "id") })
        stepTool.preflight(args, ctx)?.let { return StepRun.Failed(it.message) }
        val r = stepTool.execute(args, ctx)
        if (!r.success) return StepRun.Failed(r.message)
        controller.waitForSettle(2500)
        return StepRun.Ok
    }

    override suspend fun reveal(step: TeachStep, snap: UiSnapshot): UiSnapshot? {
        val label = step.label?.takeIf { it.isNotBlank() && (step.kind == StepKind.CLICK || step.kind == StepKind.LONG_CLICK) } ?: return null
        return try { TargetResolver.resolve(Target.ByLabel(label), controller, snap, allowScroll = true).second } catch (_: Throwable) { null }
    }

    override suspend fun pause(ms: Long) = delay(ms)
}
