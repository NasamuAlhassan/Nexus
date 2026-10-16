package com.tekyerema

import com.tekyerema.agent.RouterTiers
import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import com.tekyerema.operator.Capability
import com.tekyerema.operator.ExecutionMode
import com.tekyerema.operator.MemoryOperatorStore
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.operator.WeakModelPolicy
import com.tekyerema.policy.CapabilityScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** WS-C: the operator's standing choices — persisted, live, and the usage/budget counters that roll over each day. */
class OperatorPolicyTest {
    private val day = 86_400_000L
    /** Noon UTC on some day, with the phone's zone pinned to UTC. */
    private val noon = 20_000L * day + day / 2

    @Before fun setUp() { AppConfig.loadFrom(emptyList()); OperatorPolicy.resetForTest(); zoneUtc() }
    @After fun tearDown() { OperatorPolicy.resetForTest() }

    private fun zoneUtc() { OperatorPolicy.zoneOffset = { 0 } }

    @Test fun defaults_are_todays_behaviour() {
        val op = OperatorPolicy
        assertEquals(ExecutionMode.STANDARD, op.executionMode)
        assertTrue(op.cloudAllowed)
        assertEquals(listOf("groq", "gemini"), op.providers)
        assertEquals(WeakModelPolicy.ASK, op.weakModelPolicy)
        assertEquals(0, op.dailyCloudCallCap); assertEquals(0, op.dailyTokenCap)
        assertFalse(op.remoteControlEnabled)
        assertTrue(op.revokedCapabilities.isEmpty()); assertTrue(op.revokedScopes.isEmpty())
        assertEquals(30, op.stepLimit(30)); assertEquals(25, op.stepLimit(25))   // the config's limit until the user picks one
        assertTrue(op.cloudUsable(noon))
    }

    @Test fun every_choice_survives_a_restart() {
        val store = MemoryOperatorStore()
        OperatorPolicy.init(store)
        OperatorPolicy.executionMode = ExecutionMode.ASK_EVERY_STEP
        OperatorPolicy.cloudAllowed = false
        OperatorPolicy.providers = listOf("gemini", "groq")
        OperatorPolicy.weakModelPolicy = WeakModelPolicy.NEVER
        OperatorPolicy.maxStepsPerTask = 45
        OperatorPolicy.dailyCloudCallCap = 150
        OperatorPolicy.dailyTokenCap = 300_000
        OperatorPolicy.remoteControlEnabled = true
        OperatorPolicy.revokedCapabilities = setOf(Capability.MONEY, Capability.WEB)
        OperatorPolicy.schedulesEnabled = false
        OperatorPolicy.messageAlertsEnabled = false
        OperatorPolicy.routinesEnabled = false
        OperatorPolicy.historyRetentionDays = 30

        OperatorPolicy.resetForTest()                 // the process dies…
        assertEquals(ExecutionMode.STANDARD, OperatorPolicy.executionMode)
        OperatorPolicy.init(store)                    // …and starts again
        val op = OperatorPolicy
        assertEquals(ExecutionMode.ASK_EVERY_STEP, op.executionMode)
        assertFalse(op.cloudAllowed)
        assertEquals(listOf("gemini", "groq"), op.providers)
        assertEquals(WeakModelPolicy.NEVER, op.weakModelPolicy)
        assertEquals(45, op.maxStepsPerTask); assertEquals(45, op.stepLimit(30))
        assertEquals(150, op.dailyCloudCallCap); assertEquals(300_000, op.dailyTokenCap)
        assertTrue(op.remoteControlEnabled)
        assertEquals(setOf(Capability.MONEY, Capability.WEB), op.revokedCapabilities)
        assertFalse(op.schedulesEnabled); assertFalse(op.messageAlertsEnabled); assertFalse(op.routinesEnabled)
        assertEquals(30, op.historyRetentionDays)
    }

