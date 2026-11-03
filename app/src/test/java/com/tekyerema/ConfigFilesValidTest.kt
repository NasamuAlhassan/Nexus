package com.tekyerema

import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Every shipped config file is valid JSON — a broken one is silently skipped on the phone (3 Nov 2026: an unescaped quote in
 *  router.json's note dropped the whole router config on the A20e, thresholds included). */
class ConfigFilesValidTest {
    @Test fun every_config_file_parses() {
        val dir = listOf(File("src/main/assets/config"), File("app/src/main/assets/config")).first { it.isDirectory }
        val bad = dir.listFiles { f -> f.name.endsWith(".json") }!!.mapNotNull { f ->
            try { JSONObject(f.readText()); null } catch (t: Throwable) { "${f.name}: ${t.message}" }
        }
        if (bad.isNotEmpty()) fail("invalid config JSON:\n" + bad.joinToString("\n"))
    }
}
