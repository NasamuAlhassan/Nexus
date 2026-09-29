package com.tekyerema.agent

/**
 * The home screen is not a place to hunt for icons. Seen on the A20e (2026-09-28, "opening the calculator."): the
 * cloud model swiped the home screen left, right and up looking for the Calculator icon before trying open_app, and
 * the owner heard/felt "it swipes left and right on the home screen when it's supposed to just do the action".
 * `open_app` finds any installed app by name without touching the screen, so on the launcher a swipe/scroll the
 * user didn't ask for — or a tap on an icon label that isn't visible (the resolver would page the home screen to find
 * it) — is refused, and the model is told what to do instead. Pure, unit-tested.
 */
object LauncherGuard {
    /** The user asked for the gesture or the home-screen thing itself ("swipe left", "open the app drawer", "my widgets"). */
    private val USER_GESTURE = Regex("(?i)\\b(swipe|scroll|page|pages|widgets?|app drawer|all apps|home ?screen|wallpaper|folders?|drag|rearrange|move (?:the |an? )?(?:icon|app))\\b")
    private val HUNT_TOOLS = setOf("swipe", "scroll", "scroll_to_find", "drag", "fling")
    private val TAP_TOOLS = setOf("tap", "long_press")

    /**
     * @param onHome the observed foreground is the default launcher
     * @param labelMissing a tap/long_press names a label that isn't on the observed screen
     * @param named the app the goal is about ([AppMention.named]), if any
     * @param notInstalled the app name open_app just failed to find in this task, if any
     * @param targetLabel the label of the resolved tap target, if any
     * @param neverApps the names of the apps the user said "never touch" (D8) — open_app refuses them, and so does a
     *   tap on their home-screen icon (the model's other way in)
     * @return the refusal to give the model, or null to let the action through
     */
    fun check(tool: String, onHome: Boolean, labelMissing: Boolean, goal: String, named: String?,
              openAppTried: Boolean, notInstalled: String?, targetLabel: String? = null,
              neverApps: () -> Collection<String> = { neverAppNames() }): String? {
        if (onHome && tool in TAP_TOOLS && targetLabel != null) {
            val l = targetLabel.trim()
            neverApps().firstOrNull { it.trim().equals(l, ignoreCase = true) }?.let {
                return "the user said never to touch $it, so don't open it — not by its icon either. Tell them: " +
                    com.tekyerema.operator.AppRules.neverOpenLine(it)
            }
        }
        if (!onHome || USER_GESTURE.containsMatchIn(goal)) return null
        val hunting = tool in HUNT_TOOLS || (tool in TAP_TOOLS && labelMissing)
        if (!hunting) return null
        notInstalled?.let {
            return "open_app found no app called '$it', so it isn't on the home screen either — call ask_user to ask which app they meant."
        }
        if (named != null && !openAppTried) return "Don't page through the home screen for an icon — call open_app({\"app\":\"$named\"})."
        return "Don't page through the home screen for an icon — open the app by name with open_app, or ask_user if you don't know which app."
    }

    /** The user's "never touch" apps by name (their launcher labels, kept with the rule). */
    private fun neverAppNames(): Collection<String> = com.tekyerema.operator.OperatorPolicy.appRules.values
        .filter { it.rule == com.tekyerema.operator.AppRule.NEVER }.map { it.label }
}
