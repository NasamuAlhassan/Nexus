package com.tekyerema.agent

import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer
import org.json.JSONObject

/**
 * On-device, offline brain (directive §10/§11/§16). Maps a natural-language goal to ONE structured tool
 * call from the SAME vocabulary the cloud brains use. This is intent classification + generic argument
 * extraction + tool selection — NOT a hardcoded phrase→command table: a new app or contact needs no new
 * entry here (open_app/make_call resolve names at the tool layer), and the argument (app, contact, time,
 * direction, label) is lifted generically from the user's words.
 *
 * Conservative by design (it must know its limits, §12):
 *  - a goal it can operate → a CallTool (fast path, §15);
 *  - a goal needing the network → CannotDo([NETWORK]) so the router refuses offline / escalates online;
 *  - a goal needing multi-screen reasoning it can't ground → CannotDo([DEFER]) so the router escalates to
 *    the cloud brain when online, or asks for a simpler phrasing when offline.
 *
 * Pure (no Android context) and deterministic → unit-tested. Twi verbs are covered at a basic level.
 */
class LocalBrain : AgentBrain {
    private var step = 0
    private var doneLine: String = "Done."
    private var pendingRead = false
    /** §6.1 — set after a "read" accelerator (read_notifications / find_contact) whose RESULT is the answer:
     *  step 2 lifts the tool's result out of history and speaks it, instead of a canned doneLine. */
    private var pendingResultSpeak = false
    /** Set per request: when the command was Twi, spoken confirmations are phrased in Twi (§21). */
    private var twi = false

    // N-09 — a compound request run here, clause by clause (see [startPlan]).
    private var plan: List<String>? = null
    private var planIdx = 0
    private var planChild: LocalBrain? = null
    private var planMark = 0                         // history size when the current clause started
    private val planSaid = ArrayList<String>()

