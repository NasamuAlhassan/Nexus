package com.tekyerema.verifier

import com.tekyerema.accessibility.UiSnapshot

/**
 * The model's claim about what an action should produce, in a tiny deterministic grammar:
 *
 *   element_present: <label>          an element whose label contains <label> exists
 *   element_absent:  <label>          no element's label contains <label>
 *   new_element:     <label>          such an element exists now and did NOT exist before the action
 *   field_empty:     <element_id>     that editable field now shows only its hint / is blank
 *   field_contains:  <element_id> | <text>
 *   range_value:     <number> [± <tolerance>]   an adjustable element reports ≈ value
 *   foreground_is:   <package or title fragment>
 *   screen_changed                    VACUOUS — never sufficient on its own
 *
 * The predicates are generic; the CLAIM is task-specific and comes from the model's reasoning
 * about the goal, never from code (plan §11). No per-app verification recipe exists.
 */
sealed class Expectation {
    data class ElementPresent(val label: String) : Expectation()
    data class ElementAbsent(val label: String) : Expectation()
    data class NewElement(val label: String) : Expectation()
    data class FieldEmpty(val id: Int?) : Expectation()
    data class FieldFilled(val id: Int?) : Expectation()
    data class FieldContains(val id: Int?, val text: String) : Expectation()
    /** A text box has the cursor (the keyboard is up for it): what "tap the search box" should leave. [label] is only a hint,
     *  never required — a box's name differs between its hint and its caption. */
    data class FieldFocused(val label: String?) : Expectation()
    data class RangeValue(val value: Double, val tolerance: Double) : Expectation()
    data class ForegroundIs(val token: String) : Expectation()
    /** A switch/checkbox/toggle tile labelled [label] is ON ([on]=true) or OFF — the goal state of "turn X on/off". */
    data class Toggled(val label: String, val on: Boolean) : Expectation()
    object ScreenChanged : Expectation()
    data class Unparsed(val raw: String) : Expectation()
}

object Expects {
    private const val QUOTES = "\"'“”‘’`"

    /** The model often quotes the label ("element_present: 'About phone'"); a quote is never part of what's on screen. */
    internal fun unquote(s: String): String {
        var t = s.trim()
        while (t.length >= 2 && t.first() in QUOTES && t.last() in QUOTES) t = t.substring(1, t.length - 1).trim()
        return t
    }

    /** The same claim in plain words — seen on the A20e: "element containing 'Open'", which parsed as nothing and
     *  left a real, checkable claim unverified. Only presence phrasing; anything else stays Unparsed (vacuous). */
    private val NATURAL_PRESENT = Regex("(?i)^(?:an? |the )?(?:element|item|row|button|text|label|option|entry)s? " +
        "(?:containing|contains|with (?:the )?(?:text|label)|labell?ed|named|called|saying|that says|reading):? (.+)$")

