package com.tekyerema

import com.tekyerema.android.AppResolver
import com.tekyerema.android.AppResolver.AppInfo
import com.tekyerema.android.AppResolver.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-name resolution. The failure this guards: "open the Zorblax app" (an app that isn't installed) resolved
 * to "MBWhatsApp" because the filler word "app" was a substring of the label, and the fast path then announced
 * "MBWhatsApp is open." A name we don't have must resolve to None so the user hears an honest "not installed".
 */
class AppResolverTest {
    private val apps = listOf(
        AppInfo("Settings", "com.android.settings"),
        AppInfo("Chrome", "com.android.chrome"),
        AppInfo("Maps", "com.google.android.apps.maps"),
        AppInfo("Messages", "com.google.android.apps.messaging"),
        AppInfo("Messenger", "com.facebook.orca"),
        AppInfo("WhatsApp", "com.whatsapp"),
        AppInfo("MBWhatsApp", "com.whatsamb"),
        AppInfo("YouTube", "com.google.android.youtube"),
        AppInfo("YouTube Music", "com.google.android.apps.youtube.music"),
        AppInfo("Play Store", "com.android.vending"),
        AppInfo("Camera", "com.android.camera2"),
        AppInfo("MoMo App", "com.mtn.momo"),
        AppInfo("My Files", "com.sec.android.app.myfiles"),
    )

    private fun one(q: String): String? = (AppResolver.match(apps, q) as? Match.One)?.app?.label

    @Test fun absent_app_is_none_not_a_filler_word_hit() {
        for (q in listOf("the zorblax app", "zorblax app", "zorblax", "zorblax messenger", "the app", "app", "my app"))
            assertEquals(q, Match.None, AppResolver.match(apps, q))
    }

    @Test fun exact_and_filler_wrapped_names() {
        assertEquals("Settings", one("settings"))
        assertEquals("Settings", one("the settings app"))
        assertEquals("Chrome", one("chrome"))
        assertEquals("Chrome", one("google chrome"))
        assertEquals("Camera", one("my camera"))
        assertEquals("WhatsApp", one("whatsapp"))
        assertEquals("WhatsApp", one("the whatsapp app"))
    }

    @Test fun labels_containing_filler_words_still_resolve() {
        assertEquals("MoMo App", one("momo app"))
        assertEquals("MoMo App", one("momo"))
        assertEquals("My Files", one("my files"))
    }

    @Test fun asr_spacing_and_mangling() {
        assertEquals("WhatsApp", one("whats app"))
        assertEquals("YouTube", one("you tube"))
        assertEquals("YouTube", one("yutu"))
        assertEquals("WhatsApp", one("watsap"))
    }

    @Test fun vendor_prefix_and_multiword() {
        assertEquals("Maps", one("google maps"))
        assertEquals("Play Store", one("google play store"))
        assertEquals("YouTube Music", one("youtube music"))
        // Without a "Google" app installed, "google" must not count as an unknown word.
        val noGoogle = apps.filter { it.label != "Google" }
        assertEquals("Maps", (AppResolver.match(noGoogle, "google maps") as? Match.One)?.app?.label)
    }

    @Test fun strict_mode_never_fuzzy_guesses() {
        // Guessing an app out of free text must not use the ASR-mangling rescue ("yutu" → YouTube is fine
        // when the user SAID an app name to open; not when we're extracting one from a sentence).
        assertTrue(AppResolver.match(apps, "yutu", tolerant = false) is Match.None)
        assertTrue(AppResolver.match(apps, "do not disturb", tolerant = false) is Match.None)
        assertEquals("Chrome", (AppResolver.match(apps, "chrome", tolerant = false) as? Match.One)?.app?.label)
        assertEquals("Settings", (AppResolver.match(apps, "settings", tolerant = false) as? Match.One)?.app?.label)
    }

    @Test fun package_name_resolves() {
        assertEquals("Chrome", one("com.android.chrome"))
    }

    @Test fun near_names_stay_distinct() {
        assertEquals("Messages", one("messages"))
        assertEquals("Messenger", one("messenger"))
        assertTrue(AppResolver.match(apps, "") is Match.None)
    }

    /** A20e: YouTube Music is labelled "YT Music" there — "open YouTube Music" opened YouTube (word overlap tie). */
    @Test fun an_alias_names_the_app_by_package_whatever_its_label() {
        val phone = listOf(AppInfo("YouTube", "com.google.android.youtube"), AppInfo("YT Music", "com.google.android.apps.youtube.music"),
            AppInfo("Samsung Music", "com.sec.android.app.music"), AppInfo("Play Store", "com.android.vending"))
        fun pick(q: String) = (AppResolver.match(phone, q) as? Match.One)?.app?.packageName
        for (q in listOf("YouTube Music", "youtube music app", "YT Music", "yt music"))
            assertEquals(q, "com.google.android.apps.youtube.music", pick(q))
        assertEquals("com.google.android.youtube", pick("YouTube"))
        assertEquals("com.android.vending", pick("the Play Store"))
        assertEquals("com.android.vending", pick("Google Play"))
    }
}
