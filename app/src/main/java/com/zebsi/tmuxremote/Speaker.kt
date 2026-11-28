package com.zebsi.tmuxremote

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks short texts with the phone's own text-to-speech engine. Call from the main thread.
 * The engine is started on first use and kept; texts that arrive before it is ready wait.
 */
object Speaker {
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<String>()

    /** Told once per problem, in plain words (main thread). */
    var onProblem: ((String) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    fun say(text: String) {
        if (text.isBlank()) return
        val ctx = appContext ?: return
        if (tts == null) {
            waiting.add(text)
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    val queued = ArrayList(waiting)
                    waiting.clear()
                    for (t in queued) speakNow(t)
                } else {
                    tts = null
                    ready = false
                    waiting.clear()
                    onProblem?.invoke("This phone has no speech output set up (Settings, Text-to-speech).")
                }
            }
            return
        }
        if (!ready) waiting.add(text) else speakNow(text)
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        val locale = if (Store.speechLang == "de") Locale.GERMANY else Locale.US
        val r = engine.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            onProblem?.invoke("The voice for ${locale.displayLanguage} is not installed on this phone (Settings, Text-to-speech).")
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "say-" + SystemClock.uptimeMillis())
    }

    /** Be quiet now and forget what was queued. */
    fun stop() {
        waiting.clear()
        tts?.stop()
    }
}
