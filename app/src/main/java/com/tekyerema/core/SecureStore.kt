package com.tekyerema.core

import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * J-15 — small app-private files encrypted at rest with AES-256-GCM. The key is generated inside the Android Keystore
 * (hardware-backed where the phone has it) and never leaves it; `androidx.security-crypto` is deprecated, so this uses
 * the platform Keystore directly. Everything that holds a person's words goes through here: the conversation, the
 * message index, the action audit, history titles, opt-in trajectories.
 *
 * If the Keystore can't be used, NOTHING is written in the clear — [write] returns false and the caller keeps the
 * data in memory only. Format: "TKY1" + iv length (1 byte) + iv + ciphertext-with-tag. [keyProvider] lets unit tests
 * supply a software key. Writes are fsync'd before the atomic rename ([writeDurably]).
 */
object SecureStore {
    private const val ALIAS = "tekyerema_store_v1"
    private val MAGIC = "TKY1".toByteArray()
    private const val TAG_BITS = 128

    /** Test seam: a key source other than the Android Keystore. */
    @Volatile var keyProvider: (() -> SecretKey?)? = null

    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey? {
        keyProvider?.let { return it() }
        cached?.let { return it }
        return try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.secretKey
            val k = existing ?: run {
                val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                gen.generateKey()
            }
            cached = k
            k
        } catch (_: Throwable) { null }
    }

    fun encrypt(plain: ByteArray): ByteArray? {
        val k = key() ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            val iv = c.iv
            MAGIC + byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
        } catch (_: Throwable) { null }
    }

    fun decrypt(blob: ByteArray): ByteArray? {
        if (blob.size < MAGIC.size + 2 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val k = key() ?: return null
        return try {
            val ivLen = blob[MAGIC.size].toInt() and 0xff
            val start = MAGIC.size + 1
            val iv = blob.copyOfRange(start, start + ivLen)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            c.doFinal(blob, start + ivLen, blob.size - start - ivLen)
        } catch (_: Throwable) { null }
    }

    /** Encrypt [text] into [file] (atomically). False = not written (no key) — keep the data in memory only. */
    fun write(file: File, text: String): Boolean {
        val blob = encrypt(text.toByteArray(Charsets.UTF_8)) ?: return false
        return writeDurably(file, blob)
    }

    /**
     * Write [bytes] to a temp file, fsync it, then rename it over [file] — AtomicFile's contract ("completely written and
     * sync'd to disk before renaming"). The old write renamed an UN-synced temp file: after a power loss (a dying battery
     * is plausible for this user group) the rename could survive while the data didn't, leaving an empty or torn file —
     * for the effect ledger that means forgetting a send that was about to happen (research: agenticness_reliability_state
     * §2.2). Single writer per file; callers serialize (the engine runs one task at a time).
     */
    internal fun writeDurably(file: File, bytes: ByteArray): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()   // the bytes are on disk BEFORE the rename makes them the file
        }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("rename failed") }
        true
    } catch (_: Throwable) { false }

    /** The decrypted text, or null when the file is missing, not ours, or can't be decrypted. */
    fun read(file: File): String? = try {
        if (!file.exists()) null else decrypt(file.readBytes())?.toString(Charsets.UTF_8)
    } catch (_: Throwable) { null }
}
