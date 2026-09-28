package com.tekyerema.tools

import android.os.Build
import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.CapabilityRegistry
import org.json.JSONObject

/**
 * X-02 / D-01 — declarative metadata for every model-facing tool, read from `assets/config/tools.json`:
 *
 *   { "tools": { "make_call": { "feature": "E-02", "min_api": 1, "permissions": ["android.permission.CALL_PHONE"],
 *                               "idempotent": false, "callers": ["planner"], "examples": [{"contact": "Ama"}] } } }
 *
 * The Kotlin [Tool] keeps behaviour (execute/verify/preflight); the manifest makes each tool self-describing
 * (which catalog feature owns it, the API gate, the permissions it needs, whether a repeat is safe, who may call
 * it, worked examples). A tool whose feature flag is off or whose API gate the device doesn't meet is hidden from
 * the model AND refused at execution. Risk tiers are NOT here — they live in the policy file and can only be raised
 * (see [com.tekyerema.policy.PolicyConfig]).
 */
data class ToolMeta(
    val feature: String?,
    val minApi: Int,
    val permissions: List<String>,
    val idempotent: Boolean,
    val callers: List<String>,
    val examples: List<JSONObject>
)

object ToolManifest {
    /** Every caller that can decide a tool call. The fast path and triggers never reach money tools (D-08). */
    const val CALLER_PLANNER = "planner"
    const val CALLER_LOCAL = "local"
    const val CALLER_TRIGGER = "trigger"

    fun meta(toolName: String): ToolMeta {
        val o = AppConfig.obj("tools.$toolName")
        val perms = o?.optJSONArray("permissions")
        val callers = o?.optJSONArray("callers")
        val ex = o?.optJSONArray("examples")
        return ToolMeta(
            feature = o?.optString("feature")?.takeIf { it.isNotBlank() },
            minApi = o?.optInt("min_api", 1) ?: 1,
            permissions = perms?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            idempotent = o?.optBoolean("idempotent", false) ?: false,
            callers = callers?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: listOf(CALLER_PLANNER, CALLER_LOCAL, CALLER_TRIGGER),
            examples = ex?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        )
    }

    /** Why [tool] is unavailable on this device/build ("flag off", "needs API 31"…), or null when usable. Permission
     *  gaps are NOT a blocker: tools handle a denied permission themselves (e.g. a dial-pad fallback). */
    fun blocker(tool: Tool, sdk: Int = Build.VERSION.SDK_INT): String? {
        val m = meta(tool.name)
        if (sdk < m.minApi) return "needs API ${m.minApi} (device is $sdk)"
        val f = m.feature ?: return null
        if (CapabilityRegistry.get(f) == null) {
            // Unregistered feature: the flag alone decides.
            return if (AppConfig.bool("flags.$f.enabled", true)) null else "flag off ($f)"
        }
        return CapabilityRegistry.blocker(null, f, sdk)?.let { "$f: $it" }
    }

    fun isAvailable(tool: Tool, sdk: Int = Build.VERSION.SDK_INT): Boolean = blocker(tool, sdk) == null

    fun mayCall(tool: Tool, caller: String): Boolean = caller in meta(tool.name).callers
}