    @Test fun garbage_in_the_store_falls_back_to_safe_defaults() {
        val store = MemoryOperatorStore().apply {
            put(OperatorPolicy.K_MODE, "YOLO"); put(OperatorPolicy.K_CLOUD, "maybe"); put(OperatorPolicy.K_STEPS, "9999")
            put(OperatorPolicy.K_PROVIDERS, "groq,openai,GROQ, gemini"); put(OperatorPolicy.K_REVOKED, "MONEY,NUKES")
            put(OperatorPolicy.K_USAGE, "{not json")
        }
        OperatorPolicy.init(store)
        assertEquals(ExecutionMode.STANDARD, OperatorPolicy.executionMode)
        assertTrue(OperatorPolicy.cloudAllowed)
        assertEquals(60, OperatorPolicy.maxStepsPerTask)
        assertEquals(listOf("groq", "gemini"), OperatorPolicy.providers)
        assertEquals(setOf(Capability.MONEY), OperatorPolicy.revokedCapabilities)
        assertEquals(0 to 0L, OperatorPolicy.usageToday(noon))
    }

    @Test fun providers_keep_known_ids_in_the_users_order() {
        OperatorPolicy.providers = listOf("Gemini", "unknown", "groq", "gemini")
        assertEquals(listOf("gemini", "groq"), OperatorPolicy.providers)
        assertTrue(OperatorPolicy.providerAllowed("groq", noon))
        OperatorPolicy.providers = listOf("groq")
        assertFalse(OperatorPolicy.providerAllowed("gemini", noon))
        OperatorPolicy.cloudAllowed = false
        assertFalse(OperatorPolicy.providerAllowed("groq", noon))
    }

    @Test fun revoked_scopes_is_the_contract_view_of_revoked_capabilities() {
        OperatorPolicy.revokedCapabilities = setOf(Capability.MESSAGES)
        assertTrue(OperatorPolicy.revokedScopes.isEmpty())   // calls are still allowed, so COMMS_SEND isn't fully off
        OperatorPolicy.revokedCapabilities = setOf(Capability.MESSAGES, Capability.CALLS, Capability.WEB)
        assertEquals(setOf(CapabilityScope.COMMS_SEND, CapabilityScope.WEB), OperatorPolicy.revokedScopes)
        OperatorPolicy.revokedScopes = setOf(CapabilityScope.SETTINGS)
        assertEquals(setOf(Capability.SETTINGS), OperatorPolicy.revokedCapabilities)
    }

    @Test fun step_limit_is_the_users_choice_within_5_to_60() {
        OperatorPolicy.maxStepsPerTask = 2
        assertEquals(5, OperatorPolicy.stepLimit(30))
        OperatorPolicy.maxStepsPerTask = 500
        assertEquals(60, OperatorPolicy.stepLimit(30))
    }

    @Test fun usage_counts_answered_calls_per_provider_and_rolls_over_at_midnight() {
        val store = MemoryOperatorStore()
        OperatorPolicy.init(store)
        OperatorPolicy.noteModelCall("groq/openai/gpt-oss-120b", 1200, noon)
        OperatorPolicy.noteModelCall("groq/openai/gpt-oss-20b", 800, noon)
        OperatorPolicy.noteModelCall("gemini", 500, noon)
        OperatorPolicy.noteModelCall("groq/openai/gpt-oss-120b", null, noon)   // a timeout / 429: nothing billed, not counted
        assertEquals(3 to 2500L, OperatorPolicy.usageToday(noon))
        val by = OperatorPolicy.usageByProvider(noon)
        assertEquals(listOf("groq", "gemini"), by.keys.toList())
        assertEquals(2, by.getValue("groq").calls); assertEquals(2000L, by.getValue("groq").tokens)
        assertEquals("gemini", OperatorPolicy.lastModel)

        // Persisted: a restart the same day keeps the counters…
        OperatorPolicy.resetForTest(); zoneUtc(); OperatorPolicy.init(store)
        assertEquals(3 to 2500L, OperatorPolicy.usageToday(noon + 3_600_000L))
        // …and the next day starts from zero.
        assertEquals(0 to 0L, OperatorPolicy.usageToday(noon + day))
        OperatorPolicy.recordUsage("groq", 10, noon + day)
        assertEquals(1 to 10L, OperatorPolicy.usageToday(noon + day))
    }

