package com.tekyerema

import com.tekyerema.tools.CalendarFormat
import com.tekyerema.tools.IntentTemplates
import com.tekyerema.tools.LocationFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/** Pure helpers behind the organizer accelerators: calendar lines, location phrasing, deep-link templates. */
class OrganizerToolsTest {
    private val accra = TimeZone.getTimeZone("Africa/Accra")          // UTC+0, no DST
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = utc(2026, 9, 28, 8, 0)                           // Monday 28 September 2026, 08:00 Accra
    private val enc: (String) -> String = { it.replace(" ", "%20") }
    private val templates = IntentTemplates.defaults()

    // ── CalendarFormat ────────────────────────────────────────────────────────────────────────

    @Test fun parseStart_reads_iso_local_time_in_the_given_zone() {
        assertEquals(utc(2026, 9, 29, 10, 0), CalendarFormat.parseStart("2026-09-29T10:00", accra))
        assertEquals(utc(2026, 9, 29, 10, 0), CalendarFormat.parseStart(" 2026-09-29T10:00:00 ", accra))
        assertEquals(utc(2026, 9, 29, 10, 0), CalendarFormat.parseStart("2026-09-29 10:00", accra))
        // London is on BST (UTC+1) in late September: 10:00 local is 09:00 UTC.
        assertEquals(utc(2026, 9, 29, 9, 0), CalendarFormat.parseStart("2026-09-29T10:00", TimeZone.getTimeZone("Europe/London")))
        // An explicit offset is honoured as written.
        assertEquals(utc(2026, 9, 29, 10, 0), CalendarFormat.parseStart("2026-09-29T10:00Z", TimeZone.getTimeZone("Europe/London")))
    }

    @Test fun parseStart_rejects_anything_that_is_not_a_date_and_time() {
        for (bad in listOf(null, "", "   ", "tomorrow at 10", "2026-09-29", "2026-13-01T10:00", "2026-09-29T25:00", "2026-02-30T10:00"))
            assertNull("'$bad' should not parse", CalendarFormat.parseStart(bad, accra))
    }

    @Test fun line_formats_timed_all_day_and_relative_days() {
        assertEquals("10:00 — Doctor's appointment", CalendarFormat.line("Doctor's appointment", utc(2026, 9, 28, 10, 0), false, now, accra))
        assertEquals("All day — Independence Day", CalendarFormat.line("Independence Day", utc(2026, 9, 28, 0, 0), true, now, accra))
        assertEquals("Tomorrow 09:30 — Team call", CalendarFormat.line("Team call", utc(2026, 9, 29, 9, 30), false, now, accra))
        assertEquals("Tomorrow, all day — Farmers' Day", CalendarFormat.line("Farmers' Day", utc(2026, 9, 29, 0, 0), true, now, accra))
        assertEquals("Thursday 14:00 — Board meeting", CalendarFormat.line("Board meeting", utc(2026, 10, 1, 14, 0), false, now, accra))
        assertEquals("Tuesday 20 October 14:00 — Trip", CalendarFormat.line("Trip", utc(2026, 10, 20, 14, 0), false, now, accra))
        assertEquals("Yesterday 23:00 — Vigil", CalendarFormat.line("Vigil", utc(2026, 9, 27, 23, 0), false, now, accra))
        assertEquals("10:00 — Untitled event", CalendarFormat.line("  ", utc(2026, 9, 28, 10, 0), false, now, accra))
    }

