package com.tekyerema.core

/**
 * Strips hidden-channel content from UNTRUSTED screen text before it reaches the model.
 * Zero-width characters, soft hyphens, bidi controls and the Unicode TAG block
 * (U+E0000–U+E007F) are all documented carriers for instructions a user cannot see.
 * Deterministic and applied at perception time — not a prompt rule (plan §13 b2).
 */
object Sanitize {
    // Zero-width space/joiners, word joiner, invisible operators, BOM, soft hyphen, bidi marks.
    private val invisible = Regex("[\u200B-\u200F\u2028-\u202E\u2060-\u2064\u206A-\u206F\uFEFF\u00AD]")
    // Unicode TAG characters are supplementary code points (U+E0000–U+E007F); match them as code
    // points, not as surrogate halves — the regex engine sees a valid pair as ONE code point.
    private val tagBlock = Regex("[\\x{E0000}-\\x{E007F}]")
    private val whitespace = Regex("\\s+")

    fun label(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.replace(tagBlock, "").replace(invisible, "").replace(whitespace, " ").trim()
        return cleaned.ifEmpty { null }
    }

    /** True if the raw text carried a hidden channel that was removed — worth logging. */
    fun hadHiddenContent(raw: String?): Boolean =
        raw != null && (tagBlock.containsMatchIn(raw) || invisible.containsMatchIn(raw))
}
