package com.tekyerema.voice

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import com.tekyerema.core.config.AppConfig
import com.tekyerema.language.TwiText

/**
 * B-19 — post-ASR correction of NAMES against what is actually on this phone: the user's contacts and installed apps.
 *
 * Why after the recogniser: sherpa-onnx runs our Whisper-tiny fine-tunes with greedy search, and in sherpa-onnx only
 * transducer models take hotwords (and its Whisper has no initial prompt), so the phone's vocabulary can't bias the
 * decode itself. The published alternative is to retrieve phonetically similar entities from the user's own data and
 * rewrite the hypothesis (Apple, arXiv 2409.15353: up to 73.6% fewer named-entity errors). Whisper-tiny's name
 * errors are mostly sound-alike spellings — "message coffee" (Kofi), "call Kwami" (Kwame), "send it to Amma" (Ama),
 * "Krabena" (Kwabena), "Ako Sua" (Akosua) on the owner's GOLD clips — which is exactly what a phonetic key catches.
 *
 * Deliberately conservative (a wrong name is worse than a misspelt one: the tools' own fuzzy lookup, the planner and
 * the confirmation step all still see the result):
 *  - only in a NAME SLOT — right after a verb that takes a person ("call", "message", "send … to") or an app ("open");
 *  - a word that already names a contact/app, a pronoun or a family word ("her", "mum") is never touched;
 *  - one word is replaced only when its sound-key EQUALS the candidate's, or its spelling is ≥ 0.8 similar with the
 *    same first letter; a joined span ("Ako Sua", "you tube") must spell the candidate once spaces are dropped;
 *  - two different candidates that fit equally well → no change (the tools then ask which one).
 * The raw transcript is never changed ([Transcript.raw]); only the canonical goal is. Names never reach the log in a
 * release build.
 */
