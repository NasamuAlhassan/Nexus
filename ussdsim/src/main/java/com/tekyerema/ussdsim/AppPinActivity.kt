package com.tekyerema.ussdsim

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A stand-in for MyMTN's PIN sheet (28 Oct 2026, its layout as logged on the Infinix): "Enter your MoMo pin", four tap cells
 * named `otp_input` and NO visible box — the real box is hidden; a tap on a cell focuses it and brings the keyboard. Lets the
 * volume-key pad (Tɛkyerɛma's money/PinWatch + AppPin) be tested on the emulator without a real PIN.
 *
 * `am start -n com.tekyerema.ussdsim/.AppPinActivity [--es mode keypad]` — `keypad` shows the app's own 0–9 buttons instead.
 * Logs only how many digits arrived and the four it got at the end (test digits on a test screen; never a real PIN).
 */
class AppPinActivity : Activity() {
    private lateinit var hidden: EditText
    private val cells = ArrayList<TextView>()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keypad = intent.getStringExtra("mode") == "keypad"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48); setBackgroundColor(Color.WHITE) }
        root.addView(text("MoMo Balance", 22f))
        status = text("Show MoMo balance", 16f)
        root.addView(status)
        root.addView(text("Enter your MoMo pin", 20f))
        val row = FrameLayout(this)
        val cellRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(4) {
            val c = TextView(this).apply {
                id = R.id.otp_input
                gravity = Gravity.CENTER; textSize = 24f; isClickable = true
                background = GradientDrawable().apply { setStroke(3, Color.DKGRAY); cornerRadius = 16f }
                setOnClickListener { focusHidden() }
            }
            cells.add(c)
            cellRow.addView(c, LinearLayout.LayoutParams(160, 160).apply { rightMargin = 40 })
        }
        hidden = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            alpha = 0f
            // `--ez a11yHidden true`: hidden from accessibility too (harsher than React Native, whose boxes stay accessible)
            if (intent.getBooleanExtra("a11yHidden", false)) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = show(s?.toString().orEmpty())
            })
        }
        row.addView(hidden, FrameLayout.LayoutParams(1, 1))
        row.addView(cellRow)
        root.addView(row)
        root.addView(text("Make sure no one is looking at your screen", 14f))
        if (keypad) {
            val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            listOf("123", "456", "789", "0").forEach { line ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                line.forEach { d -> r.addView(Button(this).apply { text = d.toString(); setOnClickListener { hidden.append(d.toString()) } }) }
                pad.addView(r)
            }
            pad.addView(Button(this).apply { text = "Delete"; setOnClickListener { hidden.text?.let { if (it.isNotEmpty()) it.delete(it.length - 1, it.length) } } })
            root.addView(pad)
        }
        setContentView(root)
        Log.i("PinSim", "open mode=${if (keypad) "keypad" else "hidden box"}")
    }

    private fun text(s: String, size: Float) = TextView(this).apply { text = s; textSize = size; setPadding(0, 16, 0, 16) }

    private fun focusHidden() {
        hidden.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(hidden, 0)
    }

    private fun show(pin: String) {
        cells.forEachIndexed { i, c -> c.text = if (i < pin.length) "•" else "" }
        Log.i("PinSim", "digits=${pin.length}")
        if (pin.length >= 4) {
            Log.i("PinSim", "entered=$pin")
            status.text = "MoMo balance: GHS 12.50"
            cells.forEach { it.visibility = View.GONE }
        }
    }
}
