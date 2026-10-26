package com.tekyerema

import com.tekyerema.debug.TtsAbDebug
import com.tekyerema.voice.OnDeviceVoice
import com.tekyerema.voice.Verbalize
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Writes campaign/evals/reading/plans.json: each listening-test passage as the pieces and silences the app would make, for
 *  each breath setting (campaign/evals/reading/render.py turns them into WAVs with the same sherpa-onnx). Not an assertion. */
class ReadingPlanDump {
    @Test fun dump() {
        val root = JSONObject()
        for ((tag, text) in TtsAbDebug.PASSAGES) {
            val per = JSONObject()
            for (v in TtsAbDebug.VARIANTS) {
                val plan = OnDeviceVoice.plan(Verbalize.apply(text), 450, 900, 140, 60, breaths = v.breaths, breathScale = v.breathScale)
                val arr = JSONArray()
                for (p in plan) arr.put(JSONObject().apply { if (p.text != null) put("t", p.text) else put("s", p.silenceMs) })
                per.put(v.name, JSONObject().put("plan", arr).put("silenceScale", v.silenceScale.toDouble())
                    .put("noiseScale", v.noiseScale?.toDouble() ?: JSONObject.NULL).put("noiseScaleW", v.noiseScaleW?.toDouble() ?: JSONObject.NULL)
                    .put("speedFactor", v.speedFactor.toDouble()))
            }
            root.put(tag, per)
        }
        File("../campaign/evals/reading/plans.json").also { it.parentFile.mkdirs() }.writeText(root.toString(1))
    }
}
