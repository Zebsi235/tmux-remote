package com.zebsi.tmuxremote

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.trilead.ssh2.crypto.PEMDecoder

class HostsActivity : Activity() {

    private lateinit var list: LinearLayout
    private var keyField: EditText? = null
    private val green = Color.rgb(0x5F, 0xD7, 0x87)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Self-test for debug builds: recognise files/speechtest.wav and write the text to the log.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent.getStringExtra("speechtest")?.let { lang ->
                val t0 = android.os.SystemClock.elapsedRealtime()
                Dictation.transcribe(this, lang, java.io.File(filesDir, "speechtest.wav").inputStream()) {
                    android.util.Log.i("TmuxRemoteSpeech", "$lang (${android.os.SystemClock.elapsedRealtime() - t0} ms): $it")
                }
            }
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(0x10, 0x14, 0x18)) }
        root.addView(TextView(this).apply {
            text = "Tmux Remote"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(20), dp(20), dp(20), dp(12))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(12)) }
        buttons.addView(Button(this).apply { text = "Add host"; setOnClickListener { editHost(Host()) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply { text = "This phone's key"; setOnClickListener { showAppKey() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // A terminal is still open in the background: go back to it.
        val open = TmuxLink.hostId
        if (open != null) connect(open)
    }

    private fun refresh() {
        list.removeAllViews()
        val hosts = Store.hosts()
        if (hosts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No hosts yet.\n\n1. Tap \"This phone's key\" and add the shown line to ~/.ssh/authorized_keys on the host (or use a password or your own key).\n2. Tap \"Add host\"."
                setTextColor(Color.LTGRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(20), dp(20), dp(20), dp(20))
            })
            return
        }
        for (h in hosts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setOnClickListener { connect(h.id) }
                setOnLongClickListener { hostMenu(it, h); true }
            }
            row.addView(TextView(this).apply {
                text = h.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            val how = when (h.auth) { Auth.APP_KEY -> "phone key"; Auth.IMPORTED_KEY -> "imported key"; else -> "password" }
            row.addView(TextView(this).apply {
                text = "${h.user}@${h.host}:${h.port} · $how" + (if (h.lastSession.isNotEmpty()) " · last: ${h.lastSession}" else "")
                setTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            list.addView(row)
        }
        list.addView(TextView(this).apply {
            text = "Tap to connect. Hold for edit or delete."
            setTextColor(Color.DKGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        })
    }

    private fun connect(hostId: String) {
        val other = TmuxLink.hostId
        if (other != null && other != hostId) TmuxLink.disconnect()
        startActivity(Intent(this, TerminalActivity::class.java).putExtra("host", hostId))
    }

    private fun hostMenu(anchor: View, h: Host) {
        PopupMenu(this, anchor).apply {
            menu.add("Edit").setOnMenuItemClickListener { editHost(h); true }
            menu.add("Forget host key").setOnMenuItemClickListener {
                h.hostKey = ""
                Store.saveHost(h)
                Toast.makeText(this@HostsActivity, "The key is asked again on the next connect.", Toast.LENGTH_SHORT).show()
                true
            }
            menu.add("Delete").setOnMenuItemClickListener {
                AlertDialog.Builder(this@HostsActivity)
                    .setMessage("Delete ${h.label}?")
                    .setPositiveButton("Delete") { _, _ -> Store.deleteHost(h.id); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }.show()
    }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
    }

    private fun editHost(h: Host) {
        val isNew = Store.host(h.id) == null
        val name = field("Name (optional)", h.name)
        val addr = field("Host or IP", h.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Port", h.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val user = field("User", h.user)
        val auth = Spinner(this).apply {
            adapter = ArrayAdapter(this@HostsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("This phone's key", "Imported private key", "Password"))
            setSelection(h.auth)
        }
        val password = field(if (h.auth == Auth.PASSWORD && h.secret.isNotEmpty()) "Password (unchanged)" else "Password", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val key = EditText(this).apply {
            hint = if (h.auth == Auth.IMPORTED_KEY && h.secret.isNotEmpty()) "Private key (unchanged). Paste a new one to replace it." else "Paste the private key here, or pick the file"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            maxLines = 5
            gravity = Gravity.TOP
        }
        val pick = Button(this).apply {
            text = "Pick key file"
            setOnClickListener {
                keyField = key
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7)
            }
        }
        val passphrase = field("Key passphrase (if it has one)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val keyHint = TextView(this).apply {
            text = "Add the phone's key to the host first (button \"This phone's key\" on the start screen)."
            setTextColor(Color.GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        fun show() {
            val a = auth.selectedItemPosition
            keyHint.visibility = if (a == Auth.APP_KEY) View.VISIBLE else View.GONE
            password.visibility = if (a == Auth.PASSWORD) View.VISIBLE else View.GONE
            for (v in listOf(key, pick, passphrase)) v.visibility = if (a == Auth.IMPORTED_KEY) View.VISIBLE else View.GONE
        }
        auth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = show()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        show()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            for (v in listOf(name, addr, port, user, auth, keyHint, password, key, pick, passphrase)) addView(v)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Add host" else "Edit host")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = auth.selectedItemPosition
                val err = when {
                    addr.text.isBlank() -> "Host is missing."
                    user.text.isBlank() -> "User is missing."
                    a == Auth.PASSWORD && password.text.isEmpty() && !(h.auth == Auth.PASSWORD && h.secret.isNotEmpty()) -> "Password is missing."
                    a == Auth.IMPORTED_KEY && key.text.isBlank() && !(h.auth == Auth.IMPORTED_KEY && h.secret.isNotEmpty()) -> "Private key is missing."
                    else -> null
                } ?: if (a == Auth.IMPORTED_KEY && key.text.isNotBlank()) checkKey(key.text.toString(), passphrase.text.toString()) else null
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (h.host != addr.text.toString().trim() || h.port != (port.text.toString().toIntOrNull() ?: 22)) h.hostKey = ""
                h.name = name.text.toString().trim()
                h.host = addr.text.toString().trim()
                h.port = port.text.toString().toIntOrNull() ?: 22
                h.user = user.text.toString().trim()
                when (a) {
                    Auth.PASSWORD -> {
                        if (password.text.isNotEmpty()) h.secret = Store.encrypt(password.text.toString())
                        else if (h.auth != Auth.PASSWORD) h.secret = ""
                        h.passphrase = ""
                    }
                    Auth.IMPORTED_KEY -> if (key.text.isNotBlank()) {
                        h.secret = Store.encrypt(key.text.toString().trim() + "\n")
                        h.passphrase = Store.encrypt(passphrase.text.toString())
                    }
                    else -> { h.secret = ""; h.passphrase = "" }
                }
                h.auth = a
                Store.saveHost(h)
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    /** Returns an error text, or null when the key can be read. */
    private fun checkKey(pem: String, passphrase: String): String? = try {
        PEMDecoder.decode((pem.trim() + "\n").toCharArray(), passphrase.ifEmpty { null })
        null
    } catch (e: Exception) {
        "This key cannot be read (${e.message}). It must be a private key file such as id_ed25519 or id_rsa; check the passphrase."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != 7 || resultCode != RESULT_OK || uri == null) return
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { String(it) } ?: ""
            if (text.length > 20_000 || !text.contains("PRIVATE KEY")) {
                Toast.makeText(this, "That file is not a private key.", Toast.LENGTH_LONG).show()
            } else keyField?.setText(text)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read the file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAppKey() {
        val line = try {
            Store.appPublicKeyLine()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not make a key: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val cmd = "echo '$line' >> ~/.ssh/authorized_keys"
        val view = TextView(this).apply {
            text = "Public key of this phone. Add it to ~/.ssh/authorized_keys on each host:\n\n$line"
            setTextIsSelectable(true)
            setTextColor(Color.LTGRAY)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        fun copy(what: String) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ssh key", what))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        AlertDialog.Builder(this)
            .setTitle("This phone's key")
            .setView(view)
            .setPositiveButton("Copy key") { _, _ -> copy(line) }
            .setNeutralButton("Copy as command") { _, _ -> copy(cmd) }
            .setNegativeButton("Close", null)
            .show()
    }
}
