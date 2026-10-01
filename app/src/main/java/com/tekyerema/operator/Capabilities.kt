package com.tekyerema.operator

import com.tekyerema.agent.FastIntent
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier

/**
 * Which user-revocable [Capability] an action exercises (pure, unit-tested). Tools that name it outright (send_sms,
 * make_call, momo_send…) are simple; the GUI path is not: a tap on WhatsApp's "Send" is sending a message, a tap on a
 * number in the dialer is a call, "Pay GH₵ 50" is money. Those are read from what [com.tekyerema.policy.TargetSemantics]
 * already concluded about the resolved target (its tier and reason) plus the element's label, so revoking "Send
 * messages" can't be side-stepped by tapping the Send button instead of calling send_sms.
 *
 * Emergency help is never switched off: the user asked for help out loud, and it is still confirmed.
 */
object Capabilities {
    private val MONEY_TOOLS = setOf("momo_send", "mobile_money", "bank_ussd")   // + the guided bank menu (money/, round 2)
    private val CALL_TOOLS = setOf("make_call")
    private val MESSAGE_TOOLS = setOf("send_sms", "reply_to_notification", "share_text", "share_screenshot")
    private val SETTINGS_TOOLS = setOf("open_setting")
    val NEVER_SWITCHED_OFF = setOf("emergency")
    /** Tools that send something to an online service of ours (blocked while the cloud is off). */
    private val CLOUD_TOOLS = setOf("describe_screen")

    private val CALL_WORD = Regex("\\b(call|dial|video call|voice call)\\b", RegexOption.IGNORE_CASE)
    private val SEND_WORD = Regex("\\b(send|post|share|forward|reply|tweet|publish)\\b", RegexOption.IGNORE_CASE)
    /** The system Settings app and the Quick Settings shade: acting there changes the phone's settings. */
    private val SETTINGS_APPS = setOf("com.android.settings", "com.android.systemui", "com.samsung.android.settings",
        "com.google.android.settings.intelligence")

    /** Does this tool change anything on the phone (anything that isn't reading, or a plain web search)? */
    fun changesPhone(scope: CapabilityScope, tier: RiskTier): Boolean = when (scope) {
        CapabilityScope.PERCEIVE, CapabilityScope.COMMS_READ -> false
        CapabilityScope.WEB -> tier.ordinal >= RiskTier.TIER1.ordinal   // ask_app operates another app; web_search only reads
        else -> true
    }

    /** Does this tool need one of our online services? */
    fun needsCloud(tool: String, scope: CapabilityScope): Boolean = scope == CapabilityScope.WEB || tool in CLOUD_TOOLS

    /**
     * @param raisedTier the tier the TARGET or the ARGUMENTS imply (TargetSemantics / PolicyConfig.argTier), not the tool's
     *   own: TIER3 there means money (a spend button, a Mobile-Money USSD code).
     * @param targetReason TargetSemantics' reason for the resolved target, when it raised the tier.
     */
    fun of(
        tool: String,
        scope: CapabilityScope,
        changesPhone: Boolean,
        raisedTier: RiskTier? = null,
        targetReason: String? = null,
        targetLabel: String? = null,
        appPackage: String? = null,
        /** What a notification button or an app-defined action says it does ("Reply", "Call back", "Send"). */
        actionText: String? = null
    ): Capability? {
        if (tool in NEVER_SWITCHED_OFF) return null
        if (tool in MONEY_TOOLS || scope == CapabilityScope.FINANCIAL || raisedTier == RiskTier.TIER3) return Capability.MONEY
        if (tool in CALL_TOOLS) return Capability.CALLS
        if (tool in MESSAGE_TOOLS) return Capability.MESSAGES
        if (scope == CapabilityScope.WEB) return Capability.WEB
        if (tool in SETTINGS_TOOLS || scope == CapabilityScope.SETTINGS) return Capability.SETTINGS
        if (!changesPhone) return null
        if (!actionText.isNullOrBlank()) {
            if (CALL_WORD.containsMatchIn(actionText)) return Capability.CALLS
            if (SEND_WORD.containsMatchIn(actionText)) return Capability.MESSAGES
        }
        // The GUI path: only a target the semantics already flagged as consequential counts (an ordinary tap doesn't).
        if (raisedTier != null) {
            val reason = targetReason.orEmpty().lowercase()
            val label = targetLabel.orEmpty()
            if ("dial" in reason || "place a call" in reason || CALL_WORD.containsMatchIn(label)) return Capability.CALLS
            if ("send" in reason || SEND_WORD.containsMatchIn(label)) return Capability.MESSAGES
        }
        if (appPackage != null && appPackage in SETTINGS_APPS) return Capability.SETTINGS
        return null
    }

