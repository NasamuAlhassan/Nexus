package com.tekyerema

import com.tekyerema.live.LiveActivities
import com.tekyerema.live.LiveActivity
import com.tekyerema.live.LiveGlyph
import com.tekyerema.live.LiveKind
import com.tekyerema.live.LocalMusic
import com.tekyerema.live.MusicApps
import com.tekyerema.live.TimerSpeech
import com.tekyerema.live.TimerWords
import com.tekyerema.live.nav.Guidance
import com.tekyerema.live.nav.NavMath
import com.tekyerema.live.nav.NavSpeech
import com.tekyerema.live.nav.NavWords
import com.tekyerema.live.nav.OsmClient
import com.tekyerema.live.nav.Route
import com.tekyerema.live.nav.Step
import com.tekyerema.policy.ConfirmTiers
import com.tekyerema.ui.assistant.ConfirmFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's 15 Oct 2026 round: the island's live activities — own timer, walking directions, music — and the faster,
 *  person-first confirmations. Pure parts only. */
class LiveFeaturesTest {

    // ---------------------------------------------------------------- timer
    @Test fun timer_words() {
        assertEquals("5 minutes", TimerWords.duration(300))
        assertEquals("1 minute 30 seconds", TimerWords.duration(90))
        assertEquals("1 hour", TimerWords.duration(3600))
        assertEquals("1 hour 5 minutes", TimerWords.duration(3900))
        assertEquals("45 seconds", TimerWords.duration(45))
        assertEquals("4:32", TimerWords.clock(272))
        assertEquals("1:04:32", TimerWords.clock(3872))
        assertEquals("0:00", TimerWords.clock(0))
    }

    @Test fun what_is_said_to_the_timer() {
        assertEquals(TimerSpeech.Match.STOP, TimerSpeech.match("stop", ringing = true, running = false))
        assertEquals(TimerSpeech.Match.STOP, TimerSpeech.match("Okay, stop it.", ringing = true, running = false))
        assertEquals(TimerSpeech.Match.STOP, TimerSpeech.match("okay thanks, enough", ringing = true, running = false))
        assertNull(TimerSpeech.match("stop", ringing = false, running = true))          // "stop" with nothing ringing is not the timer's
        assertEquals(TimerSpeech.Match.LEFT, TimerSpeech.match("how much time is left?", ringing = false, running = true))
        assertEquals(TimerSpeech.Match.LEFT, TimerSpeech.match("how long is left on the timer", ringing = false, running = true))
        assertNull(TimerSpeech.match("how much time is left?", ringing = false, running = false))
        assertEquals(TimerSpeech.Match.ADD_MINUTE, TimerSpeech.match("add a minute", ringing = true, running = false))
        assertNull(TimerSpeech.match("call Kofi", ringing = true, running = true))
    }

    @Test fun the_island_leads_with_what_is_urgent_then_priority() {
        LiveActivities.clear()
        LiveActivities.upsert(LiveActivity("m", LiveKind.MUSIC, "Song", "Artist", priority = 1, glyph = LiveGlyph.MUSIC))
        LiveActivities.upsert(LiveActivity("t", LiveKind.TIMER, "Timer", "4:00", priority = 2))
        assertEquals("t", LiveActivities.lead()?.id)
        LiveActivities.upsert(LiveActivity("d", LiveKind.TIMER, "Time's up", "Time's up", urgent = true, priority = 10))
        assertEquals("d", LiveActivities.lead()?.id)
        var acted: String? = null
        LiveActivities.upsert(LiveActivity("x", LiveKind.TASK, "Task", "x"), onAction = { acted = it })
        LiveActivities.act("x", "stop")
        assertEquals("stop", acted)
        LiveActivities.end("d")
        assertEquals("t", LiveActivities.lead()?.id)
        LiveActivities.clear()
    }

    @Test fun the_music_island_is_off_unless_switched_on() {
        // owner, 27 Oct 2026: "the music Dynamic Island, remove it" — music plays, no island; island.music.enabled brings it back
        LiveActivities.clear()
        LiveActivities.upsert(LiveActivity("m", LiveKind.MUSIC, "Song", "Artist", priority = 1, glyph = LiveGlyph.MUSIC))
        assertNull(LiveActivities.get("m"))
        com.tekyerema.core.config.AppConfig.loadFrom(listOf("island.json" to """{"island":{"music":{"enabled":true}}}"""))
        try {
            LiveActivities.upsert(LiveActivity("m", LiveKind.MUSIC, "Song", "Artist", priority = 1, glyph = LiveGlyph.MUSIC))
            assertEquals("m", LiveActivities.get("m")?.id)
        } finally { com.tekyerema.core.config.AppConfig.loadFrom(emptyList()); LiveActivities.clear() }
    }