    @Test fun the_day_follows_the_phones_time_zone() {
        OperatorPolicy.zoneOffset = { 2 * 3_600_000 }            // UTC+2
        val lateUtc = 20_000L * day + 23 * 3_600_000L          // 23:00 UTC = 01:00 the next local day
        OperatorPolicy.recordUsage("groq", 5, lateUtc - 2 * 3_600_000L)   // 21:00 UTC = 23:00 local
        assertEquals(0 to 0L, OperatorPolicy.usageToday(lateUtc))
    }

    @Test fun a_spent_budget_takes_the_cloud_away_until_tomorrow() {
        OperatorPolicy.dailyCloudCallCap = 2
        OperatorPolicy.recordUsage("groq", 100, noon)
        assertFalse(OperatorPolicy.budgetExhausted(noon))
        OperatorPolicy.recordUsage("groq", 100, noon)
        assertTrue(OperatorPolicy.budgetExhausted(noon))
        assertFalse(OperatorPolicy.cloudUsable(noon))
        assertTrue(OperatorPolicy.cloudAllowed)                 // the user's switch is untouched
        assertTrue(OperatorPolicy.view(noon).budgetSpent)
        assertTrue(OperatorPolicy.cloudUsable(noon + day))     // a new day
    }

    @Test fun a_token_budget_counts_too() {
        OperatorPolicy.dailyTokenCap = 1000
        OperatorPolicy.recordUsage("gemini", 999, noon)
        assertTrue(OperatorPolicy.cloudUsable(noon))
        OperatorPolicy.recordUsage("gemini", 1, noon)
        assertFalse(OperatorPolicy.cloudUsable(noon))
    }

    @Test fun cloud_off_removes_every_online_router_tier_and_only_those() {
        assertTrue(RouterTiers.enabled(RouterTiers.CLOUD))
        OperatorPolicy.cloudAllowed = false
        assertFalse(RouterTiers.enabled(RouterTiers.CLOUD))
        assertFalse(RouterTiers.enabled(RouterTiers.DIRECT))
        assertFalse(RouterTiers.enabled(RouterTiers.RETRIEVAL))
        assertTrue(RouterTiers.enabled(RouterTiers.FAST_PATH))
        assertTrue(RouterTiers.enabled(RouterTiers.LOCAL))
        // The user can't ADD a tier the configuration removed.
        OperatorPolicy.cloudAllowed = true
        AppConfig.loadFrom(listOf("config/router.json" to """{"router":{"tiers":["fast_path","local_brain"]}}"""))
        assertFalse(RouterTiers.enabled(RouterTiers.CLOUD))
        AppConfig.loadFrom(emptyList())
    }

    @Test fun the_users_provider_order_is_the_planner_chain_and_can_only_narrow_it() {
        assertEquals(listOf("groq", "gemini"), com.tekyerema.agent.ModelRoles.plannerChain())
        OperatorPolicy.providers = listOf("gemini", "groq")
        assertEquals(listOf("gemini", "groq"), com.tekyerema.agent.ModelRoles.plannerChain())
        OperatorPolicy.providers = listOf("groq")
        assertEquals(listOf("groq"), com.tekyerema.agent.ModelRoles.plannerChain())
        OperatorPolicy.providers = emptyList()
        assertTrue(com.tekyerema.agent.ModelRoles.plannerChain().isEmpty())
        // A build that only ships Groq can't be widened by the user; a provider the user can't choose yet is kept.
        OperatorPolicy.providers = listOf("gemini", "groq")
        AppConfig.loadFrom(listOf("config/models.json" to """{"models":{"planner":{"chain":["groq","openrouter"]}}}"""))
        assertEquals(listOf("groq", "openrouter"), com.tekyerema.agent.ModelRoles.plannerChain())
        AppConfig.loadFrom(emptyList())
    }

