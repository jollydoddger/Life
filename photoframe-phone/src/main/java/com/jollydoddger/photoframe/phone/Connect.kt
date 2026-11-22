package com.jollydoddger.photoframe.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Gets a working TV address: the saved one if it answers, else whatever mDNS finds, else null
 * (the caller then shows [AddressPanel]). [cb] runs once, on the main thread.
 */
class Connect(private val activity: Activity, private val cb: (String?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var finder: TvFinder? = null
    private var answered = false

    fun start() {
        Thread {
            val saved = Tv.url(activity)
            if (saved != null && Tv.reachable(saved)) main.post { answer(saved) }
            else main.post { discover() }
        }.start()
    }

    private fun discover() {
        if (answered) return
        finder = TvFinder(activity) { url ->
            Thread {
                if (Tv.reachable(url)) { Tv.save(activity, url); main.post { answer(url) } }
            }.start()
        }.also { it.start() }
        main.postDelayed({ answer(null) }, 7000)
    }

    private fun answer(url: String?) {
        if (answered || activity.isFinishing) return
        answered = true
        finder?.stop()
        main.removeCallbacksAndMessages(null)
        cb(url)
    }

    fun cancel() { answered = true; finder?.stop(); main.removeCallbacksAndMessages(null) }
}

fun Activity.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** "Looking for the TV…" / "type its address" panel, used by both screens. */
class AddressPanel(private val a: Activity, private val onConnected: (String) -> Unit) {
    private val title = TextView(a).apply { textSize = 20f; setTextColor(Color.WHITE) }
    private val body = TextView(a).apply { textSize = 15f; setTextColor(Color.rgb(170, 176, 186)); setPadding(0, a.dp(6), 0, a.dp(14)) }
    private val spinner = ProgressBar(a)
    private val field = EditText(a).apply {
        hint = "192.168.1.20"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO
        setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        setOnEditorActionListener { _, _, _ -> tryAddress(); true }
    }
    private val button = Button(a).apply { text = "Connect"; setOnClickListener { tryAddress() } }
    private val manual = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(button)
    }
    val view = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(a.dp(24), a.dp(24), a.dp(24), a.dp(24))
        background = GradientDrawable().apply { cornerRadius = a.dp(18).toFloat(); setColor(Color.rgb(31, 34, 42)) }
        addView(title); addView(body); addView(spinner); addView(manual)
    }

    fun searching() {
        title.text = "Looking for the TV…"
        body.text = "Make sure this phone is on the home Wi-Fi and the Photo Frame app is open on the TV."
        spinner.visibility = View.VISIBLE
        manual.visibility = View.GONE
    }

    fun askForAddress(problem: String = "Couldn't find the TV on the Wi-Fi.") {
        title.text = problem
        body.text = "Type the address the TV shows: press ▼ on the TV remote to see it under the QR code."
        spinner.visibility = View.GONE
        manual.visibility = View.VISIBLE
        Tv.url(a)?.let { field.setText(it.removePrefix("http://")) }
    }

    private fun tryAddress() {
        val url = Tv.normalise(field.text.toString()) ?: run { field.error = "Like 192.168.1.20"; return }
        button.isEnabled = false
        spinner.visibility = View.VISIBLE
        Thread {
            val ok = Tv.reachable(url)
            a.runOnUiThread {
                button.isEnabled = true
                spinner.visibility = View.GONE
                if (ok) { Tv.save(a, url); onConnected(url) } else field.error = "No photo frame answered there"
            }
        }.start()
    }
}