    @Test fun all_day_events_keep_their_utc_date_west_of_greenwich() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val noonInNy = utc(2026, 9, 28, 16, 0)
        // Stored at 2026-09-28T00:00Z, which is 27 Sept 20:00 in New York — must still read as TODAY, not yesterday.
        assertEquals("All day — Holiday", CalendarFormat.line("Holiday", utc(2026, 9, 28, 0, 0), true, noonInNy, ny))
    }

    @Test fun dayBounds_is_local_midnight_to_midnight() {
        assertEquals(utc(2026, 9, 28, 0, 0) to utc(2026, 9, 29, 0, 0), CalendarFormat.dayBounds(now, accra))
    }

    // ── LocationFormat ────────────────────────────────────────────────────────────────────────

    @Test fun spoken_prefers_street_then_named_feature_then_area() {
        assertEquals("You're near Oxford Street, Accra — accurate to about 12 meters.",
            LocationFormat.spoken("Oxford Street", "12", "Accra", "Greater Accra", 12.4f))
        assertEquals("You're near Makola Market, Accra — accurate to about 350 meters.",
            LocationFormat.spoken(null, "Makola Market", "Accra", null, 347f))
        // A house number or a plus code is not a place name anyone would recognise when spoken.
        assertEquals("You're in Kumasi.", LocationFormat.spoken(null, "12", "Kumasi", "Ashanti", null))
        assertEquals("You're in Kumasi.", LocationFormat.spoken("  ", "X4C7+2F", "Kumasi", "Ashanti", 0f))
        assertEquals("You're in Ashanti — accurate to about 2.3 kilometers.", LocationFormat.spoken(null, null, null, "Ashanti", 2345f))
        assertEquals("You're near Oxford Street — accurate to about 1 meter.", LocationFormat.spoken("Oxford Street", null, null, null, 0.6f))
        assertEquals(LocationFormat.NO_STREET, LocationFormat.spoken(null, null, null, null, 20f))
    }

    @Test fun accuracy_is_rounded_to_speakable_values() {
        assertNull(LocationFormat.accuracyPhrase(null))
        assertNull(LocationFormat.accuracyPhrase(0f))
        assertEquals("4 meters", LocationFormat.accuracyPhrase(3.9f))
        assertEquals("100 meters", LocationFormat.accuracyPhrase(99.6f))
        assertEquals("350 meters", LocationFormat.accuracyPhrase(347f))
        assertEquals("1 kilometer", LocationFormat.accuracyPhrase(1000f))
        assertEquals("2 kilometers", LocationFormat.accuracyPhrase(2010f))
    }

    @Test fun mapsLink_uses_five_decimals_and_a_dot_whatever_the_locale() {
        assertEquals("https://maps.google.com/?q=5.60372,-0.18696", LocationFormat.mapsLink(5.603716, -0.186964))
        assertEquals("https://maps.google.com/?q=6.68850,-1.62440", LocationFormat.mapsLink(6.6885, -1.6244))
    }

    // ── IntentTemplates ───────────────────────────────────────────────────────────────────────

    @Test fun all_eight_default_templates_are_present() {
        assertEquals(
            setOf("whatsapp_chat", "sms_compose", "dial", "email_compose", "maps_search", "walking_directions", "web_search", "youtube_search"),
            templates.keys
        )
        assertEquals("com.whatsapp", templates.getValue("whatsapp_chat").pkg)
        assertEquals("android.intent.action.SENDTO", templates.getValue("sms_compose").action)
    }

    @Test fun fill_encodes_uri_slots_but_not_extras() {
        assertEquals("https://wa.me/233241234567?text=I%20am%20outside" to emptyMap<String, String>(),
            IntentTemplates.fill(templates.getValue("whatsapp_chat"), mapOf("number" to "024 123 4567", "text" to "I am outside"), enc))
        assertEquals("https://www.google.com/search?q=kenkey%20near%20me" to emptyMap<String, String>(),
            IntentTemplates.fill(templates.getValue("web_search"), mapOf("query" to "kenkey near me"), enc))
        assertEquals("google.navigation:q=Accra%20Mall&mode=w",
            IntentTemplates.fill(templates.getValue("walking_directions"), mapOf("query" to "Accra Mall"), enc).first)
        // Extras are plain text; a local number keeps its local form for SMS.
        assertEquals("smsto:0241234567" to mapOf("sms_body" to "on my way"),
            IntentTemplates.fill(templates.getValue("sms_compose"), mapOf("number" to "024 123 4567", "text" to "on my way"), enc))
        assertEquals("tel:+233241234567" to emptyMap<String, String>(),
            IntentTemplates.fill(templates.getValue("dial"), mapOf("number" to "+233 24-123-4567"), enc))
    }

    @Test fun optional_slots_may_be_empty_and_empty_extras_are_dropped() {
        assertEquals("https://wa.me/233241234567?text=",
            IntentTemplates.fill(templates.getValue("whatsapp_chat"), mapOf("number" to "0241234567"), enc).first)
        assertEquals("mailto:ama@example.com" to emptyMap<String, String>(),
            IntentTemplates.fill(templates.getValue("email_compose"), mapOf("email" to "ama@example.com", "text" to "  "), enc))
    }

    @Test fun missing_lists_required_slots_only() {
        assertEquals(listOf("number"), IntentTemplates.missing(templates.getValue("dial"), emptyMap()))
        assertEquals(listOf("number"), IntentTemplates.missing(templates.getValue("dial"), mapOf("number" to "call me")))
        assertEquals(listOf("query"), IntentTemplates.missing(templates.getValue("maps_search"), mapOf("query" to " ")))
        assertEquals(listOf("email"), IntentTemplates.missing(templates.getValue("email_compose"), mapOf("text" to "hi")))
        assertTrue(IntentTemplates.missing(templates.getValue("whatsapp_chat"), mapOf("number" to "0241234567")).isEmpty())
    }

    @Test fun ghanaIntl_normalises_the_common_forms() {
        assertEquals("233241234567", IntentTemplates.ghanaIntl("024 123 4567"))
        assertEquals("233241234567", IntentTemplates.ghanaIntl("0241234567"))
        assertEquals("233241234567", IntentTemplates.ghanaIntl("+233241234567"))
        assertEquals("233241234567", IntentTemplates.ghanaIntl("+233 24 123 4567"))
        assertEquals("233241234567", IntentTemplates.ghanaIntl("00233241234567"))
        assertEquals("233241234567", IntentTemplates.ghanaIntl("233241234567"))
        assertEquals("233241234567", IntentTemplates.ghanaIntl("241234567"))
    }

    @Test fun config_templates_override_and_extend_the_defaults() {
        val cfg = JSONObject("""{
            "web_search": {"action": "android.intent.action.VIEW", "uri": "https://duckduckgo.com/?q={query}"},
            "bolt_ride": {"action": "android.intent.action.VIEW", "uri": "https://bolt.eu/ride?to={query}", "package": "ee.mtakso.client"},
            "broken": {"uri": "no-action:{query}"}
        }""")
        val merged = IntentTemplates.merged(cfg)
        assertEquals(9, merged.size)                                               // 8 defaults + bolt_ride; "broken" skipped
        assertEquals("https://duckduckgo.com/?q={query}", merged.getValue("web_search").uri)
        assertEquals("ee.mtakso.client", merged.getValue("bolt_ride").pkg)
        assertEquals(templates.getValue("dial"), merged.getValue("dial"))
    }

    @Test fun shipped_intents_json_matches_the_compiled_defaults() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        val doc = JSONObject(java.io.File(dir, "intents.json").readText())
        assertEquals(1, doc.getInt("schema_version"))
        assertEquals(templates, IntentTemplates.parse(doc.getJSONObject("intents").getJSONObject("templates")))
    }
}
