package com.tekyerema.memory

import com.tekyerema.android.ContactResolver
import com.tekyerema.android.Relations
import java.security.MessageDigest

/**
 * Ask-once learning (MASTER_STATUS #19): the user said "my wife", the phone had no relation for it, and the call or text went to
 * Pearl Asante. After the task, ONCE: "Should I remember that “my wife” means Pearl Asante?" A yes saves the relation
 * ([Relations]); a no, a silence or a yes — the same fact is never asked again.
 *
 * Three doors, each one short call from elsewhere (nothing in the task loop):
 *  1. [noteSaid]   — [ContactResolver.resolve] is looking up a relation word ("my wife", "Mum") that has no saved relation;
 *  2. [contactUsed] — [UserMemory.noteContact]: a call or text to a saved contact went through (money never comes this way);
 *  3. [takeOffer] / [answered] — [com.tekyerema.agent.habits.Habits], whose offer slot VoiceController already speaks after a spoken
 *     task that ended well (one short question at most per task, answered by voice, a request said instead runs as usual).
 * Never during money or a PIN step ([takeOffer] refuses while a secret is typed and for a money request). Only the NAME and the relation
 * word are kept, in the encrypted [Relations] file; what was already asked is remembered as short hashes. Logs say that an offer was made, never about whom.
 */
class OfferTracker(
    private val asked: Asked,
    /** A relation word looked up this recently can still be matched to the contact used (a "which one?" question takes a while). */
    private val saidTtlMs: Long = 120_000L,
    /** A made offer waits this long for the task to finish and the question to be asked. */
    private val offerTtlMs: Long = 300_000L
) {
    interface Asked { fun has(id: String): Boolean; fun add(id: String) }
    class MemoryAsked : Asked {
        private val ids = HashSet<String>()
        override fun has(id: String) = id in ids
        override fun add(id: String) { ids.add(id) }
    }

    private class Said(val relation: String, val display: String, val at: Long)
    data class Offer(val id: String, val relation: String, val said: String, val name: String, val at: Long) {
        val question: String get() = "Should I remember that “$said” means $name?"
    }

    private var said: Said? = null
    private var offer: Offer? = null
    private var taken: Offer? = null

    @Synchronized fun noteSaid(relation: String, display: String, now: Long) { said = Said(relation, display, now) }

    /**
     * A call or text to [name] went through. When a relation word was looked up just before, and [name] is a contact the word does
     * not already point at, an offer is made. [alreadyKnown] = the relation is already saved (then there is nothing to learn).
     */
    @Synchronized fun contactUsed(name: String, now: Long, alreadyKnown: (String) -> Boolean): Boolean {
        val s = said ?: return false
        said = null
        if (now - s.at !in 0..saidTtlMs || alreadyKnown(s.relation)) return false
        if (!worthAsking(s.display, name)) return false
        val id = idOf(s.relation, name)
        if (asked.has(id) || taken?.id == id) return false
        offer = Offer(id, s.relation, s.display, name.trim(), now)
        return true
    }

    /**
     * The offer to ask now, once, or null. Not while a secret is being typed, not after a money request (it waits for the next
     * ordinary task, within its time), and never twice for the same fact: asking marks it asked.
     */
    @Synchronized fun take(goal: String, now: Long, secure: Boolean): Offer? {
        val o = offer ?: return null
        if (now - o.at !in 0..offerTtlMs) { offer = null; return null }
        if (secure || isMoney(goal)) return null
        offer = null
        asked.add(o.id)
        taken = o
        return o
    }

    /** The offer last taken with [id] (for the answer). */
    @Synchronized fun takenOffer(id: String): Offer? = taken?.takeIf { it.id == id }

    @Synchronized fun clear() { said = null; offer = null; taken = null }

    companion object {
        /** Requests that move money or reach a PIN: the offer never comes with them. */
        private val MONEY = Regex("""(?i)\b(momo|mobile money|money|cedis?|ghs|pins?|ussd|transfer|airtime|balance|pay|payment|withdraw|deposit|bank|loan|send \d+)\b|[₵$]""")
        fun isMoney(goal: String): Boolean = MONEY.containsMatchIn(goal)

        /** A person worth remembering for [said]: a real name, not the relation word itself ("Wife", "My Wife", "Mum 2"). Pure. */
        fun worthAsking(said: String, contactName: String): Boolean {
            val n = contactName.trim()
            if (n.length < 2 || n.length > 60 || n.any { it.isDigit() } || !MemoryCore.safeName(n)) return false
            val word = said.trim().lowercase().removePrefix("my ").removePrefix("our ").trim()
            if (word.isEmpty() || ContactResolver.isPronoun(n)) return false
            return !ContactResolver.ownsFamilyWord(n, word) && !n.lowercase().contains(word)
        }

        /** A short hash of "relation|name": what is asked is remembered without keeping the name in the clear. */
        fun idOf(relation: String, name: String): String =
            MessageDigest.getInstance("SHA-256").digest("${relation.lowercase()}|${name.trim().lowercase()}".toByteArray())
                .take(6).joinToString("") { "%02x".format(it) }
    }
}