    fun parse(raw: String?): Expectation? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        NATURAL_PRESENT.find(s)?.let { m -> unquote(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { return Expectation.ElementPresent(it) } }
        val idx = s.indexOf(':')
        val key = (if (idx < 0) s else s.substring(0, idx)).trim().lowercase().replace(' ', '_').replace('-', '_')
        val arg = if (idx < 0) "" else unquote(s.substring(idx + 1))
        return when (key) {
            "element_present", "present", "visible", "text", "shows", "screen_shows", "contains", "label" ->
                arg.takeIf { it.isNotEmpty() }?.let { Expectation.ElementPresent(it) } ?: Expectation.Unparsed(s)
            "element_absent", "absent", "gone" ->
                arg.takeIf { it.isNotEmpty() }?.let { Expectation.ElementAbsent(it) } ?: Expectation.Unparsed(s)
            "new_element", "new" ->
                arg.takeIf { it.isNotEmpty() }?.let { Expectation.NewElement(it) } ?: Expectation.Unparsed(s)
            "field_focused", "focused", "field_focus", "input_focused", "cursor_in", "keyboard_open", "keyboard_shown" ->
                Expectation.FieldFocused(arg.takeIf { it.isNotEmpty() })
            "field_empty" -> Expectation.FieldEmpty(arg.toIntOrNull())
            "field_filled", "field_nonempty", "field_not_empty" -> Expectation.FieldFilled(arg.toIntOrNull())
            "field_contains" -> {
                val parts = arg.split('|', limit = 2).map { it.trim() }
                if (parts.size == 2) Expectation.FieldContains(parts[0].toIntOrNull(), unquote(parts[1]))
                else Expectation.FieldContains(null, arg)
            }
            "range_value", "range", "value" -> {
                val m = Regex("(-?[0-9]+(?:\\.[0-9]+)?)(?:\\s*(?:±|\\+/-|tolerance)\\s*([0-9]+(?:\\.[0-9]+)?))?").find(arg)
                if (m == null) Expectation.Unparsed(s)
                else Expectation.RangeValue(m.groupValues[1].toDouble(), m.groupValues[2].toDoubleOrNull() ?: 5.0)
            }
            "foreground_is", "foreground", "app_is", "app" ->
                arg.takeIf { it.isNotEmpty() }?.let { Expectation.ForegroundIs(it) } ?: Expectation.Unparsed(s)
            "checked", "is_on", "switch_on", "toggled_on", "toggle_on", "on" ->
                arg.takeIf { it.isNotEmpty() }?.let { Expectation.Toggled(it, true) } ?: Expectation.Unparsed(s)
            "unchecked", "is_off", "switch_off", "toggled_off", "toggle_off", "off" ->
                arg.takeIf { it.isNotEmpty() }?.let { Expectation.Toggled(it, false) } ?: Expectation.Unparsed(s)
            "screen_changed", "screen_changes", "changed" -> Expectation.ScreenChanged
            else -> Expectation.Unparsed(s)
        }
    }

    fun isVacuous(e: Expectation?): Boolean = e == null || e is Expectation.ScreenChanged || e is Expectation.Unparsed
}

/**
 * Result of checking a claim against a fresh observation.
 *
 * [stillInField]: the tool SAW the words it typed / was sending still sitting in a text box and nowhere else — the
 * submit or the Send press did not take. A claim that is "met" only by that box is then not met (A20e 2026-10-01,
 * Telegram: `element_present: hello…` matched the composer and the task said "I sent it").
 * [authoritative]: this particular result is an observed OUTCOME even though the tool's results are normally a weak
 * signal (a tap on Send whose message is now a bubble in the chat) — it counts like an outcome tool's own check.
 */
data class VerificationResult(
    val verified: Boolean,
    val detail: String,
    val stillInField: Boolean = false,
    val authoritative: Boolean = false
)

object OutcomeVerifier {
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun elementsContaining(snap: UiSnapshot?, text: String): List<com.tekyerema.accessibility.UiElement> {
        val t = norm(text)
        return snap?.elements?.filter { e -> e.label?.let { norm(it).contains(t) } == true }.orEmpty()
    }

    private fun labelsContaining(snap: UiSnapshot?, text: String): List<String> =
        elementsContaining(snap, text).mapNotNull { it.label }

    /** A single short phrase with no sentence punctuation is most likely a placeholder/hint, not content. */
    private fun looksLikeHint(label: String?): Boolean =
        label.isNullOrBlank() || (label.length <= 40 && !label.contains('.') && !label.contains('!') && !label.contains('?'))

