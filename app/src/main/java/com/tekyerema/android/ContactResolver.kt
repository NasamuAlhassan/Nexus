package com.tekyerema.android

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Resolves contacts by name or number (requires READ_CONTACTS at runtime). */
object ContactResolver {

    data class Contact(val name: String, val number: String)

    /** General search: if the query looks like a number, reverse-lookup by number; else by name. */
    fun search(context: Context, query: String): List<Contact> {
        val digits = query.filter { it.isDigit() }
        return if (digits.length >= 5) lookupByNumber(context, query) else resolve(context, query)
    }

    /** Reverse lookup: who owns this number? Uses PhoneLookup (handles normalization). */
    fun lookupByNumber(context: Context, number: String): List<Contact> {
        val results = ArrayList<Contact>()
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number.trim())
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.PhoneLookup.NUMBER)
                while (c.moveToNext()) {
                    val nm = if (ni >= 0) c.getString(ni) else null
                    val num = if (pi >= 0) c.getString(pi) else number
                    if (!nm.isNullOrBlank()) results.add(Contact(nm, num ?: number))
                }
            }
        } catch (t: Throwable) { /* permission/provider error → empty */ }
        return results.distinctBy { it.name }
    }

    /** Every contact whose number ends with [digits] (≥ 3; a +233 / 0 prefix and spaces don't matter) — "the one ending 2019"
     *  when the name the user said doesn't hold that number (A20e, 14 Oct 2026). Unreadable contacts → empty. */
    fun endingWith(context: Context, digits: String): List<Contact> {
        val tail = digits.filter { it.isDigit() }
        if (tail.length < 3) return emptyList()
        val out = ArrayList<Contact>()
        try {
            context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1).orEmpty()
                    if (name.isNotEmpty() && number.filter { it.isDigit() }.endsWith(tail)) out.add(Contact(name, number))
                }
            }
        } catch (_: Throwable) {}
        return out.distinctBy { it.name.lowercase() to it.number.filter { ch -> ch.isDigit() }.takeLast(9) }
    }

    /** C-03: family words a user says instead of the saved name ("call mum" → a contact saved as "Mama" / "Mother").
     *  `os.contacts.aliases` (word → saved-name candidates) adds or overrides these. */
    private val DEFAULT_ALIASES = mapOf(
        "mum" to listOf("mum", "mom", "mama", "mother", "maa", "ena"),
        "mom" to listOf("mom", "mum", "mama", "mother", "maa", "ena"),
        "mother" to listOf("mother", "mum", "mom", "mama", "maa", "ena"),
        "dad" to listOf("dad", "daddy", "papa", "father", "paa", "agya"),
        "father" to listOf("father", "dad", "daddy", "papa", "agya"),
        "wife" to listOf("wife", "my wife", "darling", "sweetheart"),
        "husband" to listOf("husband", "my husband", "darling", "sweetheart"),
        // the Twi kin words ("me nua barima" → "my brother", language/TwiCommands) land here too
        "brother" to listOf("brother", "bro", "nua"),
        "sister" to listOf("sister", "sis", "nua"),
        "sibling" to listOf("sibling", "brother", "sister", "nua"),
        "son" to listOf("son"), "daughter" to listOf("daughter"),
        "grandparent" to listOf("grandparent", "grandma", "grandpa", "nana"), "child" to listOf("child", "my child")
    )

    private val FILLER_TAIL = Regex("(?i)(?:\\s+(?:please|now|right now|for me|today))+$")

    /** The saved-name candidates to try for a spoken name: itself first, then its aliases. Pure → unit-tested. */
    fun aliasesFor(rawName: String): List<String> {
        // "Call my wife now": the planner can hand the tool the whole phrase; the filler words are not part of the name.
        val name = rawName.trim().replace(FILLER_TAIL, "").ifBlank { rawName.trim() }
        val key = name.trim().lowercase().removePrefix("my ").trim()
        val cfg = com.tekyerema.core.config.AppConfig.stringList("os.contacts.aliases.$key", emptyList())
        // C-03 / H-08: a relationship the user told us ("Ama Mensah is my mother") comes FIRST — it is what they meant,
        // even when some other contact happens to be saved as "Mum".
        val told = listOfNotNull(Relations.contactFor(name))
        return (told + listOf(name.trim()) + cfg + DEFAULT_ALIASES[key].orEmpty()).distinctBy { it.lowercase() }
    }

    /** Words that point at a person without naming one. A substring search for "him" found "Phimona", and a bare
     *  "call him" was read back as a call to her (A20e 2026-10-01). Pure. */
    fun isPronoun(name: String): Boolean = name.trim().lowercase().trimEnd('.', '?', '!') in PRONOUNS
    private val PRONOUNS = setOf("him", "her", "them", "he", "she", "they", "it", "that", "this", "someone", "somebody",
        "that person", "this person", "the person", "that number", "this number", "ɔno", "ono", "wɔn", "won")

    fun resolve(context: Context, name: String): List<Contact> {
        if (isPronoun(name)) return emptyList()   // nobody is named: the caller asks who, never guesses
        Relations.ensureLoaded(context)
        // C-03: try the name as said, then its family aliases, before fuzzy matching.
        val family = isFamilyWord(name)
        for (candidate in aliasesFor(name)) {
            // "my brother" must never be some other person's: a contact saved as "meshark's brother" was read back for
            // "Frɛ me nua barima" (QA TR13, A20e, 3 October 2026). Only a contact that IS the family word (alone, or "my …")
            // — or the relation the user told us — counts; otherwise the caller asks who.
            val hit = resolveOne(context, candidate).let { hits ->
                if (family && Relations.contactFor(name) == null) hits.filter { ownsFamilyWord(it.name, candidate) } else hits
            }
            if (hit.isNotEmpty() || candidate == aliasesFor(name).last()) return hit
        }
        return emptyList()
    }

    private fun isFamilyWord(name: String): Boolean = name.trim().lowercase().removePrefix("my ").trim() in DEFAULT_ALIASES

    /** The saved [contactName] is this [word] itself — "Brother", "My brother", "Brother (Kwame)", "Bro Kofi" — not
     *  "Meshark's brother", and not "Brown" for "bro" (a whole word only; QA R4_TR13, A20e, 3 October 2026). Pure. */
    internal fun ownsFamilyWord(contactName: String, word: String): Boolean {
        val n = contactName.trim().lowercase().replace('’', '\'')
        val w = word.trim().lowercase()
        val at = n.indexOf(w)
        if (at < 0) return false
        if (at + w.length < n.length && n[at + w.length].isLetter()) return false
        val before = n.substring(0, at).trim()
        return before.isEmpty() || before == "my" || before == "our"
    }

    /** One contact taken on anything less than its name as said — by sound, a later word, a loose fit — is said in the
     *  read-back once ("I heard 'rich man' — taking it as Richmond Apenensu."). Safety audit 2026-10-05: every inferred
     *  recipient, not only the weak ones. */
    private fun noteLoose(heard: String, picked: List<Contact>) {
        if (picked.size == 1 && ContactMatcher.score(heard, picked[0].name) < ContactMatcher.AS_SAID)
            try { com.tekyerema.voice.HeardAs.noteGuess(heard, picked[0].name) } catch (_: Throwable) {}
    }

    /**
     * [contact] is a SURE match for what was said — its name (or a family word / relation the user set) as said, or by sound
     * (ContactMatcher ≥ STRONG). An action that is not read back for approval ("set my emergency contact to …", "remember
     * that … is my mother") is taken only on a sure match; otherwise the user is asked to say the full name. Pure apart
     * from the user's relations and config.
     */
    fun isSure(said: String, contact: Contact): Boolean {
        if (!com.tekyerema.core.config.AppConfig.bool("speech.contactMatcher.enabled", true)) return true
        return aliasesFor(said).any { ContactMatcher.score(it, contact.name) >= ContactMatcher.STRONG }
    }

    private fun resolveOne(context: Context, name: String): List<Contact> {
        val q = name.trim()
        // Exact-ish substring match first. The user's spoken name is untrusted text going into a SQL LIKE,
        // so escape the wildcards %/_ (and the escape char) — otherwise "%" would match EVERY contact and a
        // "_" would silently match any character (§1.11).
        val exact = query(context, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("%${escapeLike(q)}%"), name)
        if (exact.isNotEmpty() && com.tekyerema.core.config.AppConfig.bool("speech.contactMatcher.enabled", true)) {
            // Robustness layer (5 October 2026): how WELL each hit fits. A sure hit — the whole name, its first word, the
            // first word by sound — is used as before ("Prince" = every Prince, no longer "Princess"). A loose one — only a
            // later word or a fragment ("press" in "Abuakwa Printing Press", for a misheard "Prince") — is never returned
            // alone: the sound-alikes among all contacts come with it, so the caller asks "who did you mean?".
            val ranked = ContactMatcher.rank(q, exact)
            if (ranked.isNotEmpty() && ranked[0].score >= ContactMatcher.STRONG) return ContactMatcher.pick(q, ranked).also { noteLoose(q, it) }
            val all = ContactMatcher.rank(q, query(context, null, null, name))
            val merged = ContactMatcher.pick(q, (ranked + all).sortedByDescending { it.score }
                .distinctBy { it.contact.number.filter(Char::isDigit).takeLast(9) })
            noteLoose(q, merged)
            // NONE is nobody: the name only turns up INSIDE another ("Richmond" in "Richmond's Dad", "ama" in "Kwamena") —
            // those hits used to come back unscored and a single one was read back as the recipient (safety audit 2026-10-05).
            return merged
        }
        if (exact.isNotEmpty()) return exact

        // Fuzzy fallback for ASR-mangled names ("Kwami" → "Kwame", "Efua" → "Afua"). Load the address book
        // and rank by normalized edit distance against the whole display name and its best single token;
        // return the close ones (best first). The caller disambiguates when more than one comes back — we
        // never silently dial a low-confidence guess (§1.11, and the product rule: honest ask > wrong call).
        val all = query(context, null, null, name)
        // By sound and shape first (ContactMatcher: "Kwami" → Kwame by key, "prints" → Prince by skeleton) — the close ones
        // together, so two people who fit are asked about, never guessed between.
        if (com.tekyerema.core.config.AppConfig.bool("speech.contactMatcher.enabled", true)) {
            val ranked = ContactMatcher.rank(q, all)
            if (ranked.isNotEmpty()) {
                val picked = ContactMatcher.pick(q, ranked)
                noteLoose(q, picked)
                return picked
            }
        }
        val nq = normalize(q)
        if (nq.isEmpty()) return emptyList()
        return all.map { c ->
            val nname = normalize(c.name)
            val tokenBest = c.name.split(Regex("\\s+")).maxOfOrNull { similarity(nq, normalize(it)) } ?: 0.0
            c to maxOf(similarity(nq, nname), tokenBest)
        }.filter { it.second >= 0.72 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.number.filter { ch -> ch.isDigit() } }
    }

    private fun query(context: Context, selection: String?, args: Array<String>?, fallbackName: String): List<Contact> {
        val results = ArrayList<Contact>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val nm = if (ni >= 0) c.getString(ni) else fallbackName
                    val num = if (pi >= 0) c.getString(pi) else continue
                    if (!num.isNullOrBlank()) results.add(Contact(nm ?: fallbackName, num))
                }
            }
        } catch (t: Throwable) {
            // permission not granted or provider error → empty
        }
        return results.distinctBy { it.number.filter { ch -> ch.isDigit() } }
    }

    /** Escape SQL LIKE metacharacters so a user's name is matched literally (paired with ESCAPE '\'). */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** 1 − normalised Levenshtein, in [0, 1]; 1.0 = identical ([com.tekyerema.core.TextDistance]). */
    private fun similarity(a: String, b: String): Double = com.tekyerema.core.TextDistance.similarity(a, b)
}
