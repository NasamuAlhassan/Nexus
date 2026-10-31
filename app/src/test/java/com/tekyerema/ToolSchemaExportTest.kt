package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.tools.ToolRegistry
import com.tekyerema.tools.defaultTools
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The agent benchmark's tool list comes from the app's own registry, never a hand-written copy (campaign/agent_bench).
 * Writes every model-visible tool's schema, with the shipped config's descriptions and examples, to
 * app/build/tool_export/tools.json; `campaign/agent_bench/export_tools.sh` copies it into the benchmark.
 */
class ToolSchemaExportTest {

    @Test fun export_model_visible_tool_schemas() {
        val dir = File("src/main/assets/config")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        try {
            AppConfig.loadFrom(files.map { it.name to it.readText() })
            val decls = ToolRegistry(defaultTools()) { true }.functionDeclarations()
            val hidden = defaultTools().filter { it.hiddenFromModel }.map { it.name }
            val out = File("build/tool_export/tools.json")
            out.parentFile.mkdirs()
            out.writeText(JSONObject().put("tools", decls).put("hidden_from_model", hidden).toString(1))
            assertTrue("expected the registry's tools, got ${decls.length()}", decls.length() > 40)
        } finally {
            AppConfig.loadFrom(emptyList())
        }
    }
}