object PhoneticKey {
    /**
     * A sound key for Ghanaian-accented English and Akan names. Spelling-to-sound folds first (ph→f, gh→g, ck→k,
     * qu→kw, x→ks, dj/dz→j as in Adjoa, soft c→s, hard c→k, z→s, th→t, wh→w, Akan ky→ch and hy→sh), then vowels
     * collapse to three classes (a | e i y → i | o u → u — the Ghanaian vowel pairs Whisper confuses: Kwame/Kwami,
     * Kofi/coffee), "aw"/"ao" → "au" (Yaw/Yao), and doubled letters collapse (Amma/Ama).
     */
    fun of(word: String): String {
        var s = TwiText.fold(word).replace("ŋ", "ng")
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).filter { it in 'a'..'z' || it in '0'..'9' }
        if (s.isEmpty()) return s
        s = s.replace("tch", "ch").replace("ph", "f").replace("gh", "g").replace("ck", "k").replace("qu", "kw")
            .replace("q", "k").replace("x", "ks").replace("dj", "j").replace("dz", "j").replace("th", "t")
            .replace("wh", "w").replace("ky", "ch").replace("hy", "sh")
        // A final "-nce" is said "-ns", and Whisper-tiny writes it "-nt"/"-nts": the owner's "Prince" came out "prints"
        // and "print" every time (A20e 2026-09-29, in English and inside Twi). One ending, one key.
        s = s.replace(Regex("n(?:ts|t|ce|se|s)$"), "ns")
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            sb.append(when (c) {
                'c' -> if (next == 'e' || next == 'i' || next == 'y') 's' else if (next == 'h') 'c' else 'k'
                'z' -> 's'
                'e', 'i' -> 'i'
                'y' -> if (i > 0 && s[i - 1] !in VOWELS && (next == null || next !in VOWELS)) 'i' else 'y'
                'o', 'u' -> 'u'
                'w' -> if (i > 0 && s[i - 1] == 'a' && (next == null || next !in VOWELS)) 'u' else 'w'
                else -> c
            })
        }
        val folded = sb.toString().replace("ao", "au")
        val out = StringBuilder(folded.length)
        for (c in folded) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    /** Lower-case letters and digits only (accents and Akan letters folded) — "You Tube" == "YouTube". */
    fun compact(s: String): String =
        java.text.Normalizer.normalize(TwiText.fold(s), java.text.Normalizer.Form.NFD).filter { it in 'a'..'z' || it in '0'..'9' }

    /** 1 − normalised Levenshtein, in [0, 1]. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1.0 - dp[b.length].toDouble() / maxOf(a.length, b.length)
    }
}

object EntityCorrector {
    enum class Kind { CONTACT, APP }

    /** One correctable name: how it's written on the phone, and its keys. */
    class Entry(val text: String, val kind: Kind) {
        val compact = PhoneticKey.compact(text)
        val key = PhoneticKey.of(text)
    }

    /** What the phone knows, prepared once ([of]) and reused for every utterance. */
    class Vocab(val entries: List<Entry>) {
        val exactCompacts: Set<String> = entries.map { it.compact }.toHashSet()
        val isEmpty get() = entries.isEmpty()
        companion object {
            val EMPTY = Vocab(emptyList())
            /** Contact display names contribute the whole name AND each part ("Kofi Mensah" → "Kofi", "Mensah"). */
            fun of(contacts: Collection<String>, apps: Collection<String>): Vocab {
                val out = LinkedHashMap<String, Entry>()
                fun add(t: String, k: Kind) {
                    val e = Entry(t.trim(), k)
                    if (e.compact.length >= 2) out.putIfAbsent(k.name + "|" + e.compact, e)
                }
                for (c in contacts) {
                    add(c, Kind.CONTACT)
                    // A possessive part is its name ("Richmond's Dad" → "Richmond"): a heard "Richmond" is already right,
                    // never turned into "Richmond's" — someone else (A20e, 5 October 2026).
                    c.split(Regex("[\\s,.]+")).map { it.replace(Regex("['’]s$", RegexOption.IGNORE_CASE), "") }
                        .filter { PhoneticKey.compact(it).length >= 3 }.forEach { add(it, Kind.CONTACT) }
                }
                for (a in apps) {
                    add(a, Kind.APP)
                    // "Google Maps" is said "maps"; the vendor word alone never names one app
                    val words = a.trim().split(Regex("\\s+"))
                    if (words.size > 1 && words[0].lowercase() in VENDOR) add(words.drop(1).joinToString(" "), Kind.APP)
                }
                return Vocab(out.values.toList())
            }
        }
    }

    data class Fix(val from: String, val to: String, val kind: Kind)
    data class Result(val text: String, val fixes: List<Fix>)

    private val VENDOR = setOf("google", "samsung", "microsoft", "android")

    // Name slots. A person follows these verbs; "to"/"for"/"from" introduce a person only in a sending/reading request.
    private val CONTACT_VERBS = setOf("call", "ring", "phone", "dial", "message", "text", "sms", "email", "tell", "ask",
        "frɛ", "fre",   // Twi "frɛ" = call [owner: verify with a native speaker]
        "kyerɛ", "kyere")   // Twi "ka kyerɛ X" = tell X (the owner's "ka kyerɛ print sɛ …" = tell Prince that …)
    /** "switch the account to Prince": after "account", "to" names the account's owner — a person on this phone. */
    private val ACCOUNT_WORDS = setOf("account", "accounts")
    private val PERSON_PREPS = setOf("to", "for", "from")
    private val SEND_VERBS = setOf("send", "sent", "give", "transfer", "pay", "forward", "buy", "message", "text",
        "messages", "texts", "reply", "write", "email", "sms", "calls", "call", "read")
    private val APP_VERBS = setOf("open", "launch", "start", "close", "install", "uninstall", "update", "use", "bue")   // Twi "bue" = open
    /** Skipped between the verb and the name ("open THE app", "call MY …" is a family word, handled below). */
    private val FILLER = setOf("the", "a", "an", "up", "app", "application", "please", "on", "my", "to")
    /** Never a name to correct: pronouns, family words (ContactResolver aliases them), places and generic nouns. */
    private val PROTECTED = setOf(
        "me", "him", "her", "them", "us", "it", "that", "this", "these", "those", "you", "yourself", "someone",
        "somebody", "everyone", "everybody", "anyone", "nobody", "back", "home", "again", "now", "later", "today",
        "tomorrow", "mum", "mom", "mama", "mother", "dad", "daddy", "papa", "father", "wife", "husband", "brother",
        "sister", "son", "daughter", "friend", "boss", "uncle", "auntie", "aunty", "aunt", "grandma", "grandpa",
        "money", "cedis", "cedi", "airtime", "message", "messages", "number", "phone", "camera", "police", "ambulance",
        "emergency", "help", "yes", "no", "cancel", "stop", "everything", "all", "him.", "her."
    )
    /** A word that ends a name span: the name is over once one of these comes. */
    private val STOP = setOf("and", "to", "with", "on", "about", "that", "saying", "say", "please", "now", "again", "for",
        "from", "in", "at", "if", "so", "then", "but", "or", "is", "are", "was", "i", "i'm", "i'll", "i've", "we")

    private fun core(w: String) = w.lowercase().trim('.', ',', '!', '?', ';', ':', '"', '\'', '(', ')')
    private fun isRelation(w: String) = try { com.tekyerema.android.Relations.canonical(w) != null } catch (_: Throwable) { false }

    /**
     * Correct the name slots in [text] against [vocab]. Pure; the same input always gives the same output.
     */
    fun correct(text: String, vocab: Vocab): Result {
        if (text.isBlank() || vocab.isEmpty) return Result(text, emptyList())
        if (!AppConfig.bool("speech.entityCorrection.enabled", true)) return Result(text, emptyList())
        val words = text.split(" ").filter { it.isNotEmpty() }.toMutableList()
        val cores = words.map(::core)
        val fixes = ArrayList<Fix>()
        var sendSeen = false
        var accountSeen = false
        var i = 0
        while (i < words.size) {
            val c = cores[i]
            val kind = when {
                c in APP_VERBS -> Kind.APP
                c in CONTACT_VERBS -> Kind.CONTACT
                c in PERSON_PREPS && sendSeen -> Kind.CONTACT
                c == "to" && accountSeen -> Kind.CONTACT
                else -> null
            }
            if (c in SEND_VERBS) sendSeen = true
            if (c in ACCOUNT_WORDS) accountSeen = true
            if (kind == null) { i++; continue }
            // the slot starts after fillers ("open THE app …", "send it TO MY …")
            var s = i + 1
            while (s < words.size && cores[s] in FILLER && !(cores[s] == "to" && kind == Kind.APP)) s++
            if (s >= words.size) break
            // span = up to 3 words, ending at a stop word or after a word carrying sentence punctuation
            var e = s
            while (e < words.size && e - s < 3 && cores[e].isNotEmpty() && cores[e] !in STOP && cores[e].none { it.isDigit() }) {
                e++
                if (words[e - 1].last() in ".,!?;:") break
            }
            if (e == s) { i = s; continue }
            val span = matchSpan(words.subList(s, e).toList(), cores.subList(s, e), kind, vocab)
            if (span != null) {
                val (used, entry) = span
                val lead = words[s].takeWhile { !it.isLetterOrDigit() }
                val trail = words[s + used - 1].takeLastWhile { !it.isLetterOrDigit() }
                val from = words.subList(s, s + used).joinToString(" ")
                fixes.add(Fix(from, entry.text, kind))
                words[s] = lead + entry.text + trail
                for (k in 1 until used) words[s + k] = ""
            }
            i = e
        }
        if (fixes.isEmpty()) return Result(text, emptyList())
        return Result(words.filter { it.isNotEmpty() }.joinToString(" "), fixes)
    }

    /** The longest span (3 → 1 words) that names exactly one entry; null when the words are fine as they are or
     *  nothing fits well enough. */
    private fun matchSpan(words: List<String>, cores: List<String>, kind: Kind, vocab: Vocab): Pair<Int, Entry>? {
        val pool = vocab.entries.filter { it.kind == kind }
        if (pool.isEmpty()) return null
        for (n in words.size downTo 1) {
            val part = cores.subList(0, n)
            // a protected word on its own is never a name ("call her"); inside a joined span it may be ("you tube")
            // nor a relation word ("call my maame" must reach Relations, not become the contact "Mame Esi")
            if (n == 1 && (part[0] in PROTECTED || isRelation(part[0]))) continue
            val compact = PhoneticKey.compact(part.joinToString(""))
            if (compact.length < 3) continue
            // Already right (a real name, or a known label split by the recogniser in the same way): leave it.
            if (n == 1 && compact in vocab.exactCompacts) return null
            if (n > 1) {
                // a joined span must SPELL the entry once spaces go ("Ako Sua" → Akosua, "you tube" → YouTube)
                val hits = pool.filter { it.compact == compact || (it.key == PhoneticKey.of(compact) && PhoneticKey.similarity(it.compact, compact) >= 0.8) }
                    .distinctBy { it.compact }
                if (hits.isEmpty()) continue
                // a name already right is not stretched over the next word: "tell Kwame I will come" kept its "I" only
                // after this (robustness audit 2026-10-05 — "Kwame I" sounds like "Kwame", and the "I" was lost)
                if (hits.any { it.compact == PhoneticKey.compact(cores[0]) }) continue
                if (hits.size > 1 || words.subList(0, n).joinToString(" ").trim('.', ',', '!', '?').equals(hits[0].text, ignoreCase = true)) return null
                return n to hits[0]
            }
            val key = PhoneticKey.of(compact)
            if (key.length < 3) continue
            val scored = pool.mapNotNull { en ->
                val sound = en.key == key
                val spell = PhoneticKey.similarity(compact, en.compact)
                val sameStart = compact[0] == en.compact[0] || (compact[0] in "aeiou" && en.compact[0] in "aeiou")
                val score = when {
                    sound && spell >= 0.3 -> 1.0 + spell / 10        // same sound ("coffee"/Kofi spell only 0.33 alike)
                    spell >= 0.8 && sameStart -> spell
                    else -> return@mapNotNull null
                }
                en to score
            }.sortedByDescending { it.second }
            if (scored.isEmpty()) continue
            val best = scored[0]
            val rival = scored.drop(1).firstOrNull { it.first.compact != best.first.compact && !sameEntity(it.first, best.first) }
            if (rival != null && best.second - rival.second < 0.1) return null          // two people/apps fit: let the tools ask
            if (best.first.compact == compact) return null
            return 1 to best.first
        }
        return null
    }

    /** A contact's full name and one of its parts are the same person (both in the vocab). */
    private fun sameEntity(a: Entry, b: Entry) = a.kind == b.kind && (a.compact.contains(b.compact) || b.compact.contains(a.compact))
}

