package com.tekyerema

import com.tekyerema.agent.FastIntentClassifier
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The fair test for the pocket brain (3 Nov 2026): how often do the phone's CURRENT offline rules (the fast-path classifier)
 * pick the right kind of action on the owner's real requests (campaign/agent_tasks/out/pocket_real.jsonl, frozen, sha256
 * 0d7d7e1b…)? The pocket brain ships only if it beats this. Same metric as the pocket kernel: the tool name is one of the
 * case's accepted tools. A request the rules don't match counts as a miss (offline, nothing else would act on it).
 * Prints the score; asserts nothing (a measurement, not a gate). Skipped when the gitignored file is absent.
 */
class PocketRealBaselineTest {
    /** Fast-path intent → the pocket brain's tool for the same job (null = no counterpart). */
    private val TOOL = mapOf(
        "OPEN_APP" to "open_app", "CLOSE_APP" to "open_app", "OPEN_SETTING" to "open_setting",
        "WIFI_ON" to "open_setting", "WIFI_OFF" to "open_setting", "BLUETOOTH_ON" to "open_setting", "BLUETOOTH_OFF" to "open_setting",
        "VOLUME_UP" to "set_volume", "VOLUME_DOWN" to "set_volume", "VOLUME_MUTE" to "set_volume", "VOLUME_UNMUTE" to "set_volume",
        "SET_TIMER" to "set_timer", "SHOW_ALARMS" to "show_alarms", "CALENDAR_TODAY" to "calendar", "CALL_HISTORY" to "call_log",
        "LAST_SMS" to "read_sms", "READ_MESSAGE" to "read_sms", "CHAT_MESSAGES" to "read_chat", "NOTIFICATIONS" to "read_notifications",
        "DIGEST" to "read_notifications", "PLAY_MUSIC" to "play_music", "MEDIA_PLAY" to "play_music", "MAP_SEARCH" to "walk_directions",
        "WHERE_AM_I" to "location", "CAMERA" to "look_with_camera", "READ_PHOTO" to "look_with_camera",
        "DESCRIBE_SCREEN" to "read_screen", "ANSWER_CALL" to "call_control", "END_CALL" to "call_control")

    @Test fun the_offline_rules_on_the_owners_real_requests() {
        val f = listOf(File("../campaign/agent_tasks/out/pocket_real.jsonl"), File("campaign/agent_tasks/out/pocket_real.jsonl"))
            .firstOrNull { it.isFile }
        assumeTrue("pocket_real.jsonl is local-only", f != null)
        var n = 0; var hit = 0; var unmatched = 0
        val misses = ArrayList<String>()
        for (line in f!!.readLines().filter { it.isNotBlank() }) {
            val r = JSONObject(line)
            val ok = r.getJSONObject("expect").getJSONArray("any_of").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("tool") } }
            val intent = try { FastIntentClassifier.classify(r.getString("request")) } catch (_: Throwable) { null }
            val tool = intent?.let { TOOL[it.name] ?: "rule:${it.name}" }
            n++
            if (intent == null) unmatched++
            if (tool != null && tool in ok) hit++ else misses.add("${r.getString("id")}: ${tool ?: "no match"} (wanted ${ok.joinToString("/")})")
        }
        println("[POCKET_BASELINE] offline rules: tool match ${hit}/$n = ${"%.1f".format(100.0 * hit / n)}%, no match $unmatched")
        misses.take(40).forEach { println("[POCKET_BASELINE] miss $it") }
    }
}
