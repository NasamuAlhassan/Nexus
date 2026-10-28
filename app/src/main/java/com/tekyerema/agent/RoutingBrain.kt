package com.tekyerema.agent

import com.tekyerema.core.OfflineReason

/**
 * Hybrid online/offline router (directive §14/§36). One brain the engine talks to; internally it prefers
 * the on-device [LocalBrain] (fast path, works offline) and escalates to a cloud brain only when the local
 * brain cannot ground the request AND the network is available.
 *
 *  - local returns a CallTool/Done → use it (fast path, no cloud round-trip, §15).
 *  - local defers (can't work it out) or the goal needs the internet:
 *      • online + a cloud brain configured → hand the WHOLE remaining task to the cloud operator
 *        (sticky, so multi-step planning stays coherent);
 *      • offline (or no cloud) → refuse honestly: "I need an internet connection for that", or nudge the
 *        user toward a simpler local phrasing. Never silently fail, never fabricate (§12/§33).
 *
 * N-09 / N-10 / H-11 (overnight hybrid pass) — "online" means the internet ANSWERS, not that Android says connected:
 *  - [isOnline] is stall-aware ([com.tekyerema.core.LinkHealth] via NetworkMonitor), and when the last evidence is bad
 *    a quick [linkCheck] runs before a request is handed to the cloud — a dead link costs ~4 s, not a 35–80 s timeout;
 *  - a cloud step that comes back NETWORK_LOST before anything was done on the phone is answered like offline;
 *  - offline, the reply says what CAN be done: a recent answer to the same question ([AnswerCache], with its age),
 *    an offer to answer a question when the internet is back ([OfflineQueue], only if the user says yes), opening the
 *    app the request names, or the list of things that work offline;
 *  - online, a question answered recently is answered again at once from the cache (no round trip, no quota);
 *  - an optional on-device model ([LocalModelBrain], off by default, nothing bundled) gets one try offline.
 *
 * Pure of Android (connectivity is injected as [isOnline]) → unit-tested with fake brains.
 */
