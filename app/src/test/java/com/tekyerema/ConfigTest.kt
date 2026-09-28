package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.Capability
import com.tekyerema.core.config.CapabilityRegistry
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.policy.PolicyEngine
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.ToolManifest
import com.tekyerema.tools.ToolRegistry
import com.tekyerema.tools.defaultTools
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** X-01 / X-02 / X-10 / X-14 — config files, overrides, flags and capability gates. */
class ConfigTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    @Test fun files_merge_by_namespace_and_defaults_cover_missing_keys() {
        AppConfig.loadFrom(listOf(
            "config/agent.json" to """{"schema_version":1,"agent":{"limits":{"maxSteps":30,"stallAbort":5}}}""",
            "config/router.json" to """{"schema_version":1,"router":{"tiles":{"dnd":["do not disturb","dnd"]}}}"""
        ))
        assertEquals(30, AppConfig.int("agent.limits.maxSteps", 99))
        assertEquals(7, AppConfig.int("agent.limits.missing", 7))          // missing key → the code default
        assertEquals(listOf("do not disturb", "dnd"), AppConfig.stringList("router.tiles.dnd", emptyList()))
        assertEquals(2, AppConfig.loadedFiles.size)
        assertTrue(!AppConfig.has("schema_version"))
    }

    @Test fun overrides_deep_merge_and_bad_files_are_skipped() {
        AppConfig.loadFrom(listOf(
            "config/a.json" to """{"agent":{"limits":{"maxSteps":30,"stallAbort":5}}}""",
            "config/broken.json" to """{ not json"""
        ), overridesJson = """{"agent":{"limits":{"maxSteps":12}}}""")
        assertEquals(12, AppConfig.int("agent.limits.maxSteps", 0))       // overridden
        assertEquals(5, AppConfig.int("agent.limits.stallAbort", 0))      // sibling kept (deep merge)
        assertEquals(listOf("config/a.json"), AppConfig.loadedFiles)
    }

    @Test fun phrases_fill_slots() {
        AppConfig.loadFrom(listOf("p" to """{"ux":{"phrases":{"notFound":"I couldn't find {app} on this phone."}}}"""))
        assertEquals("I couldn't find Zorblax on this phone.",
            AppConfig.phrase("ux.phrases.notFound", "fallback", "app" to "Zorblax"))
        assertEquals("fallback Kofi", AppConfig.phrase("ux.phrases.none", "fallback {x}", "x" to "Kofi"))
    }

    @Test fun capability_gates_flag_api_and_dependencies() {
        AppConfig.loadFrom(listOf("config/flags.json" to """{"flags":{"T-OFF":{"enabled":false},"T-API":{"min_api":31}}}"""))
        CapabilityRegistry.register(Capability("T-BASE", "base", minApi = 26))
        CapabilityRegistry.register(Capability("T-OFF", "switched off"))
        CapabilityRegistry.register(Capability("T-API", "needs 31 via flag", minApi = 26))
        CapabilityRegistry.register(Capability("T-DEP", "depends on off", dependsOn = listOf("T-OFF")))
        assertNull(CapabilityRegistry.blocker(null, "T-BASE", sdk = 30))
        assertEquals("flag off", CapabilityRegistry.blocker(null, "T-OFF", sdk = 30))
        assertEquals("needs API 31 (device is 30)", CapabilityRegistry.blocker(null, "T-API", sdk = 30))
        assertNull(CapabilityRegistry.blocker(null, "T-API", sdk = 33))
        assertTrue(CapabilityRegistry.blocker(null, "T-DEP", sdk = 30)!!.startsWith("depends on T-OFF"))
    }

    @Test fun policy_file_can_raise_a_tier_but_never_lower_it() {
        AppConfig.loadFrom(listOf("config/policy.json" to
            """{"policy":{"toolTiers":{"open_app":"TIER3","make_call":"TIER0"}}}"""))
        val tools = defaultTools().associateBy { it.name }
        assertEquals(RiskTier.TIER3, PolicyConfig.effectiveTier(tools.getValue("open_app")))   // raised
        assertEquals(RiskTier.TIER2, PolicyConfig.effectiveTier(tools.getValue("make_call")))  // TIER0 ignored
        val d = PolicyEngine().evaluate(tools.getValue("open_app"), JSONObject().put("app", "WhatsApp"))
        assertEquals(PolicyOutcome.CONFIRM, d.outcome)
        assertEquals(PolicyOutcome.CONFIRM, PolicyEngine().evaluate(tools.getValue("make_call"), JSONObject().put("contact", "Ama")).outcome)
    }

    @Test fun manifest_hides_tools_whose_flag_is_off_or_api_is_too_new() {
        AppConfig.loadFrom(listOf(
            "config/tools.json" to """{"tools":{"set_alarm":{"feature":"T-ALARM"},"pinch":{"feature":"G-04","min_api":31}}}""",
            "config/flags.json" to """{"flags":{"T-ALARM":{"enabled":false}}}"""
        ))
        val reg = ToolRegistry(defaultTools()) { ToolManifest.isAvailable(it, sdk = 30) }
        assertNull(reg.get("set_alarm"))                                    // flag off → refused
        assertNull(reg.get("pinch"))                                        // min_api 31 on API 30 → refused
        assertNotNull(reg.get("tap"))
        val names = (0 until reg.openAiTools().length()).map { reg.openAiTools().getJSONObject(it).getJSONObject("function").getString("name") }
        assertFalse("set_alarm" in names); assertFalse("pinch" in names); assertTrue("tap" in names)
        assertEquals(defaultTools().size, reg.installed.size)
    }

    @Test fun shipped_config_files_parse_and_cover_every_tool() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { "config/${it.name}" to it.readText() }
        AppConfig.loadFrom(files)
        assertEquals(files.size, AppConfig.loadedFiles.size)                // every shipped file is valid JSON
        for (t in defaultTools()) {
            assertNotNull("tools.json has no entry for ${t.name}", AppConfig.obj("tools.${t.name}"))
            assertTrue("${t.name} must be available on the API 30 target", ToolManifest.isAvailable(t, sdk = 30))
        }
        assertEquals(com.tekyerema.settings.Settings.DEFAULT_GROQ_MODEL, AppConfig.string("models.planner.model", ""))
        // Money and calls are never offered to unattended triggers (D-08).
        for (n in listOf("run_ussd", "make_call", "mobile_money", "momo_send", "reply_to_notification"))
            assertFalse("$n must not be callable by a trigger", ToolManifest.mayCall(defaultTools().first { it.name == n }, ToolManifest.CALLER_TRIGGER))
    }

    @Test fun newer_schema_version_is_read_best_effort() {
        AppConfig.loadFrom(listOf("config/x.json" to """{"schema_version":99,"agent":{"limits":{"maxSteps":20}}}"""))
        assertEquals(20, AppConfig.int("agent.limits.maxSteps", 0))
        assertFalse(AppConfig.has("schema_version"))
    }
}