    /**
     * @param expectation the model's parsed claim (may be null/vacuous)
     * @param toolDefault the tool's own verify() result
     * @param toolVerifiesOutcome whether that default is outcome-typed (open_app, set_range) or weak
     */
    fun verify(
        expectation: Expectation?,
        before: UiSnapshot?,
        after: UiSnapshot?,
        actionSucceeded: Boolean,
        toolDefault: VerificationResult,
        toolVerifiesOutcome: Boolean
    ): VerificationResult {
        if (!actionSucceeded) return VerificationResult(false, "action failed; nothing to verify")
        val changed = Verify.screenChanged(before, after)
        // A normally-weak tool can still have OBSERVED this action's outcome (a Send tap → the bubble in the chat).
        val outcomeTyped = toolVerifiesOutcome || toolDefault.authoritative
        if (Expects.isVacuous(expectation)) {
            val why = when (expectation) {
                null -> "no expectation given"
                is Expectation.ScreenChanged -> "expectation 'screen_changed' is vacuous"
                is Expectation.Unparsed -> "expectation '${expectation.raw.take(60)}' not understood"
                else -> ""
            }
            return if (outcomeTyped)
                toolDefault.copy(detail = "${toolDefault.detail} ($why; tool's own outcome check used)")
            else
                VerificationResult(false, "$why; screen changed=$changed is only a weak signal — state a concrete expectation " +
                    "(element_present/new_element/element_absent/field_empty/field_contains/foreground_is/range_value)")
        }
        val generic = when (val e = expectation!!) {
            is Expectation.ElementPresent -> {
                val hits = elementsContaining(after, e.label)
                when {
                    hits.isEmpty() -> VerificationResult(false, "expected an element containing '${e.label}'; none found on ${after?.appPackage ?: "unknown"} (screen changed=$changed)")
                    // The only place the words are is the text box the tool says they are still stuck in: not sent.
                    toolDefault.stillInField && hits.all { it.editable } -> stillInBox(toolDefault)
                    else -> VerificationResult(true, "found '${(hits.firstOrNull { !it.editable } ?: hits.first()).label!!.take(60)}'")
                }
            }
            is Expectation.ElementAbsent -> {
                val hits = labelsContaining(after, e.label)
                if (hits.isEmpty()) VerificationResult(true, "no element containing '${e.label}' remains")
                else VerificationResult(false, "expected '${e.label}' to be gone but found '${hits.first().take(60)}'")
            }
            is Expectation.NewElement -> {
                val nowEls = elementsContaining(after, e.label)
                val priorEls = elementsContaining(before, e.label)
                val now = nowEls.mapNotNull { it.label }
                val prior = priorEls.mapNotNull { it.label }
                // A sent message MOVES from the box to a bubble: the count is the same (1 → 1) but a non-editable copy is
                // new — that is the new element. The reverse (only the box holds it) is never "new content".
                val nowShown = nowEls.count { !it.editable }
                val priorShown = priorEls.count { !it.editable }
                when {
                    now.isEmpty() -> VerificationResult(false, "expected a NEW element containing '${e.label}'; none found now (screen changed=$changed)")
                    toolDefault.stillInField && nowShown == 0 -> stillInBox(toolDefault)
                    now.size > prior.size -> VerificationResult(true, "new element '${now.first().take(60)}' appeared (${prior.size} before, ${now.size} now)")
                    nowShown > priorShown -> VerificationResult(true,
                        "new element '${nowEls.first { !it.editable }.label!!.take(60)}' appeared (it left the text box and now shows on the page)")
                    else -> VerificationResult(false, "'${e.label}' was already present before the action (${prior.size} before, ${now.size} now) — not new; possible duplicate or no-op")
                }
            }
            is Expectation.FieldFocused -> {
                // The claim is that a text box now has focus: any editable element with it counts (the name is only a hint).
                val field = after?.elements?.firstOrNull { it.editable && it.focused }
                if (field != null) VerificationResult(true, "a text box has the cursor${field.hint?.takeIf { it.isNotBlank() }?.let { " ('${it.take(40)}')" } ?: ""}")
                else VerificationResult(false, "no text box has the cursor yet")
            }
            is Expectation.FieldEmpty -> {
                val field = e.id?.let { after?.element(it) }
                    ?: after?.elements?.firstOrNull { it.editable && it.focused }
                    ?: after?.elements?.firstOrNull { it.editable }
                when {
                    field == null -> VerificationResult(false, "no editable field to check")
                    looksLikeHint(field.label) -> VerificationResult(true, "field ${field.id} is empty (shows '${field.label ?: ""}')")
                    else -> VerificationResult(false, "field ${field.id} still contains '${field.label!!.take(60)}'")
                }
            }
            is Expectation.FieldFilled -> {
                val field = e.id?.let { after?.element(it) }
                    ?: after?.elements?.firstOrNull { it.editable && it.focused }
                    ?: after?.elements?.firstOrNull { it.editable }
                when {
                    field == null -> VerificationResult(false, "no editable field to check")
                    !looksLikeHint(field.label) -> VerificationResult(true, "field ${field.id} now contains text ('${field.label!!.take(40)}')")
                    else -> VerificationResult(false, "field ${field.id} still looks empty (shows '${field.label ?: ""}')")
                }
            }
            is Expectation.FieldContains -> {
                // Ids are re-numbered on every snapshot: once the keyboard opens (Samsung Contacts, 2026-09-29) the old id can
                // name a different element, so a field holding the text anywhere on screen also counts.
                val field = e.id?.let { after?.element(it) }?.takeIf { it.label?.contains(e.text, true) == true }
                    ?: after?.elements?.firstOrNull { it.editable && it.label?.contains(e.text, true) == true }
                if (field != null && field.label?.contains(e.text, true) == true) VerificationResult(true, "field ${field.id} contains '${e.text}'")
                else VerificationResult(false, "no editable field contains '${e.text}'")
            }
            is Expectation.RangeValue -> {
                val ranges = after?.elements?.mapNotNull { it.range }.orEmpty()
                val hit = ranges.firstOrNull { kotlin.math.abs(it.current - e.value) <= e.tolerance }
                if (hit != null) VerificationResult(true, "range now ${hit.current} (target ${e.value} ±${e.tolerance})")
                else VerificationResult(false, "no adjustable element reports ≈${e.value}; values now: ${ranges.map { it.current }}")
            }
            is Expectation.ForegroundIs -> {
                val pkg = after?.appPackage.orEmpty()
                val title = after?.screenTitle.orEmpty()
                val ok = pkg.contains(e.token, true) || title.contains(e.token, true)
                if (ok) VerificationResult(true, "foreground is $pkg${if (title.isNotEmpty()) " ($title)" else ""}")
                else VerificationResult(false, "expected foreground '${e.token}', it is $pkg${if (title.isNotEmpty()) " ($title)" else ""}")
            }
            is Expectation.Toggled -> {
                // Only CHECKABLE elements count: a plain text row that merely mentions the label says nothing about state.
                // Quick-settings tiles put their state IN the label ("Do Not Disturb, Off" → "…, On" after the tap), and the
                // model often copies it into the claim (seen on-device: "checked: Do Not Disturb, Off") — match the NAME.
                val t = norm(e.label).replace(Regex("[,;:.]?\\s*\\b(on|off|enabled|disabled|checked|unchecked)$"), "").trim()
                val toggles = after?.elements?.filter { it.checkable && it.label?.let { l -> norm(l).contains(t) } == true }.orEmpty()
                val want = if (e.on) "on" else "off"
                when {
                    toggles.any { it.checked == e.on } ->
                        VerificationResult(true, "'${toggles.first { it.checked == e.on }.label!!.take(50)}' is $want")
                    toggles.isNotEmpty() ->
                        VerificationResult(false, "'${toggles.first().label!!.take(50)}' is still ${if (e.on) "off" else "on"}")
                    else -> VerificationResult(false, "no switch labelled '${e.label}' is visible to confirm it is $want")
                }
            }
            is Expectation.ScreenChanged, is Expectation.Unparsed -> VerificationResult(false, "vacuous expectation")
        }
        // An outcome-verifying tool (open_app, set_range, set_volume…) knows its own result
        // authoritatively — e.g. set_volume reads the actual AudioManager stream value back. When the
        // model attaches a screen-shaped expectation a headless/system action can't satisfy on-screen
        // (a range_value with no slider visible), the tool's own CONFIRMED outcome still counts. This
        // never WEAKENS verification: a passing generic check is returned as-is, and this only ADDS an
        // already-confirmed authoritative outcome as an accepted path. Non-outcome tools are unaffected.
        if (outcomeTyped && toolDefault.verified && !generic.verified)
            return VerificationResult(true, "${toolDefault.detail} (tool's own outcome check; on-screen expectation not observable for this action)")
        return generic
    }

    /** The claim was "met" only by the text box the words are still sitting in. */
    private fun stillInBox(toolDefault: VerificationResult) = VerificationResult(false, toolDefault.detail, stillInField = true)
}
