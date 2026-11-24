package com.zebsi.tmuxremote

import android.annotation.SuppressLint
import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.concurrent.CountDownLatch

class TerminalActivity : Activity(), TmuxLink.Listener, TerminalViewClient, TerminalSessionClient, ExtraKeys.Host {

    private lateinit var host: Host
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerList: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var bottomButton: TextView
    private lateinit var termView: TerminalView
    private lateinit var overlay: TextView
    private lateinit var extraKeys: ExtraKeys
    private lateinit var heardBar: TextView
    private var dictating = false
    private lateinit var session: TerminalSession

    private var opened = false
    private var started = false
    private var shownSignature = ""
    private var endDialog: AlertDialog? = null

    private val green = Color.rgb(0x5F, 0xD7, 0x87)
    private val grey = Color.rgb(0xB0, 0xB0, 0xB0)
    private val panel = Color.rgb(0x16, 0x1A, 0x1E)
    private val amber = Color.rgb(0xFF, 0xAF, 0x00)
    private val composeBox by lazy {
        ComposeBox(this) { text, enter ->
            if (text.isNotEmpty()) session.emulator?.paste(text)
            if (enter) session.write("\r")
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val h = intent.getStringExtra("host")?.let { Store.host(it) }
        if (h == null) {
            finish()
            return
        }
        host = h
        intent.getStringExtra("session")?.let { TmuxLink.requestSession(it) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()

        session = TerminalSession(null, this)
        termView.setTerminalViewClient(this)
        termView.setTextSize(fontPx())
        termView.mScrollHook = object : TerminalView.ScrollHook {
            override fun onScrollRows(rowsDown: Int): Boolean {
                val ok = TmuxLink.scroll(rowsDown)
                if (ok && rowsDown < 0) bottomButton.visibility = View.VISIBLE
                return ok
            }

            override fun onSidewaysFling(towardsLeft: Boolean) = TmuxLink.step(forward = towardsLeft)
        }
        termView.attachSession(session)
        termView.requestFocus()
    }

    private fun fontPx() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Store.fontSizeSp.toFloat(), resources.displayMetrics).toInt()

    private fun barButton(label: String, onClick: (View) -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        minimumWidth = dp(42)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener(onClick)
    }

    private fun buildUi() {
        drawer = DrawerLayout(this)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        topBar = LinearLayout(this).apply { setBackgroundColor(panel); gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(barButton("≡") { drawer.openDrawer(GravityCompat.START) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        tabs = LinearLayout(this)
        tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }
        topBar.addView(tabScroll, LinearLayout.LayoutParams(0, dp(38), 1f))
        bottomButton = barButton("⤓") {
            TmuxLink.jumpToBottom()
            it.visibility = View.GONE
        }.apply { setTextColor(Color.rgb(0xFF, 0xAF, 0x00)); visibility = View.GONE }
        topBar.addView(bottomButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        topBar.addView(barButton("✎") { compose() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        topBar.addView(barButton("⋮") { showMenu(it) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        topBar.visibility = if (Store.showTopBar) View.VISIBLE else View.GONE
        content.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val frame = FrameLayout(this)
        termView = TerminalView(this, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
        }
        frame.addView(termView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(0xD0, 0x16, 0x1A, 0x1E))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = View.GONE
        }
        frame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        content.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        heardBar = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0x5A, 0x1F, 0x1F))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            visibility = View.GONE
        }
        content.addView(heardBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        extraKeys = ExtraKeys(this, this)
        content.addView(extraKeys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        drawer.addView(content, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val side = ScrollView(this).apply { setBackgroundColor(panel); addView(drawerList) }
        drawer.addView(side, DrawerLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = GravityCompat.START })

        setContentView(drawer)

        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            side.setPadding(bars.left, bars.top, 0, bars.bottom)
            insets
        }
        rebuildDrawer(TmuxState.EMPTY)
    }

    override fun onStart() {
        super.onStart()
        started = true
        // Disconnected from the notification while this screen was in the background.
        if (opened && TmuxLink.hostId == null) {
            finish()
            return
        }
        TmuxLink.listener = this
        if (opened) TmuxLink.resume()
        onStatus(TmuxLink.currentStatus)
    }

    override fun onStop() {
        super.onStop()
        started = false
        stopDictation()
        if (opened) TmuxLink.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (TmuxLink.listener === this) TmuxLink.listener = null
    }

    /** Drop the connection and go back to the host list. */
    private fun close() {
        TmuxLink.disconnect()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START)
        else moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ link callbacks

    override fun onStatus(text: String?) {
        overlay.text = text ?: ""
        overlay.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    override fun onState(state: TmuxState) {
        bottomButton.visibility = if (state.scrolledBack) View.VISIBLE else View.GONE
        state.current?.let { getSystemService(NotificationManager::class.java).cancel(App.alertId(it.name)) }
        val sig = state.currentId + "|" + state.sessions.joinToString { it.id + it.name + it.windows + TmuxLink.mark(it.id) + TmuxLink.isWatched(it.name) } + "|" +
            state.windows.joinToString { it.sessionId + it.index + it.name + it.active }
        if (sig == shownSignature) return
        shownSignature = sig
        rebuildTabs(state)
        rebuildDrawer(state)
    }

    override fun onEnded(reason: EndReason, message: String) {
        when (reason) {
            EndReason.DETACHED -> close()
            EndReason.EXITED -> {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                close()
            }
            EndReason.ERROR -> {
                onStatus(null)
                endDialog?.dismiss()
                endDialog = AlertDialog.Builder(this)
                    .setTitle("Not connected")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Retry") { _, _ -> TmuxLink.retry() }
                    .setNegativeButton("Close") { _, _ -> close() }
                    .show()
            }
        }
    }

    override fun askHostKey(host: Host, fingerprint: String, changed: Boolean): Boolean {
        val latch = CountDownLatch(1)
        var answer = false
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                latch.countDown()
                return@runOnUiThread
            }
            val msg = if (changed)
                "WARNING: the key of ${host.host} is different from the one saved earlier.\n\nThis happens after a reinstall of the host, or when someone else answers in its place.\n\nNew key:\n$fingerprint\n\nTrust the new key?"
            else
                "First connection to ${host.host}.\n\nKey of the host:\n$fingerprint\n\nTrust this host?"
            AlertDialog.Builder(this)
                .setTitle(if (changed) "Host key changed" else "Unknown host")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(if (changed) "Trust new key" else "Trust") { _, _ -> answer = true; latch.countDown() }
                .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await()
        return answer
    }

    // ------------------------------------------------------------------ tabs, drawer, menu

    private fun rebuildTabs(state: TmuxState) {
        tabs.removeAllViews()
        var currentView: View? = null
        for (s in state.sessions) {
            val isCurrent = s.id == state.currentId
            val v = TextView(this).apply {
                text = sessionLabel(s)
                gravity = Gravity.CENTER
                typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isCurrent) Color.BLACK else grey)
                setBackgroundColor(if (isCurrent) green else Color.TRANSPARENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { if (!isCurrent) TmuxLink.switchTo(s.id, ::toast) }
                setOnLongClickListener { sessionMenu(it, s); true }
            }
            if (isCurrent) currentView = v
            tabs.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val plus = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            setTextColor(grey)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { newSessionDialog() }
        }
        tabs.addView(plus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        currentView?.let { cv ->
            tabScroll.post {
                val target = cv.left - (tabScroll.width - cv.width) / 2
                tabScroll.smoothScrollTo(maxOf(0, target), 0)
            }
        }
    }

    /** Name plus marks: green dot = printing now, amber dot = new output you have not seen, bell = watched. */
    private fun sessionLabel(s: TmuxSession): CharSequence {
        val b = SpannableStringBuilder(s.name)
        val mark = TmuxLink.mark(s.id)
        if (mark != Mark.NONE) {
            b.append(" ●")
            b.setSpan(ForegroundColorSpan(if (mark == Mark.BUSY) green else amber), b.length - 1, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (TmuxLink.isWatched(s.name)) b.append(" 🔔")
        return b
    }

    private fun setWatch(s: TmuxSession, on: Boolean) {
        TmuxLink.setWatched(s.name, on)
        shownSignature = ""
        onState(TmuxLink.state)
        if (!on) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !Store.askedBattery) {
            Store.askedBattery = true
            AlertDialog.Builder(this)
                .setTitle("Notify on time")
                .setMessage("Android pauses apps when the phone lies still for a while, and the notification then comes late. Allow this app to run unrestricted?")
                .setPositiveButton("Allow") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun drawerRow(label: CharSequence, color: Int, indent: Int, bold: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (bold) 16f else 14f)
        setPadding(dp(16 + indent), dp(if (bold) 12 else 8), dp(16), dp(if (bold) 12 else 8))
        maxLines = 1
        setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            onClick()
        }
    }

    private fun rebuildDrawer(state: TmuxState) {
        drawerList.removeAllViews()
        drawerList.addView(TextView(this).apply {
            text = host.label
            setTextColor(grey)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
        for (s in state.sessions) {
            val isCurrent = s.id == state.currentId
            val row = drawerRow(sessionLabel(s), if (isCurrent) green else Color.WHITE, 0, true) { TmuxLink.switchTo(s.id, ::toast) }
            row.setOnLongClickListener { sessionMenu(it, s); true }
            drawerList.addView(row)
            val wins = state.windows.filter { it.sessionId == s.id }
            if (wins.size > 1) for (w in wins) {
                val mark = if (w.active) "● " else "○ "
                drawerList.addView(drawerRow("$mark${w.index}: ${w.name}", if (isCurrent && w.active) green else grey, 14, false) {
                    TmuxLink.switchWindow(s.id, w.index)
                })
            }
        }
        drawerList.addView(View(this).apply { setBackgroundColor(Color.DKGRAY) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        drawerList.addView(drawerRow("+ New session", Color.WHITE, 0, false) { newSessionDialog() })
        drawerList.addView(drawerRow("+ New window", Color.WHITE, 0, false) { TmuxLink.newWindow() })
        drawerList.addView(drawerRow("Disconnect", Color.rgb(0xFF, 0x87, 0x87), 0, false) { close() })
    }

    private fun sessionMenu(anchor: View, s: TmuxSession) {
        PopupMenu(this, anchor).apply {
            val watched = TmuxLink.isWatched(s.name)
            menu.add(if (watched) "Stop notifying" else "Notify when quiet").setOnMenuItemClickListener {
                setWatch(s, !watched)
                true
            }
            menu.add("Rename").setOnMenuItemClickListener {
                askText("Rename session", s.name) { name -> if (name.isNotBlank()) TmuxLink.renameSession(s.id, name, ::toast) }
                true
            }
            menu.add("Close session").setOnMenuItemClickListener {
                AlertDialog.Builder(this@TerminalActivity)
                    .setMessage("Close session \"${s.name}\"? Everything running in it stops.")
                    .setPositiveButton("Close session") { _, _ -> TmuxLink.killSession(s.id, ::toast) }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }.show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Paste").setOnMenuItemClickListener { paste(); true }
            menu.add("Write a message").setOnMenuItemClickListener { compose(); true }
            menu.add("What the keys do").setOnMenuItemClickListener { showKeyHelp(); true }
            menu.add("New session").setOnMenuItemClickListener { newSessionDialog(); true }
            menu.add("New window").setOnMenuItemClickListener { TmuxLink.newWindow(); true }
            menu.add("Text larger").setOnMenuItemClickListener { changeFont(1); true }
            menu.add("Text smaller").setOnMenuItemClickListener { changeFont(-1); true }
            menu.add("Hide this bar (hold ⌨ to show)").setOnMenuItemClickListener { toggleTopBar(); true }
            menu.add("Disconnect").setOnMenuItemClickListener { close(); true }
        }.show()
    }

    private fun newSessionDialog() = askText("New session", "", "Name (optional)") { name -> TmuxLink.newSession(name, ::toast) }

    private fun askText(title: String, initial: String, hint: String = "", onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelection(initial.length)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        if (msg.isNotBlank()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun changeFont(step: Int) {
        Store.fontSizeSp = Store.fontSizeSp + step
        termView.setTextSize(fontPx())
    }

    // ------------------------------------------------------------------ extra keys

    override fun sendKey(keyCode: Int, keyMod: Int) {
        val em = session.emulator ?: return
        val code = KeyHandler.getCode(keyCode, keyMod, em.isCursorKeysApplicationMode, em.isKeypadApplicationMode) ?: return
        session.write(code)
    }

    override fun sendText(text: String) = session.write(text)

    override fun sendPrefix() {
        val b = byteArrayOf(TmuxLink.prefixByte.toByte())
        session.write(b, 0, 1)
    }

    override fun paste() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isNotEmpty()) session.emulator?.paste(text)
    }

    override fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val visible = ViewCompat.getRootWindowInsets(termView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) imm.hideSoftInputFromWindow(termView.windowToken, 0)
        else {
            termView.requestFocus()
            imm.showSoftInput(termView, 0)
        }
    }

    override fun compose() {
        stopDictation()
        composeBox.show()
    }

    private fun langName() = if (Store.speechLang == "de") "Deutsch" else "English"

    /** Microphone key: speech is typed into the terminal at the cursor (no Enter) until tapped again. */
    override fun dictate() {
        if (dictating) {
            stopDictation()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 31)
            toast("Allow the microphone, then tap the microphone key again.")
            return
        }
        dictating = true
        extraKeys.setMic(true)
        heardBar.text = "🎤 Loading the speech model (${langName()})…"
        heardBar.visibility = View.VISIBLE
        Dictation.start(this, Store.speechLang, object : Dictation.Callback {
            override fun onPartial(text: String) {
                if (dictating) heardBar.text = "🎤 " + text.ifEmpty { "Listening (${langName()})… tap 🎤 to stop" }
            }

            override fun onText(text: String) {
                session.write("$text ")
                if (dictating) heardBar.text = "🎤 Listening (${langName()})… tap 🎤 to stop"
            }

            override fun onError(message: String) {
                toast("Dictation stopped: $message")
                stopDictation()
            }
        }) { if (dictating) heardBar.text = "🎤 Listening (${langName()})… tap 🎤 to stop" }
    }

    private fun stopDictation() {
        if (!dictating) return
        dictating = false
        Dictation.stop()
        extraKeys.setMic(false)
        heardBar.visibility = View.GONE
    }

    override fun nextDictationLanguage() {
        stopDictation()
        Store.speechLang = Dictation.LANGUAGES[(Dictation.LANGUAGES.indexOf(Store.speechLang) + 1) % Dictation.LANGUAGES.size]
        toast("Dictation language: ${langName()}")
    }

    private fun showKeyHelp() {
        val text = """
            ESC, TAB  the keys of that name. ⇧TAB is Shift+Tab (in Claude Code it switches the mode).
            / - |  these characters, to save a trip to the symbol keyboard.
            HOME, END  jump to the start or end of the line.
            ↑ ↓ ← →  arrow keys. Hold to repeat.
            PGUP, PGDN  page up and down in programs like less or vim.
            PFX  the tmux prefix (Ctrl-b unless the host uses another). Then one more key, e.g. c = new window, d = detach, z = zoom pane.
            CTRL, ALT  apply to the next key you type: CTRL then c = Ctrl-C. Tap twice to keep it on, once more to turn it off.
            🎤  dictation: speak, and the words are typed into the terminal. Tap again to stop. Hold to switch Deutsch/English.
            PASTE  insert the clipboard. Hold to open the message box.
            ⌨  show or hide the keyboard. Hold to show or hide the top bar.
        """.trimIndent()
        AlertDialog.Builder(this).setTitle("What the keys do").setMessage(text).setPositiveButton("OK", null).show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("session")?.let { TmuxLink.requestSession(it) }
    }

    override fun toggleTopBar() {
        val show = topBar.visibility != View.VISIBLE
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        Store.showTopBar = show
    }

    // ------------------------------------------------------------------ TerminalViewClient

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeFont(if (scale > 1f) 1 else -1)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        termView.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(termView, 0)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = extraKeys.readCtrl()
    override fun readAltKey() = extraKeys.readAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false

    override fun onEmulatorSet() {
        if (opened) return
        val em = session.emulator ?: return
        opened = true
        TmuxLink.listener = this
        TmuxLink.open(host, session, em.mColumns, em.mRows)
    }

    // ------------------------------------------------------------------ TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) = termView.onScreenUpdated()
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) = paste()
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) = termView.invalidate()
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
