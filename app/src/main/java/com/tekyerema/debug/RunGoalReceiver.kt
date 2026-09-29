package com.tekyerema.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tekyerema.BuildConfig
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.TestScenario
import kotlinx.coroutines.launch

/**
 * DEBUG ONLY. Lets a developer trigger a goal over ADB, e.g.:
 *   adb shell am broadcast -a com.tekyerema.RUN_GOAL --es goal "open settings" -p com.tekyerema
 * Ignored in release builds. Remove before any production distribution.
 */
class RunGoalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        AgentEngine.init(context.applicationContext)
        // Zero-LLM-cost diagnostic: report the real attached-controller/brain state, so
        // Accessibility binding can be verified without spending any model quota.
        intent.getStringExtra("dumptrace")?.let {
            try {
                val dir = java.io.File(context.applicationContext.filesDir, "traces")
                val newest = dir.listFiles()?.maxByOrNull { it.lastModified() }
                if (newest == null) Log.i("Tekyerema", "[TRACE] no trace files")
                else newest.readLines().forEach { Log.i("Tekyerema", "[TRACE] $it") }
            } catch (t: Throwable) { Log.i("Tekyerema", "[TRACE] error ${t.message}") }
            return
        }
        // W8 telemetry (Q-01..Q-10): a roll-up of the on-device turn records, or the last few raw records.
        //   adb ... --es telemetry summary | --es telemetry last
        //   adb ... --es trajectories export   (Q-07, only if the user opted in; decrypted into files/export/, pull with run-as)
        intent.getStringExtra("telemetry")?.let { what ->
            val recs = com.tekyerema.core.telemetry.Telemetry.records()
            if (what == "last") recs.takeLast(5).forEach { Log.i("Tekyerema", "[TELEMETRY] $it") }
            else Log.i("Tekyerema", "[TELEMETRY] ${com.tekyerema.core.telemetry.Telemetry.summary(recs)}")
            return
        }
        intent.getStringExtra("trajectories")?.let {
            val out = java.io.File(context.applicationContext.filesDir, "export/trajectories.jsonl")
            val n = com.tekyerema.core.telemetry.Trajectories.exportTo(context.applicationContext, out)
            Log.i("Tekyerema", "[TRAJECTORIES] exported $n to ${out.absolutePath}")
            return
        }
        // X-13/N-12: config overrides and capability state, zero LLM cost.
        //   adb ... --es set_config '{"flags":{"E-08":{"enabled":false}}}'   |   --es set_config off
        //   adb ... --es dump_config models.planner          (any dotted path; "1" dumps the whole tree)
        //   adb ... --es dump_capabilities 1
        intent.getStringExtra("set_config")?.let { json ->
            com.tekyerema.core.config.AppConfig.setOverrides(context.applicationContext, json.takeUnless { it.trim() == "off" })
            Log.i("Tekyerema", "[CONFIG] overrides ${if (json.trim() == "off") "cleared" else "set"}")
            return
        }
        intent.getStringExtra("dump_config")?.let { path ->
            val node = if (path == "1") com.tekyerema.core.config.AppConfig.snapshot() else com.tekyerema.core.config.AppConfig.node(path)
            Log.i("Tekyerema", "[CONFIG] $path = ${node?.toString()?.take(3500)}")
            return
        }
        intent.getStringExtra("dump_capabilities")?.let {
            com.tekyerema.core.config.CapabilityRegistry.describe(context.applicationContext).forEach { Log.i("Tekyerema", "[CAPS] $it") }
            val reg = com.tekyerema.tools.ToolRegistry(com.tekyerema.tools.defaultTools())
            reg.installed.forEach { t -> Log.i("Tekyerema", "[TOOLS] ${t.name}: ${com.tekyerema.tools.ToolManifest.blocker(t) ?: "available"} tier=${com.tekyerema.policy.PolicyConfig.effectiveTier(t)}") }
            return
        }
        intent.getStringExtra("ping")?.let {
            AgentEngine.refreshReadiness()
            val s = AgentEngine.state.value
            // voice= / voiceBusy= let scripts/device_suite.py wait until a reply has been read aloud (a request typed
            // while the voice layer is busy is dropped). "?" when the voice layer isn't initialised yet.
            val voice = try { "${com.tekyerema.voice.VoiceEngine.state.value.state} voiceBusy=${com.tekyerema.voice.VoiceEngine.isRunning}" } catch (_: Throwable) { "? voiceBusy=?" }
            Log.i("Tekyerema", "[PING] serviceReady=${s.serviceReady} hasApiKey=${s.hasApiKey} brain=${s.brain} status=${s.status} fg=${AgentEngine.currentController()?.foregroundPackage()} voice=$voice")
            return
        }
        // Deterministic infrastructure scenario, zero LLM cost — see TestBrain.
        //   adb shell am broadcast -a com.tekyerema.RUN_GOAL --es scenario "STATE_RACE" -p com.tekyerema
        intent.getStringExtra("scenario")?.let { name ->
            val scenario = try { TestScenario.valueOf(name.trim().uppercase()) } catch (t: Throwable) {
                Log.i("Tekyerema", "[PING] Unknown scenario '$name'. Valid: ${TestScenario.entries.joinToString()}")
                return
            }
            AgentEngine.submitTestScenario(scenario)
            return
        }
        // Voice debug (Phase 2): validate ASR→agent wiring + mic/VAD on-device without a human voice.
        //   adb ... --es transcribe_wav K14.wav --es lang gh_en   (WAV pushed to the app's files dir)
        //   adb ... --es mic_test 1
        intent.getStringExtra("transcribe_wav")?.let { name ->
            com.tekyerema.voice.VoiceDebug.transcribeFileAndRun(context.applicationContext, name, intent.getStringExtra("lang"))
            return
        }
        intent.getStringExtra("mic_test")?.let {
            com.tekyerema.voice.VoiceDebug.micTest(context.applicationContext)
            return
        }
        // Phase 5 ASR diagnostic: per-stage timings + proxy/raw/normalized/final. No goal submitted.
        //   adb ... --es asr_diag twi_C01.wav --es lang twi
        intent.getStringExtra("asr_diag")?.let { name ->
            com.tekyerema.voice.VoiceDebug.asrDiag(context.applicationContext, name, intent.getStringExtra("lang"))
            return
        }
        // §P0 VAD probe: run the segmenter over a pushed WAV (no mic). --es vad_probe <wav> [--es scale <f>]
        intent.getStringExtra("vad_probe")?.let { name ->
            com.tekyerema.voice.VoiceDebug.vadProbe(context.applicationContext, name, intent.getStringExtra("scale"))
            return
        }
        // Phase 8 hybrid-router observability: log the routing decision without recognizing.
        //   adb ... --es asr_route gh_en   |   --es asr_route twi
        intent.getStringExtra("asr_route")?.let { lang ->
            com.tekyerema.voice.VoiceDebug.asrRoute(context.applicationContext, lang.takeIf { it.isNotBlank() && it != "1" })
            return
        }
        // Phase 8: set/clear a (placeholder) online ASR endpoint for on-device routing tests.
        //   adb ... --es set_asr_online https://my-endpoint/v1   |   --es set_asr_online off
        intent.getStringExtra("set_asr_online")?.let { url ->
            com.tekyerema.voice.VoiceDebug.setAsrOnline(context.applicationContext, url)
            return
        }
        // Repoint the cloud brain at a relay/endpoint.  --es set_brain <url>|off [--es model <m>]
        intent.getStringExtra("set_brain")?.let { url ->
            com.tekyerema.voice.VoiceDebug.setBrain(context.applicationContext, url, intent.getStringExtra("model"))
            return
        }
        // Log what the agent's perception sees on the current screen (DEBUG only — may include on-screen text).
        //   --es dump_screen 1
        intent.getStringExtra("dump_screen")?.let {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                try {
                    val snap = AgentEngine.currentController()?.buildSnapshot()
                    Log.i("Tekyerema", "[SCREEN] app=${snap?.appPackage} name=${snap?.appName} title=${snap?.screenTitle} n=${snap?.elements?.size} " +
                        "compactJsonChars=${snap?.toCompactJson()?.toString()?.length} truncated=${snap?.truncated}")
                    snap?.toCompactJson()?.toString()?.chunked(3000)?.forEach { Log.d("Tekyerema", "[SCREEN-JSON] $it") }
                    snap?.elements?.forEach { e ->
                        Log.i("Tekyerema", "[SCREEN] #${e.id} ${e.role} '${e.label?.take(50)}'" +
                            (if (e.editable) " EDITABLE hint='${e.hint}' id=${e.viewId?.substringAfter(":id/")}" else "") +
                            (if (e.clickable) " click" else "") + (if (e.focused) " focused" else ""))
                    }
                } finally { pending.finish() }
            }
            return
        }
        // A/B the cloud model's reasoning effort without a rebuild.  --es reasoning low|medium|high|default
        intent.getStringExtra("reasoning")?.let { v ->
            val s = com.tekyerema.settings.Settings(context.applicationContext)
            s.reasoningEffortOverride = if (v.equals("default", true)) null else v.lowercase()
            Log.i("Tekyerema", "[BRAIN] reasoning_effort → ${s.groqReasoningEffort.ifBlank { "(provider default)" }}")
            return
        }
        // Route every request to the cloud brain, without changing the URL.  --es cloud_first 1|0
        intent.getStringExtra("cloud_first")?.let { v ->
            com.tekyerema.voice.VoiceDebug.setCloudFirst(context.applicationContext, v == "1" || v.equals("true", true))
            return
        }
        // §B.4: enumerate device TTS voices → definitive Ghanaian/African-English yes/no.
        //   adb ... --es list_tts_voices 1
        intent.getStringExtra("list_tts_voices")?.let {
            com.tekyerema.voice.VoiceDebug.listTtsVoices(context.applicationContext)
            return
        }
        intent.getStringExtra("tts_test")?.let { text ->
            com.tekyerema.voice.VoiceDebug.ttsTest(context.applicationContext, text, intent.getStringExtra("lang"))
            return
        }
        // Design review: force the Home visual into a state.  --es preview_presence THINKING | off
        intent.getStringExtra("preview_presence")?.let { name ->
            com.tekyerema.ui.voice.UiDebug.forcedPresence.value =
                if (name.equals("off", true)) null
                else try { com.tekyerema.ui.voice.Presence.valueOf(name.trim().uppercase()) } catch (t: Throwable) { null }
            return
        }
        // D: the computer connection (MCP) for device testing, until the Settings switch lands (Workstream C).
        //   --es mcp on|off|status   --es mcp_actions on|off   (pairing is still spoken/shown on the phone; nothing here pairs)
        intent.getStringExtra("mcp")?.let { v ->
            when (v.lowercase()) {
                "on" -> com.tekyerema.operator.OperatorPolicy.remoteControlEnabled = true
                "off" -> com.tekyerema.mcp.RemoteControl.turnOff("The computer connection is off.")
            }
            Log.i("Tekyerema", "[MCP] ${com.tekyerema.mcp.RemoteControl.status()}")
            return
        }
        intent.getStringExtra("mcp_actions")?.let { v ->
            com.tekyerema.mcp.RemoteControl.actionsAllowed = v.equals("on", true) || v == "1"
            Log.i("Tekyerema", "[MCP] ${com.tekyerema.mcp.RemoteControl.status()}")
            return
        }
        // Drive confirmation / stop over ADB during testing.
        intent.getStringExtra("stop")?.let { AgentEngine.stop(); return }
        // A fresh conversation (the Settings "reset" button), so one test's turns don't colour the next request.
        intent.getStringExtra("reset_conversation")?.let {
            AgentEngine.resetConversation(); android.util.Log.i("Tekyerema", "[DEBUG] conversation reset"); return
        }
        intent.getStringExtra("confirm")?.let {
            AgentEngine.confirm(it.equals("yes", true) || it.equals("true", true))
            return
        }
        // Signal completion of Secure Input Mode (MoMo PIN entered) over ADB, for testing the boundary.
        intent.getStringExtra("secure_done")?.let { AgentEngine.secureInputDone(); return }
        // Simulate a wake-word detection (no audio) → activation path. --es wake_test 1
        intent.getStringExtra("wake_test")?.let { com.tekyerema.voice.WakeWordService.simulate(context.applicationContext); return }
        // QA: audition the "thinking" filler clips of a language, one after another. --es play_fillers twi|en
        intent.getStringExtra("play_fillers")?.let { lang ->
            val l = if (lang.startsWith("tw", true)) com.tekyerema.voice.Lang.TWI else com.tekyerema.voice.Lang.GH_EN
            com.tekyerema.voice.VoiceEngine.auditionFillers(l)
            return
        }
        // TYPED command through the FULL voice pipeline (TTS + ask-card + Twi translation) — mirrors typing in
        // the command field. Use for validating the typed demo. --es type_text "<command>"
        intent.getStringExtra("type_text")?.let { t ->
            // accepted=false: dropped because a turn was still running — the device suite waits and resends. No words logged.
            if (t.isNotBlank()) Log.i("Tekyerema", "[TYPE_TEXT] accepted=${com.tekyerema.voice.VoiceEngine.submitText(t.trim())} len=${t.trim().length}")
            return
        }
        // Show/hide the confirmation overlay directly, to verify it appears (Telegram-confirm-bug repro).
        //   --es overlay_test 1   |   --es overlay_test off
        intent.getStringExtra("overlay_test")?.let { v ->
            when {
                v.equals("off", true) -> com.tekyerema.ui.OverlayConfirm.hide(context.applicationContext)
                v.equals("ask", true) -> com.tekyerema.ui.OverlayConfirm.showQuestion(context.applicationContext, "What's Prince's number?")
                else -> com.tekyerema.ui.OverlayConfirm.show(context.applicationContext, "Send Kofi: “On my way”?")
            }
            return
        }
        // Wake monitor: log live wake scores + save/transcribe every near-miss and wake. --es wake_monitor 1|0
        intent.getStringExtra("wake_monitor")?.let { v ->
            com.tekyerema.voice.WakeWordService.monitor = v == "1" || v.equals("true", true)
            Log.i("Tekyerema", "[WAKE] monitor → ${com.tekyerema.voice.WakeWordService.monitor}")
            return
        }
        // Arm/disarm the on-device wake word at runtime. --es set_wake 1|0
        intent.getStringExtra("set_wake")?.let { v ->
            val s = com.tekyerema.settings.Settings(context.applicationContext)
            s.wakeWordEnabled = v == "1" || v.equals("true", true)
            com.tekyerema.voice.WakeWordService.stop(context.applicationContext)
            if (s.wakeWordEnabled) com.tekyerema.voice.WakeWordService.start(context.applicationContext)
            Log.i("Tekyerema", "[WAKE] set_wake → enabled=${s.wakeWordEnabled}")
            return
        }
        // Phase II eval harness: run the state-checked task suite.
        //   --es eval_suite core [--es eval_route voice|agent] [--ei seed 42]
        intent.getStringExtra("eval_suite")?.let { suite ->
            com.tekyerema.eval.EvalRunner.run(context.applicationContext, suite, intent.getIntExtra("seed", 0),
                intent.getStringExtra("eval_route") ?: "voice",
                intent.getStringExtra("eval_only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty())
            return
        }
        val goal = intent.getStringExtra("goal")?.trim().orEmpty()
        if (goal.isNotEmpty()) {
            AgentEngine.submitGoal(goal)
        }
    }
}
