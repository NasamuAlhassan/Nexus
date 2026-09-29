package com.tekyerema.android

import android.content.Context
import com.tekyerema.core.SecureStore
import org.json.JSONObject
import java.io.File

/**
 * C-03 / H-08 — relationship contacts: "remember that Ama Mensah is my mother", then "call my mother" reaches her.
 *
 * People in Ghana rarely save family under the word itself: the mother is "Ama Mensah", "Mama Ama", "Maa Akos" or
 * "Mummy 2"; the fixed family aliases in [ContactResolver] only help when the saved name contains the word. Siri
 * learns relationships from a Contacts card field; Google Assistant's "call mom" asks once and saves it in a Google
 * account — Gemini on Android dropped it for most phones, and neither understands "me maame". Here the user says it
 * once, by voice, and it is kept ON THE PHONE (encrypted, [SecureStore]); the contact itself is never changed.
 *
 * The relation only picks WHO a name means: calling or texting "my mother" still goes through make_call / send_sms and
 * the policy's confirmation ("Call Ama Mensah?"), so a wrong or stale relation is heard before anything happens.
 *
 * Parsing is pure and unit-tested; the store is in memory with an encrypted file behind it.
 */
object Relations {
    /** canonical relation → the words people say for it. English and Ghanaian English; Twi words (maame, ɛna, agya,
     *  yere, kunu, nua, wofa, nana) are PENDING NATIVE REVIEW. Config `os.contacts.relations.<canonical>` adds words. */
    private val DEFAULT_WORDS: Map<String, List<String>> = linkedMapOf(
        "mother" to listOf("mother", "mum", "mom", "mummy", "mommy", "mama", "maa", "maame", "ɛna"),
        "father" to listOf("father", "dad", "daddy", "papa", "paa", "dada", "agya"),
        "wife" to listOf("wife", "yere"),
        "husband" to listOf("husband", "kunu"),
        "brother" to listOf("brother", "big brother", "little brother", "younger brother", "elder brother"),
        "sister" to listOf("sister", "big sister", "little sister", "younger sister", "elder sister"),
        "sibling" to listOf("sibling", "nua"),
        "son" to listOf("son"),
        "daughter" to listOf("daughter"),
        "grandmother" to listOf("grandmother", "grandma", "granny"),
        "grandfather" to listOf("grandfather", "grandpa"),
        "grandparent" to listOf("grandparent", "nana"),
        "aunt" to listOf("aunt", "auntie", "aunty"),
        "uncle" to listOf("uncle", "wofa"),
        "cousin" to listOf("cousin"),
        "best friend" to listOf("best friend", "bestie"),
        "friend" to listOf("friend"),
        "girlfriend" to listOf("girlfriend"),
        "boyfriend" to listOf("boyfriend"),
        "partner" to listOf("partner", "fiance", "fiancé", "fiancee", "fiancée"),
        "boss" to listOf("boss", "manager"),
        "doctor" to listOf("doctor"),
        "pastor" to listOf("pastor", "imam"),
        "landlord" to listOf("landlord", "landlady"),
        "driver" to listOf("driver"),
        "neighbour" to listOf("neighbour", "neighbor"),
        "caregiver" to listOf("caregiver", "carer", "helper"),
        "teacher" to listOf("teacher")
    )

    private fun words(): Map<String, List<String>> = DEFAULT_WORDS.mapValues { (k, v) ->
        (v + com.tekyerema.core.config.AppConfig.stringList("os.contacts.relations.$k", emptyList())).distinct()
    }

    /** "my mum" / "Mum" / "me maame" / "mother's" → "mother"; null when it isn't a relation word. */
    fun canonical(word: String): String? {
        val w = word.trim().lowercase().trimEnd('.', '?', '!')
            .removePrefix("my ").removePrefix("me ").removeSuffix("'s").removeSuffix("’s").trim()
        if (w.isEmpty()) return null
        return words().entries.firstOrNull { (_, ws) -> w in ws }?.key
    }

    // ---------------------------------------------------------------- parsing (pure)

    data class SetRequest(val relation: String, val said: String, val name: String, val explicit: Boolean)

    /** Every relation word, longest first, so "best friend" wins over "friend". */
    private fun relAlternation(): String = words().values.flatten().distinct()
        .filter { SAFE_WORD.matches(it) }   // config words go into a pattern: letters, spaces and apostrophes only
        .sortedByDescending { it.length }.joinToString("|")
    private val SAFE_WORD = Regex("^[\\p{L}' ]{2,30}$")

    private const val NAME = "([\\p{L}\\p{N}' .-]{2,40}?)"
    private val STOP_START = Regex("^(?:not|no|sick|ill|coming|calling|here|there|home|at|in|on|a|an|the|very|so|too|also|still|going|" +
        "late|fine|dead|busy|away|out|back|angry|happy|sad|tired|hungry|pregnant|travel(?:l)?ing|asleep|awake|ok|okay|good|well|who|what|where|" +
        "why|how|now|always|never|really|just|my|your|his|her|our|their|this|that|it|he|she|they|we|you|i)\\b", RegexOption.IGNORE_CASE)
    private val COMPOUND = Regex("\\b(?:and|then|but|because)\\b|[,;]", RegexOption.IGNORE_CASE)