class RoutingBrain(
    private val local: AgentBrain,
    private val cloud: AgentBrain?,
    /** Optional goal rewrite BEFORE handing to the cloud operator — used to translate a natural Twi
     *  request to English, since the cloud brain can't plan from Twi. Returns null to keep the goal as-is
     *  (English, or translation unavailable). Applied once per task; the (possibly translated) goal is
     *  reused for every sticky cloud step so multi-step planning stays coherent. LocalBrain still handles
     *  command-style Twi directly (and answers in Twi) — this only fires when it DEFERS to the cloud.
     *  (Kept before [isOnline] so existing trailing-lambda callers `RoutingBrain(local, cloud){online}` work.) */
    private val goalForCloud: (suspend (String) -> String?)? = null,
    /** When true (and online with a cloud brain), route EVERY request to the cloud brain — skip the on-device
     *  LocalBrain fast path entirely. Used to make the relay (Claude) the brain for all requests, not just
     *  the escalated ones. Defaults false so normal local-first behaviour is unchanged. */
    private val cloudFirst: () -> Boolean = { false },
    /** The installed app a compound goal is about, as (label, package) — or null. When the local brain defers a
     *  task that names one, its FIRST step is to open it locally (no cloud round-trip); the cloud takes over
     *  from inside the app. Injected (needs PackageManager) so this class stays Android-free. */
    private val appForGoal: ((String) -> Pair<String, String>?)? = null,
    /** N-10: when the link's last evidence is bad, is the internet answering right now? null = can't tell (JVM tests,
     *  probing off) → trust [isOnline]. */
    private val linkCheck: (suspend () -> Boolean?)? = { if (com.tekyerema.core.LinkHealth.suspect()) com.tekyerema.core.LinkProbe.check() else null },
    /** Why the cloud can't be used — so the reply says "the internet isn't working" rather than "you're offline". */
    private val offlineReason: () -> OfflineReason = { try { com.tekyerema.core.NetworkMonitor.offlineReason() } catch (_: Throwable) { OfflineReason.NONE } },
    /** H-11: answers to recent questions (null = no cache). */
    private val answers: AnswerCache? = AnswerCache.shared,
    /** N-09: keep a question for when the internet is back (after the user said yes); false = couldn't. null = no queue. */
    private val enqueue: ((String) -> Boolean)? = { OfflineQueue.add(it) },
    /** N-09: is this goal already kept (i.e. this run IS the queued question being answered)? Then don't offer again. */
    private val queued: (String) -> Boolean = { OfflineQueue.store.contains(it) },
    /** C-04: optional on-device model for requests the rules don't match, used only without the cloud. */
    private val localModel: AgentBrain? = LocalModelBrain.ifEnabled(),
    private val isOnline: () -> Boolean
) : AgentBrain {

    companion object {
        /** C-17: the engine's history line for the previous step reports a failure, a rejection or an unverified result.
         *  A "partial" step (moved on, claim not met yet — G-21) is a deviation too: the local rules' routine expected
         *  that claim, so the rest of the task goes to the planner that can explore. */
        fun deviated(history: List<String>): Boolean {
            // The last STEP's line, not the last line: the engine adds its own notes after a step (Workstream B's recovery
            // ladder, the plan). With lastOrNull, a failed local play_music ("play the playlist on the screen") was never
            // handed to the planner, and the task ended claiming "Playing …" (A20e 2026-09-29).
            val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return false
            if (Regex("→ (?:fail|rejected|refused|BLOCKED|NOT )").containsMatchIn(last)) return true
            // A READ that succeeded is its own result — the screen can't "verify" it, so its verified=false is no
            // deviation (QA D10, A20e, 3 October 2026: the local read_notifications for "read my last message" ran fine,
            // was marked unverified, and the task went to the cloud, which opened Messages and read the wrong bubble).
            if (READ_ONLY_STEP.containsMatchIn(last) && last.contains("→ success")) return false
            return last.contains("verified=false") || last.contains("verified=partial")
        }

        /** The tool's own words for the last step when it failed and declared itself a dead end (Recovery.Kind.DEAD_END —
         *  the engine's history line carries the "genuine dead-end" hint), else null. Pure. */
        fun deadEnd(history: List<String>): String? {
            val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return null
            if (!last.contains("→ fail") || !last.contains("genuine dead-end")) return null
            // the tool's words only — not the planner-facing recovery hint the engine appends, nor the tool's own
            // guidance to the planner ("Tell the user that; don't open Messages to try.")
            return userFacing(LocalBrain.resultMessage(last).substringBefore(" — this route is a genuine dead-end")).ifBlank { "I couldn't do that." }
        }

        private val PLANNER_TALK = Regex("(?i)^(?:tell the user|ask the user|only if|don'?t open|do not open|never open|do not|use |call )|" +
            "\\b(?:ask_user|cannot_do|install_app|the planner)\\b")

        /** The sentences of a tool's message meant for the USER — everything before the first one addressed to the planner. Pure. */
        fun userFacing(message: String): String {
            val out = ArrayList<String>()
            for (s in message.split(Regex("(?<=[.!?])\\s+"))) {
                val t = s.trim().replace(Regex("\\s*\\((?:ask_user|cannot_do|install_app)\\)"), "")
                if (t.isEmpty()) continue
                if (PLANNER_TALK.containsMatchIn(t)) break
                out.add(t)
            }
            return out.joinToString(" ").trim()
        }

        const val DEAD_END = "dead_end"

        private val NOT_INSTALLED = Regex("I couldn't find an app called (.+?) on this phone")

        /** The plain line for a locally planned open_app that found no such app, or null. Pure. */
        fun appNotInstalled(history: List<String>): String? {
            val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return null
            if (!last.startsWith("open_app(") || !last.contains("→ fail")) return null
            val app = NOT_INSTALLED.find(last)?.groupValues?.get(1)?.trim() ?: return null
            // A step already succeeded in this task (an app was opened for it): this open_app was a later step of a plan — a
            // PAGE taken for an app ("go to the Software information page", Pixel emulator, 12 October 2026). That is a
            // deviation for the planner, which can find the page from inside the open app; not an app that isn't installed.
            if (history.any { it !== last && STEP_LINE.containsMatchIn(it) && it.contains("→ success") }) return null
            return "I couldn't find an app called $app on this phone — it isn't installed, or I misheard the name. Say it again if I got it wrong."
        }

        /**
         * The user answered a read-back with a change the engine could not apply itself (ReviseTurn), and nothing has run
         * since: only a planner can re-plan it. The on-device rules must not be asked — with no step of their own to
         * report they ended the task by reading the engine's note aloud ("You proposed: … The user did NOT approve it …",
         * A20e audit 2026-10-01, C09/K01) or with a routine "Opened Mobile Money" (F08). Pure.
         */
        fun pendingRevision(history: List<String>): Boolean {
            val i = history.indexOfLast { it.startsWith("You proposed:") && !it.contains("it is being proposed again") }
            return i >= 0 && history.drop(i + 1).none { STEP_LINE.containsMatchIn(it) }
        }

        /** Said when a correction can't be re-planned because no planner is reachable. Nothing ran. */
        const val REVISION_NEEDS_PLANNER = "I couldn't make that change just now. Nothing was done — please say the whole request again."

        /**
         * SECURE BOUNDARY: momo_send stopped at the Mobile Money PIN — the task is OVER. Its own words ("enter your PIN
         * to send …") are the reply; no brain plans another step. A20e 2026-09-29: after a live 0.1-cedi send reached
         * MTN's PIN prompt ("… Enter MM # for next", no word "PIN" in it, so the credential-screen guard didn't fire),
         * the step read as "not verified", the router escalated to the cloud planner, and it tried to tap the PIN field
         * "to finish sending money" (the policy asked first; the owner stopped it). Pure.
         */
        fun pinHandoff(history: List<String>): String? {
            val last = LocalBrain.lastStep(history).ifEmpty { return null }
            if (!last.startsWith("momo_send(") || !last.contains("→ success")) return null
            return LocalBrain.resultMessage(last).ifBlank { "The Mobile Money PIN screen is up — enter your PIN yourself. I never handle the PIN." }
        }

        /**
         * Money safety (2026-09-29): the momo_send step that ENDS the task, as (status, the line to say) — the hand-off at
         * the PIN ([pinHandoff]: MTN's own confirmation with the recipient's registered name), or a walk of the menu that
         * failed or was cancelled because MTN's screen didn't match (nothing was sent — no planner improvises inside a
         * money menu afterwards; the user can simply ask again). A preflight refusal (a misheard number) is not an end:
         * the planner asks for the number again. AgentEngine checks this at the top of every step, before the screen is
         * looked at, so the confirmation is said BEFORE the PIN rather than after the Secure Input hand-off. Pure.
         */
        /** A history line that records a tool step ("tool(args) → ok/fail: …"). */
        fun isStepLine(line: String): Boolean = STEP_LINE.containsMatchIn(line)

        fun moneyEnd(history: List<String>): Pair<AgentStatus, String>? {
            // Money safety round 2 (money/MoneyPlan): a send refused by the user's spending limit, and a guided bank step that
            // failed or reached a payment confirmation / PIN, end here too.
            com.tekyerema.money.MoneyPlan.end(history)?.let { return it }
            // The last STEP's line — the engine may add its own notes (recovery, plan) after it.
            val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return null
            if (!last.startsWith("momo_send(")) return null
            pinHandoff(listOf(last))?.let { return AgentStatus.COMPLETED to it }
            if (!last.contains("→ fail") || last.contains("[preflight]")) return null
            val line = RECOVERY_HINT.split(LocalBrain.resultMessage(last), 2)[0].trim()
            return AgentStatus.CANNOT_DO to line.ifBlank { "The Mobile Money send didn't finish. Nothing was sent." }
        }

        /** A step's own line in history ("momo_send(number=…) → success …"), as opposed to a note the engine added. */
        private val STEP_LINE = Regex("^[a-z_]+\\(")
        /** The engine's recovery advice appended to a failed step in history (Recovery.hint) — for the planner, never said. */
        private val RECOVERY_HINT = Regex(" — (?:this route|re-observe|only the user|the '[^']*' accelerator)")

        /** Reason class of a local request the offline router could not do (spoken detail differs by case). */
        const val OFFLINE_UNKNOWN = "offline_unknown_command"

        /** Tools that change nothing — a history of only these means nothing has been done on the phone yet. */
        // (search_contacts / find_contact added 3 October 2026: "what is Richmond's number" was answered on the phone and then
        // handed to the cloud as a deviation because the look-up "didn't verify" — QA R_D11.)
        private val READ_ONLY_STEP = Regex("^(?:read_state|read_screen|read_all|read_notifications|web_search|find_tool|wait|ask_user|search_contacts|find_contact)\\(")
    }

    @Volatile private var stickyCloud = false

    /** Q-02: did any step of this task go to the cloud operator? */
    val usedCloud: Boolean get() = stickyCloud
    @Volatile private var cloudGoal: String? = null   // cached (possibly translated) goal for the cloud
    /** N-09: the question we offered to answer later; the user's reply comes back through ask_user. */
    @Volatile private var offered: String? = null
    /** C-04: the on-device model took this task — later steps finish with it. */
    @Volatile private var stickyLocalModel = false

    private fun cloudCtx(ctx: BrainContext): BrainContext =
        cloudGoal?.takeIf { it != ctx.goal }?.let { ctx.copy(goal = it) } ?: ctx

    /** Lightweight Twi signal (§4.6) — Twi-specific letters or a few unambiguous stems. Pure, no Android. */
    private fun looksTwi(s: String): Boolean =
        s.any { it in "ɛɔŋʋɩ" } || Regex("\\b(frɛ\\w*|bue\\w*|kɔ|sɛ|mepɛ|kenkan|nkra|aane|daabi|wopɛ)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)

    override suspend fun decide(ctx: BrainContext): AgentDecision {
        // N-09: the user answered our "should I find out when the internet is back?" offer.
        offered?.let { return answerOffer(ctx, it) }
        // (Trust 2026-09-29: the task stopped at the PIN — the helper can't be brought in now; agent/helper/HelperGate.)
        pinHandoff(ctx.history)?.let { com.tekyerema.agent.helper.HelperGate.atPin(); return AgentDecision.Done(it) }
        // Trust (2026-09-29): "call my helper" / "tell my helper what you did" are planned on the phone, online or offline,
        // whichever brain is in charge (agent/helper/TrustedHelper.kt); the engine's gate confirms the call or the text.
        com.tekyerema.agent.helper.HelperPlan.decide(ctx.goal, ctx.history)?.let { return it }
        // Money safety round 2 (money/MoneyPlan): "try again" after a *170# timeout, "I sent money to the wrong number" and the
        // guided bank menu are decided on the phone, online or offline — no model picks a money or bank-menu step.
        // The user's own money controls ("set my mobile money limit to 20 cedis per send", "remove my limit", "what's my
        // limit") are answered here for every way a request arrives — the voice turn's fast path handles them before the
        // agent, but a typed request or the debug hook reaches the agent directly, and "limit to 20 cedis per send" was then
        // read back as "Send 20 cedis to per send" (emulator, 13 October 2026). First step only; never a planner matter.
        if (ctx.history.isEmpty()) com.tekyerema.money.MoneyCommands.handle(ctx.goal)?.let { return AgentDecision.Done(it.spoken) }
        // The network dropped while MTN's app was doing a money task (money/AppToUssd): said at once; the USSD menu offered when
        // it can do the request, and never after the read-back or the PIN (the money may already have gone). Before MoneyPlan,
        // which would otherwise start a menu walk on top of the app's steps.
        val menuCanDoIt = try { com.tekyerema.money.MoneyAsk.parse(ctx.goal)?.kind in com.tekyerema.money.MoneyFlow.APP_KINDS } catch (_: Throwable) { false }
        when (val o = com.tekyerema.money.AppToUssd.decide(ctx.history, isOnline(), menuCanDoIt) {
            com.tekyerema.voice.Confirmation.interpret(it) == com.tekyerema.voice.Confirmation.Answer.YES }) {
            is com.tekyerema.money.AppToUssd.Outcome.Ask -> {
                android.util.Log.i("Tekyerema", "[ROUTE] the network dropped mid MTN-app task → asking to finish on the USSD menu")
                return AgentDecision.CallTool("ask_user", org.json.JSONObject().put("question", com.tekyerema.money.AppToUssd.ASK), "network dropped mid app task")
            }
            is com.tekyerema.money.AppToUssd.Outcome.Stop -> { stickyCloud = false; return AgentDecision.Done(o.line) }
            is com.tekyerema.money.AppToUssd.Outcome.Switch -> {
                stickyCloud = false
                val rest = ctx.copy(history = o.history)
                return com.tekyerema.money.MoneyPlan.decide(rest.goal, rest.history) ?: local.decide(rest)
            }
            null -> {}
        }
        com.tekyerema.money.MoneyPlan.decide(ctx.goal, ctx.history)?.let { return it }
        if (stickyLocalModel && localModel != null) return localModel.decide(ctx)

        // Cloud-first: hand EVERYTHING to the cloud brain (the relay = Claude), skipping LocalBrain. Sticky so
        // multi-step planning stays coherent; the (optional) Twi→English rewrite still runs once.
        // N-10: not on a weak connection — the on-device brain answers what it can there without a slow round-trip.
        // C-01: with the local_brain tier switched off, every request goes to the cloud operator the same way.
        val skipLocal = !RouterTiers.enabled(RouterTiers.LOCAL)
        if (!stickyCloud && (cloudFirst() || skipLocal) && cloud != null && isOnline() && (skipLocal || !isWeak())) {
            android.util.Log.i("Tekyerema", "[ROUTE] cloud-first: all requests → cloud brain '${com.tekyerema.core.Privacy.content(ctx.goal.take(60))}'")
            stickyCloud = true
            if (cloudGoal == null) cloudGoal = cloudGoalFor(ctx.goal)
        }
        if (stickyCloud && cloud != null) return fromCloud(ctx, cloud.decide(cloudCtx(ctx)))

        // A step the rules planned hit a dead end the TOOL itself declared (recoverable=false — the network refused the
        // text, a permission is missing): no on-screen route gets round it, so the task ends with the tool's own line. It
        // used to go to the cloud as a "deviation", which drove Samsung Messages for 9 model calls / 60 s to re-send a text
        // the network had just refused for lack of airtime (QA R_TW03, A20e, 3 October 2026).
        if (!stickyCloud) deadEnd(ctx.history)?.let { line ->
            android.util.Log.i("Tekyerema", "[ROUTE] the local step hit a dead end its tool declared — ending honestly, no planner")
            return AgentDecision.CannotDo(DEAD_END, line)
        }
        // "Open Signal" when no such app is installed: the phone already knows, so it says so — the cloud used to be asked
        // (one model call, 4 s) only to ask the user which app they meant (QA E06, A20e, 3 October 2026).
        if (!stickyCloud) appNotInstalled(ctx.history)?.let { line ->
            android.util.Log.i("Tekyerema", "[ROUTE] the app isn't installed — said on the phone, no planner")
            return AgentDecision.CannotDo(DEAD_END, line)
        }
        // C-17: the local brain's last step failed or didn't verify — a deviation from its routine plan. Hand the rest
        // of the task to the cloud operator (sticky) instead of letting the rules try the same thing again.
        if (cloud != null && isOnline() && deviated(ctx.history) &&
            com.tekyerema.core.config.AppConfig.bool("router.escalation.onDeviation", true)) {
            android.util.Log.i("Tekyerema", "[ROUTE] local step deviated → escalating to cloud brain mid-task")
            stickyCloud = true
            if (cloudGoal == null) cloudGoal = cloudGoalFor(ctx.goal)
            return fromCloud(ctx, cloud.decide(cloudCtx(ctx)))
        }

        // A correction at a read-back that the engine couldn't apply itself goes to the planner — never to the rules.
        if (pendingRevision(ctx.history)) {
            if (cloud != null && isOnline()) {
                android.util.Log.i("Tekyerema", "[ROUTE] the user corrected a proposal → the cloud brain re-plans it")
                stickyCloud = true
                if (cloudGoal == null) cloudGoal = cloudGoalFor(ctx.goal)
                return fromCloud(ctx, cloud.decide(cloudCtx(ctx)))
            }
            return AgentDecision.CannotDo(OFFLINE_UNKNOWN, REVISION_NEEDS_PLANNER)
        }

        val d = local.decide(ctx)
        val escalatable = d is AgentDecision.CannotDo && (d.reasonClass == LocalBrain.DEFER || d.reasonClass == LocalBrain.NETWORK)
        if (!escalatable) return d   // CallTool / Done / a genuine local CannotDo

        val needsNet = (d as AgentDecision.CannotDo).reasonClass == LocalBrain.NETWORK
        // H-11: the same question was answered a moment ago — say it again now (no round trip, no quota).
        if (ctx.simple && ctx.history.isEmpty()) answers?.fresh(ctx.goal)?.let {
            android.util.Log.i("Tekyerema", "[ROUTE] answered from the recent-answer cache")
            return AgentDecision.Done(it)
        }
        if (cloud != null && isOnline() && linkAnswers()) {
            // Step one of a task about a named app: open it here — deterministic, ~0 ms instead of a round-trip.
            if (!stickyCloud && ctx.history.isEmpty()) appForGoal?.invoke(ctx.goal)?.let { (label, pkg) ->
                if (ctx.screen.optString("app") != pkg) {
                    stickyCloud = true
                    if (cloudGoal == null) cloudGoal = cloudGoalFor(ctx.goal)
                    // N-09 (3): "open Settings and go to about phone and tell me …" — straight to the page by its own intent
                    // (one step, no scroll down the list), and the cloud brain takes over from there.
                    settingsPageIn(ctx.goal, pkg)?.let { page ->
                        android.util.Log.i("Tekyerema", "[ROUTE] goal names the ${page.spoken} page of $label → opening it by its own intent first, then the cloud brain")
                        return AgentDecision.CallTool("open_setting", org.json.JSONObject().put("target", page.target),
                            "local:goal names the ${page.spoken} page", expects = "foreground_is: ${page.title}")
                    }
                    android.util.Log.i("Tekyerema", "[ROUTE] goal names $label → opening it locally first, then the cloud brain")
                    return AgentDecision.CallTool("open_app", org.json.JSONObject().put("app", label), "local:goal names $label",
                        expects = "foreground_is: $pkg")
                }
            }
            if (!stickyCloud)   // log the escalation ONCE per task so it's visible live during the demo
                android.util.Log.i("Tekyerema", "[ROUTE] local ${d.reasonClass} '${com.tekyerema.core.Privacy.content(ctx.goal.take(60))}' → escalating to cloud brain")
            stickyCloud = true
            if (cloudGoal == null) cloudGoal = cloudGoalFor(ctx.goal)
            return fromCloud(ctx, cloud.decide(cloudCtx(ctx)))
        }
        // C-04: no cloud right now — the optional on-device model gets one try at a request the rules didn't match.
        if (!needsNet && localModel != null && ctx.history.isEmpty()) {
            val m = localModel.decide(ctx)
            if (m is AgentDecision.CallTool) {
                android.util.Log.i("Tekyerema", "[ROUTE] offline → on-device model chose ${m.name}")
                stickyLocalModel = true
                return m
            }
        }
        // offline or no cloud brain configured — never silently do nothing (§A.4), and this is a FAILURE,
        // not a completion, so it must be CannotDo → status CANNOT_DO in history, never COMPLETED (§1.2).
        return offlineReply(ctx, needsNet, lost = false)
    }

    /** The cloud's decision, with two N-09/H-11 touches: a direct answer is remembered; a lost connection before
     *  anything was done on the phone is answered the offline way (cached answer / offer / what works offline). */
    private fun fromCloud(ctx: BrainContext, proposed: AgentDecision): AgentDecision {
        // A Twi request was planned with its names, amounts and numbers as TAGS ([P1], [A1], [N1]): the real values go back
        // in HERE, before anything acts on, asks about or says the decision (language/EntityTags; owner, 2 October 2026).
        val untagged = com.tekyerema.language.TwiTagSession.forGoal(ctx.goal)?.entities?.takeIf { it.isNotEmpty() }
            ?.let { TagRestore.restoreTags(proposed, it) } ?: proposed
        // No model walks a money menu or dials a code it made up (money/MoneyFlow.fromPlanner).
        // …and no call, text or money action stands on a recipient or an amount this task never stated or found
        // (agent/FinalActionGuard: asked for instead — an example in the instructions or an earlier request's person or
        // amount is never used; end-to-end pass, 6 October 2026).
        val d = openInsteadOfHunting(ctx, FinalActionGuard.apply(com.tekyerema.money.MoneyFlow.fromPlanner(untagged, ctx.goal, ctx.history),
            ctx.goal, ctx.history, ctx.screen.toString(), ctx.screen.optString("app").takeIf { it.isNotBlank() }))
        if (d is AgentDecision.Done && ctx.simple) answers?.put(ctx.goal, d.summary)
        // Mid-task, the internet drops and all that is left is to READ what the last step opened: reading needs no internet (the
        // screen tree, or OCR on the phone), so it is done here instead of pausing for "carry on" (Infinix, 27 Oct 2026, the
        // judge's demo: "open the most recent PDF and read it" stopped with the PDF open and the answer one step away).
        if (d is AgentDecision.CannotDo && d.reasonClass == FallbackBrain.NETWORK_LOST && !nothingDoneYet(ctx.history))
            readWhatIsOpen(ctx)?.let { return it }
        if (d is AgentDecision.CannotDo && d.reasonClass == FallbackBrain.NETWORK_LOST && nothingDoneYet(ctx.history)) {
            android.util.Log.i("Tekyerema", "[ROUTE] the cloud couldn't be reached — answering the offline way")
            stickyCloud = false
            return offlineReply(ctx, needsNet = true, lost = true)
        }
        return d
    }

    private fun nothingDoneYet(history: List<String>): Boolean = history.all { READ_ONLY_STEP.containsMatchIn(it) }

    /**
     * The task's LAST clause is a reading ("…, open the most recent PDF and read it") and the step before it was verified: read
     * the screen on the phone and finish. null (the task pauses as before) when the last clause is anything else, the previous
     * step was not verified (the screen may not be the one meant), or the reading itself already failed.
     */
    internal fun readWhatIsOpen(ctx: BrainContext): AgentDecision? {
        val clause = ctx.goal.split(Regex("\\s*(?:,|;|\\bthen\\b|\\band\\b)\\s*")).lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (!ScreenReadIntent.isRead(clause)) return null
        val last = LocalBrain.lastStep(ctx.history)
        if (last.startsWith("read_screen(")) {
            return if ("→ ok" in last || "→ success" in last) AgentDecision.Done(LocalBrain.resultMessage(last)) else null
        }
        if (!Regex("verified=true").containsMatchIn(last)) return null
        return AgentDecision.CallTool("read_screen", org.json.JSONObject().put("continue", false),
            "the internet dropped; reading what is open needs none")
    }

    /**
     * The planner reaches for the HOME SCREEN (a tap, a long-press, a swipe) while the goal names an installed app that
     * isn't open: that app is opened by name instead. The engine's LauncherGuard would refuse the gesture and tell the model
     * to call open_app — which cost two refused steps and ended in "Which app would you like to open?" (Pixel emulator,
     * 12 October 2026). Deterministic, logged, and never for a gesture the user asked for. Pure given [appForGoal].
     */
    internal fun openInsteadOfHunting(ctx: BrainContext, d: AgentDecision): AgentDecision {
        if (d !is AgentDecision.CallTool || d.name !in HUNT_ON_HOME) return d
        val screenApp = ctx.screen.optString("app")
        if (!isHomeScreen(screenApp) || LauncherGuard.userAskedGesture(ctx.goal)) return d
        val (label, pkg) = try { appForGoal?.invoke(ctx.goal) } catch (_: Throwable) { null } ?: return d
        if (pkg == screenApp) return d
        if (ctx.history.any { it.startsWith("open_app(") && it.contains(label, ignoreCase = true) }) return d   // tried already
        android.util.Log.i("Tekyerema", "[ROUTE] the planner reached for the home screen (${d.name}) — opening $label by name instead")
        return AgentDecision.CallTool("open_app", org.json.JSONObject().put("app", label),
            "local:goal names $label (instead of ${d.name} on the home screen)", expects = "foreground_is: $pkg")
    }

    private val HUNT_ON_HOME = setOf("tap", "long_press", "swipe", "scroll", "drag")

    /** N-09 (3): the Settings page a goal names right after "open Settings" ("… and go to about phone and …"), when Android
     *  opens that page directly (SettingsPages); null for any other app, no page, or a page without its own intent. Pure. */
    internal fun settingsPageIn(goal: String, pkg: String): SettingsPages.Page? {
        if (!pkg.endsWith(".settings")) return null
        val clauses = LocalBrain.splitClauses(goal) ?: return null
        val i = clauses.indexOfFirst { OPENS_SETTINGS.containsMatchIn(it) }
        if (i < 0 || i + 1 >= clauses.size) return null
        val label = LocalBrain.inAppNavTarget(clauses[i + 1])
            ?: return SettingsPages.topicIn(clauses[i + 1])   // "… and tell me my font size": the page that shows it
        return SettingsPages.find(label) ?: SettingsPages.holderOf(label)
    }
    private val OPENS_SETTINGS = Regex("(?i)^(?:please\\s+|kindly\\s+)?(?:open|launch|start|go\\s+to|bring\\s+up|bue\\S*)\\b.*\\bsettings\\b")
    /** The default launcher, by package name — every stock and OEM launcher carries the word (nexuslauncher, launcher3,
     *  com.sec.android.app.launcher, …). Android-free on purpose. */
    private fun isHomeScreen(pkg: String?): Boolean = pkg != null && pkg.lowercase().contains("launcher")

    /** The link's last evidence was bad → probe before waiting on a cloud call (N-10). No evidence → go ahead. */
    private suspend fun linkAnswers(): Boolean {
        val ok = try { linkCheck?.invoke() } catch (_: Throwable) { null }
        if (ok == false) android.util.Log.w("Tekyerema", "[ROUTE] the internet isn't answering → staying on the phone")
        return ok != false
    }

    /**
     * What to say (or offer) when the cloud can't be used. Order: a recent answer to this very question; an offer to
     * answer the question when the internet is back; opening the app the request names; what was done so far; the
     * list of things that work offline. Always honest about WHY: offline, stalled, or no online assistant set up.
     */
    private fun offlineReply(ctx: BrainContext, needsNet: Boolean, lost: Boolean): AgentDecision {
        val reason = if (lost) OfflineReason.STALLED else offlineReason()
        val noCloud = cloud == null && reason == OfflineReason.NONE
        val lead = when {
            noCloud -> "No online assistant is set up"
            reason == OfflineReason.STALLED -> "The internet isn't working right now"
            reason == OfflineReason.SLOW -> "The internet is slow right now"
            else -> "I'm offline"
        }
        val reasonClass = if (needsNet) LocalBrain.NETWORK else OFFLINE_UNKNOWN
        // H-11: an answer from earlier, with its age — better than nothing for "what's the dollar rate".
        if (ctx.simple && ctx.history.isEmpty()) answers?.stale(ctx.goal)?.let { (answer, age) ->
            return AgentDecision.Done("$lead, but when I checked ${AnswerCache.spokenAge(age)}: $answer")
        }
        // N-09: offer to answer it when the internet is back — only if the user says yes (C-13 ask_user).
        if (needsNet && !noCloud && enqueue != null && ctx.history.isEmpty() &&
            OfflineQueueStore.queueable(ctx.goal) && !queued(ctx.goal)) {
            offered = ctx.goal
            val q = "$lead, so I can't look that up. Should I find out when the internet is back?"
            return AgentDecision.CallTool("ask_user", org.json.JSONObject().put("question", q), "offline: offer to answer later")
        }
        // Part of a compound task was done on the phone before it needed the internet.
        if (ctx.history.isNotEmpty() && !nothingDoneYet(ctx.history))
            return AgentDecision.CannotDo(reasonClass, "$lead, so I did the first part but can't finish the rest.")
        // The request names an app: that much can be done without the internet.
        if (ctx.history.isEmpty()) appForGoal?.let { f -> try { f(ctx.goal) } catch (_: Throwable) { null } }?.let { (label, pkg) ->
            if (ctx.screen.optString("app") != pkg)
                return AgentDecision.CannotDo(reasonClass, "$lead, so I can't do all of that. I can open $label and read the screen to you — say \"open $label\".")
        }
        return AgentDecision.CannotDo(reasonClass, when {
            noCloud && needsNet -> "That needs the internet and an online assistant, which isn't set up. On the phone I can " + LocalBrain.offlineCapabilities() + "."
            noCloud -> "I don't know that command yet, and no online assistant is set up. On the phone I can " + LocalBrain.offlineCapabilities() + "."
            needsNet && reason == OfflineReason.STALLED -> "The internet isn't working right now, so I can't do that one. Offline I can still " +
                LocalBrain.offlineCapabilities() + "."
            needsNet && reason == OfflineReason.SLOW -> "The internet is slow right now, so I can't do that one yet. Offline I can still " +
                LocalBrain.offlineCapabilities() + "."
            needsNet -> "I don't have internet, so I can't do that one right now. Offline I can still " + LocalBrain.offlineCapabilities() + "."
            else -> "$lead and I don't know that command yet. Offline I can " + LocalBrain.offlineCapabilities() + "."
        })
    }

    /** N-09: the user's reply to the offer (the engine put it in history as `ask_user(…) → the user answered: "…"`). */
    private fun answerOffer(ctx: BrainContext, goal: String): AgentDecision {
        offered = null
        val reply = Regex("the user answered: \"(.*)\"\\s*$").find(ctx.history.lastOrNull().orEmpty())?.groupValues?.get(1)
        return when (OfflineQueueStore.yesNo(reply)) {
            true -> if (enqueue?.invoke(goal) == true) AgentDecision.Done("Okay. I'll find out when the internet is back, and tell you.")
                else AgentDecision.Done("Sorry, I'm already keeping as many questions as I can for later. Please ask again when the internet is back.")
            false -> AgentDecision.Done("Okay, I won't.")
            null -> AgentDecision.Done("Okay, I'll leave it for now.")   // unclear → keep nothing (the safe default)
        }
    }

    private fun isWeak(): Boolean = try { com.tekyerema.core.NetworkMonitor.isWeak() } catch (_: Throwable) { false }

    /** The goal as the cloud should see it (computed once per task). §4.6 — do NOT replace the user's Twi with the
     *  machine translation: Khaya MT can mangle it (observed: "…tell Prince to come home" → "…asks print to clean
     *  houses") and the LLM then plans off garbage. Give the model BOTH its own reading of the Twi AND the rough
     *  translation as a hint. (Translation only fires for Twi, so `translated != null` ⇒ the goal is Twi.) If MT is
     *  unavailable but the goal is Twi, still send the raw Twi with an interpret hint. null ⇒ use the goal as-is. */
    private suspend fun cloudGoalFor(goal: String): String? {
        val translated = goalForCloud?.invoke(goal)?.takeIf { it.isNotBlank() }
        // The request as tagged for translation: names, amounts and numbers as [P1] / [A1] / [N1]. The planner is told
        // what KIND each tag is — never the value — and writes the tags back; fromCloud puts the real values in.
        val tags = com.tekyerema.language.TwiTagSession.forGoal(goal)
        val twi = tags?.twiTagged ?: goal
        val facts = tags?.entities?.takeIf { it.isNotEmpty() }?.let { es ->
            "\nTags in this request (the phone holds their real values): " + es.joinToString("; ") { e ->
                e.tag + " = " + when (e.kind) {
                    com.tekyerema.language.EntityTags.Kind.PERSON -> "a person's name"
                    com.tekyerema.language.EntityTags.Kind.AMOUNT -> "an amount of money"
                    com.tekyerema.language.EntityTags.Kind.NUMBER -> "a phone number"
                    com.tekyerema.language.EntityTags.Kind.QUANTITY -> "a number"
                }
            } + ". Write each tag exactly as it is (e.g. [P1]) wherever you mean it — in tool arguments, questions and " +
                "replies. Never translate, spell out or guess the value of a tag."
        }.orEmpty()
        return when {
            // The answer and any question go back through the English→Twi translator (ReplyLocalizer), which only takes
            // plain English: so the planner answers in plain English (2 October 2026: an English question was read out by
            // the Twi voice, and a Twi answer of the planner's own was wrong Twi).
            translated != null ->
                "The user spoke Asante Twi: \"$twi\"\n" +
                "A machine translation (it can be WRONG — trust the Twi if they disagree; speech recognition may also have run " +
                "words together or garbled numbers): \"$translated\"\n" +
                "Interpret the request and act on it; do NOT type it verbatim. Write every reply and question in short, plain " +
                "English — it is translated into Twi for the user." + facts
            // No translation came (the translator cold or down): the tags only ever protected names and amounts from the
            // TRANSLATOR, so the planner gets the user's own words with them in — with tags alone it asked "what is the
            // name of the person whose messages you want?" for a name that was in the request (QA TR04, 3 October 2026).
            looksTwi(goal) || facts.isNotEmpty() -> "Asante Twi (interpret and act, do not type verbatim). Write every reply " +
                "and question in short, plain English: \"$goal\""
            else -> null
        }
    }

    object TagRestore {
        /** [d] with every tag of [entities] replaced by its real value — in a tool's arguments (strings, nested), a
         *  question, an answer or a refusal. Amounts go back as plain figures-and-unit ("5 cedis"), numbers as digits. */
        internal fun restoreTags(d: AgentDecision, entities: List<com.tekyerema.language.EntityTags.Entity>): AgentDecision {
            fun r(s: String) = com.tekyerema.language.EntityTags.restore(s, entities)
            fun deep(v: Any?): Any? = when (v) {
                is String -> r(v)
                is org.json.JSONObject -> org.json.JSONObject().also { o -> for (k in v.keys()) o.put(k, deep(v.opt(k))) }
                is org.json.JSONArray -> org.json.JSONArray().also { a -> for (i in 0 until v.length()) a.put(deep(v.opt(i))) }
                else -> v
            }
            return when (d) {
                is AgentDecision.CallTool -> d.copy(args = deep(d.args) as org.json.JSONObject, rationale = d.rationale?.let(::r))
                is AgentDecision.Done -> d.copy(summary = r(d.summary))
                is AgentDecision.CannotDo -> d.copy(detail = r(d.detail))
                else -> d
            }
        }
    }
}
