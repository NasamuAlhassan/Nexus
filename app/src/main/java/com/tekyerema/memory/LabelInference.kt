package com.tekyerema.memory

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.policy.ActionSemantics
import com.tekyerema.policy.TargetSemantics

/**
 * What a tap on an unlabelled control PROVED about it (label memory source 2). Only two outcomes are strong enough, and only
 * after the engine's verifier agreed the step did what it was meant to:
 *  - "Search": before the tap the screen had no search box; after it a search box exists and has the keyboard.
 *  - "Send": before the tap a message was typed in the compose box; after it the box is empty and those same words are now
 *    on screen as a sent message (so it was not "clear", "attach" or "emoji").
 * Anything else — a screen that merely changed, a dialog, a new page — proves nothing about the control, so null. Pure; the
 * typed words are only compared, never returned or stored.
 */
object LabelInference {
    fun infer(before: UiSnapshot, target: UiElement, after: UiSnapshot): String? {
        if (!target.label.isNullOrBlank() && !target.learned) return null
        if (before.appPackage == null || before.appPackage != after.appPackage) return null
        if (before.secureWindow || after.secureWindow) return null
        return searchOpened(before, after) ?: messageSent(before, after)
    }

    private fun searchOpened(before: UiSnapshot, after: UiSnapshot): String? {
        if (before.elements.any { TargetSemantics.isQueryBox(it) }) return null
        if (before.elements.any { it.editable && it.focused }) return null
        val box = after.elements.firstOrNull { it.editable && it.focused && !it.password && TargetSemantics.isQueryBox(it) }
        return if (box != null) "Search" else null
    }

    private fun messageSent(before: UiSnapshot, after: UiSnapshot): String? {
        val msg = ActionSemantics.composerText(before)?.trim() ?: return null
        if (msg.length < 4) return null
        if (after.elements.any { it.editable && it.label?.trim() == msg }) return null     // still in the box: not sent
        val shown = msg.take(60)
        val inThread = after.elements.any { !it.editable && !it.password && it.label?.contains(shown) == true }
        return if (inThread) "Send" else null
    }
}
