package com.zebsi.tmuxremote

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.termux.terminal.TerminalSession
import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.InteractiveCallback
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import com.zebsi.tmuxremote.TmuxScripts.MARK
import com.zebsi.tmuxremote.TmuxScripts.NATURAL
import com.zebsi.tmuxremote.TmuxScripts.q

data class TmuxSession(val id: String, val name: String, val windows: Int)
data class TmuxWindow(
    val sessionId: String, val index: Int, val name: String, val active: Boolean, val inMode: Boolean,
    /** Host time (epoch seconds) of the last output in this window. */
    val activity: Long = 0L,
    val bell: Boolean = false,
)

class TmuxState(val sessions: List<TmuxSession>, val windows: List<TmuxWindow>, val currentId: String?, val hostTime: Long = 0L) {
    val current: TmuxSession? get() = sessions.firstOrNull { it.id == currentId }

    /** True when the pane the user looks at is scrolled back (tmux copy mode). */
    val scrolledBack: Boolean get() = windows.any { it.sessionId == currentId && it.active && it.inMode }

    companion object {
        val EMPTY = TmuxState(emptyList(), emptyList(), null)
    }
}

enum class EndReason { DETACHED, EXITED, ERROR }

/**
 * One SSH connection to a host with a tmux client attached on it.
 *
 * - The "main" channel has a pty and runs `tmux new-session -A`; its bytes go to the terminal view.
 * - Short "control" channels on the same connection run plain tmux commands (list, switch, scroll),
 *   so the app can drive tmux without typing prefix keys into the terminal.
 */
object TmuxLink {

    interface Listener {
        /** Text for the overlay; null hides it. Main thread. */
        fun onStatus(text: String?)

        /** Main thread. */
        fun onState(state: TmuxState)

        /** The link is closed and will not come back by itself. Main thread. */
        fun onEnded(reason: EndReason, message: String)

        /** Called on a worker thread; block until the user answered. */
        fun askHostKey(host: Host, fingerprint: String, changed: Boolean): Boolean
    }

    private class Fatal(message: String) : Exception(message)
    private class ExecResult(val out: String, val err: String, val status: Int)

    private val ui = Handler(Looper.getMainLooper())
    private val tx = Executors.newSingleThreadExecutor()
    private val ctl = Executors.newSingleThreadScheduledExecutor()
    private val scr = Executors.newSingleThreadExecutor()

    @Volatile var listener: Listener? = null
    @Volatile var state: TmuxState = TmuxState.EMPTY
        private set
    @Volatile var prefixByte: Int = 2
        private set

    @Volatile private var term: TerminalSession? = null
    @Volatile private var host: Host? = null
    @Volatile private var conn: Connection? = null
    @Volatile private var main: Session? = null
    @Volatile private var tty = ""
    @Volatile private var gen = 0
    @Volatile private var mainGen = 0
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var active = false
    @Volatile private var wantSession = ""
    @Volatile private var stoppedAt = 0L
    @Volatile private var lastStatus: String? = null
    @Volatile private var hostKeyDeclined = false

    private val scrollPending = AtomicInteger(0)
    private val scrollBusy = AtomicBoolean(false)

    private val tracker = ActivityTracker()
    private var tick = 0

    /** Called with true/false when the link should (not) be kept alive in the background. Any thread. */
    @Volatile var keepAliveHook: ((Boolean) -> Unit)? = null

    /** Called while watched sessions are busy (true) or all settled (false). Any thread. */
    @Volatile var busyHook: ((Boolean) -> Unit)? = null

    /** Receives alerts for watched sessions while they are not on screen. Any thread. */
    @Volatile var alertSink: ((Host, Alert) -> Unit)? = null

    init {
        // Foreground: every 2.5 s. Background with watched sessions: every 5 s.
        ctl.scheduleWithFixedDelay({ if (active || (keepAlive && tick++ % 2 == 0)) pollOnce() }, 1000, 2500, TimeUnit.MILLISECONDS)
    }

    /** Sessions are watched: the link stays up and polls while the app is in the background. */
    val keepAlive: Boolean get() = host?.watched?.isNotEmpty() == true
    private val wantLink: Boolean get() = active || keepAlive
    val isForeground: Boolean get() = active

