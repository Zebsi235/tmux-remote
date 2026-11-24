package com.zebsi.tmuxremote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.terminal.KeyHandler

/**
 * Two rows of keys a phone keyboard lacks. CTRL and ALT are sticky: tap = applies to the next key,
 * tap twice = stays on until tapped again.
 */
@SuppressLint("ViewConstructor")
class ExtraKeys(context: Context, private val host: Host) : LinearLayout(context) {

    interface Host {
        /** Send an Android key code with KeyHandler modifier bits. */
        fun sendKey(keyCode: Int, keyMod: Int)

        /** Send text as typed (ctrl/alt applied by the caller already). */
        fun sendText(text: String)
        fun sendPrefix()
        fun paste()
        fun toggleKeyboard()
        fun toggleTopBar()

        /** Open the box for writing a longer text. */
        fun compose()

        /** Start or stop dictation into the terminal. */
        fun dictate()

        /** Switch the dictation language. */
        fun nextDictationLanguage()
    }

    private class Key(val label: String, val code: Int = 0, val text: String? = null, val action: String? = null, val repeat: Boolean = false)

    private val rowsDef = listOf(
        listOf(
            Key("ESC", KeyEvent.KEYCODE_ESCAPE), Key("⇧TAB", action = "shifttab"),
            Key("/", text = "/"), Key("-", text = "-"), Key("|", text = "|"),
            Key("PGUP", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
            Key("HOME", KeyEvent.KEYCODE_MOVE_HOME), Key("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
            Key("END", KeyEvent.KEYCODE_MOVE_END), Key("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
        ),
        listOf(
            Key("PFX", action = "prefix"), Key("CTRL", action = "ctrl"), Key("ALT", action = "alt"),
            Key("🎤", action = "mic"), Key("PASTE", action = "paste"), Key("⌨", action = "kbd"),
            Key("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true), Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
            Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true), Key("TAB", KeyEvent.KEYCODE_TAB),
        ),
    )

    private val HOLD_ACTIONS = setOf("kbd", "paste", "mic")

    // 0 = off, 1 = next key only, 2 = locked
    private var ctrl = 0
    private var alt = 0
    private lateinit var ctrlView: TextView
    private lateinit var altView: TextView
    private lateinit var micView: TextView

    /** Paint the microphone key red while dictation runs. */
    fun setMic(on: Boolean) {
        micView.setBackgroundColor(if (on) Color.rgb(0xD7, 0x3A, 0x3A) else Color.TRANSPARENT)
    }
    private val handler = Handler(Looper.getMainLooper())

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(0x16, 0x1A, 0x1E))
        val h = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 38f, resources.displayMetrics).toInt()
        for (row in rowsDef) {
            val line = LinearLayout(context)
            for (key in row) line.addView(makeKey(key), LayoutParams(0, h, 1f))
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** Used by the terminal view for keys typed on the soft keyboard. */
    fun readCtrl(): Boolean {
        val on = ctrl != 0
        if (ctrl == 1) {
            ctrl = 0
            paint()
        }
        return on
    }

    fun readAlt(): Boolean {
        val on = alt != 0
        if (alt == 1) {
            alt = 0
            paint()
        }
        return on
    }

    private fun paint() {
        fun look(v: TextView, s: Int) {
            v.setTextColor(if (s == 0) Color.rgb(0xD0, 0xD0, 0xD0) else Color.BLACK)
            v.setBackgroundColor(when (s) { 0 -> Color.TRANSPARENT; 1 -> Color.rgb(0x5F, 0xD7, 0x87); else -> Color.rgb(0xFF, 0xAF, 0x00) })
        }
        look(ctrlView, ctrl)
        look(altView, alt)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(key: Key): TextView {
        val v = TextView(context)
        v.text = key.label
        v.gravity = Gravity.CENTER
        v.typeface = Typeface.MONOSPACE
        v.setTextColor(Color.rgb(0xD0, 0xD0, 0xD0))
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key.label.length > 3) 10f else if (key.label.length > 1) 11f else 15f)
        v.maxLines = 1
        if (key.action == "ctrl") ctrlView = v
        if (key.action == "alt") altView = v
        if (key.action == "mic") micView = v
        if (key.action == "prefix") v.setTextColor(Color.rgb(0x5F, 0xD7, 0x87))

        var longFired = false
        val repeater = object : Runnable {
            override fun run() {
                press(key)
                handler.postDelayed(this, 55)
            }
        }
        val longPress = Runnable {
            if (key.action in HOLD_ACTIONS) {
                longFired = true
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                when (key.action) {
                    "kbd" -> host.toggleTopBar()
                    "paste" -> host.compose()
                    else -> host.nextDictationLanguage()
                }
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    v.alpha = 0.5f
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (key.repeat) {
                        press(key)
                        handler.postDelayed(repeater, 400)
                    } else if (key.action in HOLD_ACTIONS) {
                        handler.postDelayed(longPress, 500)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    handler.removeCallbacks(repeater)
                    handler.removeCallbacks(longPress)
                    if (e.actionMasked == MotionEvent.ACTION_UP && !key.repeat && !longFired) press(key)
                }
            }
            true
        }
        return v
    }

    private fun press(key: Key) {
        when (key.action) {
            "ctrl" -> { ctrl = (ctrl + 1) % 3; paint(); return }
            "alt" -> { alt = (alt + 1) % 3; paint(); return }
            "prefix" -> { host.sendPrefix(); return }
            "paste" -> { host.paste(); return }
            "kbd" -> { host.toggleKeyboard(); return }
            "mic" -> { host.dictate(); return }
            "shifttab" -> { host.sendKey(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT); return }
        }
        val c = readCtrl()
        val a = readAlt()
        if (key.text != null) {
            var t: String = key.text
            if (c) t = when (t) { "/" -> "\u001f"; "-" -> "\u001f"; "|" -> "\u001c"; "~" -> "\u001e"; else -> t }
            host.sendText(if (a) "\u001b$t" else t)
        } else {
            var mod = 0
            if (c) mod = mod or KeyHandler.KEYMOD_CTRL
            if (a) mod = mod or KeyHandler.KEYMOD_ALT
            host.sendKey(key.code, mod)
        }
    }
}
