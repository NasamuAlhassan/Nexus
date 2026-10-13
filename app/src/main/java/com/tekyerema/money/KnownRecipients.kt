package com.tekyerema.money

import com.tekyerema.agent.NumberSpeech
import com.tekyerema.operator.OperatorStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The numbers the user has sent Mobile Money to — so a send to a NEW number says so in the read-back ("You haven't sent
 * money to this number before."). Research: secure_payment_auth.md recommendation 7 (scam shield: first-time recipient);
 * the "sent to you by mistake, send it back" script always sends to a number the victim has never paid.
 *
 * Privacy: the numbers themselves are never kept — only a salted SHA-256 of the normalised number (0XXXXXXXXX; +233 folds
 * to 0), with a random salt made once on this phone, so the list can't be read back or matched against a table of
 * numbers. Bounded ([MAX], oldest dropped). A number is added when the NETWORK'S own message confirms a send to it
 * (money/TxLedger, finance audit 2026-10-01 — it used to be added at the PIN hand-off, so a send the user cancelled at the
 * PIN silenced the first-time warning for that number the next time). Emptied by "delete
 * everything" (then every number is new again — the safe direction). Stored in the operator's store (unit tests: a map).
 */
object KnownRecipients {
    const val MAX = 200
    const val K_SALT = "op.momo.salt"
    const val K_KNOWN = "op.momo.known"

    @Volatile private var store: OperatorStore? = null
    private var salt: String? = null
    /** Oldest first. */
    private val hashes = LinkedHashSet<String>()

    @Synchronized fun init(s: OperatorStore) {
        store = s
        hashes.clear()
        salt = s.get(K_SALT)
        s.get(K_KNOWN)?.split(',')?.map { it.trim() }?.filter { it.length == 64 }?.forEach { hashes.add(it) }
    }

    @Synchronized fun resetForTest(s: OperatorStore? = null) {
        store = null; salt = null; hashes.clear()
        if (s != null) init(s)
    }

    private fun saltNow(): String = salt ?: run {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        salt = s
        try { store?.put(K_SALT, s) } catch (t: Throwable) { android.util.Log.w("Tekyerema", "[RECIPIENTS] salt not stored: ${t.javaClass.simpleName}") }   // P3-04: unsaved = every recipient is "new" again
        s
    }

    /** The salted hash of [number] with [salt], or null when it has no digits. Pure. */
    fun hash(salt: String, number: String?): String? {
        val n = NumberSpeech.normalize(number)
        if (n.isEmpty()) return null
        val d = MessageDigest.getInstance("SHA-256").digest("$salt:$n".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Has the user sent Mobile Money to [number] before (on this phone)? */
    @Synchronized fun isKnown(number: String?): Boolean {
        val s = salt ?: return false   // no salt yet = nothing was ever added
        val h = hash(s, number) ?: return false
        return h in hashes
    }

    /** Remember [number] (the newest last; the oldest dropped past [MAX]). */
    @Synchronized fun add(number: String?) {
        val h = hash(saltNow(), number) ?: return
        hashes.remove(h); hashes.add(h)
        while (hashes.size > MAX) hashes.remove(hashes.first())
        try { store?.put(K_KNOWN, hashes.joinToString(",")) } catch (t: Throwable) { android.util.Log.w("Tekyerema", "[RECIPIENTS] not stored: ${t.javaClass.simpleName}") }
    }

    /** The salted hash of [number] for another money store to keep instead of the number (money/TxLedger): with
     *  [create] false it is null until a salt exists (nothing was ever hashed, so nothing can match). */
    @Synchronized fun keyOf(number: String?, create: Boolean = true): String? {
        val s = if (create) saltNow() else salt ?: return null
        return hash(s, number)
    }

    /** Remember a number by the hash [keyOf] gave for it — the network confirmed a send to it (money/TxLedger). */
    @Synchronized fun addKey(key: String?) {
        val h = key?.takeIf { it.length == 64 } ?: return
        hashes.remove(h); hashes.add(h)
        while (hashes.size > MAX) hashes.remove(hashes.first())
        try { store?.put(K_KNOWN, hashes.joinToString(",")) } catch (t: Throwable) { android.util.Log.w("Tekyerema", "[RECIPIENTS] not stored: ${t.javaClass.simpleName}") }
    }

    @Synchronized fun count(): Int = hashes.size

    /** "Delete everything": forget them all (and the salt, so nothing old can be matched). */
    @Synchronized fun clear() {
        hashes.clear(); salt = null
        try { store?.put(K_KNOWN, null); store?.put(K_SALT, null) } catch (t: Throwable) { android.util.Log.w("Tekyerema", "[RECIPIENTS] not cleared: ${t.javaClass.simpleName}") }
    }

    /** The read-back line for a first-time recipient. */
    const val FIRST_TIME_LINE = "You haven't sent money to this number before."
}
