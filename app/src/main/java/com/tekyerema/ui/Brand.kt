package com.tekyerema.ui

/**
 * The names the user sees and hears (redesign, 2 Nov 2026). The product is **Nexus** (renamed 30 Oct 2026). The wake word is
 * "Nexus" too (owner, 8 Nov 2026: "Hey Nexus", "Nexus", or anything that mentions Nexus), heard by the keyword spotter
 * (voice/NexusSpotter, speech.json wake.engine "kws"). If wake.engine is set back to "oww" (the trained "Hey Tɛkyerɛma" head),
 * set [WAKE] back to "Hey Tɛkyerɛma" with it, so the app never tells the user to say a word it is not listening for.
 * Code, packages and the accessibility service id keep their old names on purpose: renaming them would switch the user's
 * screen control off on update.
 */
object Brand {
    const val NAME = "Nexus"
    const val WAKE = "Hey Nexus"
}
