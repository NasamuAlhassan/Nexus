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
        /** The PIN dialog the network PUSHES after its last word (MTN: "You will soon receive payment prompt"): its text. */
        const val EXTRA_PUSH = "push"
        const val EXTRA_PIN_SHA = "pinSha256"
        const val EXTRA_NAME = "name"
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    }

    private var pushScheduled = false

    /** The rehearsal's fake account name and the test PIN's SHA-256, from the dialling app; kept for the pushed PIN dialog. */
    private var pinSha: String? = null
    private var holder: String? = null
    private fun scriptFor(intent: Intent?): MtnScript {
        intent?.getStringExtra(EXTRA_PIN_SHA)?.takeIf { it.isNotBlank() }?.let { pinSha = it }
        intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { holder = it }
        val name = holder ?: "PRINCE NASAMU ALHASSAN"
        return intent?.getStringExtra(EXTRA_PUSH)?.let { MtnScript("*567#", registeredName = name, pushedPin = it, pinSha256 = pinSha) }
            ?: MtnScript(intent?.getStringExtra(EXTRA_CODE) ?: "", registeredName = name, pinSha256 = pinSha)
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
        script = scriptFor(intent)
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
        script = scriptFor(intent)
        pushScheduled = false
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
        // The network's push: a NEW dialog a few seconds after its last word, whether or not this one was closed (the real
        // phone app dismisses the previous alert when the next MMI arrives — 3GPP TS 23.090, AOSP PhoneUtils; R-A9).
        if (s.final && s.pushAfterMs > 0 && s.pushText != null && !pushScheduled) {
            pushScheduled = true
            val app = applicationContext
            val text = s.pushText
            handler.postDelayed({
                app.startActivity(Intent(app, SimActivity::class.java).putExtra(EXTRA_PUSH, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }, s.pushAfterMs)
        }
    }
}
