package com.tekyerema.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * The phone's assistant gesture opens Tɛkyerɛma (Abena study, 3 October 2026: Abena is the phone's default assistant;
 * Tɛkyerɛma wasn't). Once the user picks Tɛkyerɛma as the "Digital assistant app" (Settings → Apps → Default apps), a
 * long-press on Home — or the phone's own assistant gesture — opens the same arrive-on-top voice screen as the bubble,
 * the wake word and the Quick Settings tile ([VoiceActivity]), ready to listen.
 *
 * Exported because the system starts it. It takes nothing from the intent and shows nothing of its own (Theme.NoDisplay):
 * it only opens the voice screen and finishes. ManifestHardeningTest pins its single ASSIST filter.
 */
class AssistEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("Tekyerema", "[TRIGGER] assistant gesture → talk")
        try { com.tekyerema.accessibility.TekyeremaAccessibilityService.live?.collapseShade() } catch (_: Throwable) {}
        try { startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (t: Throwable) { Log.w("Tekyerema", "[TRIGGER] assistant gesture couldn't open the voice screen: ${t.message}") }
        finish()
    }
}
