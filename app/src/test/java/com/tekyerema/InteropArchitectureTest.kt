package com.tekyerema

import com.tekyerema.mcp.RemoteBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guards (research note §1.4 "one executor", "approval only on the phone"). They read the source tree, so a
 * change that adds an unguarded path fails the build:
 *  - every place a Tool is EXECUTED is pinned — each sits behind the policy gate (AgentEngine's step loop; the fast path
 *    runs a tool only when PolicyEngine says ALLOW; EmergencyTool's own execute runs its two sub-tools after its TIER2
 *    confirmation). A new call site must be reviewed and added here on purpose.
 *  - the MCP front door and the share/selection target can never approve, answer, resume or execute: no confirm,
 *    revise, answer, steer, secure-step or tool call; the only goal they submit is a remote-tagged one; no TCP server.
 *  - ConfirmGate's "no sequence number" yes (the ADB test hook) is reachable only from the debug receiver — never from
 *    MCP or the interop package.
 *  - notification and shared text never become a goal.
 */
class InteropArchitectureTest {
    private val src = listOf(File("src/main/java/com/tekyerema"), File("app/src/main/java/com/tekyerema")).first { it.isDirectory }

    /** Kotlin source without comments (line comments and KDoc/block comment lines), so prose never counts as a call. */
    private fun code(f: File): List<String> {
        var inBlock = false
        return f.readLines().map { raw ->
            var line = raw
            if (inBlock) { val end = line.indexOf("*/"); if (end < 0) return@map ""; line = line.substring(end + 2); inBlock = false }
            val start = line.indexOf("/*")
            if (start >= 0) { val end = line.indexOf("*/", start + 2); line = if (end >= 0) line.removeRange(start, end + 2) else { inBlock = true; line.substring(0, start) } }
            val slash = Regex("(?<![:\"])//").find(line)?.range?.first
            if (slash != null) line.substring(0, slash) else line
        }
    }

