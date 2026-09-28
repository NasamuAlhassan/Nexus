package com.tekyerema.voice

import com.tekyerema.core.config.AppConfig

/**
 * X-06 — per-locale spoken phrases from `assets/config/locales.json` (`locales.<en|tw>.<key>`), with the wording in
 * code as the fallback, and `{slot}` filling. New spoken lines go through here so a phrase can be corrected (or a
 * native speaker's Twi dropped in) without a code change.
 */
object Phrases {
    fun get(key: String, twi: Boolean, default: String, vararg slots: Pair<String, String>): String =
        AppConfig.phrase("locales.${if (twi) "tw" else "en"}.$key", default, *slots)
}
