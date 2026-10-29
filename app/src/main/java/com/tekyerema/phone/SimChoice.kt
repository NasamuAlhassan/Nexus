package com.tekyerema.phone

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.tekyerema.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import com.tekyerema.tools.GhanaMobileMoney

/**
 * Two SIMs: which one makes the call, sends the text, dials the code. 27 Oct 2026 (owner): with two SIMs the app never asked — it
 * dialled on the phone's default voice SIM, which on a Ghana dual-SIM phone is often not the one the person meant (an MTN
 * Mobile Money code dialled on the Vodafone SIM answers "invalid MMI code").
 *
 * The rule, pure ([decide], → SimChoiceTest):
 *  - one SIM (or none readable): nothing to ask;
 *  - an answer given earlier in the last [REMEMBER_MS] and that SIM still in: use it;
 *  - the task is tied to a network (Mobile Money is MTN's) and exactly ONE SIM is on it: use that one, no question;
 *  - otherwise one short question — "Which SIM: MTN in slot 1, or Vodafone in slot 2?" — answered by number, "first/second", or the network.
 */
object SimChoice {
    const val REMEMBER_MS = 10 * 60_000L

    data class Sim(val subId: Int, val slot: Int, val carrier: String?, val network: String?) {
        val name: String get() = carrier?.takeIf { it.isNotBlank() } ?: "SIM ${slot + 1}"
        val label: String get() = "$name in slot ${slot + 1}"
    }

    sealed class Decision {
        object Default : Decision()
        data class Use(val sim: Sim) : Decision()
        data class Ask(val question: String, val sims: List<Sim>) : Decision()
    }

    fun decide(sims: List<Sim>, preferNetwork: String?, chosen: Sim?): Decision {
        if (sims.size < 2) return Decision.Default
        chosen?.let { c -> sims.firstOrNull { it.subId == c.subId }?.let { return Decision.Use(it) } }
        if (!preferNetwork.isNullOrBlank()) {
            val onIt = sims.filter { it.network.equals(preferNetwork, ignoreCase = true) }
            if (onIt.size == 1) return Decision.Use(onIt[0])
        }
        return Decision.Ask(question(sims), sims)
    }

    fun question(sims: List<Sim>): String {
        val l = sims.sortedBy { it.slot }.map { it.label }
        return "Which SIM: " + l.dropLast(1).joinToString(", ") + ", or " + l.last() + "?"
    }

    /** Per slot: words that name it outright ("second", "sim 2"), then the bare numbers ("2", "two") that "the second one" must not trip. */
    private val STRONG = listOf(listOf("first", "1st", "sim 1", "sim one"), listOf("second", "2nd", "sim 2", "sim two"))
    private val WEAK = listOf(listOf("1", "one"), listOf("2", "two"))

    /** The SIM [answer] points at — its slot number, "first/second", or its network's name — or null when it is none of them. */
    fun parseAnswer(answer: String, sims: List<Sim>): Sim? {
        val a = answer.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (a.isEmpty()) return null
        val byName = sims.filter { s -> listOfNotNull(s.carrier, s.network).any { n -> n.length >= 3 && a.contains(n.lowercase()) } }
        if (byName.size == 1) return byName[0]
        val words = a.split(' ').toSet()
        val ordered = sims.sortedBy { it.slot }
        fun hits(table: List<List<String>>): List<Sim> = ordered.filterIndexed { i, _ -> i < table.size &&
            table[i].any { o -> if (o.contains(' ')) a.contains(o) else o in words } }
        return hits(STRONG).singleOrNull() ?: hits(WEAK).singleOrNull()
    }


    private val MENTION = Regex("(?i)[\\s,]*\\b(?:using|on|with|from|via|through|use)?\\s*(?:the |my )?(?:(first|second|1st|2nd|one|two|1|2|mtn|telecel|vodafone|airtel ?tigo|at)\\s+)?sim(?:\\s*(?:card|tool|slot))?(?:\\s+(one|two|1|2|mtn|telecel|vodafone))?\\s*$")

    /** "0552093690 using SIM two" → ("0552093690", "two"); a request that names no SIM comes back unchanged with null. */
    fun extractMention(text: String): Pair<String, String?> {
        val m = MENTION.find(text) ?: return text to null
        val said = (m.groupValues[1].ifBlank { m.groupValues[2] }).lowercase().ifBlank { null }
        val rest = text.substring(0, m.range.first).trim().trimEnd(',', '.')
        return if (rest.isEmpty()) text to null else rest to said
    }