/** The Android side of [OfferTracker]: the hashes of what was asked live in app storage; a yes writes [Relations]. */
object MemoryLearning {
    private const val TAG = "Tekyerema"
    private const val PREFS = "tekyerema_memory_offers"
    private const val KEY = "asked"
    private const val MAX_ASKED = 200

    private object PrefsAsked : OfferTracker.Asked {
        private fun prefs() = com.tekyerema.agent.AgentEngine.appContext()?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        private val local = HashSet<String>()   // also kept in memory: no context (tests, early start) must not mean asking twice
        fun clearLocal() { local.clear() }
        override fun has(id: String) = id in local || prefs()?.getStringSet(KEY, emptySet())?.contains(id) == true
        override fun add(id: String) {
            local.add(id)
            val p = prefs() ?: return
            val all = LinkedHashSet(p.getStringSet(KEY, emptySet()).orEmpty())
            all.add(id)
            while (all.size > MAX_ASKED) all.remove(all.first())
            p.edit().putStringSet(KEY, all).apply()
        }
    }

    internal var tracker = OfferTracker(PrefsAsked)

    private fun enabled(): Boolean = try {
        UserMemory.isOn(MemKind.CONTACT) && com.tekyerema.core.config.AppConfig.bool("memory.askOnce.enabled", true)
    } catch (_: Throwable) { false }

    /** [ContactResolver.resolve] looks up [name]. A relation word with no saved relation is noted; anything else is ignored. */
    fun noteSaid(name: String) {
        try {
            if (!enabled() || com.tekyerema.core.Privacy.secureStep) return
            val w = name.trim().trimEnd('.', '?', '!')
            val rel = Relations.canonical(w) ?: return
            if (rel == "helper" || Relations.contactFor(w) != null) return   // the helper has its own flow; a known relation needs no asking
            tracker.noteSaid(rel, w, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    /** A call or text to a saved contact went through ([UserMemory.noteContact]). */
    fun contactUsed(name: String?) {
        try {
            if (name.isNullOrBlank() || !enabled() || com.tekyerema.core.Privacy.secureStep) return
            if (tracker.contactUsed(name, System.currentTimeMillis()) { rel -> Relations.all().containsKey(rel) })
                android.util.Log.i(TAG, "[MEMORY_OFFER] a relation is worth asking about")   // never who
        } catch (_: Throwable) {}
    }

    /** The offer to speak now (Habits.completed, after a task that ended well), or null. [goal] = what the user asked. */
    fun takeOffer(goal: String): OfferTracker.Offer? = try {
        if (!enabled()) null
        else tracker.take(goal, System.currentTimeMillis(), com.tekyerema.core.Privacy.secureStep)
            ?.also { android.util.Log.i(TAG, "[MEMORY_OFFER] asking once") }
    } catch (_: Throwable) { null }

    /** The user's answer to the offer [id]: [yes] true = yes, false = no, null = no clear answer. Returns the line to say ("" = nothing). */
    fun answered(id: String, yes: Boolean?): String {
        val o = tracker.takenOffer(id) ?: return ""
        return when (yes) {
            true -> try {
                val ctx = com.tekyerema.agent.AgentEngine.appContext()
                Relations.ensureLoaded(ctx)
                if (Relations.all().containsKey(o.relation)) "Okay." else {
                    Relations.set(o.relation, o.name)
                    android.util.Log.i(TAG, "[MEMORY_OFFER] yes — relation saved")
                    "Okay. I'll remember that “${o.said}” means ${o.name}."
                }
            } catch (_: Throwable) { "Sorry, I couldn't save that." }
            false -> "Okay, I won't ask again."
            null -> ""   // a mumble or silence: no nag, and it is not asked again either
        }
    }

    /** "forget everything": what was asked goes too, so the questions may come again. */
    fun forget(ctx: android.content.Context?) {
        try {
            tracker.clear()
            PrefsAsked.clearLocal()
            ctx?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        } catch (_: Throwable) {}
    }
}
