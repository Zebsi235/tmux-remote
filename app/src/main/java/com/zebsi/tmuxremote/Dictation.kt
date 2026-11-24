package com.zebsi.tmuxremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.SpeechStreamService
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Speech to text on the phone itself (Vosk). The German and English models ship inside the APK
 * (assets/speech/<lang>) and are copied to the app's storage on first use, because the
 * recogniser needs real files. Nothing is sent anywhere.
 */
object Dictation {

    interface Callback {
        /** Words heard so far in the running sentence. */
        fun onPartial(text: String)

        /** A finished sentence. */
        fun onText(text: String)
        fun onError(message: String)
    }

    val LANGUAGES = listOf("de", "en")

    private val ui = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var modelLang = ""
    private var service: SpeechService? = null
    private var session = 0

    /** Copy assets/speech/<lang> to files/speech/<lang> once per model version. */
    private fun unpack(ctx: Context, lang: String): File {
        val dir = File(ctx.filesDir, "speech/$lang")
        val want = ctx.assets.open("speech/$lang/MODEL_NAME").use { String(it.readBytes()).trim() }
        val mark = File(dir, ".unpacked")
        if (mark.exists() && mark.readText() == want) return dir
        dir.deleteRecursively()
        fun copy(asset: String, to: File) {
            val children = ctx.assets.list(asset) ?: emptyArray()
            if (children.isEmpty()) {
                to.parentFile?.mkdirs()
                ctx.assets.open(asset).use { i -> to.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            } else {
                to.mkdirs()
                for (c in children) copy("$asset/$c", File(to, c))
            }
        }
        copy("speech/$lang", dir)
        mark.writeText(want)
        return dir
    }

    @Synchronized
    private fun load(ctx: Context, lang: String): Model {
        model?.let { if (modelLang == lang) return it }
        runCatching { model?.close() }
        model = null
        val m = Model(unpack(ctx, lang).absolutePath)
        model = m
        modelLang = lang
        return m
    }

    private fun text(json: String?, key: String): String = try {
        JSONObject(json ?: "{}").optString(key).trim()
    } catch (e: Exception) {
        ""
    }

    /** Start listening on the microphone. [onReady] runs when the model is loaded (first time: a few seconds). */
    fun start(ctx: Context, lang: String, cb: Callback, onReady: () -> Unit) {
        stop()
        val mine = ++session
        val app = ctx.applicationContext
        thread(name = "speech-load") {
            try {
                val rec = Recognizer(load(app, lang), 16000f)
                ui.post {
                    if (mine != session) {
                        rec.close()
                        return@post
                    }
                    try {
                        val svc = SpeechService(rec, 16000f)
                        service = svc
                        svc.startListening(object : RecognitionListener {
                            override fun onPartialResult(hypothesis: String?) {
                                if (mine == session) cb.onPartial(text(hypothesis, "partial"))
                            }

                            override fun onResult(hypothesis: String?) {
                                val t = text(hypothesis, "text")
                                if (mine == session && t.isNotEmpty()) cb.onText(t)
                            }

                            override fun onFinalResult(hypothesis: String?) {
                                val t = text(hypothesis, "text")
                                if (t.isNotEmpty()) cb.onText(t)
                            }

                            override fun onError(exception: Exception?) {
                                if (mine == session) cb.onError(exception?.message ?: "microphone error")
                            }

                            override fun onTimeout() {}
                        })
                        onReady()
                    } catch (e: Exception) {
                        cb.onError(e.message ?: "The microphone could not be opened.")
                    }
                }
            } catch (e: Throwable) {
                ui.post { if (mine == session) cb.onError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Stop listening; the words of the running sentence still arrive through onText. */
    fun stop() {
        session++
        val svc = service ?: return
        service = null
        runCatching { svc.stop() }
        ui.postDelayed({ runCatching { svc.shutdown() } }, 500)
    }

    /** Free the model's memory (it is loaded again on the next start). */
    @Synchronized
    fun release() {
        runCatching { model?.close() }
        model = null
        modelLang = ""
    }

    /** Recognise a 16 kHz mono 16-bit WAV stream; used by the self-test. Calls [done] on the main thread. */
    fun transcribe(ctx: Context, lang: String, wav: InputStream, done: (String) -> Unit) {
        val app = ctx.applicationContext
        thread(name = "speech-file") {
            try {
                val rec = Recognizer(load(app, lang), 16000f)
                wav.skip(44)
                val parts = ArrayList<String>()
                ui.post {
                    SpeechStreamService(rec, wav, 16000f).start(object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String?) {}
                        override fun onResult(hypothesis: String?) {
                            text(hypothesis, "text").let { if (it.isNotEmpty()) parts.add(it) }
                        }

                        override fun onFinalResult(hypothesis: String?) {
                            text(hypothesis, "text").let { if (it.isNotEmpty()) parts.add(it) }
                            done(parts.joinToString(" "))
                        }

                        override fun onError(exception: Exception?) = done("ERROR ${exception?.message}")
                        override fun onTimeout() {}
                    })
                }
            } catch (e: Throwable) {
                ui.post { done("ERROR ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }
}
