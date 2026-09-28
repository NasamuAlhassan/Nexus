package com.tekyerema

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests run on Java's regex engine; the app runs on Android's ICU engine, which rejects some Java-only syntax at
 * RUNTIME. "(?U)" in LangPipeline broke every spoken request on the Galaxy A20e (2026-09-28) while all tests passed.
 * This scans the app's Kotlin source for those constructs so it can't happen again unnoticed.
 */
class AndroidRegexCompatTest {
    private val javaOnly = listOf(
        "(?U)" to "the UNICODE_CHARACTER_CLASS flag — use \\\\p{L} / \\\\p{N} classes",
        "UNICODE_CHARACTER_CLASS" to "unsupported flag on Android",
        "\\\\p{Is" to "Java-only property syntax — use \\\\p{L}, \\\\p{N}, \\\\p{M}",
        "\\\\p{java" to "Java-only property syntax",
        "\\\\p{In" to "Java-only block syntax"
    )

    @Test fun no_java_only_regex_syntax_in_app_source() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("source root not found from ${File(".").absolutePath}")
        val hits = root.walk().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")   // comments may mention the construct
                javaOnly.firstOrNull { (bad, _) -> bad in code }?.let { (bad, why) -> "${f.name}:${i + 1} uses $bad ($why)" }
            }
        }.toList()
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }
}
