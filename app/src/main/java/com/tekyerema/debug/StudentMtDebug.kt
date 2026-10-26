package com.tekyerema.debug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tekyerema.language.StudentTokenizer
import com.tekyerema.language.StudentTranslator
import org.json.JSONObject
import java.io.File

/**
 * Debug hook for the offline student translator: runs the models session's golden lines through the ONNX graphs on the
 * phone and logs `[SMT]` lines (match / mismatch, time per line, load time).
 *   adb ... --es student_mt <folder under the app's external files dir> --es smt_variant int8|q4 --es smt_prepack 1|0
 * The folder holds encoder.onnx, decoder.onnx, table_q8.bin, scales_f32.bin and golden_decoder.json.
 */
object StudentMtDebug {
    fun run(context: Context, folder: String, variant: String, prepack: Boolean) {
        Thread {
            try {
                val dir = File(context.getExternalFilesDir(null), folder)
                val rows = StudentTokenizer.parseTsv(context.assets.open("mt/student/bpe_merges.tsv").bufferedReader().lineSequence())
                val tok = StudentTokenizer(rows)
                val golden = JSONObject(File(dir, "golden_decoder.json").readText()).getJSONObject("variants").getJSONArray(variant)
                val t0 = SystemClock.uptimeMillis()
                StudentTranslator(dir, tok, threads = 2, prepacking = prepack).use { mt ->
                    Log.i("Tekyerema", "[SMT] $variant prepack=$prepack loaded in ${SystemClock.uptimeMillis() - t0} ms")
                    var same = 0
                    var total = 0L
                    for (i in 0 until golden.length()) {
                        val g = golden.getJSONObject(i)
                        val text = g.getString("text")
                        val ids = (0 until g.getJSONArray("ids").length()).map { g.getJSONArray("ids").getLong(it) }
                        val tokOk = tok.encode(text).toList() == ids
                        val t1 = SystemClock.uptimeMillis()
                        val out = mt.translate(text) ?: ""
                        val ms = SystemClock.uptimeMillis() - t1
                        total += ms
                        val ok = out == g.getString("out")
                        if (ok) same++
                        Log.i("Tekyerema", "[SMT] #$i tokenOk=$tokOk match=$ok ${ms} ms \"$text\" -> \"$out\"" + if (ok) "" else " (want \"${g.getString("out")}\")")
                    }
                    Log.i("Tekyerema", "[SMT] $variant prepack=$prepack matched $same/${golden.length()}, ${total / golden.length().coerceAtLeast(1)} ms per line")
                }
            } catch (t: Throwable) { Log.w("Tekyerema", "[SMT] failed: $t") }
        }.start()
    }
}
