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
        if (!BuildConfig.DEV_HOOKS) return
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
        // Finance audit (2026-10-01): the payment-outcome path without touching a money menu — a SIMULATED hand-off and a
        // SIMULATED network message go through the real ledger, follow-up and speech. Nothing is dialled; no number is
        // kept (the simulated hand-off carries a name only, so the "numbers you've paid" list is untouched).
        //   adb ... --es money_sim "handoff|5|Kofi Mensah"      (airtime: "handoff|5|airtime")
        //   adb ... --es money_sim "sms|Payment made for GHS 5.00 to KOFI MENSAH. Current Balance: GHS 1.00. ..."
        //   adb ... --es money_sim ledger | clear
        intent.getStringExtra("money_sim")?.let { what ->
            val app = context.applicationContext
            val parts = what.split('|')
            when (parts[0]) {
                "handoff" -> {
                    val airtime = parts.getOrNull(2) == "airtime"
                    val tx = com.tekyerema.money.MoneyStatus.handedOff(app,
                        if (airtime) com.tekyerema.money.TxLedger.What.AIRTIME else com.tekyerema.money.TxLedger.What.SEND,
                        parts.getOrNull(1)?.toBigDecimalOrNull(), null, parts.getOrNull(2)?.takeIf { !airtime }, "mtn")
                    Log.i("Tekyerema", "[MONEY_SIM] hand-off recorded (simulated) state=${tx.state}")
                }
                "sms" -> {
                    val line = com.tekyerema.phonecare.PhoneAlerts.simulatedOutcome(app, what.substringAfter('|'))
                    Log.i("Tekyerema", "[MONEY_SIM] network message (simulated) → ${line ?: "settled nothing"}")
                }
                "clear" -> { com.tekyerema.money.TxLedger.clear(); Log.i("Tekyerema", "[MONEY_SIM] ledger cleared") }
                else -> com.tekyerema.money.TxLedger.all().forEach {
                    Log.i("Tekyerema", "[MONEY_SIM] ${it.what} ${it.amount} ${it.state} told=${it.told} ${(System.currentTimeMillis() - it.at) / 1000}s ago")
                }
            }
            return
        }
        intent.getStringExtra("ping")?.let {
            AgentEngine.refreshReadiness()
            val s = AgentEngine.state.value
            // voice= / voiceBusy= let scripts/device_suite.py wait until a reply has been read aloud (a request typed
            // while the voice layer is busy is dropped). "?" when the voice layer isn't initialised yet.
            val voice = try { "${com.tekyerema.voice.VoiceEngine.state.value.state} voiceBusy=${com.tekyerema.voice.VoiceEngine.isRunning}" } catch (_: Throwable) { "? voiceBusy=?" }
            Log.i("Tekyerema", "[PING] serviceReady=${s.serviceReady} hasApiKey=${s.hasApiKey} brain=${s.brain} status=${s.status} fg=${AgentEngine.currentController()?.foregroundPackage()} windows=${AgentEngine.currentController()?.windowCount()} voice=$voice twi='${try { com.tekyerema.voice.TwiVoiceStatus.shared.status() } catch (_: Throwable) { "?" }}' wake=[${try { com.tekyerema.voice.WakeWordService.status() } catch (_: Throwable) { "?" }}]")
            return
        }
        // Deterministic infrastructure scenario, zero LLM cost — see TestBrain.
        //   adb shell am broadcast -a com.tekyerema.RUN_GOAL --es scenario "STATE_RACE" -p com.tekyerema
        intent.getStringExtra("scenario")?.let { name ->
            val scenario = try { TestScenario.valueOf(name.trim().uppercase()) } catch (t: Throwable) {
                Log.i("Tekyerema", "[PING] Unknown scenario '$name'. Valid: ${TestScenario.entries.joinToString()}")
                return
            }
            AgentEngine.submitTestScenario(scenario, intent.getStringExtra("contact"))
            return
        }
        // Voice debug (Phase 2): validate ASR→agent wiring + mic/VAD on-device without a human voice.
        //   adb ... --es transcribe_wav K14.wav --es lang gh_en   (WAV pushed to the app's files dir)
        //   adb ... --es mic_test 1
        intent.getStringExtra("transcribe_wav")?.let { name ->
            com.tekyerema.voice.VoiceDebug.transcribeFileAndRun(context.applicationContext, name, intent.getStringExtra("lang"))
            return
        }
        // Queue recordings for the next listens (WAV files in the app's external files dir): --es inject_wav w1.wav,w2.wav | clear
        intent.getStringExtra("inject_wav")?.let { names ->
            if (names == "clear") com.tekyerema.voice.DebugMicInject.clear()
            else com.tekyerema.voice.DebugMicInject.add(context.getExternalFilesDir(null), names)
            Log.i("Tekyerema", "[VOICE-DEBUG] inject_wav $names")
            return
        }
        intent.getStringExtra("mic_test")?.let {
            com.tekyerema.voice.VoiceDebug.micTest(context.applicationContext)
            return
        }
        // Phase 5 ASR diagnostic: per-stage timings + proxy/raw/normalized/final. No goal submitted.
        //   adb ... --es asr_diag twi_C01.wav --es lang twi
        // The online recogniser on a pushed WAV (the Space for Twi), no goal: --es online_asr C10.wav --es lang twi
        intent.getStringExtra("online_asr")?.let { name ->
            com.tekyerema.voice.VoiceDebug.onlineAsr(context.applicationContext, name, intent.getStringExtra("lang"))
            return
        }
        intent.getStringExtra("asr_diag")?.let { name ->
            com.tekyerema.voice.VoiceDebug.asrDiag(context.applicationContext, name, intent.getStringExtra("lang"))
            return
        }
        // Offline English bake-off: every WAV in files/<dir>/ through ONE production recogniser. No goal submitted.
        //   adb ... --es asr_batch bench --es lang gh_en
        intent.getStringExtra("asr_batch")?.let { dir ->
            com.tekyerema.voice.VoiceDebug.asrBatch(context.applicationContext, dir, intent.getStringExtra("lang"))
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
        // P5-02 test harness (13 October 2026): open the picture reader on a file in the app's own cache. The reader is not
        // exported, so `am start` from the shell is refused (as it should be); the share sheet is the user's way to it.
        intent.getStringExtra("read_image")?.let { path ->
            val f = java.io.File(path)
            // --es read_image_mode describe (P5-07): the picture goes to the structured describer instead of OCR.
            val mode = if (intent.getStringExtra("read_image_mode") == "describe") com.tekyerema.camera.CameraReadActivity.MODE_DESCRIBE
                else com.tekyerema.camera.CameraReadActivity.MODE_TEXT
            Log.i("Tekyerema", "[IMPORT] debug read_image exists=${f.exists()} bytes=${f.length()} mode=$mode")
            if (f.exists()) try {
                context.startActivity(com.tekyerema.camera.CameraReadActivity.imageIntent(context, android.net.Uri.fromFile(f),
                    mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) { Log.w("Tekyerema", "[IMPORT] debug read_image failed: ${t.javaClass.simpleName}") }
            return
        }
        // Money training/test photos (camera/MoneyCollectActivity):  --es money_collect note_50 [--ei count 30] [--el every 600]
        intent.getStringExtra("money_collect")?.let { label ->
            context.startActivity(com.tekyerema.camera.MoneyCollectActivity.intent(context, label,
                intent.getIntExtra("count", 30), intent.getLongExtra("every", 600L)))
            return
        }
        // P5-03 test harness: open the document reader on a PDF in the app's own sandbox (the reader is not exported).
        intent.getStringExtra("read_document")?.let { path ->
            val f = java.io.File(path)
            Log.i("Tekyerema", "[DOC] debug read_document exists=${f.exists()} bytes=${f.length()}")
            if (f.exists()) try {
                context.startActivity(com.tekyerema.camera.DocumentReadActivity.intent(context, f).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) { Log.w("Tekyerema", "[DOC] debug read_document failed: ${t.javaClass.simpleName}") }
            return
        }
        // Gate G audit (13 October 2026): put the SCREEN READER's focus on a node, so TalkBack speaks it the way it would for a
        // blind user — the one way to hear TalkBack's reading of a Compose node from the shell (input injected by adb never
        // reaches TalkBack's touch exploration or key handling).   --es a11y_focus "History" [--es a11y_focus_nth 2] [--es a11y_focus_self 1]
        // The node is the nth whose text or description contains the words; unless a11y_focus_self is set, focus goes to its
        // nearest clickable or focusable ancestor (the element TalkBack stops on). Logged as [A11Y-FOCUS-SET].
        intent.getStringExtra("a11y_focus")?.let { needle ->
            val self = intent.getStringExtra("a11y_focus_self") == "1"
            var nth = intent.getStringExtra("a11y_focus_nth")?.toIntOrNull() ?: 1
            val svc = com.tekyerema.accessibility.TekyeremaAccessibilityService.live
            val root = try { svc?.rootInActiveWindow } catch (_: Throwable) { null }
            if (root == null) { Log.i("Tekyerema", "[A11Y-FOCUS-SET] no root (service live=${svc != null})"); return }
            fun hit(n: android.view.accessibility.AccessibilityNodeInfo): Boolean =
                n.text?.toString()?.contains(needle, true) == true || n.contentDescription?.toString()?.contains(needle, true) == true
            var found: android.view.accessibility.AccessibilityNodeInfo? = null
            val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>(); stack.add(root)
            while (stack.isNotEmpty() && found == null) {
                val n = stack.removeFirst()
                if (hit(n) && --nth <= 0) { found = n; break }
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addFirst(it) }
            }
            var target = found
            if (target != null && !self && !(target.isClickable || target.isFocusable)) {
                var p = target.parent; var up = 0
                while (p != null && up < 4) {
                    if (p.isClickable || p.isFocusable) { target = p; break }
                    p = p.parent; up++
                }
            }
            val ok = target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) ?: false
            Log.i("Tekyerema", "[A11Y-FOCUS-SET] '$needle' -> " + if (target == null) "not found" else
                "ok=$ok cls=${target.className?.toString()?.substringAfterLast('.')} text='${target.text}' desc='${target.contentDescription}' " +
                "click=${target.isClickable} focusable=${target.isFocusable} children=${target.childCount}")
            return
        }
        // Press a node the way TalkBack's double-tap does: ACTION_CLICK through the accessibility service, on the nth node (any
        // window) whose text or description contains the words, or its nearest clickable ancestor. Fast — a uiautomator dump
        // waits ~8 s for the animated orb to idle, too slow to catch a running task's Stop (docs/assistant-ux, 14 Oct 2026).
        //   --es a11y_click "Stop the task" [--es a11y_click_nth 2]     Logged as [A11Y-CLICK].
        intent.getStringExtra("a11y_click")?.let { needle ->
            var nth = intent.getStringExtra("a11y_click_nth")?.toIntOrNull() ?: 1
            val svc = com.tekyerema.accessibility.TekyeremaAccessibilityService.live
            val roots = try {
                (svc?.windows?.mapNotNull { it.root } ?: emptyList()).ifEmpty { listOfNotNull(svc?.rootInActiveWindow) }
            } catch (_: Throwable) { emptyList() }
            var found: android.view.accessibility.AccessibilityNodeInfo? = null
            for (root in roots) {
                val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>(); stack.add(root)
                while (stack.isNotEmpty() && found == null) {
                    val n = stack.removeFirst()
                    val hit = n.text?.toString()?.contains(needle, true) == true || n.contentDescription?.toString()?.contains(needle, true) == true
                    if (hit && --nth <= 0) { found = n; break }
                    for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addFirst(it) }
                }
                if (found != null) break
            }
            var target = found; var up = 0
            while (target != null && !target.isClickable && up < 4) { target = target.parent; up++ }
            val ok = target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) ?: false
            Log.i("Tekyerema", "[A11Y-CLICK] '$needle' -> " + if (target == null) "not found (windows=${roots.size})" else
                "ok=$ok cls=${target.className?.toString()?.substringAfterLast('.')} desc='${target.contentDescription}' text='${target.text}'")
            return
        }
        // Twi lines into the phone voice's cache ahead of time:  --es tts_prerender "line one|line two"  (or "default")
        intent.getStringExtra("tts_prerender")?.let { v ->
            val lines = if (v == "default") emptyList() else v.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            com.tekyerema.voice.VoiceEngine.prerenderTwi(lines)
            Log.i("Tekyerema", "[TTS] pre-render asked for ${lines.size} extra line(s)")
            return
        }
        // One buzz with a chosen usage (core/Haptics):  --es haptic_test accessibility|notification|alarm|touch
        intent.getStringExtra("haptic_test")?.let { u ->
            if (android.os.Build.VERSION.SDK_INT >= 26) com.tekyerema.core.Haptics.play(context.applicationContext,
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 120), -1), usageOverride = u)
            Log.i("Tekyerema", "[HAPTIC] test buzz usage=$u")
            return
        }
        // A PIN screen's shape only (money/AppPin.layout — no box text, safe at the secure step):  --es pin_layout 1
        intent.getStringExtra("pin_layout")?.let {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                try {
                    val snap = AgentEngine.currentController()?.buildSnapshot()
                    if (snap == null) Log.i("Tekyerema", "[PINPAD] layout: no screen")
                    else {
                        Log.i("Tekyerema", "[PINPAD] layout of ${snap.appPackage}: n=${snap.elements.size} keypad=${com.tekyerema.money.AppPin.find(snap)?.let { k -> if (k.field != null) "box" else "keys" } ?: "none"}")
                        com.tekyerema.money.AppPin.layout(snap).forEach { Log.i("Tekyerema", "[PINPAD] layout $it") }
                    }
                } finally { pending.finish() }
            }
            return
        }
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
        // Time the phone's own voice:  --es tts_local "<line>" --es lang twi|gh_en   → "[TTS] on-device voice: … first audio N ms"
        intent.getStringExtra("tts_local")?.let { text ->
            val lang = if (intent.getStringExtra("lang") == com.tekyerema.voice.Lang.TWI) com.tekyerema.voice.Lang.TWI else com.tekyerema.voice.Lang.GH_EN
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val t0 = android.os.SystemClock.uptimeMillis()
                val ok = try { com.tekyerema.voice.VoiceEngine.speakOnDevice(text, lang) } catch (_: Throwable) { false }
                Log.i("Tekyerema", "[VOICE-DEBUG] tts_local spoke=$ok lang=$lang in ${android.os.SystemClock.uptimeMillis() - t0} ms")
            }
            return
        }
        intent.getStringExtra("tts_ab")?.let { TtsAbDebug.run(context.applicationContext); return }
        intent.getStringExtra("tts_test")?.let { text ->
            com.tekyerema.voice.VoiceDebug.ttsTest(context.applicationContext, text, intent.getStringExtra("lang"),
                save = intent.getStringExtra("tts_save") == "1")
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
        // P2b: the device suite's per-scenario language reset. A Twi request leaves the SESSION language on Twi (typed English
        // is too weak a signal to flip it back), so the next English scenario was answered in Twi (fp_battery_level, 25 Oct).
        // Only the session language moves; the user's language MODE is untouched.  --es session_lang gh_en|twi
        intent.getStringExtra("session_lang")?.let { v ->
            if (v == com.tekyerema.voice.Lang.GH_EN || v == com.tekyerema.voice.Lang.TWI)
                com.tekyerema.settings.Settings(context.applicationContext).sessionLanguage = v
            android.util.Log.i("Tekyerema", "[DEBUG] session language ${com.tekyerema.settings.Settings(context.applicationContext).sessionLanguage}")
            return
        }
        intent.getStringExtra("confirm")?.let {
            AgentEngine.confirm(it.equals("yes", true) || it.equals("true", true))
            return
        }
        // Signal completion of Secure Input Mode (MoMo PIN entered) over ADB, for testing the boundary.
        intent.getStringExtra("secure_done")?.let { AgentEngine.secureInputDone(); return }
        // P2-02: a volume key delivered to the PIN pad as the accessibility key filter would deliver it — `down` (count one),
        // `up` (enter the digit), `hold_down` / `hold_up` (a long press). Injected key events (`input keyevent`, the emulator
        // console) never reach the key filter on the Pixel emulator (13 October 2026), so this is how the pad's counting,
        // typing and Send are exercised against the USSD simulator; the physical-key delivery is the phone's test.
        //   adb ... --es pinpad_key down|up|hold_down|hold_up
        // The screen as the pad (money/PinPad.onGesture), bypassing the touch layer: `--es pinpad_touch tap|long|two`.
        // Real touches on the layer are exercised with `adb shell input tap` / `input swipe x y x y 700` while it is up.
        intent.getStringExtra("pinpad_touch")?.let { which ->
            val ctrl = AgentEngine.currentController() ?: run { Log.i("Tekyerema", "[PINPAD] no accessibility controller"); return }
            val g = when (which) { "long" -> com.tekyerema.money.PinPad.Gesture.LONG_PRESS; "two" -> com.tekyerema.money.PinPad.Gesture.TWO_FINGER_TAP
                else -> com.tekyerema.money.PinPad.Gesture.TAP }
            val took = com.tekyerema.money.PinPad.onGesture(ctrl, g, android.os.SystemClock.uptimeMillis())
            Log.i("Tekyerema", "[PINPAD] debug touch $which → ${if (took) "taken by the pad" else "not the pad's (not armed / no secure step / touch pad off)"}")
            return
        }
        intent.getStringExtra("pinpad_key")?.let { which ->
            val ctrl = AgentEngine.currentController() ?: run { Log.i("Tekyerema", "[PINPAD] no accessibility controller"); return }
            val code = if (which.endsWith("down")) android.view.KeyEvent.KEYCODE_VOLUME_DOWN else android.view.KeyEvent.KEYCODE_VOLUME_UP
            val hold = which.startsWith("hold")
            val t0 = android.os.SystemClock.uptimeMillis()
            val took = com.tekyerema.money.PinPad.onKey(ctrl, code, android.view.KeyEvent.ACTION_DOWN, t0)
            com.tekyerema.money.PinPad.onKey(ctrl, code, android.view.KeyEvent.ACTION_UP, t0 + if (hold) 900L else 80L)
            Log.i("Tekyerema", "[PINPAD] debug key $which → ${if (took) "taken by the pad" else "not the pad's (not armed / no secure step)"}")
            return
        }
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
        // As if the recogniser wrote it: the speech-robustness layer runs (numbers, MTN, money words, contact names, the
        // read-back's "I heard …"), and an answer is read for its question. --es heard_text "<what the ASR wrote>"
        intent.getStringExtra("heard_text")?.let { t ->
            if (t.isNotBlank()) Log.i("Tekyerema", "[HEARD_TEXT] accepted=${com.tekyerema.voice.VoiceEngine.submitHeard(t.trim())} len=${t.trim().length}")
            return
        }
        intent.getStringExtra("type_text")?.let { t ->
            // accepted=false: dropped because a turn was still running — the device suite waits and resends. No words logged.
            if (t.isNotBlank()) Log.i("Tekyerema", "[TYPE_TEXT] accepted=${com.tekyerema.voice.VoiceEngine.submitText(t.trim())} len=${t.trim().length}")
            return
        }
        // Show/hide the confirmation overlay directly, to verify it appears (Telegram-confirm-bug repro).
        //   --es overlay_test 1   |   --es overlay_test off
        //   adb ... --es volume_key up|down   — a volume key through the accessibility key filter (demo step 1: answer a call)
        intent.getStringExtra("volume_key")?.let { k ->
            val svc = com.tekyerema.accessibility.TekyeremaAccessibilityService.live
            if (svc == null) { android.util.Log.w("Tekyerema", "[KEYS] debug volume_$k: no accessibility service"); return@let }
            svc.debugKey(when (k) { "up" -> android.view.KeyEvent.KEYCODE_VOLUME_UP; "back" -> android.view.KeyEvent.KEYCODE_BACK
                else -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN })
            android.util.Log.i("Tekyerema", "[KEYS] debug volume_$k delivered to the key filter")
        }
        intent.getStringExtra("overlay_test")?.let { v ->
            when {
                v.equals("off", true) -> com.tekyerema.ui.OverlayConfirm.hide(context.applicationContext)
                v.equals("ask", true) -> com.tekyerema.ui.OverlayConfirm.showQuestion(context.applicationContext, "What's their number?")
                else -> com.tekyerema.ui.OverlayConfirm.show(context.applicationContext, "Send this message: “On my way”?")
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