    // ------------------------------------------------------------------------------------------------------ the phone

    /** Tools of money (a code on one network) ask for a SIM on that network; calls and texts ask whichever is meant. */
    fun preferredNetwork(tool: String, args: JSONObject): String? =
        if (tool == "send_sms" || tool == "make_call") null else args.optString("network").ifBlank { "mtn" }

    /**
     * The SIM question as a tool preflight result, or null to go on. A pick comes back as `sim` in [args] (ContactChoice.apply) and is
     * remembered; a SIM already settled (chosen, or the only one on the task's network) is remembered and nothing is asked.
     */
    fun preflight(ctx: Context, tool: String, args: JSONObject): ToolResult? {
        if (args.optString("via").contains("whatsapp", ignoreCase = true)) return null
        val sims = active(ctx)
        // "call 0552093690 using SIM two": the planner leaves the words in the contact; take them out and use them.
        for (key in listOf("contact", "number")) {
            val v = args.optString(key).takeIf { it.isNotBlank() } ?: continue
            val (clean, said) = extractMention(v)
            if (clean != v) {
                args.put(key, clean)
                said?.let { w -> parseAnswer(w, sims)?.let { choose(it) } }
            }
        }
        args.optInt("sim", -1).takeIf { it >= 0 }?.let { id -> sims.firstOrNull { it.subId == id }?.let { choose(it); return null } }
        when (val d = decide(sims, preferredNetwork(tool, args), current(ctx))) {
            is Decision.Use -> { choose(d.sim); return null }
            is Decision.Ask -> {
                val choices = JSONArray(); d.sims.forEach { choices.put(JSONObject().put("label", it.label).put("sim", it.subId)) }
                return ToolResult(false, d.question, data = JSONObject().put("ask", d.question)
                    .put("options", JSONArray(d.sims.map { it.label })).put("choices", choices), needsDisambiguation = true)
            }
            Decision.Default -> return null
        }
    }

    @Volatile private var chosenSub = -1
    @Volatile private var chosenAt = 0L

    /** Active SIMs, or empty when the phone will not say (no permission, no telephony): the app then dials as it always did. */
    fun active(ctx: Context): List<Sim> = try {
        val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        sm?.activeSubscriptionInfoList.orEmpty().map { i ->
            val carrier = i.carrierName?.toString()?.ifBlank { null }
            Sim(i.subscriptionId, i.simSlotIndex, carrier, GhanaMobileMoney.network(carrier))
        }.sortedBy { it.slot }
    } catch (_: Throwable) { emptyList() }

    fun decide(ctx: Context, preferNetwork: String?): Decision = decide(active(ctx), preferNetwork, current(ctx))

    fun choose(sim: Sim) { chosenSub = sim.subId; chosenAt = android.os.SystemClock.elapsedRealtime() }

    /** The SIM chosen in the last [REMEMBER_MS] that is still in the phone, else null. */
    fun current(ctx: Context): Sim? {
        if (chosenSub < 0 || android.os.SystemClock.elapsedRealtime() - chosenAt > REMEMBER_MS) return null
        return active(ctx).firstOrNull { it.subId == chosenSub }
    }

    /** [intent] (a call or a USSD dial) made to go out on the chosen SIM; unchanged when none was chosen or its account can't be found. */
    fun attach(ctx: Context, intent: Intent?): Intent? {
        if (intent == null) return null
        val sim = current(ctx) ?: return intent
        handleFor(ctx, sim)?.let { intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        return intent
    }

    private fun handleFor(ctx: Context, sim: Sim): PhoneAccountHandle? = try {
        val tc = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val handles = tc.callCapablePhoneAccounts
        handles.firstOrNull { h ->
            (Build.VERSION.SDK_INT >= 30 && tm.createForPhoneAccountHandle(h)?.subscriptionId == sim.subId) || h.id == sim.subId.toString()
        } ?: handles.takeIf { it.size == active(ctx).size }?.getOrNull(sim.slot)     // call-capable accounts come in slot order
    } catch (_: Throwable) { null }

    /** The text-message manager of the chosen SIM, or the phone's default one. */
    fun smsManager(ctx: Context): SmsManager? = try {
        val sim = current(ctx)
        when {
            sim != null && Build.VERSION.SDK_INT >= 31 -> ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(sim.subId)
            sim != null -> @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sim.subId)
            Build.VERSION.SDK_INT >= 31 -> ctx.getSystemService(SmsManager::class.java)
            else -> @Suppress("DEPRECATION") SmsManager.getDefault()
        }
    } catch (_: Throwable) { null }
}
