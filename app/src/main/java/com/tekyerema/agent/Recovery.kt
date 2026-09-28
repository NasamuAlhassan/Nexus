package com.tekyerema.agent

/**
 * Pure recovery framing for the hybrid execution model (directive §3/§4/§9).
 *
 * Given the outcome of one executed action, it produces the guidance the engine appends to the
 * planner's history so a FAILED action never silently ends the task:
 *  - an ACCELERATOR miss  → "fall back to the General Operator" (the shortcut didn't apply; the
 *    universal UI route still can),
 *  - a General-Operator primitive failure → "re-observe and try a different approach",
 *  - a genuine environment dead-end → "cannot_do only if NO route works".
 *
 * Deliberately Android-free so the §3 branching is deterministically unit-testable off-device.
 * It carries no app knowledge — it keys only on `isAccelerator` + `recoverable`, never on any
 * package or task.
 */
object Recovery {
    enum class Kind { NONE, ACCELERATOR_MISS, PRIMITIVE_RETRY, DEAD_END, ASK_USER }

    /** [needsUser]: the tool couldn't tell what the user meant (an app that isn't installed, two close matches) —
     *  only the user can answer that, so no on-screen route is suggested (seen on the A20e: "the on-screen UI" hint
     *  after "not installed" sent the model paging the home screen and into the launcher search). */
    fun classify(isAccelerator: Boolean, success: Boolean, recoverable: Boolean, needsUser: Boolean = false): Kind = when {
        success -> Kind.NONE
        needsUser -> Kind.ASK_USER
        !recoverable -> Kind.DEAD_END
        isAccelerator -> Kind.ACCELERATOR_MISS
        else -> Kind.PRIMITIVE_RETRY
    }

    /** Human/planner-facing guidance to append to the history line. Empty for a successful action. */
    fun hint(name: String, kind: Kind): String = when (kind) {
        Kind.NONE -> ""
        Kind.ACCELERATOR_MISS ->
            " — the '$name' accelerator (a shortcut) did not complete; this is NOT task failure. " +
                "Fall back to the General Operator: open the relevant app and use its own on-screen search / scroll / tap to accomplish this. Do not call cannot_do."
        Kind.DEAD_END ->
            " — this route is a genuine dead-end (environment-blocked); only call cannot_do if NO other route (accelerator or on-screen UI) could accomplish the goal. " +
                "A missing permission is the user's to grant: tell them (cannot_do) — never open Settings to grant Tɛkyerɛma a permission yourself."
        Kind.ASK_USER ->
            " — only the user can settle this: call ask_user (which one they meant). Don't search the home screen, the app drawer or an app store."
        Kind.PRIMITIVE_RETRY ->
            " — re-observe the new screen and try a DIFFERENT approach; do not repeat the same call."
    }

    fun isAcceleratorMiss(kind: Kind) = kind == Kind.ACCELERATOR_MISS
}