/**
 * The phone's names for [EntityCorrector]: contact display names (only when READ_CONTACTS is ALREADY granted — this
 * never asks) and launcher app labels. Loaded off the voice path ([refresh] at warm-up and after each turn) and read
 * as an in-memory snapshot, so correcting a transcript never waits on a content provider. Never logged except as
 * counts; never leaves the phone.
 */
object SpeechVocabulary {
    @Volatile private var snapshot: EntityCorrector.Vocab = EntityCorrector.Vocab.EMPTY
    @Volatile private var loadedAt = 0L

    /** What the corrector uses now (empty until the first [refresh], and always in unit tests). */
    fun current(): EntityCorrector.Vocab = snapshot

    /** For tests and the debug hook. */
    fun set(v: EntityCorrector.Vocab) { snapshot = v; loadedAt = System.currentTimeMillis() }

    /** Reload when older than `speech.entityCorrection.refreshMs` (default 10 min). Call from a background thread. */
    fun refresh(ctx: Context, force: Boolean = false) {
        val ttl = AppConfig.long("speech.entityCorrection.refreshMs", 600_000L)
        if (!force && System.currentTimeMillis() - loadedAt < ttl && !snapshot.isEmpty) return
        val t0 = android.os.SystemClock.uptimeMillis()
        val contacts = contactNames(ctx)
        val apps = try { com.tekyerema.android.AppResolver.launchable(ctx).map { it.label } } catch (_: Throwable) { emptyList() }
        snapshot = EntityCorrector.Vocab.of(contacts, apps)
        loadedAt = System.currentTimeMillis()
        Log.i("Tekyerema", "[ASR] vocabulary: ${contacts.size} contacts, ${apps.size} apps (${android.os.SystemClock.uptimeMillis() - t0} ms)")
    }

    private fun contactNames(ctx: Context): List<String> {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val max = AppConfig.int("speech.entityCorrection.maxContacts", 2000)
        val out = LinkedHashSet<String>()
        try {
            ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null)?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (c.moveToNext() && out.size < max) {
                    val n = if (ni >= 0) c.getString(ni) else null
                    if (!n.isNullOrBlank() && n.any { it.isLetter() }) out.add(n.trim())
                }
            }
        } catch (_: Throwable) {}
        return out.toList()
    }
}