    /**
     * "remember that Ama Mensah is my mother", "save Kofi as my brother", "set my mum to Akos" (explicit: an unknown
     * name is answered honestly), or the bare "Ama is my mother" / "my mother is Ama" (acted on only when the name is a
     * contact — "my mother is sick" must not become a relation). Twi "Ama yɛ me maame" is PENDING NATIVE REVIEW.
     */
    fun parseSet(raw: String): SetRequest? {
        val g = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        val rel = relAlternation()
        val lead = "^(?:please |okay |ok )?"
        val explicit = listOf(
            Regex("$lead(?:remember|note|know) (?:that )?$NAME (?:is|as) my ($rel)$", RegexOption.IGNORE_CASE) to false,
            Regex("$lead(?:remember|note|know) (?:that )?my ($rel) is $NAME$", RegexOption.IGNORE_CASE) to true,
            Regex("$lead(?:save|set|add|mark|make) $NAME as my ($rel)$", RegexOption.IGNORE_CASE) to false,
            Regex("$lead(?:set|save|change|make) my ($rel) (?:to|as|to be) $NAME$", RegexOption.IGNORE_CASE) to true,
            Regex("${lead}my ($rel) is (?:saved|called|named|listed) (?:as |in my contacts as )?$NAME$", RegexOption.IGNORE_CASE) to true
        )
        for ((re, relFirst) in explicit) re.matchEntire(g)?.let { m -> return request(m, relFirst, explicit = true) }
        val bare = listOf(
            Regex("^$NAME is my ($rel)$", RegexOption.IGNORE_CASE) to false,
            Regex("^my ($rel) is $NAME$", RegexOption.IGNORE_CASE) to true,
            // Twi: "<name> yɛ me <relation>" (PENDING NATIVE REVIEW).
            Regex("^$NAME (?:yɛ|ye) me ($rel)$", RegexOption.IGNORE_CASE) to false
        )
        for ((re, relFirst) in bare) re.matchEntire(g)?.let { m -> return request(m, relFirst, explicit = false) }
        return null
    }

    private fun request(m: MatchResult, relFirst: Boolean, explicit: Boolean): SetRequest? {
        val said = (if (relFirst) m.groupValues[1] else m.groupValues[2]).trim()
        val name = (if (relFirst) m.groupValues[2] else m.groupValues[1]).trim().trimEnd('.', ',')
        val relation = canonical(said) ?: return null
        if (name.length < 2 || STOP_START.containsMatchIn(name) || COMPOUND.containsMatchIn(name)) return null
        if (name.split(' ').size > 4 || canonical(name) != null) return null   // "my mum is my mother" is not a name
        return SetRequest(relation, said.lowercase(), name, explicit)
    }

    /** "who is my mother" → "mother"; "who are my family" → "" (all). Null when it isn't a relation question. */
    fun parseQuery(g: String): String? {
        val s = g.trim().lowercase().trimEnd('.', '!', '?')
        Regex("^(?:who(?:'s| is)|what(?:'s| is) the name of) my (${relAlternation()})$").matchEntire(s)?.let { return canonical(it.groupValues[1]) }
        if (Regex("^(?:who are|list|tell me) (?:all )?my (?:family|relations|relatives|people)(?: members)?$").matches(s)) return ""
        return null
    }

    /** "forget who my mother is", "forget that Ama is my mother", "remove my mother" → "mother". */
    fun parseForget(g: String): String? {
        val s = g.trim().lowercase().trimEnd('.', '!', '?')
        val rel = relAlternation()
        Regex("^forget who my ($rel) is$").matchEntire(s)?.let { return canonical(it.groupValues[1]) }
        Regex("^forget (?:that )?.{2,40}? is my ($rel)$").matchEntire(s)?.let { return canonical(it.groupValues[1]) }
        Regex("^(?:forget|remove|clear|unset) my ($rel)(?: relation| label)?$").matchEntire(s)?.let { return canonical(it.groupValues[1]) }
        return null
    }

    // ---------------------------------------------------------------- store

    private val map = LinkedHashMap<String, String>()
    @Volatile private var file: File? = null
    @Volatile private var loaded = false

    /** Load once from the encrypted file (later calls are free). Safe to call from any request path. */
    fun ensureLoaded(ctx: Context?) {
        if (loaded || ctx == null) return
        synchronized(this) {
            if (loaded) return
            val f = File(ctx.applicationContext.filesDir, "memory/relations.enc")
            file = f
            loaded = true
            val text = try { SecureStore.read(f) } catch (_: Throwable) { null } ?: return
            try {
                val o = JSONObject(text)
                for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { map[k] = it }
            } catch (_: Throwable) {}
        }
    }

    /** The saved contact name for a spoken relation ("my mum" → "Ama Mensah"), or null. */
    @Synchronized fun contactFor(word: String): String? = canonical(word)?.let { map[it] }

    @Synchronized fun all(): Map<String, String> = LinkedHashMap(map)

    @Synchronized fun set(relation: String, contactName: String) { map[relation] = contactName; save() }

    @Synchronized fun remove(relation: String): Boolean { val had = map.remove(relation) != null; if (had) save(); return had }

    @Synchronized fun clear() { map.clear(); try { file?.delete() } catch (_: Throwable) {} }

    private fun save() {
        val f = file ?: return   // not loaded from a context (unit tests): memory only
        val o = JSONObject(); map.forEach { (k, v) -> o.put(k, v) }
        try { SecureStore.write(f, o.toString()) } catch (_: Throwable) {}
    }

    /** "your mother is Ama Mensah and your brother is Kofi" — for "who are my family" and "what do you remember". */
    fun spokenAll(m: Map<String, String> = all()): String =
        m.entries.joinToString(", ") { (r, n) -> "your $r is $n" }.let { s ->
            val i = s.lastIndexOf(", ")
            if (i < 0) s else s.substring(0, i) + " and " + s.substring(i + 2)
        }
}