    /** The capability a fast-path command exercises (the fast path's own device controls). */
    fun ofFast(intent: FastIntent): Capability? = when {
        intent is FastIntent.QuickToggle || intent is FastIntent.OpenSetting || intent is FastIntent.Ringer ||
            intent is FastIntent.BrightnessSet -> Capability.SETTINGS
        intent.name in setOf("WIFI_ON", "WIFI_OFF", "BLUETOOTH_ON", "BLUETOOTH_OFF", "BRIGHTNESS_UP", "BRIGHTNESS_DOWN") -> Capability.SETTINGS
        intent is FastIntent.AskApp -> Capability.WEB
        else -> null
    }

    /** A fast-path command that needs our online vision (blocked while the cloud is off). */
    fun fastNeedsCloud(intent: FastIntent): Boolean =
        intent is FastIntent.DescribeScreen || (intent is FastIntent.Camera && intent.mode == "describe")

    // ------------------------------------------------------------------ words the user hears
    fun label(c: Capability): String = when (c) {
        Capability.MESSAGES -> "Send messages"
        Capability.CALLS -> "Make calls"
        Capability.MONEY -> "Money"
        Capability.SETTINGS -> "Change settings"
        Capability.WEB -> "Use the web"
    }

    /** "send messages for you" — what the user switched off, as a verb phrase. */
    fun doing(c: Capability): String = when (c) {
        Capability.MESSAGES -> "send messages for you"
        Capability.CALLS -> "make calls for you"
        Capability.MONEY -> "move money for you"
        Capability.SETTINGS -> "change your phone's settings"
        Capability.WEB -> "use the web"
    }

    /** What to say to switch it back on by voice. */
    fun restorePhrase(c: Capability): String = when (c) {
        Capability.MESSAGES -> "you can send messages again"
        Capability.CALLS -> "you can make calls again"
        Capability.MONEY -> "you can handle money again"
        Capability.SETTINGS -> "you can change settings again"
        Capability.WEB -> "you can use the web again"
    }

    fun revokedLine(c: Capability): String =
        "You've switched off “${label(c)}”, so I won't ${doing(c)}. Switch it back on in Settings, Control — or say “${restorePhrase(c)}”."

    const val CLOUD_OFF_LINE = "The cloud is off, so I'm working on this phone only — that needs the online AI. " +
        "Say “use the cloud again” to switch it back on."

    const val BUDGET_LINE = "You've reached today's limit for the online AI, so I'm working on this phone only until tomorrow. " +
        "You can change the limit in Settings, Control."

    /** Why the cloud can't be used right now, in words (switched off, or today's budget is spent). */
    fun cloudLine(op: OperatorPolicy.View): String = if (!op.cloudAllowed) CLOUD_OFF_LINE else BUDGET_LINE

    const val GUIDE_PREFIX = "I'm in guide-only mode, so I won't do it myself."
}

/**
 * WS-C — what the agent loop does right after a step was BLOCKED by one of the user's own choices (pure state, tested):
 *  - a capability the user switched off ends the task at once with their reason, spoken as is — never a quiet detour
 *    (the model must not, say, phone someone the user asked to text);
 *  - Guide only lets the model explain how (its history carries the reason); if it tries to act again, the task ends
 *    with a plain guide-only line instead of looping.
 */
object OperatorStops {
    private var task = Long.MIN_VALUE
    private var guideBlocks = 0

    const val GUIDE_STOP_LINE = "I'm in guide-only mode, so I won't do it myself. Ask me how to do it and I'll talk you " +
        "through it, or say “normal mode” to let me do it."

    /** @return the line to end the task with, or null to let the model carry on. */
    @Synchronized fun afterBlock(task: Long, operatorRule: String?): String? {
        if (operatorRule == null) return null
        if (task != this.task) { this.task = task; guideBlocks = 0 }
        return when {
            operatorRule.startsWith("off:") ->
                Capability.entries.firstOrNull { it.name == operatorRule.removePrefix("off:") }?.let { Capabilities.revokedLine(it) }
            operatorRule == "mode:GUIDE_ONLY" -> if (++guideBlocks >= 2) GUIDE_STOP_LINE else null
            else -> null
        }
    }

    const val GUIDE_DONE_PREFIX = "I'm in guide-only mode, so I haven't changed anything myself."

    /**
     * What a finished task says once Guide only blocked a step in it: the model's words, led by a plain statement that
     * nothing was done — so "I've turned Wi-Fi on" can never reach a blind user as if it had happened.
     */
    @Synchronized fun doneSummary(task: Long, summary: String): String =
        if (task == this.task && guideBlocks > 0 && !summary.trimStart().startsWith("I'm in guide-only mode")) "$GUIDE_DONE_PREFIX $summary"
        else summary
}