    // ---------------------------------------------------------------- walking directions
    @Test fun where_to_kind_or_name() {
        assertEquals(true to "mall", OsmClient.kindOnly("the nearest mall"))
        assertEquals(true to "pharmacy", OsmClient.kindOnly("a pharmacy near me"))
        assertEquals(true to "bus stop", OsmClient.kindOnly("the bus stop"))
        assertEquals(false to "Accra Mall", OsmClient.kindOnly("Accra Mall"))
        assertEquals(false to "Ridge Hospital", OsmClient.kindOnly("the Ridge Hospital"))
        assertEquals(listOf("shop" to "mall"), OsmClient.categoryOf("shopping mall"))
        assertEquals(listOf("amenity" to "pharmacy"), OsmClient.categoryOf("chemist"))
        assertNull(OsmClient.categoryOf("Kofi's house"))
    }

    @Test fun words_for_turns_and_distances() {
        assertEquals("Turn left onto Oxford Street" to LiveGlyph.ARROW_LEFT, NavWords.step("turn", "left", "Oxford Street", null, null, "Accra Mall"))
        assertEquals("Bear right" to LiveGlyph.ARROW_SLIGHT_RIGHT, NavWords.step("turn", "slight right", "", null, null, "Accra Mall"))
        assertEquals("Head north on Liberation Road", NavWords.step("depart", null, "Liberation Road", null, 2.0, "Accra Mall").first)
        assertEquals("You've arrived at Accra Mall, on your left", NavWords.step("arrive", "left", "", null, null, "Accra Mall").first)
        assertEquals("At the roundabout, take the second exit onto Spintex Road",
            NavWords.step("roundabout", "right", "Spintex Road", 2, null, "X").first)
        assertEquals("10 metres", NavWords.distance(7.0))
        assertEquals("90 metres", NavWords.distance(86.0))
        assertEquals("250 metres", NavWords.distance(262.0))
        assertEquals("1.2 kilometres", NavWords.distance(1240.0))
        assertEquals("90 m", NavWords.short(86.0))
        assertEquals("north-east", NavWords.cardinal(44.0))
    }

    @Test fun osrm_reply_becomes_steps() {
        val body = """{"code":"Ok","routes":[{"distance":420.5,"duration":300,"geometry":{"coordinates":[[-0.187,5.65],[-0.187,5.652],[-0.185,5.652]]},
            "legs":[{"steps":[
              {"distance":220,"name":"Okponglo Road","maneuver":{"type":"depart","bearing_after":0,"location":[-0.187,5.65]}},
              {"distance":200,"name":"Ring Road","maneuver":{"type":"turn","modifier":"right","location":[-0.187,5.652]}},
              {"distance":0,"name":"","maneuver":{"type":"arrive","modifier":"left","location":[-0.185,5.652]}}]}]}]}"""
        val r = OsmClient.parseRoute(body, "Accra Mall")!!
        assertEquals(3, r.steps.size)
        assertEquals("Head north on Okponglo Road", r.steps[0].instruction)
        assertEquals("Turn right onto Ring Road", r.steps[1].instruction)
        assertTrue(r.steps[2].arrive)
        assertEquals(420.5, r.totalM, 0.01)
        assertNull(OsmClient.parseRoute("""{"code":"NoRoute"}""", "x"))
    }

    @Test fun guidance_says_the_turn_ahead_then_now_then_arrives() {
        val route = Route(listOf(
            Step("Head north", LiveGlyph.WALK, 5.6500, -0.1870, 220.0),
            Step("Turn right onto Ring Road", LiveGlyph.ARROW_RIGHT, 5.6520, -0.1870, 220.0),
            Step("You've arrived at Accra Mall", LiveGlyph.ARRIVE, 5.6520, -0.1850, 0.0, arrive = true)),
            totalM = 440.0, durationS = 330.0, line = listOf(5.65 to -0.187, 5.652 to -0.187, 5.652 to -0.185))
        val g = Guidance(route, "Accra Mall")
        assertTrue(g.opening().startsWith("Walking to Accra Mall: 450 metres, about 6 minutes. Head north"))
        assertNull(g.update(5.6500, -0.1870).say)                                  // 220 m from the turn: nothing yet
        val ahead = g.update(5.6511, -0.1870)                                      // ~100 m before
        assertTrue(ahead.say!!, ahead.say!!.startsWith("In 100 metres, turn right onto Ring Road"))
        assertNull(g.update(5.6512, -0.1870).say)                                  // said once only
        val near = g.update(5.6517, -0.1870)                                       // ~33 m
        assertTrue(near.urgent); assertTrue(near.say!!.startsWith("In 30 metres"))
        val now = g.update(5.65195, -0.1870)                                       // at the turn
        assertTrue(now.say!!, now.say!!.startsWith("Now turn right"))
        val arrive = g.update(5.6520, -0.18505)
        assertTrue(arrive.arrived); assertEquals("You've arrived at Accra Mall.", arrive.say)
    }

