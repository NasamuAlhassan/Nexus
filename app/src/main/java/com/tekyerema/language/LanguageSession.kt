package com.tekyerema.language

import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Lang

/**
 * B-05 — language state per conversation turn. Two different questions used to share one persisted variable
 * (Settings.sessionLanguage), which every turn overwrote:
 *
 *  1. "Which language is this conversation in right now?" — the language of the user's LAST turn, while the
 *     conversation is live. Replies, confirmations and follow-up questions use it: reply in the language I spoke.
 *     It is also the low-signal fallback for the next utterance ("50", a bare name), so a Twi conversation stays Twi
 *     through a short answer and an English one stays English.
 *  2. "Which language does this person usually speak?" — the HABITUAL language, persisted. It only changes after
 *     [switchAfterTurns] turns in a row in the other language, so one code-switched request ("okay, call Kofi") in
 *     an otherwise Twi life doesn't make the next day's first low-signal utterance English.
 *
 * [current] answers (1) and falls back to (2) once the conversation has been quiet for [conversationTtlMs].
 * An explicit language mode (English only / Twi only) is applied upstream and always wins over both.
 *
 * Code-switching inside one utterance is resolved before this by the on-device LID (dominant language, and never a
 * flip on a single token); this class sees one language per turn. Pure and unit-tested; thread-safe.
 */
class LanguageSession(
    private val loadHabitual: () -> String,
    private val saveHabitual: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val conversationTtlMs: () -> Long = { AppConfig.long("speech.language.conversationTtlSec", 300L) * 1000L },
    private val switchAfterTurns: () -> Int = { AppConfig.int("speech.language.habitualSwitchTurns", 2) }
) {
    @Volatile private var lastTurnLang: String? = null
    @Volatile private var lastTurnAt = 0L
    @Volatile private var streakLang: String? = null
    @Volatile private var streak = 0

    /** The language of the conversation now: the last turn's while it's live, else the habitual one. */
    fun current(): String {
        val last = lastTurnLang
        if (last != null && clock() - lastTurnAt <= conversationTtlMs()) return last
        return habitual()
    }

    fun habitual(): String = loadHabitual().takeIf { it in TURN_LANGS } ?: Lang.GH_EN

    /** Record the resolved language of a user turn. Anything but gh_en/twi/kus (e.g. "auto") is ignored. */
    @Synchronized fun onTurn(lang: String) {
        if (lang !in TURN_LANGS) return
        lastTurnLang = lang
        lastTurnAt = clock()
        val habitual = habitual()
        if (lang == habitual) { streakLang = null; streak = 0; return }
        streak = if (streakLang == lang) streak + 1 else 1
        streakLang = lang
        if (streak >= switchAfterTurns().coerceAtLeast(1)) {
            saveHabitual(lang); streakLang = null; streak = 0
        }
    }

    private companion object { val TURN_LANGS = setOf(Lang.GH_EN, Lang.TWI, Lang.KUS) }

    /** For logs/debug: "twi (habitual gh_en, 1/2 toward twi)". */
    fun describe(): String = "${current()} (habitual ${habitual()}" +
        (streakLang?.let { ", $streak/${switchAfterTurns()} toward $it" } ?: "") + ")"
}
