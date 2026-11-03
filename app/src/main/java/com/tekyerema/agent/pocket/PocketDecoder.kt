package com.tekyerema.agent.pocket

import com.tekyerema.agent.AgentDecision
import org.json.JSONObject

/** The model's raw outputs for ONE request (batch dimension removed). tag[j] = flat [nArgNames * 3] logits of token j. */
class PocketOutputs(val tool: FloatArray, val status: FloatArray, val missing: FloatArray, val slot: FloatArray, val tag: Array<FloatArray>)

class PocketResult(val decision: AgentDecision, val tool: String, val status: String, val p: Float)

/**
 * Logits -> one AgentDecision (docs/agent/research/pocket_brain_deep_2026-11-03.md; run_pocket.py `action_probs`/`decode_args`):
 *   P(tool t) = P(ready) * P(tool=t);  P(ask) = P(missing) + P(ambiguous) + P(ready) * P(_none);  P(cannot) = P(unsafe) + P(no_tool_fits)
 * using the spec's temperatures. The winner decides:
 *  - a tool, with P at or above the spec's threshold (stricter for money / send / call): CallTool with the arguments rebuilt
 *    from the slot and span heads; an argument the request does not carry -> a question instead;
 *  - missing slot -> a natural question for it; ambiguous / below threshold -> "Did you mean A or B?" from the top two tools;
 *  - unsafe / no tool fits -> CannotDo with an honest line.
 * Money: an amount or a recipient is used only if it is a span OF THE REQUEST (a figure in it) - never a learned constant,
 * never guessed; otherwise it is asked for. Pure -> PocketBrainTest.
 */
class PocketDecoder(private val spec: PocketSpec, tauRisk: Float? = null, tauOther: Float? = null) {
    private val tauRisk = tauRisk ?: spec.tauRisk
    private val tauOther = tauOther ?: spec.tauOther

    fun decode(request: String, enc: PocketEncoding, out: PocketOutputs): PocketResult {
        val pt = softmax(out.tool, spec.tTool)
        val ps = softmax(out.status, spec.tStatus)
        val iReady = spec.statuses.indexOf("ready"); val iMiss = spec.statuses.indexOf("missing_slot")
        val iAmb = spec.statuses.indexOf("ambiguous"); val iUnsafe = spec.statuses.indexOf("unsafe"); val iNoFit = spec.statuses.indexOf("no_tool_fits")
        val iNone = spec.tools.indexOf(spec.noneTool)
        var best = -1
        for (j in pt.indices) if (j != iNone && (best < 0 || pt[j] > pt[best])) best = j
        val tool = if (best >= 0) spec.tools[best] else spec.noneTool
        val pReal = ps[iReady] * (if (best >= 0) pt[best] else 0f)
        val pMiss = ps[iMiss]; val pAmb = ps[iAmb]; val pNoneAsk = ps[iReady] * pt[iNone]
        val pAsk = pMiss + pAmb + pNoneAsk
        val pUnsafe = ps[iUnsafe]; val pNoFit = ps[iNoFit]; val pCan = pUnsafe + pNoFit
        val statusName = spec.statuses[ps.indices.maxByOrNull { ps[it] } ?: 0]

        fun res(d: AgentDecision, t: String, p: Float) = PocketResult(d, t, statusName, p)

        // ---- cannot_do
        if (pCan > pReal && pCan > pAsk) {
            return if (pUnsafe >= pNoFit) res(AgentDecision.CannotDo(UNSAFE, "I'm not sure that is safe to do, so I won't do it. " +
                "Tell me a little more, or do it yourself."), tool, pCan)
            else res(AgentDecision.CannotDo(NO_TOOL, "I can't do that on the phone. Offline I can open apps, set alarms and timers, " +
                "make calls and send texts."), tool, pCan)
        }
        // ---- ask
        if (pAsk > pReal) {
            val q = when {
                pMiss >= pAmb && pMiss >= pNoneAsk -> {
                    val slot = spec.missingSlots.getOrNull(argmax(out.missing)) ?: "query"
                    question(tool, slot)
                }
                pAmb >= pNoneAsk -> didYouMean(pt, iNone)
                else -> "I'm not sure what you want me to do. Could you say it another way?"
            }
            return res(ask(q, "pocket: ask"), tool, pAsk)
        }
        // ---- a tool, if the model is sure enough
        val tau = if (spec.isRisk(tool)) tauRisk else tauOther
        if (pReal < tau) return res(ask(didYouMean(pt, iNone), "pocket: below threshold"), tool, pReal)
        val args = JSONObject()
        val missing = buildArgs(tool, request, enc, out, args)
        missing?.let { return res(ask(question(tool, it), "pocket: $it missing"), tool, pReal) }
        return res(AgentDecision.CallTool(tool, args, "pocket:$tool"), tool, pReal)
    }

