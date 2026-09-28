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

    /**
     * Java accepts a bare "}" as a literal; ICU throws PatternSyntaxException. `\$\{([A-Za-z0-9_.]+)}` in HttpSpeech
     * killed the whole speech-client layer on the A20e (2026-09-28) at class init. Every one-line `Regex("…")` literal
     * is decoded and a "}" that doesn't close a `{n,m}` quantifier or a `\p{…}` property must be escaped.
     */
    @Test fun no_bare_closing_brace_in_regex_literals() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("source root not found from ${File(".").absolutePath}")
        val hits = root.walk().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                Regex("Regex\\(\"").findAll(line.substringBefore("//")).mapNotNull { m -> literalAt(line, m.range.last + 1) }
                    .firstOrNull { bareClosingBrace(it) }?.let { "${f.name}:${i + 1}: $it" }
            }
        }.toList()
        assertTrue("escape these as \\\\}:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    /** The Kotlin string literal starting at [start] (just after the opening quote), decoded; null for templates. */
    private fun literalAt(line: String, start: Int): String? {
        val sb = StringBuilder(); var i = start
        while (i < line.length) {
            val c = line[i]
            if (c == '"') return sb.toString()
            if (c == '$' && i + 1 < line.length && (line[i + 1] == '{' || line[i + 1].isLetter())) return null   // a template
            if (c == '\\' && i + 1 < line.length) { sb.append(when (val n = line[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> n }); i += 2; continue }
            sb.append(c); i++
        }
        return null
    }

    private fun bareClosingBrace(re: String): Boolean {
        var i = 0; var inClass = false
        while (i < re.length) {
            val c = re[i]
            when {
                c == '\\' && i + 1 < re.length -> {
                    // \p{L}, \P{…}, \x{…}, \N{…}: skip to their own closing brace
                    if (re[i + 1] in "pPxN" && i + 2 < re.length && re[i + 2] == '{') { val e = re.indexOf('}', i + 3); if (e < 0) return true; i = e + 1 }
                    else i += 2
                    continue
                }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> { val q = Regex("\\{\\d+(,\\d*)?\\}").find(re, i)?.takeIf { it.range.first == i }; if (q != null) { i = q.range.last + 1; continue } }
                c == '}' -> return true
            }
            i++
        }
        return false
    }
}
