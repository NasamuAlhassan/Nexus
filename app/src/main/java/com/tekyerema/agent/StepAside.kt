package com.tekyerema.agent

/**
 * Which requests need Tɛkyerɛma's own screen out of the way before they run. Owner (A20e, 2026-09-28): "immediately
 * I finish the prompt, it should just close the app and start thinking." A task that operates the phone steps aside at
 * its start (AgentEngine.ensureOffOwnUi); a fast-path command that acts on whatever is in front ("go back", "scroll
 * down", "take a screenshot", "open WhatsApp") steps aside here first, or it would act on our screen. An answer (the
 * time, the battery, a fact) and a device setting (volume, torch, Wi-Fi) leave the screen up — the answer card stays
 * readable, and there is nothing underneath to reach. Config `ux.stepAside.enabled` switches it off. Pure, unit-tested.
 */
object StepAside {
    fun enabled(): Boolean = com.tekyerema.core.config.AppConfig.bool("ux.stepAside.enabled", true)

    /** A fast-path command that acts on the app in front (so our screen must not be it). */
    fun forFast(intent: FastIntent): Boolean = when (intent) {
        FastIntent.Back, FastIntent.Home, FastIntent.ScrollUp, FastIntent.ScrollDown, FastIntent.Screenshot,
        FastIntent.Recents, FastIntent.Notifications, FastIntent.LockScreen, FastIntent.PowerMenu, FastIntent.TakePhoto,
        FastIntent.ShowAlarms, FastIntent.WhatChanged -> true
        is FastIntent.OpenApp, is FastIntent.Magnify -> true
        else -> false
    }
}
