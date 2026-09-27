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

    fun resolve(context: Context, name: String): List<Contact> {
        val q = name.trim()
        // Exact-ish substring match first. The user's spoken name is untrusted text going into a SQL LIKE,
        // so escape the wildcards %/_ (and the escape char) — otherwise "%" would match EVERY contact and a
        // "_" would silently match any character (§1.11).
        val exact = query(context, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("%${escapeLike(q)}%"), name)
        if (exact.isNotEmpty()) return exact

        // Fuzzy fallback for ASR-mangled names ("Kwami" → "Kwame", "Efua" → "Afua"). Load the address book
        // and rank by normalized edit distance against the whole display name and its best single token;
        // return the close ones (best first). The caller disambiguates when more than one comes back — we
        // never silently dial a low-confidence guess (§1.11, and the product rule: honest ask > wrong call).
        val all = query(context, null, null, name)
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

    /** 1 - normalizedLevenshtein, in [0,1]; 1.0 == identical. */
    private fun similarity(a: String, b: String): Double {
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