    @Test fun switched_off_routines_and_triggers_turn_their_features_off_but_never_on() {
        assertTrue(FeatureFlags.isEnabled("A-13", true))
        OperatorPolicy.schedulesEnabled = false
        assertFalse(FeatureFlags.isEnabled("A-13", true))
        assertFalse(FeatureFlags.isEnabled("A-11", true))
        OperatorPolicy.messageAlertsEnabled = false
        assertFalse(FeatureFlags.isEnabled("A-12", true))
        OperatorPolicy.routinesEnabled = false
        assertFalse(FeatureFlags.isEnabled("H-02", true))
        assertTrue(FeatureFlags.isEnabled("J-12", true))         // unrelated features untouched
        OperatorPolicy.schedulesEnabled = true
        AppConfig.loadFrom(listOf("config/flags.json" to """{"flags":{"A-13":{"enabled":false}}}"""))
        assertFalse(FeatureFlags.isEnabled("A-13", true))        // switching on here can't override the config's off
        AppConfig.loadFrom(emptyList())
    }

    @Test fun retention_can_only_be_shortened() {
        assertEquals(90, com.tekyerema.core.Privacy.historyDays())
        OperatorPolicy.historyRetentionDays = 7
        assertEquals(7, com.tekyerema.core.Privacy.historyDays())
        OperatorPolicy.historyRetentionDays = 365
        assertEquals(90, com.tekyerema.core.Privacy.historyDays())
        OperatorPolicy.historyRetentionDays = 0
        assertEquals(90, com.tekyerema.core.Privacy.historyDays())
        // A configured 0 or less is left exactly as it is — the user can't lengthen it from there either.
        OperatorPolicy.historyRetentionDays = 30
        assertEquals(0, OperatorPolicy.retentionFor(0))
        assertEquals(-1, OperatorPolicy.retentionFor(-1))
    }

    @Test fun revoke_and_restore_change_one_capability_and_leave_the_rest() {
        OperatorPolicy.revokedCapabilities = setOf(Capability.WEB)
        OperatorPolicy.revoke(Capability.MONEY)
        assertEquals(setOf(Capability.WEB, Capability.MONEY), OperatorPolicy.revokedCapabilities)
        OperatorPolicy.restore(Capability.WEB)
        assertEquals(setOf(Capability.MONEY), OperatorPolicy.revokedCapabilities)
    }

    @Test fun cloud_off_keeps_speech_on_the_phone_too() = kotlinx.coroutines.test.runTest {
        val dir = listOf(File("src/main/assets/config"), File("app/src/main/assets/config")).first { it.isDirectory }
        AppConfig.loadFrom(dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { "config/${it.name}" to it.readText() })
        var sent = 0
        val transport = object : com.tekyerema.language.SpeechTransport {
            override suspend fun send(request: okhttp3.Request, connectMs: Long, callMs: Long): com.tekyerema.language.HttpReply {
                sent++; return com.tekyerema.language.HttpReply(200, "{}".toByteArray(), "application/json", null)
            }
        }
        val sink = object : com.tekyerema.language.AudioSink {
            override suspend fun play(audio: ByteArray) = true
            override fun stop() {}
        }
        val r = com.tekyerema.language.LanguageLayer.build(AppConfig.obj("providers.speech.clients"),
            mapOf("SPEECH_HCILAB_TOKEN" to "speech_live_x", "SPEECH_KHAYA_WEB_KEY" to "k-test"), transport, { sink })   // Twi = Khaya since 16 Oct
        val twi = com.tekyerema.voice.Lang.TWI
        assertTrue(com.tekyerema.language.LanguageLayer.onlineAsr(twi, r.asr, cloudAllowed = true) != null)
        OperatorPolicy.cloudAllowed = false
        assertNull(com.tekyerema.language.LanguageLayer.onlineAsr(twi, r.asr, cloudAllowed = true))
        assertFalse(r.tts.getValue("hcilab_tts").speak("Mema wo akye", twi))   // the phone's own voice says it instead
        assertFalse(r.tts.getValue("khaya_web_tts").speak("Mema wo akye", twi))
        assertEquals(0, sent)
        AppConfig.loadFrom(emptyList())
    }

