package com.tekyerema.agent.journal

import com.tekyerema.accessibility.UiSnapshot

/**
 * Reconciliation: before an effect whose outcome is UNKNOWN is redone, look at the world where Android lets us — there is
 * no server that dedupes a WhatsApp message or an SMS for us, so the ledger gives at-most-once plus a check (research:
 * agenticness_reliability_state §3). [DONE] / [NOT_DONE] are facts; [UNDETERMINED] means the user decides.
 */
enum class WorldVerdict { DONE, NOT_DONE, UNDETERMINED }

data class WorldResult(val verdict: WorldVerdict, val evidence: String) {
    companion object { val UNDETERMINED = WorldResult(WorldVerdict.UNDETERMINED, "no way to check this on the phone") }
}

fun interface WorldCheck {
    /**
     * @param payload the payload of the attempt being considered (its hash equals [effect]'s when they match), or null
     * when only the ledger row is known. @param screen the screen in front now, for checks that read it (a chat).
     */
    fun check(effect: Effect, payload: String?, screen: UiSnapshot?): WorldResult

    companion object {
        /** No provider access (unit tests of other code, no context): only the screen can be read. */
        val SCREEN_ONLY = WorldCheck { e, p, s -> ChatCheck.check(e, p, s) }
    }
}

/**
 * A chat message's outcome from the chat on screen — the only witness for WhatsApp and most chat apps (no public API).
 * Pure: the screen must BE that conversation (its title normalizes to the effect's target). Then:
 *  - the message is still sitting in the composer → it did NOT go (NOT_DONE);
 *  - the composer is empty and a message bubble reads the same words → it went (DONE);
 *  - otherwise we can't tell from here (UNDETERMINED).
 * Which delivery labels each app exposes ("Sent", "Delivered", a clock icon) is unverified on the A20e — not relied on.
 */
object ChatCheck {
    fun check(effect: Effect, payload: String?, screen: UiSnapshot?): WorldResult {
        if (effect.kind != EffectKind.CHAT_MESSAGE && effect.kind != EffectKind.SMS) return WorldResult.UNDETERMINED
        screen ?: return WorldResult.UNDETERMINED
        val title = EffectKeys.chatTitle(screen) ?: return WorldResult.UNDETERMINED
        if (EffectLedger.normalizeTarget(title) != effect.target) return WorldResult.UNDETERMINED
        val want = payload?.let { EffectLedger.normalizePayload(it) }?.takeIf { it.isNotEmpty() }
        val composer = EffectKeys.composerText(screen)
        // Without the words themselves only an exact hash match can be recognised.
        fun same(label: String): Boolean {
            val n = EffectLedger.normalizePayload(label)
            return if (want != null) n == want || (want.length >= 6 && n.contains(want))
                else EffectLedger.hash(label) == effect.payloadHash
        }
        if (composer != null && same(composer)) return WorldResult(WorldVerdict.NOT_DONE, "the message is still in the box, unsent")
        val bubble = screen.elements.any { !it.editable && !it.label.isNullOrBlank() && same(it.label) }
        if (composer == null && bubble) return WorldResult(WorldVerdict.DONE, "the message shows in the chat with $title")
        return WorldResult.UNDETERMINED
    }
}