    fun mark(sessionId: String): Mark = tracker.mark(sessionId, if (active) state.currentId else null)

    fun isWatched(name: String): Boolean = host?.watched?.contains(name) == true

    fun setWatched(name: String, on: Boolean) {
        val h = host ?: return
        if (on) h.watched.add(name) else h.watched.remove(name)
        Store.saveHost(h)
        keepAliveHook?.invoke(keepAlive)
        if (!keepAlive) busyHook?.invoke(false)
    }

    /** Show this session as soon as possible (used when a notification is tapped). */
    fun requestSession(name: String) {
        val s = state.sessions.firstOrNull { it.name == name }
        if (s != null && hasTty) switchTo(s.id) else wantSession = name
    }

    val hostId: String? get() = host?.id
    val hasTty: Boolean get() = tty.isNotEmpty() && conn != null

    // ------------------------------------------------------------------ lifecycle

    /** Bind a terminal to [h]: connect, or reuse the live connection and attach again. */
    fun open(h: Host, session: TerminalSession, columns: Int, lines: Int) {
        term = session
        cols = columns
        rows = lines
        session.setTransport(transport)
        active = true
        val c = conn
        if (c != null && host?.id == h.id) {
            val g = gen
            if (wantSession.isBlank() || state.sessions.none { it.name == wantSession }) wantSession = state.current?.name ?: wantSession
            keepAliveHook?.invoke(keepAlive)
            thread(name = "tmux-reattach") {
                try {
                    attach(c, g, pickSession(c))
                } catch (e: Exception) {
                    lost(g)
                }
            }
        } else {
            host = h
            if (wantSession.isBlank()) wantSession = h.lastSession
            state = TmuxState.EMPTY
            tracker.clear()
            keepAliveHook?.invoke(keepAlive)
            connectAsync(retry = false)
        }
    }

    /** The screen is visible again. */
    fun resume() {
        active = true
        if (host == null) return
        val away = SystemClock.elapsedRealtime() - stoppedAt
        // With watched sessions the background polls have kept checking the connection.
        if (conn == null || (!keepAlive && stoppedAt != 0L && away > 20_000)) connectAsync(retry = true) else pollSoon()
        stoppedAt = 0L
    }

    /** The screen went to the background. The connection is left alone and checked on return. */
    fun pause() {
        active = false
        stoppedAt = SystemClock.elapsedRealtime()
    }

    /** Close everything for good. */
    fun disconnect() {
        gen++
        mainGen++
        val c = conn
        conn = null
        main = null
        host = null
        tty = ""
        wantSession = ""
        state = TmuxState.EMPTY
        tracker.clear()
        keepAliveHook?.invoke(false)
        busyHook?.invoke(false)
        if (c != null) thread { runCatching { c.close() } }
    }

    fun retry() {
        if (host != null) connectAsync(retry = false)
    }

    // ------------------------------------------------------------------ connecting

    private fun status(text: String?) {
        lastStatus = text
        ui.post { listener?.onStatus(text) }
    }

    val currentStatus: String? get() = lastStatus

    private fun connectAsync(retry: Boolean) {
        val g = synchronized(this) { ++gen }
        mainGen++
        val old = conn
        conn = null
        main = null
        tty = ""
        thread(name = "tmux-connect") {
            runCatching { old?.close() }
            var attempt = 0
            while (g == gen) {
                try {
                    status(if (retry || attempt > 0) "Reconnecting…" else "Connecting to ${host?.label}…")
                    doConnect(g)
                    return@thread
                } catch (e: Fatal) {
                    end(g, EndReason.ERROR, e.message ?: "Failed")
                    return@thread
                } catch (e: Exception) {
                    if (g != gen) return@thread
                    if (!retry) {
                        end(g, EndReason.ERROR, e.message ?: e.javaClass.simpleName)
                        return@thread
                    }
                    if (!wantLink) {
                        status("Connection lost")
                        return@thread
                    }
                    attempt++
                    Thread.sleep(if (active) 1500L else minOf(30_000L, 3000L * attempt))
                }
            }
        }
    }