    override suspend fun decide(ctx: BrainContext): AgentDecision {
        step++
        if (plan != null) return continuePlan(ctx)
        if (pendingRead) return summarizeScreen(ctx.screen)   // 2nd step of "what's on my screen"
        if (pendingResultSpeak) return speakToolResult(ctx)   // §6.1 2nd step of read_notifications / find_contact
        if (step > 1) return finishLine(ctx)                   // §C.2 consult the tool result before any success line

        val raw = ctx.goal.trim()
        val g = raw.lowercase()
        if (g.isEmpty()) return defer("empty request")
        twi = looksTwi(raw)   // phrase spoken confirmations in the language the user used

        // --- online-only intents: honest network gate (router decides refuse vs escalate) ---
        // --- F-12: "call this number" — the number on the screen, when there is exactly one (the engine confirms). ---
        if (RE_THIS_NUMBER.containsMatchIn(g)) {
            val labels = ctx.screen.optJSONArray("elements")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("label") } }.orEmpty()
            val nums = ScreenEntities.phoneNumbers(labels)
            when {
                nums.size == 1 && g.contains("copy") -> { pendingResultSpeak = true; return call("clipboard", JSONObject().put("action", "copy").put("text", nums[0]), "copy on-screen number") }
                nums.size == 1 -> return call("make_call", JSONObject().put("number", nums[0]), "call on-screen number")
                nums.size > 1 -> return AgentDecision.Done("I see ${nums.size} numbers: ${nums.take(3).joinToString(", ") { it.chunked(3).joinToString(" ") }}. Which one?")
            }
        }

        // --- L-04 emergency: before the network gate (a call and an SMS need no internet). The engine confirms. ---
        if (RE_EMERGENCY.containsMatchIn(g)) { pendingResultSpeak = true; return call("emergency", JSONObject(), "emergency") }

        // --- read the screen word for word: no internet needed (the tree, or on-device OCR). "continue" goes on. ---
        //     Before the network gate — "what does it say" is question-shaped — and before notifications, so
        //     "read this message" reads the message that is open. ---
        if (ScreenReadIntent.isRead(raw) || (ScreenReadIntent.isContinue(raw) && ScreenReadCursor.pending)) {
            pendingResultSpeak = true
            return call("read_screen", JSONObject().put("continue", ScreenReadIntent.isContinue(raw)), "read the screen")
        }

        // --- N-09 "what can you do?" — a fixed answer, given on the phone (question-shaped, so before the network gate). ---
        if (RE_CAPABILITIES.matches(g.trimEnd('.', '!', '?').trim())) return AgentDecision.Done(capabilityAnswer())

        // --- N-09 send a text: SMS goes over the mobile network, no internet needed. Before the network gate and the
        //     compound check, because the message itself may say "weather" or "and". send_sms is confirmed (TIER2) with
        //     the resolved person and number read back, so a mis-heard split is caught before anything is sent. ---
        smsDecision(raw)?.let { return it }

        // --- N-09 "what did Kwame say?", "any messages from Ama?" — answered from the notifications on the phone
        //     (question-shaped, so it used to be sent to the cloud and refused offline). A pronoun ("what did he say")
        //     needs the conversation, so it stays with the planner. ---
        RE_FROM_WHO.find(raw.trim().trimEnd('.', '!', '?'))?.let { m ->
            val who = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (who.isNotEmpty() && !RE_PRONOUN.matches(who) && who.split(Regex("\\s+")).size <= 3) {
                pendingResultSpeak = true
                return call("read_notifications", JSONObject().put("filter", who), "messages from a sender")
            }
        }

        if (OfflineCapability.needsInternet(raw)) return network(raw)

        // --- compound / multi-step requests: run them here clause by clause when EVERY clause is a local intent
        //     ("open WhatsApp and read my messages" — offline too); otherwise the cloud operator plans them. ---
        if (RE_MULTISTEP.containsMatchIn(g)) {
            startPlan(ctx)?.let { return it }
            if (!isScreenRead(g)) return defer("multi-step request")
        }

        // --- greetings / acknowledgements → speak, don't operate ---
        if (RE_GREET.matches(g)) return AgentDecision.Done(greeting())

        // --- read notifications / messages (§6.1). Checked BEFORE screen-read so "Kenkan me nkra" (read my
        //     messages) routes to notifications, not a launcher read (§6.6). "read my WhatsApp messages" reads only
        //     WhatsApp's (the tool filters by app, sender or text). ---
        if (RE_READ_NOTIFS.containsMatchIn(g)) {
            pendingResultSpeak = true
            val args = JSONObject()
            RE_MSG_APP.find(g)?.let { args.put("filter", it.groupValues[1]) }
            return call("read_notifications", args, "read notifications")
        }

        // --- find a contact (§6.1) — the search_contacts accelerator; its RESULT is spoken at step 2. ---
        RE_FIND_CONTACT.find(raw)?.let { m ->
            val who = m.groupValues.last().trim().trimEnd('.', '!', '?')
            if (who.isNotEmpty()) { pendingResultSpeak = true; return call("search_contacts", JSONObject().put("query", who), "find contact") }
        }

        // --- read the screen ---
        if (isScreenRead(g)) { pendingRead = true; return call("read_state", JSONObject(), "read the current screen") }

        // --- navigation keys ---
        matchKey(g)?.let { key -> doneLine = keyDone(key); return call("key_event", JSONObject().put("key", key), "navigation: $key") }

        // --- scroll / swipe with a direction ---
        RE_SCROLL.find(g)?.let { m ->
            val dir = direction(m.groupValues.getOrNull(2)) ?: "down"
            doneLine = "Scrolled $dir."; return call("scroll", JSONObject().put("direction", dir), "scroll $dir")
        }
        RE_SWIPE.find(g)?.let { m ->
            val dir = direction(m.groupValues.getOrNull(2)) ?: "up"
            doneLine = "Swiped $dir."; return call("swipe", JSONObject().put("direction", dir), "swipe $dir")
        }

        // --- volume ---
        volumeDecision(g)?.let { return it }

        // --- alarm / timer --- (§6.7 gate also on Twi timer words so Twi durations reach timerDecision)
        if (g.contains("alarm")) alarmDecision(raw)?.let { return it }
        if (g.contains("timer") || g.contains("simma") || g.contains("sima") || (twi && g.contains("bere")))
            timerDecision(raw)?.let { return it }

        // --- §A.2 Mobile Money: buy airtime / send money → open the network menu, user does amount + PIN ---
        if (g.contains("airtime") || g.contains("top up") || g.contains("top-up") || g.contains("recharge"))
            buyAirtimeDecision(raw)?.let { return it }
        if ((g.contains("send") || g.contains("transfer") || g.contains("fa")) &&
            (g.contains("money") || g.contains("cash") || g.contains("cedi") || g.contains("pesewa") || g.contains("momo") || g.contains("mobile money") || g.contains("sika")))
            sendMoneyDecision(raw)?.let { return it }

        // --- §A.2 play music (generic/local); §P2.4 also a named track against the local library. A track on
        //     a SPECIFIC app ("play X on YouTube") defers to the cloud planner (it needs app UI drive). ---
        if (RE_PLAY_MUSIC.containsMatchIn(g)) {
            doneLine = if (twi) "Merebɔ nnwom." else "Playing your music."
            return call("play_music", JSONObject(), "play music")
        }
        RE_PLAY_TRACK.find(raw)?.let { m ->
            val q = m.groupValues.last().trim().trimEnd('.', '!', '?')
            if (q.isNotEmpty() && !RE_ON_APP.containsMatchIn(q)) {
                doneLine = if (twi) "Merebɔ $q." else "Playing $q."
                return call("play_music", JSONObject().put("query", q), "play track")
            }
        }

        // --- §A.2 settings toggle → open the setting screen (Android 11 won't let us flip it directly) ---
        if (g.contains("turn ") || g.contains("switch ") || g.contains("enable") || g.contains("disable") || g.contains(" settings")) {
            settingTarget(g)?.let { target ->
                // E-10: pass the direction the user asked for; with none, the screen is only opened (never flipped).
                val args = JSONObject().put("target", target)
                when {
                    RE_SETTING_OFF.containsMatchIn(g) -> args.put("state", "off")
                    RE_SETTING_ON.containsMatchIn(g) -> args.put("state", "on")
                }
                pendingResultSpeak = true   // speak the tool's own verified outcome ("Turned Wi-Fi off." / "already off")
                return call("open_setting", args, "open setting: $target ${args.optString("state")}".trim())
            }
        }

        // --- call a contact / number ---
        RE_CALL.find(raw)?.let { m ->
            val target = m.groupValues[2].trim().trimEnd('.', '!', '?')
            val digits = target.filter { it.isDigit() }
            return if (digits.length >= 3 && target.all { it.isDigit() || it == ' ' || it == '-' || it == '+' }) {
                doneLine = if (twi) "Merefrɛ." else "Calling."; call("make_call", JSONObject().put("number", digits), "call number")
            } else {
                doneLine = if (twi) "Merefrɛ $target." else "Calling ${target}."; call("make_call", JSONObject().put("contact", target), "call contact")
            }
        }

        // --- install an app: "install the Claude app (from the Play Store)" → install_app, which opens the store page,
        //     reads the real listing back for a yes/no and taps only an exact "Install". The fallback model drove the Play
        //     Store by hand instead (A20e, 2026-09-29) and tapped a biometrics card on the way. ---
        installTarget(raw)?.let { app ->
            pendingResultSpeak = true   // speak install_app's own checked outcome
            return call("install_app", JSONObject().put("app", app), "install an app")
        }

        // --- launch an app ---
        RE_OPEN.find(raw)?.let { m ->
            val app = m.groupValues[3].trim().trimEnd('.', '!', '?')
            // Guard against treating a whole second instruction as an app name (§1.9). An app label is a
            // short noun phrase; if it embeds an action verb or runs long, this is really a compound request
            // → defer to the planner rather than open a wrong app off a loose token match.
            if (RE_APP_HAS_VERB.containsMatchIn(app) || app.split(Regex("\\s+")).size > 3)
                return defer("app name looks like a compound instruction: \"$app\"")
            // "open the app" / "open it" names no app: the planner asks which one (it has the conversation) instead of a
            // failed search for an app called "app" first.
            if (RE_NO_APP_NAME.matches(app.lowercase())) return defer("no app named: \"$app\"")
            // Twi confirmation stays generic ("I've opened it") — the ASR-rendered app name may be rough.
            if (app.isNotEmpty()) { doneLine = if (twi) "Mabue no." else "$app is open."; return call("open_app", JSONObject().put("app", app), "launch app") }
        }

        // --- tap a visible label (only if it's actually on the current screen) ---
        RE_TAP.find(raw)?.let { m ->
            val label = m.groupValues[3].trim().trimEnd('.', '!', '?')
            if (label.isNotEmpty()) {
                return if (screenHasLabel(ctx.screen, label)) { doneLine = "Done."; call("tap", JSONObject().put("label", label), "tap $label") }
                else defer("'$label' is not on the current screen")
            }
        }

        return defer("no local intent matched")
    }

    // ---- decision builders ----
    private fun call(name: String, args: JSONObject, why: String) = AgentDecision.CallTool(name, args, "local:$why")
    private fun defer(detail: String) = AgentDecision.CannotDo(DEFER, detail)
    private fun network(goal: String) = AgentDecision.CannotDo(NETWORK, "\"$goal\" needs the internet.")

    /** N-09 — "text Ama saying I'm on my way", "send a message to 0244123456 that I'm late" → send_sms, or null. */
    private fun smsDecision(raw: String): AgentDecision? {
        val p = parseSms(raw) ?: return null
        // "text Ama saying I'm late then call Kofi": the message would swallow the second request (and a long read-back
        // is easy to approve). A later clause that starts with a command → a compound request for the plan/planner.
        if (hasFollowOnCommand(p.message)) return null
        val args = JSONObject().put("message", p.message)
        if (p.number != null) args.put("number", p.number) else args.put("contact", p.contact)
        if (p.whatsApp) args.put("via", "whatsapp")
        pendingResultSpeak = true   // speak send_sms's own checked outcome ("Sent to Ama."), never a premature "Sent"
        return call("send_sms", args, "send a text")
    }

    /**
     * N-09 — start a compound request as a LOCAL plan when every clause is something this brain does by itself, e.g.
     * "open WhatsApp and read my messages", "go home and open the camera", "set an alarm for 6 and turn the volume up".
     * Each clause is dry-run first (a throwaway brain, nothing executed); one clause that isn't local, or isn't allowed
     * in a plan, and the whole request goes to the planner exactly as before. After "open <app>", a following clause is
     * about THAT app: only reading, scrolling and navigation (and app-independent alarms/settings) stay local —
     * "open WhatsApp and call Ama" means a WhatsApp call, which is the planner's job — and "read my messages" there
     * means reading the app's screen, not the notification shade. Returns the first step, or null (not a local plan).
     */
    private suspend fun startPlan(ctx: BrainContext): AgentDecision? {
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled("N-09", true)) return null
        val clauses = splitClauses(ctx.goal) ?: return null
        val resolved = ArrayList<String>()
        var inApp = false
        for (c in clauses) {
            val clause = if (inApp) inAppClause(c) else c
            val d = LocalBrain().decide(ctx.copy(goal = clause, history = emptyList())) as? AgentDecision.CallTool ?: return null
            if (d.name !in PLAN_TOOLS || (inApp && d.name !in IN_APP_TOOLS)) return null
            inApp = when (d.name) { "open_app" -> true; "key_event" -> false; else -> inApp }
            resolved.add(clause)
        }
        plan = resolved; planIdx = 0; planChild = LocalBrain(); planMark = ctx.history.size; planSaid.clear()
        return continuePlan(ctx)
    }

    /** Run the current clause's own step; when it's done, start the next; at the end, say what each part said. */
    private suspend fun continuePlan(ctx: BrainContext): AgentDecision {
        val steps = plan ?: return AgentDecision.CannotDo(DEFER, "no plan")
        while (true) {
            val clause = steps[planIdx]
            val d = (planChild ?: LocalBrain().also { planChild = it })
                .decide(ctx.copy(goal = clause, history = ctx.history.drop(planMark)))
            when (d) {
                is AgentDecision.CallTool -> return d.copy(rationale = "local plan ${planIdx + 1}/${steps.size}: ${d.rationale}")
                is AgentDecision.Done -> {
                    if (d.summary.isNotBlank()) planSaid.add(d.summary.trim())
                    planIdx++
                    if (planIdx >= steps.size) { plan = null; return AgentDecision.Done(planSaid.joinToString(" ").ifBlank { "Done." }) }
                    planChild = LocalBrain(); planMark = ctx.history.size
                }
                is AgentDecision.CannotDo -> {
                    plan = null
                    // A step that FAILED is an honest stop (what was done, then why). A step the rules can't ground after
                    // all goes to the planner online, with the history of what was already done (the router's escalation).
                    return if (d.reasonClass == ACTION_FAILED) AgentDecision.CannotDo(ACTION_FAILED, (planSaid + d.detail).joinToString(" "))
                        else AgentDecision.CannotDo(DEFER, "local plan stopped at \"$clause\": ${d.detail}")
                }
                else -> { plan = null; return d }
            }
        }
    }

    /**
     * §6.1 — step 2 of a "read" accelerator. The AgentEngine records the tool's result message at the tail
     * of the last history line ("… ; <message>"), so lift it and speak it (the notifications, the matching
     * contacts, or the honest failure reason like "Notification access is off"). Never fabricates.
     */
    /**
     * §C.2 — a single-action intent's success line must not be spoken if the tool actually FAILED. Consult
     * the last tool result in history: on a failure, speak its honest message (recovery hint stripped)
     * instead of the optimistic doneLine; otherwise the doneLine stands. (Success-but-unverified is handled
     * separately by the engine's completion gating → "…but I couldn't confirm it", §1.7.)
     */
    private fun finishLine(ctx: BrainContext): AgentDecision {
        val last = ctx.history.lastOrNull().orEmpty()
        if (!last.contains("→ fail")) return AgentDecision.Done(doneLine)
        // The one action this intent needed FAILED — that is an honest "can't", not a completion (reporting it as
        // Done made the engine say "<failure> — but I could not confirm it").
        return AgentDecision.CannotDo(ACTION_FAILED, (speakToolResult(ctx) as AgentDecision.Done).summary)
    }

    private fun speakToolResult(ctx: BrainContext): AgentDecision {
        val msg = resultMessage(ctx.history.lastOrNull().orEmpty())
        return AgentDecision.Done(if (msg.isNotEmpty()) msg else doneLine)
    }

    private fun summarizeScreen(screen: JSONObject): AgentDecision {
        // §1.20 — protected screen blocks reading; §1.19 — no perception at all means the service is off.
        if (screen.optBoolean("secureWindow"))
            return AgentDecision.Done(if (twi) "Saa krataa yi wɔ ɔbammɔ, mintumi nkan mu." else "This screen is protected, so I can't tell you what's on it.")
        val src = screen.optJSONObject("perception")?.optString("source")
        val appName = listOf("app", "foreground", "package", "screen").map { screen.optString(it) }.firstOrNull { it.isNotBlank() && it != "unknown" }
        if (appName == null && (src == "none" || src == "no controller"))
            return AgentDecision.Done("I can't see your screen — Tɛkyerɛma's accessibility service is off. Turn it on in Settings, under Accessibility.")
        val app = listOf("app", "foreground", "package", "screen").map { screen.optString(it) }.firstOrNull { it.isNotBlank() }
        // K-01 / F-10 orientation: where you are, the main things you can DO (buttons, fields, switches), then
        // what is listed — so "what's on my screen" answers "where am I and what can I do here".
        val controls = ArrayList<String>()
        val labels = ArrayList<String>()
        val els = screen.optJSONArray("elements")
        if (els != null) {
            for (i in 0 until els.length()) {
                val o = els.optJSONObject(i) ?: continue
                val l = o.optString("label").trim()
                if (l.length !in 2..40) continue
                val flags = o.optJSONArray("flags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                // The listing names what a control can DO in `actions` (type = editable, tap = clickable); older
                // listings put that in `flags` too — accept either.
                val acts = o.optJSONArray("actions")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                val control = "editable" in flags || "type" in acts || "checkable" in flags ||
                    (("clickable" in flags || "tap" in acts) && o.optString("source") != "vision" &&
                        Regex("(?i)button|imageview|fab").containsMatchIn(o.optString("role")))
                if (control && controls.size < 4 && controls.none { it.equals(l, true) }) controls.add(l)
                else if (!control && labels.size < 6 && labels.none { it.equals(l, true) }) labels.add(l)
            }
        }
        // Speak the app's name, never its package id ("You're in com.google.android.apps.nexuslauncher").
        val name = screen.optString("appName").takeIf { it.isNotBlank() }
        val title = screen.optString("screen").takeIf { it.isNotBlank() && it != name && it.length <= 40 }
        val where = when {
            name != null && name.startsWith("the ") -> "You're on $name"
            name != null -> "You're in $name"
            !app.isNullOrBlank() && app != "unknown" && !app.contains('.') -> "You're in $app"
            else -> ""
        }.let { if (it.isNotEmpty() && title != null) "$it, on $title. " else if (it.isNotEmpty()) "$it. " else "" }
        val can = if (controls.isEmpty()) "" else "You can use: ${controls.joinToString(", ")}. "
        val what = when {
            labels.isNotEmpty() -> "I can see: ${labels.joinToString(", ")}."
            controls.isEmpty() -> "I don't see any labelled items."
            else -> ""
        }
        val more = screen.optInt("elementCount", 0).let { n -> if (n > 25) " There's more if you scroll." else "" }
        ScreenMemory.rememberJson(screen)   // K-15: "what changed?" compares against what was just described
        return AgentDecision.Done((where + can + what + more).trim())
    }

    // ---- helpers ----
    private fun matchKey(g: String): String? = when {
        RE_BACK.containsMatchIn(g) -> "back"
        RE_HOME.containsMatchIn(g) -> "home"
        RE_RECENTS.containsMatchIn(g) -> "recents"
        else -> null
    }
    private fun keyDone(key: String) = when (key) { "back" -> "Went back."; "home" -> "Home screen."; else -> "Recent apps." }

    private fun direction(s: String?): String? = when (s?.lowercase()) {
        "up" -> "up"; "down" -> "down"; "left" -> "left"; "right" -> "right"; else -> null
    }

    private fun volumeDecision(g: String): AgentDecision? {
        // §P2.7 Twi volume: "ma volume no so" (up) / "... ase|fam" (down). Keyed on volume/sound + Twi
        // direction so it doesn't over-match. [owner: verify Twi]
        if (twi) {
            if (Regex("\\b(volume|nne|dede|ɛnne)\\b").containsMatchIn(g) && Regex("\\bso\\b").containsMatchIn(g)) {
                doneLine = "Volume up."; return call("volume_up", JSONObject(), "volume up (twi)")
            }
            if (Regex("\\b(volume|nne|dede|ɛnne)\\b").containsMatchIn(g) && Regex("\\b(ase|fam|te\\s+so)\\b").containsMatchIn(g)) {
                doneLine = "Volume down."; return call("volume_down", JSONObject(), "volume down (twi)")
            }
        }
        if (RE_MUTE.containsMatchIn(g)) { doneLine = "Muted."; return call("set_volume", JSONObject().put("level", 0), "mute") }
        RE_VOL_SET.find(g)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            if (n in 0..100) { doneLine = "Volume set to $n percent."; return call("set_volume", JSONObject().put("level", n), "set volume") }
        }
        if (RE_VOL_MAX.containsMatchIn(g)) { doneLine = "Volume at max."; return call("set_volume", JSONObject().put("level", 100), "max volume") }
        if (RE_VOL_UP.containsMatchIn(g)) { doneLine = "Volume up."; return call("volume_up", JSONObject(), "volume up") }
        if (RE_VOL_DOWN.containsMatchIn(g)) { doneLine = "Volume down."; return call("volume_down", JSONObject(), "volume down") }
        return null
    }

    private fun alarmDecision(raw: String): AgentDecision? {
        val canon = Normalizer.normalize(raw, if (looksTwi(raw)) Lang.TWI else Lang.GH_EN).lowercase()
        val m = RE_TIME.find(canon) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val ampm = m.groupValues.getOrNull(3)?.replace(".", "")?.lowercase()
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        doneLine = "Alarm set for %02d:%02d.".format(hour, minute)
        return call("set_alarm", JSONObject().put("hour", hour).put("minute", minute), "set alarm")
    }

    private fun timerDecision(raw: String): AgentDecision? {
        // §6.7 — normalize in the SAME language the user spoke (was hardcoded Gh-EN, so Twi numerals like
        // "anum"→5 were never converted and Twi timers silently failed while alarms worked).
        val canon = Normalizer.normalize(raw, if (looksTwi(raw)) Lang.TWI else Lang.GH_EN).lowercase()
        val m = RE_DURATION.find(canon) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val secs = when {
            unit.startsWith("hour") || unit.startsWith("hr") || unit.startsWith("dɔn") || unit.startsWith("don") -> n * 3600
            unit.startsWith("min") || unit.startsWith("sim") -> n * 60
            else -> n
        }
        if (secs <= 0) return null
        doneLine = if (twi) "Mahyɛ bere." else "Timer set."
        return call("set_timer", JSONObject().put("seconds", secs), "set timer")
    }

    /** §A.2 — buy airtime: extract the cedi amount if present, open the network's MoMo menu, hand off. */
    private fun buyAirtimeDecision(raw: String): AgentDecision? {
        val canon = Normalizer.normalize(raw, if (twi) Lang.TWI else Lang.GH_EN)
        val amount = com.tekyerema.tools.GhanaMobileMoney.parseAmount(canon)
        val args = JSONObject().put("_kind", "buy_airtime"); amount?.let { args.put("_amount", it) }
        doneLine = if (twi) "Mabue Mobile Money." else "Opened Mobile Money."
        return call("mobile_money", args, "buy airtime")
    }

    /** §A.2 — send money: needs an amount AND a recipient (after "to"/Twi "ma"); opens the menu, user does PIN. */
    private fun sendMoneyDecision(raw: String): AgentDecision? {
        val canon = Normalizer.normalize(raw, if (twi) Lang.TWI else Lang.GH_EN)
        val marker = Regex("\\b(to|ma)\\b", RegexOption.IGNORE_CASE).find(canon) ?: return null
        var after = canon.substring(marker.range.last + 1).trim().trimEnd('.', '!', '?')
        // The amount is a number BEFORE "to" ("send 5 cedis to …") or one that carries a currency word after it ("to
        // 024… 5 cedis") — never the first digits of the sentence: "Send money to 0553238" became "Send 553238 cedis to
        // 0553238" (A20e 2026-09-29; the read-back caught it). No amount → not decided here; the planner asks.
        val amountCedis = moneyAmount(canon.substring(0, marker.range.first))?.let { parseCediAmount(it) }
            ?: RE_CURRENCY_AMOUNT.find(after)?.let { m -> after = after.removeRange(m.range).trim(); parseCediAmount(m.value) }
            ?: return null
        if (after.isEmpty()) return null
        // reference: after "for"/"reference"/"ref"/"narration". Split it off FIRST so its digits can't pollute
        // the recipient number (e.g. "for order 5").
        val refMark = Regex("\\b(?:for|reference|ref|narration|remark)\\b", RegexOption.IGNORE_CASE).find(after)
        val recipientPart = refMark?.let { after.substring(0, it.range.first) } ?: after
        val reference = refMark?.let { after.substring(it.range.last + 1).trim(':', ' ', '.', '!', '?') }?.takeIf { it.isNotBlank() }
        val digits = ghanaMobile(recipientPart.filter { it.isDigit() })
        if (digits.length >= 7 && digits.length != 10) {
            // A Ghana mobile number is 10 digits. A short or long one is a mishearing (or a number said in two
            // breaths) — say what was heard and ask again; never walk *170# with it.
            val spoken = digits.toList().joinToString(" ")
            // English in both languages until a native speaker writes the Twi line.
            return AgentDecision.Done("I heard the number $spoken — that's ${digits.length} digits, and a Ghana mobile number has 10. " +
                "Please say the number again.")
        }
        if (digits.length == 10) {
            // Deterministic *170# driver: it reads each menu screen and stops at the PIN. The TIER3 confirmation
            // reads the CEDI amount + number back FIRST, so a units mistake (pesewa vs cedi) is caught before it runs.
            val args = JSONObject().put("number", digits).put("amount", amountCedis)
            reference?.let { args.put("reference", it) }
            // Speak momo_send's OWN result at step 2 (the PIN prompt on success, or the honest failure reason)
            // — NEVER a premature "Sent the money": the money only moves when the USER enters the PIN.
            pendingResultSpeak = true
            return call("momo_send", args, "send money (deterministic)")
        }
        // Recipient given by NAME only (no number) → open the network menu; the user completes it. We do NOT
        // guess a number for a money transfer.
        val args = JSONObject().put("_kind", "send_money").put("_amount", amountCedis).put("_recipient", after)
        doneLine = if (twi) "Mabue Mobile Money." else "Opened Mobile Money."
        return call("mobile_money", args, "send money")
    }

    /** The amount said before the recipient ("send 5 cedis to …"): a number that is not phone-like (no leading 0 on 3+
     *  digits, fewer than 7 digits) — the words and currency around it are kept for [parseCediAmount]. Pure. */
    private fun moneyAmount(before: String): String? =
        Regex("(?<![\\d.])\\d+(?:\\.\\d{1,2})?(?![\\d])").findAll(before.replace(",", ""))
            .firstOrNull { m -> val d = m.value.substringBefore('.'); d.length < 7 && !(d.length >= 3 && d.startsWith("0")) }
            ?.let { m -> before.replace(",", "").substring(m.range.first) }

    /** "+233 24…" / "233 24…" → "024…"; anything else as it is. */
    private fun ghanaMobile(d: String): String = if (d.length == 12 && d.startsWith("233")) "0" + d.substring(3) else d

    /** Parse a cedi amount, converting pesewas → cedis (10 pesewas → "0.1"). Guards the worst-harm error:
     *  entering "10" for "10 pesewas" would send 10 CEDIS (100×). "cedi(s)" or a bare number stays as cedis. */
    private fun parseCediAmount(canon: String): String? {
        val n = com.tekyerema.tools.GhanaMobileMoney.parseAmount(canon)?.toDoubleOrNull() ?: return null
        val isPesewa = Regex("\\bpesewas?\\b|\\bpese\\b", RegexOption.IGNORE_CASE).containsMatchIn(canon)
        val cedis = if (isPesewa) n / 100.0 else n
        return if (!isPesewa && cedis == Math.floor(cedis)) cedis.toInt().toString()
        else String.format(java.util.Locale.US, "%.2f", cedis).trimEnd('0').trimEnd('.')
    }

    /** §A.2 — which system setting a toggle-style goal refers to, or null. */
    private fun settingTarget(g: String): String? = when {
        Regex("\\bwi.?fi\\b").containsMatchIn(g) -> "wifi"
        Regex("\\b(mobile\\s*data|cellular|\\bdata\\b)").containsMatchIn(g) -> "mobile_data"
        g.contains("bluetooth") -> "bluetooth"
        Regex("\\b(airplane|aeroplane|flight)\\b").containsMatchIn(g) -> "airplane"
        Regex("\\b(location|gps)\\b").containsMatchIn(g) -> "location"
        Regex("\\b(brightness|display)\\b").containsMatchIn(g) -> "display"
        else -> null
    }

    private fun screenHasLabel(screen: JSONObject, label: String): Boolean {
        val els = screen.optJSONArray("elements") ?: return false
        val want = label.lowercase()
        for (i in 0 until els.length()) {
            val l = els.optJSONObject(i)?.optString("label")?.lowercase() ?: continue
            if (l.isNotEmpty() && (l == want || l.contains(want) || want.contains(l))) return true
        }
        return false
    }

    private fun looksTwi(s: String): Boolean = TWI_HINT.containsMatchIn(s.lowercase())
    private fun greeting(): String = if (twi) "Mewɔ ha. Dɛn na wopɛ sɛ meyɛ?" else "I'm here. What would you like to do?"

    /** A text to send, as heard: exactly one of [contact] / [number], the [message], and whether it goes by WhatsApp. */
    data class Sms(val contact: String?, val number: String?, val message: String, val whatsApp: Boolean)

    companion object {
        /** Router-only reason classes (never surfaced verbatim to the user). */
        const val DEFER = "defer_local"
        const val NETWORK = "network_unavailable"
        /** The single tool this local intent needed failed. Not escalatable: the cloud would retry the same op. */
        const val ACTION_FAILED = "action_failed"

        /**
         * The spoken message of a tool result in the engine's history line
         * ("name(args) → success [ch]; verified=X (detail); MESSAGE"), or "" — the message follows the verify detail's
         * ")" + "; ": split at the FIRST "); " after "verified=", so a screen reading that itself contains "); " is spoken
         * whole. The planner-only recovery hint (Recovery.hint) and the J-07 untrusted marker are stripped: they must
         * never be spoken (same class as the §1.7 UNVERIFIED-prefix leak). Pure.
         */
        fun resultMessage(last: String): String {
            val v = last.indexOf("verified=")
            val cut = if (v >= 0) last.indexOf("); ", v) else -1
            return (if (cut >= 0) last.substring(cut + 3) else if ("); " in last) last.substringAfterLast("); ") else last.substringAfterLast("; "))
                .substringBefore(" — the '")
                .removePrefix(AgentEngine.UNTRUSTED_MARK)
                .trim()
        }

        /** §1.3: the offline capability line spoken on an unmatched offline goal is generated HERE, from the
         *  intents that actually work without internet — LocalBrain's own (decide()) plus the fast path's device
         *  reads (time, battery) — so we never advertise a capability no offline intent provides. Keep in sync. */
        fun offlineCapabilities(): String =
            "open apps, make calls and send texts, read your screen and your notifications, set an alarm or timer, " +
            "change the volume, tell you the time or battery, and go back or to the home screen"

        /**
         * N-09 — parse "text Ama saying …", "send a (WhatsApp) message to Ama that …", "send Ama a text saying …",
         * "message 0244 123 456: …". A clear "saying / that says / to say" wins over a bare "that", so "text the man that
         * fixed my car saying hi" keeps "the man that fixed my car" together. Null when there's no message, or the
         * recipient part runs long (probably a mis-split — the planner gets it). Pure → unit-tested.
         */
        fun parseSms(raw: String): Sms? {
            val s = raw.trim().trimEnd('.', '!').replace(Regex("\\s+"), " ")
            val m = SMS_STRONG.firstNotNullOfOrNull { it.find(s) } ?: SMS_WEAK.firstNotNullOfOrNull { it.find(s) }
                ?: return parseRelay(s)
            var who = m.groupValues[1].trim().removePrefix("to ").trim()
            var whatsApp = RE_VIA_WHATSAPP.containsMatchIn(s.substring(0, m.groups[2]!!.range.first))
            RE_VIA_TAIL.find(who)?.let { t -> whatsApp = whatsApp || t.groupValues[1].lowercase() == "whatsapp"; who = who.substring(0, t.range.first).trim() }
            val msg = m.groupValues[2].trim().trim('"', '\'', '“', '”').replaceFirst(Regex("^(?i)that\\s+"), "").trim()
            if (who.isEmpty() || msg.isEmpty() || who.split(' ').size > 4) return null
            val digits = who.filter { it.isDigit() }
            val isNumber = digits.length >= 7 && who.all { it.isDigit() || it == ' ' || it == '-' || it == '+' }
            return Sms(if (isNumber) null else who, if (isNumber) (if (who.trim().startsWith("+")) "+$digits" else digits) else null, msg, whatsApp)
        }

        /**
         * "Text Prince on WhatsApp to send the PDF, I'm waiting for him" / "tell Ama to call me" / "ask Kofi to bring the
         * charger": a message ASKING the person to do something — never the user sending a file. The owner's request
         * (A20e, 2026-09-29) was read the other way: the planner went looking for a PDF to send. The message is composed
         * from the user's words as a polite request, with the person's own pronouns turned to "you" ("call his mother"
         * → "call your mother"); the confirmation reads it back before anything is sent. Pure.
         */
        internal fun parseRelay(s: String): Sms? {
            val m = RE_RELAY.matchEntire(s) ?: return null
            var who = m.groupValues[2].trim()
            var whatsApp = m.groupValues[1].equals("whatsapp", ignoreCase = true) || m.groupValues[3].isNotEmpty() &&
                m.groupValues[3].contains("whatsapp", ignoreCase = true)
            RE_VIA_TAIL.find(who)?.let { t -> whatsApp = whatsApp || t.groupValues[1].lowercase() == "whatsapp"; who = who.substring(0, t.range.first).trim() }
            if (who.isEmpty() || who.split(' ').size > 4 || RE_NOT_A_PERSON.matches(who.lowercase())) return null
            val rest = m.groupValues[4].trim()
            if (rest.split(' ').size < 2) return null                         // "to Ama" is a recipient, not a request
            val parts = rest.split(Regex("\\s*[,;]\\s*|\\s+(?:because|since|as)\\s+"), limit = 2)
            fun toYou(t: String) = t.replace(Regex("\\b(him|her|them)\\b", RegexOption.IGNORE_CASE), "you")
                .replace(Regex("\\b(his|their)\\b", RegexOption.IGNORE_CASE), "your")
            val ask = toYou(parts[0]).trim().trimEnd('.')
            val body = buildString {
                append("Please ").append(ask).append('.')
                parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { append(' ').append(toYou(it).replaceFirstChar(Char::uppercaseChar)).append('.') }
            }
            val digits = who.filter { it.isDigit() }
            val isNumber = digits.length >= 7 && who.all { it.isDigit() || it == ' ' || it == '-' || it == '+' }
            return Sms(if (isNumber) null else who, if (isNumber) (if (who.trim().startsWith("+")) "+$digits" else digits) else null, body, whatsApp)
        }

        /** Does a text's body carry another request after it ("I'm late then call Kofi")? Pure. */
        internal fun hasFollowOnCommand(message: String): Boolean {
            val parts = message.trim().trimEnd('.', '!', '?').split(RE_CLAUSE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            return parts.drop(1).any { RE_COMMAND_LEAD.containsMatchIn(it) }
        }
        /**
         * "install WhatsApp", "install the Claude app from the Play Store", "download the Jiji app" → the app's name, or
         * null. "download"/"get" only with the word "app" or a store named ("download this song" is not an install). Pure.
         */
        fun installTarget(raw: String): String? {
            val s = raw.trim().trimEnd('.', '!', '?').trim()
            val m = RE_INSTALL.matchEntire(s) ?: return null
            val verb = m.groupValues[1].lowercase()
            val name = m.groupValues[2].trim()
            val saidApp = m.groupValues[3].isNotEmpty() || m.groupValues[4].isNotEmpty()
            if (verb != "install" && verb != "reinstall" && !saidApp) return null
            if (name.isEmpty() || name.split(Regex("\\s+")).size > 4 || RE_NO_APP_NAME.matches(name.lowercase())) return null
            if (RE_APP_HAS_VERB.containsMatchIn(name)) return null
            return name
        }
        private val RE_INSTALL = Regex("(?i)^(?:please\\s+|kindly\\s+|can\\s+you\\s+|could\\s+you\\s+|i\\s+want\\s+(?:you\\s+)?to\\s+|help\\s+me\\s+)*" +
            "(install|reinstall|download|get)\\s+(?:me\\s+)?(?:the\\s+|an?\\s+)?(.+?)(\\s+app|\\s+application)?" +
            "(\\s+(?:from|on|in|off|through)\\s+(?:the\\s+)?(?:google\\s+)?(?:play\\s*store|play|app\\s+store))?(?:\\s+please)?$")
        private val RE_NO_APP_NAME =Regex("^(?:the |an? |this |that |my |some )?(?:app|apps|application|it|this|that|one|something)$")
        private val RE_COMMAND_LEAD =Regex("^(?:please\\s+)?(?:call|ring|dial|text|sms|message|whatsapp|email|open|launch|" +
            "remind|send|set|turn|play|read|take|search|find|check|frɛ|bue|kenkan)\\b", RegexOption.IGNORE_CASE)

        /** N-09 — split a compound request into 2–4 clauses ("… and …", "… then …", "…; …", Twi "… na …"), or null. Pure. */
        fun splitClauses(goal: String): List<String>? {
            val parts = goal.trim().trimEnd('.', '!', '?').split(RE_CLAUSE_SPLIT)
                .map { it.trim().trim(',').trim().replaceFirst(Regex("^(?i)(?:also|then|please)\\s+"), "").trim() }
                .filter { it.isNotEmpty() }
            return parts.takeIf { it.size in 2..com.tekyerema.core.config.AppConfig.int("router.localPlan.maxClauses", 4) }
        }

        /** After "open <app>": "read my (last) message(s)" / "read it" means the app's screen, not the notification shade. */
        internal fun inAppClause(clause: String): String =
            if (RE_READ_NOTIFS.containsMatchIn(clause.lowercase()) || RE_IN_APP_READ.containsMatchIn(clause)) "read the screen" else clause

        /** Tools a local plan may use. No money, USSD, emergency, camera or install steps inside a compound request. */
        private val PLAN_TOOLS = setOf("open_app", "key_event", "scroll", "swipe", "volume_up", "volume_down", "set_volume",
            "read_screen", "read_state", "read_notifications", "set_alarm", "set_timer", "play_music", "open_setting",
            "make_call", "send_sms", "search_contacts")
        /** After "open <app>", only steps whose meaning doesn't change inside that app. */
        private val IN_APP_TOOLS = setOf("open_app", "key_event", "scroll", "swipe", "volume_up", "volume_down", "set_volume",
            "read_screen", "read_state", "set_alarm", "set_timer", "open_setting")

        /** N-09: "what can you do?" — answered on the phone, the same online or off (no round trip for a fixed answer). */
        fun capabilityAnswer(): String =
            "I work your phone by voice. Even without internet I can " + offlineCapabilities() + ". " +
            "With internet I can also answer questions, search the web, and do longer tasks inside apps like WhatsApp. " +
            "What would you like?"

        // English + minimal Twi verb coverage. Twi: bue=open, frɛ=call, kenkan=read, san (kɔ)=back.
        // Natural-phrasing coverage (§A.3): fuzzy/paraphrase alternations, EN + Ghanaian-English + Twi,
        // not just exact verb stems — so common variants match LOCALLY instead of needlessly hitting the cloud.
        // Twi "open" = bue (ASR renders buei/buee/buɛ/bua); allow an optional polite lead-in ("please",
        // "I want to", "can you", "mepɛ sɛ", "wo bɛtumi") before the verb.
        private const val LEADIN = "(?:please\\s+|kindly\\s+|can\\s+you\\s+|could\\s+you\\s+|i\\s+want\\s+(?:you\\s+)?to\\s+|i'?d?\\s+like\\s+to\\s+|help\\s+me\\s+|mepɛ\\s+sɛ\\s+|me\\s+pɛ\\s+sɛ\\s+|wo\\s+bɛtumi\\s+|wu?bue\\s+)?"
        // §6.2 — the command verb need NOT be sentence-initial: allow up to two leading words (a Twi
        // vocative/name or filler, e.g. "Bagyo frɛ 0552093690") before the (optional) polite lead-in.
        private const val PREVERB = "(?:\\S+\\s+){0,2}?"
        // -ing forms too ("opening the calculator." went to the cloud, which paged the home screen hunting the icon).
        private val RE_OPEN = Regex("^\\s*$PREVERB$LEADIN(open(?:ing)?|launch(?:ing)?|start(?:ing)?|go\\s*to|goto|bring\\s*up|pull\\s*up|show\\s*me|take\\s*me\\s*to|put\\s*on|bue\\S*|bua\\S*)\\s+(?!hours?\\b|times?\\b)(?:up\\s+)?(the\\s+|my\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
        private val RE_CALL = Regex("^\\s*$PREVERB$LEADIN(call|phone|dial|ring|get\\s+me|reach|call\\s+up|phone\\s+up|frɛ\\S*)\\s+(.+)$", RegexOption.IGNORE_CASE)
        // §6.1/§6.6 — read notifications / messages. Requires a read/check verb + messages/nkra, OR the word
        // "notifications", OR a bare "nkra" — so "send prince a message" (a send, not a read) does NOT match.
        private val RE_READ_NOTIFS = Regex("\\bnotifications?\\b|\\b(read|check|show|any|new|hwɛ|kenkan)\\b[^.?!]*\\b(messages?|nkra)\\b|^\\s*nkra\\b", RegexOption.IGNORE_CASE)
        // §6.1 — find/search a contact by name (English + Twi "hwehwɛ"); trailing "in my contacts" tolerated.
        private val RE_FIND_CONTACT = Regex("^\\s*$LEADIN(find|search\\s+for|look\\s+up|look\\s+for|hwehwɛ\\S*)\\s+(?:the\\s+)?(?:contact\\s+)?(.+?)(?:\\s+in\\s+(?:my\\s+)?contacts?)?\\s*$", RegexOption.IGNORE_CASE)
        private val RE_TAP = Regex("^\\s*(tap|click|press|select|choose|hit)\\s+(on\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        // §A.2 — generic/local music only ("play music", "play my music", "play a song"); a named track on a
        // specific app ("play X on youtube") does NOT match and defers to the cloud planner.
        /** An amount that names its currency: "5 cedis", "2.50 GHS", "GH₵ 10", "50 pesewas". */
        private val RE_CURRENCY_AMOUNT = Regex("(?i)(?:(?:gh₵|₵|ghs)\\s*\\d+(?:\\.\\d{1,2})?|(?<![\\d.])\\d+(?:\\.\\d{1,2})?\\s*(?:ghana\\s+)?(?:cedis?|pesewas?|ghs|gh₵)\\b)")
        private val RE_PLAY_MUSIC = Regex("^\\s*$LEADIN(?:play|bɔ|to)\\s+(?:me\\s+|some\\s+|my\\s+)?(?:music|songs?|nnwom|a\\s+song)\\s*$", RegexOption.IGNORE_CASE)
        // §P2.4 named track: "play <track>". The last group is the track query.
        private val RE_PLAY_TRACK = Regex("^\\s*$LEADIN(?:play|bɔ)\\s+(?:me\\s+|some\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        private val RE_ON_APP = Regex("\\bon\\s+(youtube|yutu\\S*|spotify|boomplay|audiomack|tiktok|instagram|soundcloud|apple\\s+music)\\b", RegexOption.IGNORE_CASE)
        /**
         * A request to DO something with what is on the screen is an action, not a request to hear the screen: the
         * owner's "play the playlist on the screen right now" and "play the playlist you can see on my screen" (A20e
         * 2026-09-29) were both answered with a description of YouTube, because "on my screen" alone meant "read it".
         * An action verb opening the request, or opening any clause of it, hands it to the planner.
         */
        private val RE_ACTION_CLAUSE = Regex("(?:^|[.,;!?]\\s*|\\b(?:and|then|so|now)\\s+)(?:(?:please|kindly|just|now|ok(?:ay)?|can you|could you|would you|will you|i want you to|help me)\\s+)*" +
            "(?:play|tap|click|press|hit|open|select|choose|pick|start|watch|download|save|send|share|forward|delete|remove|like|" +
            "subscribe|follow|add|turn|switch|go to|scroll to|close|stop|pause|skip|call|reply|install|uninstall|buy|book|join|" +
            "shuffle|put on|copy|paste|type|write|enter|fill|accept|decline|allow|answer|update|sign|log|clear|mute|unmute|zoom)\\b",
            RegexOption.IGNORE_CASE)

        /** "What's on my screen?" / "read the screen" — and not "play the playlist on my screen". Pure. */
        internal fun isScreenRead(g: String): Boolean = RE_READ_SCREEN.containsMatchIn(g) && !RE_ACTION_CLAUSE.containsMatchIn(g)

        private val RE_READ_SCREEN = Regex("\\b(what('?s| is| am i)\\b.*\\b(screen|looking at|showing|open)|on\\s+(my|the)\\s+screen|read (the|my) screen|describe (the|my) screen|what do you see|kenkan|(which|what) app am i (in|on|using))\\b", RegexOption.IGNORE_CASE)
        private val RE_GREET = Regex("^\\s*(hi|hello|hey|good (morning|afternoon|evening)|thanks|thank you|akwaaba|medaase)\\s*[.!]*\\s*$", RegexOption.IGNORE_CASE)
        // Compound requests → the local fast path only does ONE action, so hand these to the cloud planner
        // (§1.9). Bare "and"/Twi "na"/"then"/";" all count — the earlier regex missed a plain "and", so
        // "open whatsapp and send prince a message" fell through to RE_OPEN and mis-opened a single app.
        private val RE_MULTISTEP = Regex("\\b(and|then|after that|na)\\b|;", RegexOption.IGNORE_CASE)
        // F-12: a number the user points at on the screen.
        private val RE_THIS_NUMBER = Regex("\\b(?:call|dial|ring|copy) (?:this|that|the) (?:phone )?number\\b|\\bnumber on (?:the |my )?screen\\b", RegexOption.IGNORE_CASE)
        // L-04: an urgent request for personal help (the public lines are "call an ambulance/police" on the fast path).
        private val RE_EMERGENCY = Regex("^(?:please )?(?:help me(?: please)?|i need help|i need urgent help|this is an emergency|emergency|" +
            "call (?:my )?emergency contact|i(?:'ve| have) fallen(?: down)?|mmoa(?: me)?|boa me)[.!]*$", RegexOption.IGNORE_CASE)
        private val RE_SETTING_OFF = Regex("\\b(turn|switch|put)\\s+(\\w+\\s+){0,3}?off\\b|\\bdisable\\b|\\bdeactivate\\b", RegexOption.IGNORE_CASE)
        private val RE_SETTING_ON = Regex("\\b(turn|switch|put)\\s+(\\w+\\s+){0,3}?on\\b|\\benable\\b|\\bactivate\\b", RegexOption.IGNORE_CASE)
        // Action verbs that essentially never occur inside an app LABEL — if "open <x>" carries one, <x> is
        // really a second instruction (e.g. "open whatsapp send a message"), so defer to the planner rather
        // than open a wrong app off a loose token match (§1.9). Deliberately excludes ambiguous words that DO
        // appear in app names ("play" → Play Store, "set" → Settings) to avoid false deferrals.
        private val RE_APP_HAS_VERB = Regex("\\b(send|message|reply|forward|dial|frɛ\\S*|kyerɛw\\S*)\\b", RegexOption.IGNORE_CASE)
        // N-09 "what can you do?" and its everyday variants (whole utterance).
        private val RE_CAPABILITIES = Regex("^(?:please\\s+)?(?:so\\s+)?(?:what\\s+(?:else\\s+)?(?:can|do)\\s+you\\s+do(?:\\s+for\\s+me)?|" +
            "what\\s+are\\s+you\\s+able\\s+to\\s+do|what\\s+can\\s+i\\s+(?:ask|say\\s+to|tell)\\s+you|what\\s+can\\s+i\\s+(?:ask|say)|" +
            "how\\s+(?:can|do)\\s+you\\s+help(?:\\s+me)?|what\\s+are\\s+your\\s+(?:abilities|capabilities|features|skills)|" +
            "what\\s+do\\s+you\\s+do|tell\\s+me\\s+what\\s+you\\s+can\\s+do)$", RegexOption.IGNORE_CASE)
        // N-09 a sender's messages: 1 = "what did <who> say/send", 2 = "(any|read|check) messages from <who>".
        private val RE_FROM_WHO = Regex("^(?:please\\s+)?(?:what\\s+(?:did|has)\\s+(.+?)\\s+(?:say|send|write|text|message)(?:\\s+me)?|" +
            "(?:any|read|check)\\s+(?:(?:new|my|the)\\s+)?(?:messages?|texts?)\\s+from\\s+(.+))$", RegexOption.IGNORE_CASE)
        private val RE_PRONOUN = Regex("(?i)(?:he|she|they|him|her|them|it|you|someone|anyone|anybody|somebody)")
        // A messaging app named in "read my WhatsApp messages" → read_notifications' filter.
        private val RE_MSG_APP = Regex("\\b(whatsapp|telegram|messenger|signal|instagram|facebook|gmail|email|imo)\\b", RegexOption.IGNORE_CASE)
        // N-09 send-a-text phrasings. Groups: 1 = recipient as heard, 2 = the message.
        private const val SMS_NOUN = "(?:whatsapp\\s+)?(?:text(?:\\s+message)?|sms|message)"
        private const val MARK_STRONG = "(?:\\s(?:saying|that\\s+says|which\\s+says|to\\s+say|and\\s+say|telling\\s+(?:him|her|them))\\s|:)"
        private const val MARK_WEAK = "\\sthat\\s"
        private fun smsPatterns(mark: String) = listOf(
            Regex("^\\s*$LEADIN(?:send|write)\\s+(?:an?\\s+)?$SMS_NOUN\\s+to\\s+(.+?)\\s*$mark\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^\\s*$LEADIN(?:send|write)\\s+(.+?)\\s+an?\\s+$SMS_NOUN\\s*$mark\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^\\s*$LEADIN(?:text|sms|message|whatsapp)\\s+(.+?)\\s*$mark\\s*(.+)$", RegexOption.IGNORE_CASE)
        )
        private val SMS_STRONG = smsPatterns(MARK_STRONG)
        private val SMS_WEAK = smsPatterns(MARK_WEAK)
        private val RE_VIA_WHATSAPP = Regex("\\bwhatsapp\\b", RegexOption.IGNORE_CASE)
        // "text|message|whatsapp|tell|ask <who> [on WhatsApp] to <request>" — the request must start with a verb-like word
        // (not "to Ama"), so "text Ama to say hi" is a relay and "send a text to Ama" never reaches here.
        private val RE_RELAY = Regex("^\\s*$LEADIN(text|sms|message|whatsapp|tell|ask)\\s+(.+?)(\\s+(?:on|via|through|by)\\s+(?:whatsapp|sms|text))?\\s+to\\s+" +
            "((?:please\\s+)?[a-z]+\\b.*)$", RegexOption.IGNORE_CASE)
        // Not people: "ask ChatGPT to write a poem" is a question for an app (ask_app), never a text message.
        private val RE_NOT_A_PERSON = Regex("^(?:me|myself|it|this|that|the app|a message|a text|an sms|the message|" +
            "chat ?gpt|gemini|claude|google|siri|alexa|bixby|copilot|perplexity|meta ai|t[ɛe]kyer[ɛe]ma)$")
        private val RE_VIA_TAIL = Regex("\\s+(?:on|via|by|through|using|in|over)\\s+(whatsapp|sms|text)$", RegexOption.IGNORE_CASE)
        // N-09 clause boundaries of a compound request. Twi "na" (and/then), but not after a question word ("dɛn na …").
        private val RE_CLAUSE_SPLIT = Regex("\\s*[,;]?\\s*\\b(?:and\\s+then|and\\s+also|and|then|after\\s+that|afterwards)\\b\\s*|\\s*;\\s*|" +
            "\\s(?<!(?:bɛn|bɛ|dɛn|hena|ɛhe|ehe|ben|den)\\s)na\\s", RegexOption.IGNORE_CASE)
        // After "open <app>": "check my chats", "read the latest conversation".
        private val RE_IN_APP_READ = Regex("^(?:read|check)\\s+(?:it|them|(?:the|my)\\s+(?:(?:last|latest|new|newest|recent|first|top)\\s+)?(?:chats?|messages?|conversations?))$", RegexOption.IGNORE_CASE)
        private val RE_BACK = Regex("^back\\b|\\b(go back|previous|san\\s+(kɔ|ko))\\b", RegexOption.IGNORE_CASE)
        private val RE_HOME = Regex("^home\\b|\\b(home screen|go home|home button|kɔ\\s+fie|san\\s+kɔ\\s+fie|fie)\\b", RegexOption.IGNORE_CASE)  // §P2.7 Twi "fie" = home [owner: verify]
        private val RE_RECENTS = Regex("\\b(recent apps|recents|switch apps|app switcher)\\b", RegexOption.IGNORE_CASE)
        private val RE_SCROLL = Regex("\\bscroll(\\s+(up|down|left|right))?\\b", RegexOption.IGNORE_CASE)
        private val RE_SWIPE = Regex("\\bswipe(\\s+(up|down|left|right))?\\b", RegexOption.IGNORE_CASE)
        private val RE_VOL_UP = Regex("\\b(volume up|louder|turn (it|the volume) up|increase (the )?volume|raise (the )?volume)\\b", RegexOption.IGNORE_CASE)
        private val RE_VOL_DOWN = Regex("\\b(volume down|quieter|softer|turn (it|the volume) down|lower (the )?volume|decrease (the )?volume)\\b", RegexOption.IGNORE_CASE)
        private val RE_VOL_SET = Regex("\\bvolume\\b(?:.*?(?:to|at))?\\s*(\\d{1,3})\\s*(?:%|percent)?", RegexOption.IGNORE_CASE)
        private val RE_VOL_MAX = Regex("\\b(max|maximum|full)\\s+volume|volume\\s+(to\\s+)?(max|maximum|full)\\b", RegexOption.IGNORE_CASE)
        private val RE_MUTE = Regex("\\b(mute|silence|silent)\\b", RegexOption.IGNORE_CASE)
        private val RE_TIME = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", RegexOption.IGNORE_CASE)
        // §6.7 — English + common Twi duration units (simma/sima = minutes, dɔnhwere = hours).
        private val RE_DURATION = Regex("\\b(\\d{1,4})\\s*(hours?|hrs?|minutes?|mins?|simma|sima|seconds?|secs?|dɔnhwere|donhwere)\\b", RegexOption.IGNORE_CASE)
        // Twi signal for choosing the response language. Uses Twi-specific letters and words; avoids
        // English-ambiguous stems (e.g. NOT "fre*", which would match "free"/"friend").
        private val TWI_HINT = Regex("[ɛɔŋ]|\\b(bue|buei|buee|bua|frɛ\\w*|kenkan|san|mepɛ|mepa|wubue|akwaaba|medaase|aane|daabi|yoo|aduonu|aduonum|ɔha|baako|mmienu|anum)\\b")
    }
}
