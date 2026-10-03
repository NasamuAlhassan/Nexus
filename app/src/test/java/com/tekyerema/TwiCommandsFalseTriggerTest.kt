package com.tekyerema

import com.tekyerema.language.TwiCommands
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordinary Twi must NOT turn into a phone command (3 October 2026, after the near-match and the recogniser repairs were
 * added): every FLORES+ Asante Twi sentence (dev + devtest, 2,009 news/wiki sentences — none of them a request to a
 * phone) through [TwiCommands.gloss]. Runs only where build/flores_twi.txt exists (fetched from the Hub, not committed).
 */
class TwiCommandsFalseTriggerTest {
    @Test fun `ordinary Twi sentences are not taken for phone commands`() {
        val f = listOf(java.io.File("../build/flores_twi.txt"), java.io.File("build/flores_twi.txt")).firstOrNull { it.isFile } ?: return
        val lines = f.readLines().filter { it.isNotBlank() }
        val hits = lines.mapNotNull { s -> TwiCommands.gloss(s)?.let { "$s  →  $it" } }
        hits.forEach { println("FALSE TRIGGER: $it") }
        assertTrue("${hits.size} of ${lines.size} ordinary sentences became commands:\n" + hits.take(10).joinToString("\n"), hits.size <= 2)
    }
}