    @Test fun guidance_notices_a_walk_off_the_route() {
        val route = Route(listOf(Step("Head north", LiveGlyph.WALK, 5.65, -0.187, 220.0),
            Step("Turn right", LiveGlyph.ARROW_RIGHT, 5.652, -0.187, 220.0)), 440.0, 330.0, listOf(5.65 to -0.187, 5.652 to -0.187))
        val g = Guidance(route, "X")
        repeat(2) { assertFalse(g.update(5.651, -0.1875).reroute) }                 // ~55 m off, twice
        assertTrue(g.update(5.651, -0.1875).reroute)                                // the third time: plan again
        assertTrue(NavMath.distanceToLine(5.651, -0.187, route.line) < 1.0)
    }

    @Test fun what_is_said_to_the_directions() {
        assertEquals(NavSpeech.Match.STOP, NavSpeech.match("stop the directions", active = true))
        assertEquals(NavSpeech.Match.REPEAT, NavSpeech.match("where do I turn?", active = true))
        assertEquals(NavSpeech.Match.HOW_FAR, NavSpeech.match("how far is it", active = true))
        assertNull(NavSpeech.match("stop the directions", active = false))
    }

    @Test fun the_new_abilities_said_in_twi() {
        fun g(t: String) = com.tekyerema.language.TwiCommands.gloss(t)
        assertEquals("show me directions to Accra Mall", g("kyerɛ me kwan kɔ Accra Mall"))
        assertEquals("show me directions to the nearest mall", g("fa me kɔ mall a ɛbɛn paa"))
        assertEquals("set a timer for 5 minutes", g("hyɛ timer simma anum"))
        assertEquals("set a timer for 1 minute", g("hyɛ timer simma baako"))
        assertEquals("set a timer for 10 minutes", g("hyɛ timer simma 10"))
    }

    @Test fun a_translators_slip_with_a_tag_is_mended_and_quotes_round_tags_are_taken_off() {
        val t = com.tekyerema.language.EntityTags
        val ents = listOf(com.tekyerema.language.EntityTags.Entity("[P2]", com.tekyerema.language.EntityTags.Kind.PERSON, "transport"),
            com.tekyerema.language.EntityTags.Entity("[P1]", com.tekyerema.language.EntityTags.Kind.PERSON, "school fees"))
        // Khaya web, 15 Oct 2026, word for word: the "[" of the second tag went missing after a curly quote
        val out = "Dɛn na ɛsɛ sɛ nea wɔreka ho asɛm no ka? Sɛ nhwɛso no, ka sɛ “[P2]” anaa “P1]."
        val mended = t.mend(out, ents)
        assertTrue(mended, mended.contains("“[P1]."))
        assertTrue(t.intact("say “[P2]” or “[P1]”.", mended, ents))
        assertEquals("say [P2] or [P1].", t.forTranslator("say “[P2]” or “[P1]”."))
        // a tag that is there is left alone; nothing else is touched
        assertEquals("ka [P2] ne A12", t.mend("ka [P2] ne A12", listOf(ents[0])))
        // the tag's number dropped: mended when it is the line's only amount, left broken (→ English) when two amounts could be meant
        val amt = com.tekyerema.language.EntityTags.Entity("[A1]", com.tekyerema.language.EntityTags.Kind.AMOUNT, "50 cedis")
        assertEquals("Wo susudua ne [A1].", t.mend("Wo susudua ne [A].", listOf(amt)))
        val amt2 = com.tekyerema.language.EntityTags.Entity("[A2]", com.tekyerema.language.EntityTags.Kind.AMOUNT, "20 cedis")
        assertEquals("[A] ne [A2]", t.mend("[A] ne [A2]", listOf(amt, amt2)))
    }

