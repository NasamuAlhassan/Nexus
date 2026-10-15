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
        /** The wallet balance the script starts with (a rehearsal's "failed transfer": too little for the amount). */
        const val EXTRA_BALANCE = "balance"
        /** The twin-emulator rehearsal's mock MTN on the laptop (scripts/mocknet/mtn_server.py), e.g. http://10.0.2.2:8787 —
         *  a completed transfer is settled there (wallets moved, both phones texted). Blank = the script's own receipt. */
        const val EXTRA_NET = "mocknet"
        /** This phone's own MoMo number in the mock network (the payer). */
        const val EXTRA_MSISDN = "msisdn"
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    }

    private var pushScheduled = false

    /** The rehearsal's fake account name, the test PIN's SHA-256 and a balance, from the dialling app; kept for the pushed PIN dialog. */
    private var pinSha: String? = null
    private var holder: String? = null
    private var balance: String? = null
    private var net: String? = null
    private var msisdn: String? = null
    /** The network's own answer to a settled transfer, shown instead of the script's receipt. */
    private var networkText: String? = null
    /** Waiting for the mock MTN: the dialog keeps an (idle) entry box, so the app reading it waits for the real answer. */
    private var settling = false
    private fun scriptFor(intent: Intent?): MtnScript {
        intent?.getStringExtra(EXTRA_NET)?.let { n -> net = n.trim().trimEnd('/').takeIf { it.startsWith("http") } }
        intent?.getStringExtra(EXTRA_MSISDN)?.let { m -> msisdn = m.trim().ifBlank { null } }
        networkText = null
        intent?.getStringExtra(EXTRA_PIN_SHA)?.takeIf { it.isNotBlank() }?.let { pinSha = it }
        intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { holder = it }
        // (present but blank = back to the default: the app always sends it; the activity's own push intent doesn't)
        intent?.getStringExtra(EXTRA_BALANCE)?.let { b -> balance = b.takeIf { it.toBigDecimalOrNull() != null } }
        val name = holder ?: "PRINCE NASAMU ALHASSAN"
        val bal = balance ?: "12.50"
        return intent?.getStringExtra(EXTRA_PUSH)?.let { MtnScript("*567#", registeredName = name, balance = bal, pushedPin = it, pinSha256 = pinSha) }
            ?: MtnScript(intent?.getStringExtra(EXTRA_CODE) ?: "", registeredName = name, balance = bal, pinSha256 = pinSha)
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
        val done = script.settlement
        val base = net
        if (done != null && base != null) { settling = true; render(); settle(base, done) }
    }

    /** The mock MTN settles the transfer (wallets, texts to both phones) and its receipt replaces the script's. */
    private fun settle(base: String, s: MtnScript.Settlement) {
        val from = msisdn ?: ""
        Thread {
            val answer = try {
                val c = java.net.URL("$base/momo/transfer").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 8000; c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                val body = org.json.JSONObject().put("from", from).put("to", s.to).put("amount", s.amount)
                    .put("reference", s.reference).put("network", s.network ?: "")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
                c.disconnect()
                org.json.JSONObject(text).optString("text").ifBlank { null }
            } catch (_: Throwable) { null }   // the laptop isn't there: the script's own receipt stands
            runOnUiThread { settling = false; networkText = answer; render() }
        }.start()
    }

    private fun render() {
        val s = script.shown()
        if (settling) {
            message.text = "Processing your request. Please wait."
            entry.visibility = View.VISIBLE; entry.isEnabled = false; entry.setText("")
            send.isEnabled = false; cancel.visibility = View.GONE
            return
        }
        entry.isEnabled = true; send.isEnabled = true
        message.text = if (s.final) networkText ?: s.text else s.text
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
