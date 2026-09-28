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

    override suspend fun decide(ctx: BrainContext): AgentDecision {
        step++
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

        if (OfflineCapability.needsInternet(raw)) return network(raw)

        // --- compound / multi-step requests → let the cloud operator plan them (fast path is single-action) ---
        if (RE_MULTISTEP.containsMatchIn(g) && !RE_READ_SCREEN.containsMatchIn(g)) return defer("multi-step request")

        // --- greetings / acknowledgements → speak, don't operate ---
        if (RE_GREET.matches(g)) return AgentDecision.Done(greeting())

        // --- read notifications / messages (§6.1). Checked BEFORE screen-read so "Kenkan me nkra" (read my
        //     messages) routes to notifications, not a launcher read (§6.6). ---
        if (RE_READ_NOTIFS.containsMatchIn(g)) { pendingResultSpeak = true; return call("read_notifications", JSONObject(), "read notifications") }

        // --- find a contact (§6.1) — the search_contacts accelerator; its RESULT is spoken at step 2. ---
        RE_FIND_CONTACT.find(raw)?.let { m ->
            val who = m.groupValues.last().trim().trimEnd('.', '!', '?')
            if (who.isNotEmpty()) { pendingResultSpeak = true; return call("search_contacts", JSONObject().put("query", who), "find contact") }
        }

        // --- read the screen ---
        if (RE_READ_SCREEN.containsMatchIn(g)) { pendingRead = true; return call("read_state", JSONObject(), "read the current screen") }

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
            (g.contains("money") || g.contains("cedi") || g.contains("pesewa") || g.contains("momo") || g.contains("mobile money") || g.contains("sika")))
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

        // --- launch an app ---
        RE_OPEN.find(raw)?.let { m ->
            val app = m.groupValues[3].trim().trimEnd('.', '!', '?')
            // Guard against treating a whole second instruction as an app name (§1.9). An app label is a
            // short noun phrase; if it embeds an action verb or runs long, this is really a compound request
            // → defer to the planner rather than open a wrong app off a loose token match.
            if (RE_APP_HAS_VERB.containsMatchIn(app) || app.split(Regex("\\s+")).size > 3)
                return defer("app name looks like a compound instruction: \"$app\"")
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
        // History line: "name(args) → success [ch]; verified=X (detail); MESSAGE". The message follows the
        // detail's ")" + "; ", so split there (robust to a message that itself contains "; ").
        val last = ctx.history.lastOrNull().orEmpty()
        // Strip the planner-only recovery hint the engine appends to a missed accelerator (Recovery.hint) —
        // it must never be spoken to the user (same class as the §1.7 UNVERIFIED-prefix leak).
        // The message starts after the verify detail's ")" — split at the FIRST "); " after "verified=", so a screen
        // reading that itself contains "); " is spoken whole.
        val v = last.indexOf("verified=")
        val cut = if (v >= 0) last.indexOf("); ", v) else -1
        val msg = (if (cut >= 0) last.substring(cut + 3) else if ("); " in last) last.substringAfterLast("); ") else last.substringAfterLast("; "))
            .substringBefore(" — the '")
            .removePrefix(AgentEngine.UNTRUSTED_MARK)   // J-07 planner-only marker, never spoken
            .trim()
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
                val control = "editable" in flags || "checkable" in flags ||
                    ("clickable" in flags && Regex("(?i)button|imageview|fab").containsMatchIn(o.optString("role")))
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
        val amountCedis = parseCediAmount(canon) ?: return null
        val marker = Regex("\\b(to|ma)\\b", RegexOption.IGNORE_CASE).find(canon) ?: return null
        val after = canon.substring(marker.range.last + 1).trim().trimEnd('.', '!', '?')
        if (after.isEmpty()) return null
        // reference: after "for"/"reference"/"ref"/"narration". Split it off FIRST so its digits can't pollute
        // the recipient number (e.g. "for order 5").
        val refMark = Regex("\\b(?:for|reference|ref|narration|remark)\\b", RegexOption.IGNORE_CASE).find(after)
        val recipientPart = refMark?.let { after.substring(0, it.range.first) } ?: after
        val reference = refMark?.let { after.substring(it.range.last + 1).trim(':', ' ', '.', '!', '?') }?.takeIf { it.isNotBlank() }
        val digits = recipientPart.filter { it.isDigit() }
        if (digits.length >= 7) {
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

    companion object {
        /** Router-only reason classes (never surfaced verbatim to the user). */
        const val DEFER = "defer_local"
        const val NETWORK = "network_unavailable"
        /** The single tool this local intent needed failed. Not escalatable: the cloud would retry the same op. */
        const val ACTION_FAILED = "action_failed"

        /** §1.3: the offline capability line spoken on an unmatched offline goal is generated HERE, from the
         *  intents decide() actually handles — so we never advertise a capability (e.g. "check notifications")
         *  that no local intent provides. Keep in sync with decide(). */
        fun offlineCapabilities(): String =
            "open apps, make calls, go back or to the home screen, scroll, change the volume, " +
            "set an alarm or timer, and read your screen to you"

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
        private val RE_PLAY_MUSIC = Regex("^\\s*$LEADIN(?:play|bɔ|to)\\s+(?:me\\s+|some\\s+|my\\s+)?(?:music|songs?|nnwom|a\\s+song)\\s*$", RegexOption.IGNORE_CASE)
        // §P2.4 named track: "play <track>". The last group is the track query.
        private val RE_PLAY_TRACK = Regex("^\\s*$LEADIN(?:play|bɔ)\\s+(?:me\\s+|some\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        private val RE_ON_APP = Regex("\\bon\\s+(youtube|yutu\\S*|spotify|boomplay|audiomack|tiktok|instagram|soundcloud|apple\\s+music)\\b", RegexOption.IGNORE_CASE)
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
