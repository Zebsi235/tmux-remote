package com.zebsi.tmuxremote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * A normal text field (autocorrect, swipe typing) whose content is sent to the terminal in one
 * go. The Dictate button turns speech into text on the phone itself, see [Dictation].
 */
class ComposeBox(private val a: Activity, private val send: (text: String, enter: Boolean) -> Unit) {

    private var draft = ""
    private var listening = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()

    private fun langLabel(l: String) = if (l == "de") "Deutsch" else "English"

    fun show() {
        val input = EditText(a).apply {
            setText(draft)
            setSelection(draft.length)
            hint = "Write here, then send it to the terminal"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP
        }
        val heard = TextView(a).apply {
            setTextColor(Color.GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = View.GONE
        }
        val mic = Button(a).apply { text = "🎤 Dictate" }
        val lang = Button(a).apply { text = langLabel(Store.speechLang) }

        fun stop() {
            if (listening) Dictation.stop()
            listening = false
            mic.text = "🎤 Dictate"
            heard.visibility = View.GONE
            lang.isEnabled = true
        }

        fun insert(text: String) {
            val at = input.selectionStart.coerceIn(0, input.text.length)
            val before = input.text.substring(0, at)
            val glue = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
            input.text.insert(at, glue + text)
        }

        fun start() {
            listening = true
            mic.text = "■ Stop"
            lang.isEnabled = false
            heard.text = "Loading the speech model…"
            heard.visibility = View.VISIBLE
            Dictation.start(a, Store.speechLang, object : Dictation.Callback {
                override fun onPartial(text: String) {
                    if (listening) heard.text = text.ifEmpty { "Listening…" }
                }

                override fun onText(text: String) {
                    insert(text)
                    if (listening) heard.text = "Listening…"
                }

                override fun onError(message: String) {
                    Toast.makeText(a, "Dictation stopped: $message", Toast.LENGTH_LONG).show()
                    stop()
                }
            }) { if (listening) heard.text = "Listening…" }
        }

        mic.setOnClickListener {
            when {
                listening -> stop()
                a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                    a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 31)
                    Toast.makeText(a, "Allow the microphone, then tap Dictate again.", Toast.LENGTH_LONG).show()
                }
                else -> start()
            }
        }
        lang.setOnClickListener {
            val next = Dictation.LANGUAGES[(Dictation.LANGUAGES.indexOf(Store.speechLang) + 1) % Dictation.LANGUAGES.size]
            Store.speechLang = next
            lang.text = langLabel(next)
        }

        val row = LinearLayout(a).apply {
            addView(mic, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(lang, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(heard)
            addView(row)
        }
        var sent = false
        val dialog = AlertDialog.Builder(a)
            .setTitle("Message")
            .setView(box)
            .setPositiveButton("Send") { _, _ -> sent = true; send(input.text.toString(), true) }
            .setNeutralButton("Insert only") { _, _ -> sent = true; send(input.text.toString(), false) }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            stop()
            // Cancelled text is kept for the next time.
            draft = if (sent) "" else input.text.toString()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        input.requestFocus()
    }
}