    // ------------------------------------------------------------------ arguments

    /** Fills [args] for [tool]; returns the slot name to ask about when something required is missing / unsafe to use, else null. */
    private fun buildArgs(tool: String, request: String, enc: PocketEncoding, out: PocketOutputs, args: JSONObject): String? {
        val seen = norm(request)
        for (k in spec.keysOf(tool)) {
            val c = argmax(out.slot, k.from, k.to) - k.from
            if (c <= 0) continue
            val v: Any? = if (c == 1) span(request, enc, out, k.arg) else k.classes.getOrNull(c)
            if (v == null) continue
            // An open-text value the model remembered from training (not cut from the request) is a guess unless the request says it.
            if (c >= 2 && v is String && k.mode == "span" && norm(v) !in seen) continue
            // Grounded in the words: a learned value the request doesn't say, while the request DOES say another of this slot's
            // values, gives way to the one said (A20e, 3 Nov 2026: "anything new on WhatsApp" opened Messages).
            val said = if (c >= 2 && v is String && norm(v) !in seen)
                k.classes.drop(2).filterIsInstance<String>().filter { it.length >= 3 && norm(it) in seen }.maxByOrNull { it.length } else null
            args.put(k.arg, said ?: v)
        }
        // arguments the rules (not the model) build
        if (tool == "web_search" || tool == "look_with_camera") args.put(if (tool == "web_search") "query" else "question", request.trim())
        // a recipient is a name the user said, even for non-money tools
        for (a in listOf("contact", "_recipient")) {
            val v = args.optString(a)
            if (v.isNotEmpty() && norm(v) !in seen) args.remove(a)
        }
        return when (tool) {
            "momo_send" -> momoSend(args, seen)
            "mobile_money" -> mobileMoney(args, seen)
            else -> REQUIRED[tool]?.let { req -> missingOf(tool, req, args) }
        }
    }

    private fun momoSend(args: JSONObject, seen: String): String? {
        val amount = args.optString("amount")
        if (!hasFigure(amount) || norm(amount) !in seen) return "amount"
        val who = args.optString("contact")
        if (who.isEmpty()) return "contact"
        val digits = who.filter { it.isDigit() }
        if (digits.length >= 9 && digits.length == who.count { it.isDigit() || it == ' ' || it == '+' || it == '-' }) {
            args.remove("contact"); args.put("number", digits)
        }
        return null
    }

    private fun mobileMoney(args: JSONObject, seen: String): String? {
        val kind = args.optString("_kind")
        if (kind.isEmpty()) return "kind"
        val amount = args.optString("_amount")
        val amountOk = hasFigure(amount) && norm(amount) in seen
        if (!amountOk) args.remove("_amount")
        val who = args.optString("_recipient")
        if (who.isNotEmpty() && norm(who) !in seen) args.remove("_recipient")
        return when (kind) {
            "send_money" -> if (!amountOk) "amount" else if (!args.has("_recipient")) "contact" else null
            "buy_airtime" -> if (!amountOk) "amount" else null
            else -> null
        }
    }

    private fun missingOf(tool: String, required: List<String>, args: JSONObject): String? {
        for (a in required) if (!args.has(a) || args.optString(a).isBlank()) return slotOf(a)
        if (tool == "calendar" && args.optString("action").let { it.isEmpty() || it == "add" }) {
            if (args.optString("title").isBlank()) return "title"
            return "time"   // the start time is built by rules, not the model: asked, never guessed
        }
        if (tool == "play_music" && !args.has("query") && args.optString("action").let { it.isEmpty() || it == "play" }) return "query"
        if (tool == "search_messages" && !args.has("contains") && !args.has("sender")) return "query"
        return null
    }

    /** First begin/inside token of [arg]'s channel, extended while inside; null when none. Same walk as training's decode_args. */
    private fun span(request: String, enc: PocketEncoding, out: PocketOutputs, arg: String): String? {
        val ai = spec.spanArgNames.indexOf(arg)
        if (ai < 0) return null
        fun label(j: Int): Int {
            val t = out.tag.getOrNull(j) ?: return 0
            return argmax(t, ai * 3, ai * 3 + 3) - ai * 3
        }
        var j = 0
        while (j < enc.size) {
            if (enc.seq[j] == 0 && enc.end(j) > enc.start(j) && label(j) in 1..2) {
                val s = enc.start(j); var e = enc.end(j); j++
                while (j < enc.size && enc.seq[j] == 0 && label(j) == 2) { e = enc.end(j); j++ }
                if (s < 0 || e > request.length || e <= s) return null
                return request.substring(s, e)
            }
            j++
        }
        return null
    }

    // ------------------------------------------------------------------ words

    private fun ask(q: String, why: String) = AgentDecision.CallTool("ask_user", JSONObject().put("question", q), why)