/** What the fast path should do with a command, given the user's execution mode and switched-off capabilities. */
sealed class FastHold {
    /** Run it as usual. */
    object Proceed : FastHold()
    /** Hand it to the agent instead, whose policy gate asks first (Ask every step) or explains how (Guide only). */
    object ToAgent : FastHold()
    /** Answer with this line and do nothing ([refusal] = it refuses what was asked, so the error cue plays). */
    data class Say(val line: String, val refusal: Boolean = true) : FastHold()
}

object FastGate {
    /** Commands that configure Tɛkyerɛma itself (how it speaks, schedules, routines, memory) — never "touching the phone". */
    private val ASSISTANT_OWN = setOf("PREFERENCE", "SET_EMERGENCY_CONTACT", "CREATE_SCHEDULE", "CLEAR_SCHEDULES", "WATCH_SENDER",
        "CLEAR_HISTORY", "SET_RELATION", "FORGET_RELATION", "CREATE_ROUTINE", "DELETE_ROUTINE", "UNDO", "FORGET")

    /** Emergency help is never held back by a mode or a switched-off capability. */
    private val ALWAYS = setOf("EMERGENCY_LINE")

    /** The fast commands a routine's steps resolve to (a routine runs them without coming back through this gate). */
    private val routineSteps: (String) -> List<FastIntent> = { name ->
        com.tekyerema.agent.Routines.get(name)?.steps.orEmpty()
            .mapNotNull { (com.tekyerema.agent.Routines.resolve(it) as? com.tekyerema.agent.Routines.Step.Fast)?.intent }
    }

    /**
     * @param isAction the fast path's own verdict that this command changes something (it has "did" words for it).
     * @param steps the fast commands of a routine (test seam).
     */
    fun check(
        intent: FastIntent,
        isAction: Boolean,
        op: OperatorPolicy.View = OperatorPolicy.view(),
        steps: (String) -> List<FastIntent> = routineSteps
    ): FastHold {
        if (intent.name in ALWAYS) return FastHold.Proceed
        Capabilities.ofFast(intent)?.let { c -> if (c in op.revoked) return FastHold.Say(Capabilities.revokedLine(c)) }
        if (!op.cloudUsable && Capabilities.fastNeedsCloud(intent)) return FastHold.Say(Capabilities.cloudLine(op))
        // A routine may not do, step by step, what the user switched off.
        if (intent is FastIntent.RunRoutine) for (s in steps(intent.routine)) {
            Capabilities.ofFast(s)?.let { c ->
                if (c in op.revoked) return FastHold.Say("Your “${intent.routine}” routine would ${Capabilities.doing(c)}. " +
                    Capabilities.revokedLine(c))
            }
        }
        // A routine runs several commands without coming back through this gate: hold the whole routine.
        val changes = (isAction && intent.name !in ASSISTANT_OWN) || intent is FastIntent.RunRoutine
        if (!changes) return FastHold.Proceed
        return when (op.mode) {
            ExecutionMode.STANDARD -> FastHold.Proceed
            // The agent's gate confirms every step that changes something (PolicyEngine) — the user is asked first.
            ExecutionMode.ASK_EVERY_STEP -> FastHold.ToAgent
            ExecutionMode.GUIDE_ONLY -> guide(intent)?.let { FastHold.Say("${Capabilities.GUIDE_PREFIX} $it", refusal = false) } ?: FastHold.ToAgent
        }
    }

    /** How to do a common command yourself (Samsung / stock Android wording); null = let the agent explain. */
    fun guide(intent: FastIntent): String? = when (intent) {
        FastIntent.VolumeUp -> "To turn it up, press the upper volume key on the side of your phone."
        FastIntent.VolumeDown -> "To turn it down, press the lower volume key on the side of your phone."
        FastIntent.VolumeMute -> "To mute, hold the lower volume key until the sound is off."
        FastIntent.FlashlightOn, FastIntent.FlashlightOff ->
            "Swipe down from the top of the screen with two fingers, find Flashlight, and double-tap it."
        FastIntent.WifiOn, FastIntent.WifiOff ->
            "Swipe down from the top of the screen with two fingers, find Wi-Fi, and double-tap it."
        FastIntent.BluetoothOn, FastIntent.BluetoothOff ->
            "Swipe down from the top of the screen with two fingers, find Bluetooth, and double-tap it."
        FastIntent.Back -> "Double-tap the Back button in the bar at the bottom of the screen."
        FastIntent.Home -> "Double-tap the Home button in the middle of the bar at the bottom of the screen."
        FastIntent.Recents -> "Double-tap the Recent apps button in the bar at the bottom of the screen."
        FastIntent.Notifications -> "Swipe down from the top of the screen with two fingers."
        is FastIntent.OpenApp -> "Go to the home screen, swipe up with two fingers to see all apps, and find ${intent.query}."
        FastIntent.MediaPlay, FastIntent.MediaPause, FastIntent.MediaStop ->
            "Open the app that's playing and double-tap its Play or Pause button."
        FastIntent.LockScreen -> "Press the power key on the side of your phone once."
        else -> null
    }
}