    private fun doConnect(g: Int) {
        val h = host ?: throw Fatal("No host")
        val c = Connection(h.host, h.port)
        try {
            hostKeyDeclined = false
            try {
                // Long key-exchange limit: it also covers the time the user reads the host key question.
                c.connect(verifier(h), 8000, 120_000)
            } catch (e: IOException) {
                if (hostKeyDeclined) throw Fatal("The key of ${h.host} was not accepted, so nothing was sent to it.")
                throw e
            }
            if (!authenticate(c, h)) throw Fatal("Login refused for ${h.user}@${h.host}. Check the key or password.")
            if (g != gen) {
                c.close()
                return
            }
            c.addConnectionMonitor { lost(g) }
            val probe = exec(c, TmuxScripts.PROBE)
            if (probe.status == 127) throw Fatal("tmux was not found on ${h.host}.")
            prefixByte = TmuxScripts.parsePrefix(probe.out.trim())
            conn = c
            attach(c, g, pickSession(c))
        } catch (e: Exception) {
            if (conn === c) conn = null
            runCatching { c.close() }
            throw e
        }
    }

    private fun verifier(h: Host) = ServerHostKeyVerifier { _, _, algorithm, key ->
        val enc = algorithm + " " + Base64.encodeToString(key, Base64.NO_WRAP)
        if (h.hostKey == enc) return@ServerHostKeyVerifier true
        val fp = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.NO_PADDING)
        val ok = listener?.askHostKey(h, "$algorithm $fp", h.hostKey.isNotEmpty()) ?: false
        if (ok) {
            h.hostKey = enc
            Store.saveHost(h)
        } else hostKeyDeclined = true
        ok
    }

    private fun authenticate(c: Connection, h: Host): Boolean = when (h.auth) {
        Auth.APP_KEY -> c.authenticateWithPublicKey(h.user, Store.appKeyPair())
        Auth.IMPORTED_KEY -> {
            val pem = Store.decrypt(h.secret)
            val pass = Store.decrypt(h.passphrase).ifEmpty { null }
            try {
                c.authenticateWithPublicKey(h.user, pem.toCharArray(), pass)
            } catch (e: IOException) {
                if (c.isAuthenticationComplete) true
                else throw Fatal("The imported key could not be used: ${e.message}")
            }
        }
        else -> {
            val pw = Store.decrypt(h.secret)
            if (c.isAuthMethodAvailable(h.user, "password")) c.authenticateWithPassword(h.user, pw)
            else c.authenticateWithKeyboardInteractive(h.user, InteractiveCallback { _, _, n, _, _ -> Array(n) { pw } })
        }
    }

    private fun pickSession(c: Connection): String {
        val names = exec(c, TmuxScripts.LIST_NAMES).out.lines().filter { it.isNotBlank() }
        return when {
            wantSession in names -> wantSession
            names.isNotEmpty() -> names.sortedWith(NATURAL).first()
            else -> wantSession.ifBlank { "main" }
        }
    }

    // ------------------------------------------------------------------ main channel

    private fun attach(c: Connection, g: Int, name: String) {
        val mg = synchronized(this) { ++mainGen }
        runCatching { main?.close() }
        tty = ""
        val s = c.openSession()
        s.requestPTY("xterm-256color", cols, rows, 0, 0, null)
        s.execCommand(TmuxScripts.wrap(TmuxScripts.attach(name)))
        main = s
        wantSession = name
        ui.post { term?.reset() }
        thread(name = "tmux-read") { readLoop(c, s, g, mg) }
    }

    private fun readLoop(c: Connection, s: Session, g: Int, mg: Int) {
        val buf = ByteArray(16 * 1024)
        val head = ByteArrayOutputStream()
        val lock = Any()
        var headDone = false
        val tail = StringBuilder()

        fun attached() {
            status(null)
            // The size may have changed while connecting.
            tx.execute { runCatching { if (mg == mainGen) s.resizePTY(cols, rows, 0, 0) } }
            pollSoon()
        }

        // If the tty line never shows up (odd shell setup), show what came anyway.
        ui.postDelayed({
            synchronized(lock) {
                if (!headDone && mg == mainGen) {
                    headDone = true
                    val b = head.toByteArray()
                    term?.feed(b, 0, b.size)
                    attached()
                }
            }
        }, 2500)

        try {
            val inp = s.stdout
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                if (mg != mainGen) return
                synchronized(lock) {
                    if (!headDone) {
                        head.write(buf, 0, n)
                        val bytes = head.toByteArray()
                        val str = String(bytes, Charsets.ISO_8859_1)
                        val i = str.indexOf(MARK)
                        val nl = if (i >= 0) str.indexOf('\n', i) else -1
                        if (nl >= 0) {
                            tty = str.substring(i + MARK.length, nl).trim().filter { it.isLetterOrDigit() || it == '/' }
                            term?.feed(bytes, 0, i)
                            term?.feed(bytes, nl + 1, bytes.size - nl - 1)
                            headDone = true
                            attached()
                        } else if (bytes.size > 2048) {
                            term?.feed(bytes, 0, bytes.size)
                            headDone = true
                            attached()
                        }
                    } else {
                        term?.feed(buf, 0, n)
                    }
                }
                tail.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (tail.length > 600) tail.delete(0, tail.length - 300)
            }
        } catch (e: IOException) {
            // falls through to the checks below
        }
        if (mg != mainGen || g != gen) return

        synchronized(lock) {
            if (!headDone) {
                headDone = true
                val b = head.toByteArray()
                term?.feed(b, 0, b.size)
            }
        }
        val alive = try {
            exec(c, "true").status == 0
        } catch (e: Exception) {
            false
        }
        if (!alive) {
            lost(g)
            return
        }
        if (mg != mainGen || g != gen) return
        val t = tail.toString()
        try {
            when {
                t.contains("[detached") -> end(g, EndReason.DETACHED, "Detached")
                t.contains("[exited]") || t.contains("server exited") -> {
                    val names = exec(c, TmuxScripts.LIST_NAMES).out.lines().filter { it.isNotBlank() }
                    if (names.isNotEmpty()) attach(c, g, names.sortedWith(NATURAL).first())
                    else end(g, EndReason.EXITED, "The last tmux session was closed.")
                }
                else -> end(g, EndReason.ERROR, t.replace(Regex("\u001b\\[[0-9;?]*[a-zA-Z]"), "").trim().takeLast(200).ifBlank { "tmux ended." })
            }
        } catch (e: Exception) {
            lost(g)
        }
    }

    private fun lost(g: Int) {
        if (g != gen) return
        if (wantLink) connectAsync(retry = true)
        else {
            gen++
            mainGen++
            val c = conn
            conn = null
            main = null
            runCatching { c?.close() }
            status("Connection lost")
        }
    }

    private fun end(g: Int, reason: EndReason, message: String) {
        if (g != gen) return
        gen++
        mainGen++
        val c = conn
        conn = null
        main = null
        tty = ""
        runCatching { c?.close() }
        lastStatus = null
        val h = host
        if (!active && h != null && keepAlive && reason != EndReason.DETACHED) alertSink?.invoke(h, Alert("", h.label, AlertKind.LINK_ENDED))
        keepAliveHook?.invoke(false)
        busyHook?.invoke(false)
        ui.post { listener?.onEnded(reason, message) }
    }

    private val transport = object : TerminalSession.Transport {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            val copy = data.copyOfRange(offset, offset + count)
            val s = main ?: return
            val g = gen
            tx.execute {
                try {
                    val out = s.stdin
                    out.write(copy)
                    out.flush()
                } catch (e: IOException) {
                    if (s === main) lost(g)
                }
            }
        }

        override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            cols = columns
            this@TmuxLink.rows = rows
            val s = main ?: return
            tx.execute { runCatching { s.resizePTY(columns, rows, 0, 0) } }
        }
    }

    // ------------------------------------------------------------------ control channel

    private fun exec(c: Connection, script: String): ExecResult {
        val g = gen
        val watchdog = Runnable { thread { if (conn === c) lost(g) else runCatching { c.close() } } }
        ui.postDelayed(watchdog, 10_000)
        val s = c.openSession()
        try {
            s.execCommand(TmuxScripts.wrap(script))
            val out = s.stdout.readBytes()
            val err = s.stderr.readBytes()
            s.waitForCondition(ChannelCondition.EXIT_STATUS, 2000)
            return ExecResult(String(out), String(err), s.exitStatus ?: -1)
        } finally {
            ui.removeCallbacks(watchdog)
            runCatching { s.close() }
        }
    }

    /** Run a tmux script on the control channel, then refresh the session list. */
    private fun command(script: String, onError: ((String) -> Unit)? = null) {
        val c = conn ?: return
        val g = gen
        ctl.execute {
            try {
                val r = exec(c, script)
                if (r.status != 0 && onError != null) {
                    val msg = (r.err.ifBlank { r.out }).trim()
                    ui.post { onError(msg) }
                }
                pollOnce()
            } catch (e: Exception) {
                lost(g)
            }
        }
    }

    fun pollSoon() {
        ctl.execute { pollOnce() }
    }

    private fun pollOnce() {
        val c = conn ?: return
        if (main == null) return
        val g = gen
        try {
            val r = exec(c, TmuxScripts.STATE)
            if (g != gen || r.status != 0) return
            val st = TmuxScripts.parseState(r.out, tty)
            state = st
            st.current?.let { cur ->
                wantSession = cur.name
                host?.let { h ->
                    if (h.lastSession != cur.name) {
                        h.lastSession = cur.name
                        Store.saveHost(h)
                    }
                }
            }
            val alerts = tracker.update(st, if (active) st.currentId else null)
            host?.let { h ->
                if (h.watched.isNotEmpty()) {
                    for (a in alerts) if (a.sessionName in h.watched) alertSink?.invoke(h, a)
                    busyHook?.invoke(tracker.pending(st.sessions.filter { it.name in h.watched }.map { it.id }))
                }
            }
            ui.post { listener?.onState(st) }
        } catch (e: Exception) {
            lost(g)
        }
    }

    // ------------------------------------------------------------------ actions

    fun switchTo(sessionId: String, onError: ((String) -> Unit)? = null) {
        if (!hasTty) return
        command(TmuxScripts.switchTo(tty, sessionId), onError)
    }

    fun switchWindow(sessionId: String, index: Int) {
        if (!hasTty) return
        command(TmuxScripts.switchTo(tty, "$sessionId:$index"))
    }

    /** Step to the session after ([forward]) or before the current one, in list order. */
    fun step(forward: Boolean): Boolean {
        val st = state
        val list = st.sessions
        if (list.size < 2) return false
        val i = list.indexOfFirst { it.id == st.currentId }
        if (i < 0) return false
        val next = list[(i + (if (forward) 1 else -1) + list.size) % list.size]
        switchTo(next.id)
        return true
    }

    fun newSession(name: String, onError: (String) -> Unit) {
        if (!hasTty) return
        command(TmuxScripts.newSession(tty, name), onError)
    }

    fun renameSession(sessionId: String, name: String, onError: (String) -> Unit) {
        command(TmuxScripts.rename(sessionId, name), onError)
    }

    fun killSession(sessionId: String, onError: (String) -> Unit) {
        val st = state
        val other = st.sessions.firstOrNull { it.id != sessionId }
        if (sessionId == st.currentId && other != null && hasTty) command(TmuxScripts.switchThenKill(tty, other.id, sessionId), onError)
        else command(TmuxScripts.kill(sessionId), onError)
    }

    fun newWindow() {
        if (!hasTty) return
        command(TmuxScripts.newWindow(tty))
    }

    /** Leave scroll-back and return to the live bottom of the pane. */
    fun jumpToBottom() {
        if (!hasTty) return
        command(TmuxScripts.cancelCopyMode(tty))
    }

    /**
     * Scroll the pane the user looks at by [rowsDown] lines (negative = back in history).
     * In a plain pane this enters tmux copy mode (leaving it again at the bottom); in a full-screen
     * program it sends wheel events or arrow keys, like a desktop terminal does.
     * Returns false when the link cannot do it (the view then falls back to its own handling).
     */
    fun scroll(rowsDown: Int): Boolean {
        if (!hasTty) return false
        scrollPending.addAndGet(rowsDown)
        if (scrollBusy.compareAndSet(false, true)) {
            scr.execute {
                try {
                    while (true) {
                        val n = scrollPending.getAndSet(0)
                        if (n == 0) break
                        val c = conn ?: break
                        exec(c, TmuxScripts.scroll(tty, n))
                    }
                } catch (e: Exception) {
                    // the poll notices a dead link
                } finally {
                    scrollBusy.set(false)
                }
                if (scrollPending.get() != 0) scroll(0) else pollSoon()
            }
        }
        return true
    }
}