    private fun didYouMean(pt: FloatArray, iNone: Int): String {
        val order = pt.indices.filter { it != iNone }.sortedByDescending { pt[it] }
        val a = order.getOrNull(0)?.let { phrase(spec.tools[it]) }
        val b = order.getOrNull(1)?.takeIf { pt[it] >= 0.15f }?.let { phrase(spec.tools[it]) }
        return when {
            a == null -> "I'm not sure what you want me to do. Could you say it another way?"
            b == null -> "Did you mean to $a?"
            else -> "Did you mean to $a, or to $b?"
        }
    }

    companion object {
        const val UNSAFE = "unsafe_or_uncertain"
        const val NO_TOOL = "no_tool_fits"

        /** Arguments each tool cannot run without (the model sees a request, not a schema). Any missing -> ask, never guess. */
        private val REQUIRED: Map<String, List<String>> = mapOf(
            "send_sms" to listOf("contact", "message"), "make_call" to listOf("contact"), "set_timer" to listOf("seconds"),
            "set_alarm" to listOf("hour"), "walk_directions" to listOf("destination"), "open_app" to listOf("app"),
            "open_setting" to listOf("target"), "find_contact" to listOf("query"), "search_contacts" to listOf("query"),
            "create_contact" to listOf("name", "number"), "read_chat" to listOf("contact"))

        private fun slotOf(arg: String) = when (arg) {
            "contact", "_recipient", "name", "sender" -> "contact"
            "message", "text" -> "message"
            "amount", "_amount" -> "amount"
            "seconds", "hour", "start" -> "time"
            "destination" -> "place"
            "app" -> "app"
            "target" -> "setting"
            "title" -> "title"
            "number" -> "number"
            else -> "query"
        }

        /** The natural question for a missing slot of [tool]. */
        fun question(tool: String, slot: String): String = when (slot) {
            "amount" -> "How much?"
            "message" -> "What should the message say?"
            "time" -> if (tool == "set_timer") "For how long?" else "What time?"
            "place" -> "Where to?"
            "app" -> "Which app?"
            "setting" -> "Which setting?"
            "title" -> "What should I call it?"
            "number" -> "What is the number?"
            "kind" -> "What would you like to do with Mobile Money?"
            "contact" -> when (tool) {
                "make_call" -> "Who should I call?"
                "send_sms" -> "Who should I send it to?"
                "momo_send", "mobile_money" -> "Who should I send the money to?"
                "read_chat" -> "Whose messages should I read?"
                "find_contact", "search_contacts" -> "Whose number do you want?"
                "create_contact" -> "What is the name?"
                else -> "Who is it for?"
            }
            else -> when (tool) {
                "play_music" -> "What should I play?"
                "web_search" -> "What should I look up?"
                else -> "What should I look for?"
            }
        }

        private val PHRASES = mapOf(
            "send_sms" to "send a message", "make_call" to "make a call", "momo_send" to "send money",
            "mobile_money" to "use Mobile Money", "set_alarm" to "set an alarm", "set_timer" to "set a timer",
            "calendar" to "add a reminder or event", "open_app" to "open an app", "open_setting" to "change a setting",
            "play_music" to "play music", "web_search" to "search the web", "walk_directions" to "get walking directions",
            "read_sms" to "read your texts", "read_notifications" to "read your notifications", "read_screen" to "read the screen",
            "read_chat" to "read a chat", "find_contact" to "look up a contact", "search_contacts" to "look up a contact",
            "create_contact" to "save a contact", "look_with_camera" to "look with the camera", "location" to "say where you are",
            "volume_up" to "turn the volume up", "volume_down" to "turn the volume down", "set_volume" to "change the volume",
            "call_log" to "check your calls", "call_control" to "answer the call", "show_alarms" to "show your alarms",
            "search_messages" to "search your messages", "open_intent" to "write an email")

        fun phrase(tool: String): String = PHRASES[tool] ?: tool.replace('_', ' ')

        internal fun softmax(x: FloatArray, t: Float): FloatArray {
            val z = FloatArray(x.size) { x[it] / (if (t > 0f) t else 1f) }
            val m = z.maxOrNull() ?: 0f
            var s = 0f
            for (i in z.indices) { z[i] = Math.exp((z[i] - m).toDouble()).toFloat(); s += z[i] }
            for (i in z.indices) z[i] /= s
            return z
        }

        internal fun argmax(x: FloatArray, from: Int = 0, to: Int = x.size): Int {
            var b = from
            for (i in from until minOf(to, x.size)) if (x[i] > x[b]) b = i
            return b
        }

        /** Lower-case letters and digits (and the decimal point) only, single-spaced - for "does the request say this". */
        internal fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}. ]"), "").replace(Regex("\\s+"), " ").trim()

        private fun hasFigure(s: String) = s.any { it.isDigit() }
    }
}