    @Test fun every_change_has_plain_words_for_the_activity_log() {
        assertEquals("switched the cloud off", OperatorPolicy.describeChange(OperatorPolicy.K_CLOUD, "false"))
        assertEquals("set how I work to ask before every step", OperatorPolicy.describeChange(OperatorPolicy.K_MODE, "ASK_EVERY_STEP"))
        assertEquals("switched off: Send messages, Money", OperatorPolicy.describeChange(OperatorPolicy.K_REVOKED, "MESSAGES,MONEY"))
        assertEquals("allowed everything again", OperatorPolicy.describeChange(OperatorPolicy.K_REVOKED, ""))
        assertEquals("set the online AI to Groq, then Google Gemini", OperatorPolicy.describeChange(OperatorPolicy.K_PROVIDERS, "groq,gemini"))
        assertEquals("removed the daily call limit", OperatorPolicy.describeChange(OperatorPolicy.K_CAP_CALLS, "0"))
        assertEquals("set the daily token limit to 1000", OperatorPolicy.describeChange(OperatorPolicy.K_CAP_TOKENS, "1000"))
        assertNull(OperatorPolicy.describeChange(OperatorPolicy.K_USAGE, "{}"))
        // Every stored key but the usage counter is described.
        val keys = OperatorPolicy::class.java.declaredFields.filter { it.name.startsWith("K_") }
            .map { it.isAccessible = true; it.get(null) as String }
        assertTrue(keys.size >= 14)
        for (k in keys) if (k != OperatorPolicy.K_USAGE) assertTrue(k, OperatorPolicy.describeChange(k, "1") != null)
    }

    @Test fun keys_are_shown_masked_and_checked_before_saving() {
        val p = com.tekyerema.operator.ProviderKeys
        assertEquals("••••wxyz", p.mask("gsk_abcdefghijklmnopqrstuvwxyz"))
        assertEquals("••••", p.mask("short-key"))            // too short to show any of it
        assertEquals("", p.mask("  "))
        assertNull(p.keyProblem("gsk_abcdefghijklmnop"))
        assertTrue(p.keyProblem("gsk abc defghijk")!!.contains("no spaces"))
        assertTrue(p.keyProblem("abc")!!.contains("too short"))
        assertTrue(p.keyProblem("")!!.contains("first"))
    }

    @Test fun your_own_server_must_be_https_or_on_your_own_network() {
        val p = com.tekyerema.operator.ProviderKeys
        assertNull(p.serverProblem("https://my-llm.example.com/v1"))
        assertNull(p.serverProblem("http://192.168.1.20:11434/v1"))
        assertNull(p.serverProblem("http://localhost:8080/v1"))
        assertNull(p.serverProblem("http://laptop.local:11434/v1"))
        assertTrue(p.serverProblem("http://example.com/v1")!!.contains("https"))
        assertTrue(p.serverProblem("http://172.32.0.1/v1")!!.contains("https"))   // outside 172.16/12
        assertTrue(p.serverProblem("ftp://example.com")!!.contains("https"))
        assertTrue(p.serverProblem("not a url")!!.isNotEmpty())
    }

    @Test fun the_export_snapshot_has_no_secrets() {
        OperatorPolicy.revokedCapabilities = setOf(Capability.CALLS)
        val j = OperatorPolicy.toJson(noon).toString()
        assertTrue(j.contains("\"executionMode\":\"STANDARD\""))
        assertTrue(j.contains("CALLS"))
        assertFalse(j.lowercase().contains("key\""))
    }

    @Test fun usage_is_recorded_in_exactly_one_place() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val calls = root.walk().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { l -> !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") &&
                (l.contains("OperatorPolicy.recordUsage(") || l.contains("OperatorPolicy.noteModelCall(")) }
                .map { f.name + ": " + it.trim() }
        }.toList()
        // Telemetry.noteLlm sees every model call; a second call site would count each call twice.
        assertEquals(calls.joinToString("\n"), 1, calls.size)
        assertTrue(calls.single().startsWith("Telemetry.kt"))
    }
}