    /** The top-level arguments of the call whose "(" is at [open] in [line] (quotes and nested parentheses respected). */
    private fun topLevelArgs(line: String, open: Int): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        var depth = 0; var inStr = false; var i = open + 1
        while (i < line.length) {
            val c = line[i]
            when {
                inStr -> { cur.append(c); if (c == '\\') { i++; if (i < line.length) cur.append(line[i]) } else if (c == '"') inStr = false }
                c == '"' -> { inStr = true; cur.append(c) }
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { if (depth == 0) { out += cur.toString().trim(); return out }; depth--; cur.append(c) }
                c == ',' && depth == 0 -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }

    private fun kt(dir: String): List<File> = File(src, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun rel(f: File) = f.relativeTo(src).invariantSeparatorsPath

    @Test fun tool_execute_call_sites_are_pinned() {
        val call = Regex("(?:\\btool|\\w+Tool)\\s*\\??\\.execute\\s*\\(|\\.execute\\s*\\([^\\n]*(?:ToolContext\\(|toolCtx)")
        val found = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { f -> rel(f) to code(f).count { call.containsMatchIn(it) } }
            .filterValues { it > 0 }
        assertEquals(mapOf(
            "agent/AgentEngine.kt" to 2,        // the step loop (after the gate) and the goal-directed extra scrolls of an ALLOWed scroll
            "agent/LocalFastRouter.kt" to 1,    // runTool: only when PolicyEngine.evaluate(...) == ALLOW and ToolManifest allows "local"
            "tools/EmergencyTool.kt" to 2       // inside EmergencyTool.execute (TIER2, confirmed): its location link and SMS
        ), found)
        // The MCP and interop packages never execute a tool themselves.
        for (f in kt("mcp") + kt("interop")) assertTrue(rel(f), code(f).none { it.contains(".execute(") })
    }

    @Test fun the_remote_and_interop_doors_have_no_approval_or_action_path() {
        val forbidden = listOf(
            Regex("\\.confirm\\s*\\("), Regex("\\bconfirmFor\\s*\\("), Regex("\\bConfirmGate\\b"), Regex("\\bsecureInputDone\\s*\\("),
            Regex("\\.revise\\s*\\("), Regex("\\.answer\\s*\\("), Regex("\\.steer\\s*\\("), Regex("\\.resume\\s*\\("),
            Regex("\\bsubmitTyped\\s*\\("), Regex("\\bsubmitText\\s*\\("), Regex("\\bsubmitTestScenario\\s*\\("),
            Regex("\\bglobalAction\\s*\\("), Regex("\\bperformAction\\s*\\("), Regex("\\bdispatchGesture\\s*\\("),
            Regex("\\bjava\\.net\\.ServerSocket\\b|\\bServerSocket\\s*\\(|\\bio\\.ktor\\b"))
        val files = kt("mcp") + kt("interop")
        assertTrue("the packages exist", files.size >= 10)
        for (f in files) {
            val lines = code(f)
            for (rx in forbidden) {
                // LocalServerSocket (an abstract unix socket) is the transport; a TCP ServerSocket never is.
                val hit = lines.firstOrNull { l -> rx.containsMatchIn(l.replace("LocalServerSocket", "")) }
                assertTrue("${rel(f)} must not use ${rx.pattern}: $hit", hit == null)
            }
        }
        // The only goal these packages submit is a remote-tagged one.
        val submits = files.flatMap { f -> code(f).filter { it.contains("submitGoal(") }.map { rel(f) to it.trim() } }
        assertEquals(listOf("mcp/EngineBackend.kt"), submits.map { it.first })
        assertTrue(submits.single().second.contains("origin = origin"))
        // The interop package submits nothing at all: shared text is content, never a goal.
        assertTrue(kt("interop").none { f -> code(f).any { it.contains("submitGoal(") } })
    }

    @Test fun the_backend_the_computer_reaches_cannot_approve_anything() {
        val names = RemoteBackend::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("runTask", "getTask", "stop", "describeScreen", "activity", "actionsAllowed"), names)
    }

    @Test fun a_yes_without_a_confirmation_number_comes_only_from_the_debug_receiver() {
        // AgentEngine.confirm(approved) with no seq is ConfirmGate's ADB hook. Map every caller of AgentEngine.confirm and
        // count its top-level arguments (commas inside nested calls or strings don't count).
        val confirmCall = Regex("AgentEngine\\.confirm\\s*\\(")
        val callers = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            code(f).flatMap { l -> confirmCall.findAll(l).map { rel(f) to topLevelArgs(l, it.range.last) }.toList() }
        }.toList()
        assertTrue("the known callers are found", callers.size >= 7)
        val noSeq = callers.filter { (_, args) -> args.size < 2 }.map { (f, a) -> f to a.single() }
        for ((file, args) in noSeq) {
            val ok = file == "debug/RunGoalReceiver.kt" ||                                   // DEBUG-only receiver (not in the main manifest)
                (file == "eval/EvalRunner.kt" && args == "false") ||                          // a "no" can only stop things
                (file == "voice/VoiceController.kt" && args == "approved")                    // the bridge's legacy overload
            assertTrue("$file calls AgentEngine.confirm($args) without a confirmation number", ok)
        }
        assertTrue(callers.none { it.first.startsWith("mcp/") || it.first.startsWith("interop/") })
        // …and the bridge overload without a seq is never called by the voice layer itself (it answers with confirmFor).
        val voice = File(src, "voice/VoiceController.kt")
        assertTrue(code(voice).none { Regex("\\bagent\\.confirm\\s*\\(").containsMatchIn(it) })
    }

    @Test fun notification_and_shared_text_never_become_a_goal() {
        val readers = listOf("agent/triggers/NotificationTriggers.kt", "notifications/TekyeremaNotificationListener.kt",
            "notifications/NotificationStore.kt", "interop/SharedText.kt", "interop/SharedTextActivity.kt", "interop/ReadSharedTool.kt")
        for (p in readers) {
            val f = File(src, p)
            assertTrue(p, f.isFile)
            assertTrue("$p must not start a task", code(f).none { Regex("\\bsubmit(Goal|Text|Typed)\\s*\\(").containsMatchIn(it) })
        }
    }
}
