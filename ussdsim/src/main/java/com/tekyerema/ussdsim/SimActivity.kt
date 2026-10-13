package com.tekyerema.ussdsim

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The simulated network's dialog: the screen's words as plain text, one numeric entry box (a password box on a PIN screen,
 * so the digits are masked to every observer), and the dialog's own Send / Cancel — or OK on the session's last word.
 * Started with the dialled code in [EXTRA_CODE]; a redial while open starts the session again. Nothing is logged.
 */
class SimActivity : Activity() {
    companion object {
        const val TITLE = "USSD simulator"
        const val EXTRA_CODE = "code"
    }

    private lateinit var script: MtnScript
    private lateinit var message: TextView
    private lateinit var entry: EditText
    private lateinit var send: Button
    private lateinit var cancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = TITLE
        setFinishOnTouchOutside(false)
        script = MtnScript(intent?.getStringExtra(EXTRA_CODE) ?: "")
        val dp = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        message = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        entry = EditText(this).apply { maxLines = 1; isSingleLine = true }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        cancel = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }
        send = Button(this).apply { text = "Send"; setOnClickListener { onSend() } }
        row.addView(cancel); row.addView(send)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT; val match = ViewGroup.LayoutParams.MATCH_PARENT
        column.addView(message, LinearLayout.LayoutParams(match, wrap))
        column.addView(entry, LinearLayout.LayoutParams(match, wrap))
        column.addView(row, LinearLayout.LayoutParams(match, wrap))
        setContentView(column)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        script = MtnScript(intent?.getStringExtra(EXTRA_CODE) ?: "")
        render()
    }

    private fun onSend() {
        if (script.shown().final) { finish(); return }
        script.enter(entry.text.toString())
        render()
    }

    private fun render() {
        val s = script.shown()
        message.text = s.text
        entry.visibility = if (s.input) View.VISIBLE else View.GONE
        entry.inputType = if (s.masked) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        entry.setText("")
        if (s.input) entry.requestFocus()
        send.text = if (s.final) "OK" else "Send"
        cancel.visibility = if (s.final) View.GONE else View.VISIBLE
    }
}