    // ---------------------------------------------------------------- music
    @Test fun the_phones_songs_are_matched_before_any_app() {
        val songs = listOf(
            LocalMusic.Song(1, "Kofi Kinaata — Things Fall Apart", "Kofi Kinaata", "Single", 200_000),
            LocalMusic.Song(2, "Adonai", "Sarkodie", "Highest", 240_000),
            LocalMusic.Song(3, "Rap Sessions", "Sarkodie", "Highest", 210_000))
        assertEquals(listOf(2L, 3L), LocalMusic.match(songs, null, "Sarkodie").map { it.id })
        assertEquals(2L, LocalMusic.match(songs, "adonai", null).first().id)
        assertEquals(3, LocalMusic.match(songs, null, null).size)                  // "play music": all, shuffled
        assertTrue(LocalMusic.match(songs, "Shatta Wale", null).isEmpty())          // → a music app
    }

    @Test fun which_music_app_is_asked_and_understood() {
        val opts = listOf("com.google.android.apps.youtube.music" to "YouTube Music", "com.spotify.music" to "Spotify")
        assertEquals("Which app should I play it on — YouTube Music or Spotify?", MusicApps.question(opts))
        assertEquals("com.spotify.music", MusicApps.pick("Spotify please", opts)?.first)
        assertEquals("com.google.android.apps.youtube.music", MusicApps.pick("youtube", opts)?.first)
        assertEquals("com.spotify.music", MusicApps.pick("the second one", opts)?.first)
        assertNull(MusicApps.pick("never mind", opts))
    }

    // ---------------------------------------------------------------- confirmations
    @Test fun the_person_a_confirmation_is_about() {
        val money = ConfirmFacts.parse("Send 5 cedis to Kofi Demo, on 0 5 0, 0 0 0, 2 2 2 2, a Telecel number, with Mobile Money, reference “transport”?")!!
        assertEquals(ConfirmFacts.Kind.MONEY, money.kind); assertEquals("Kofi Demo", money.name)
        assertEquals("0500002222", money.digits); assertEquals("Telecel", money.network); assertEquals("5", money.amount)
        assertEquals("050 000 2222", ConfirmFacts.shownNumber(money))
        val text = ConfirmFacts.parse("Taking 'him' as Kofi Demo, from before. Text Kofi Demo, ending 1 1 1 1.⁣The message: \"I've sent you 5 cedis by Mobile Money. Reference: transport.\"⁣Send it?")!!
        assertEquals(ConfirmFacts.Kind.TEXT, text.kind); assertEquals("•••• 1111", ConfirmFacts.shownNumber(text))
        assertEquals("I've sent you 5 cedis by Mobile Money. Reference: transport.", text.message)
        val call = ConfirmFacts.parse("Call Kofi Demo, ending 1 1 1 1?")!!
        assertEquals(ConfirmFacts.Kind.CALL, call.kind); assertEquals("KD", ConfirmFacts.initials(call.name))
        assertNull(ConfirmFacts.parse("Open WhatsApp?"))
        val calling = ConfirmFacts.parse("Calling Kofi Sample, ending 3 3 3 3. Say stop to cancel.")!!
        assertTrue(calling.announced); assertEquals("Kofi Sample", calling.name); assertEquals("•••• 3333", ConfirmFacts.shownNumber(calling))
        assertEquals("Cancel" to "Call now", com.tekyerema.ui.assistant.ConfirmWords.buttons("Calling Kofi Sample, ending 3 3 3 3. Say stop to cancel.", false))
    }

    @Test fun a_plain_call_is_announced_not_asked() {
        assertEquals("Calling Kofi Demo, ending 1 1 1 1. Say stop to cancel.", ConfirmTiers.callingLine("Call Kofi Demo, ending 1 1 1 1?"))
        assertEquals("Calling Kofi Demo, ending 1 1 1 1. Say stop to cancel.",
            ConfirmTiers.callingLine("Taking 'him' as Kofi Demo, from before. Call Kofi Demo, ending 1 1 1 1?"))
        assertNull(ConfirmTiers.callingLine("Send 5 cedis to Kofi Demo?"))
        assertNotNull(ConfirmTiers.callingLine("Call Mum?"))
        val w = ConfirmTiers.windowMs("Calling Kofi Demo, ending 1 1 1 1. Say stop to cancel.")
        assertTrue(w in 3_500L..9_000L)
    }
}
