package com.zebsi.tmuxremote

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The shell and tmux command texts the app sends to the host, and the parsers for their output.
 * No Android classes in here, so the test in src/test can run them against a real host.
 */
object TmuxScripts {

    const val MARK = "TMUXREMOTE_TTY="
    private const val PATH_FIX = "PATH=\"\$PATH:/usr/local/bin:/opt/homebrew/bin\"; export PATH; "

    const val PROBE = "command -v tmux >/dev/null 2>&1 || exit 127; tmux show-options -gv prefix 2>/dev/null; exit 0"
    const val LIST_NAMES = "tmux list-sessions -F '#{session_name}' 2>/dev/null"
    const val STATE =
        "printf 'T\\t%s\\n' \"\$(date +%s)\"; " +
            "tmux list-sessions -F 'S\t#{session_id}\t#{session_name}\t#{session_windows}' \\; " +
            "list-clients -F 'C\t#{client_tty}\t#{session_id}' \\; " +
            "list-windows -a -F 'W\t#{session_id}\t#{window_index}\t#{window_name}\t#{window_active}\t#{pane_in_mode}\t#{window_activity}\t#{window_bell_flag}'"

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Run through /bin/sh whatever the login shell is, with common tmux install paths added. */
    fun wrap(script: String) = "exec sh -c " + q(PATH_FIX + script)

    /** The command of the main channel: report the tty, then become the tmux client. */
    fun attach(session: String) = "echo $MARK\$(tty); exec tmux -u new-session -A -s ${q(session)}"

    /** Sets `$sid` to the session our client (by tty) shows right now; stops when it is gone. */
    fun clientSid(tty: String) =
        "t=${q(tty)}; sid=\$(tmux list-clients -F '#{client_tty} #{session_id}' | awk -v t=\"\$t\" '\$1==t{print \$2}'); [ -n \"\$sid\" ] || exit 0; "

    fun switchTo(tty: String, target: String) = "tmux switch-client -c ${q(tty)} -t ${q(target)}"

    fun newSession(tty: String, name: String): String {
        val named = if (name.isBlank()) "" else " -s ${q(name.trim())}"
        return "id=\$(tmux new-session -d -P -F '#{session_id}'$named) && tmux switch-client -c ${q(tty)} -t \"\$id\""
    }

    fun rename(sessionId: String, name: String) = "tmux rename-session -t ${q(sessionId)} ${q(name.trim())}"

    fun kill(sessionId: String) = "tmux kill-session -t ${q(sessionId)}"

    fun switchThenKill(tty: String, otherId: String, sessionId: String) =
        "tmux switch-client -c ${q(tty)} -t ${q(otherId)} \\; kill-session -t ${q(sessionId)}"

    fun newWindow(tty: String) = clientSid(tty) + "tmux new-window -t \"\$sid:\""

    /**
     * Spoken replies. Marks the client's session as "the phone is listening" (the host hook
     * tools/host/tmuxremote_voice.py looks at that mark) and prints the size of the say log,
     * then what was added to it after byte [offset]. A negative offset only asks for the size.
     */
    fun sayPoll(tty: String, offset: Long): String {
        val tail = if (offset < 0) "" else " if [ \"\$n\" -gt $offset ]; then tail -c +${offset + 1} \"\$f\"; fi"
        return clientSid(tty) + "tmux set-option -t \"\$sid\" @tmuxremote_voice \"\$(date +%s)\"; " +
            "f=\"\$HOME/.cache/tmuxremote/say.log\"; n=0; [ -f \"\$f\" ] && n=\$(wc -c < \"\$f\" | tr -d ' '); " +
            "printf 'Z\\t%s\\n' \"\$n\";" + tail
    }

    /** The phone stopped listening to the client's session. */
    fun sayOff(tty: String) = clientSid(tty) + "tmux set-option -t \"\$sid\" -u @tmuxremote_voice"

    class Said(val sessionId: String, val text: String)
    class SayResult(val offset: Long, val said: List<Said>)

    /** Reads the answer of [sayPoll]. Only complete lines count; the offset moves past them. */
    fun parseSay(out: String, offset: Long): SayResult {
        val first = out.substringBefore('\n')
        val size = first.removePrefix("Z\t").trim().toLongOrNull() ?: return SayResult(offset, emptyList())
        if (!first.startsWith("Z\t")) return SayResult(offset, emptyList())
        if (offset < 0 || size < offset) return SayResult(size, emptyList())
        val body = out.substringAfter('\n', "")
        val end = body.lastIndexOf('\n')
        if (end < 0) return SayResult(offset, emptyList())
        val whole = body.substring(0, end + 1)
        val said = whole.lines().mapNotNull { line ->
            val f = line.split('\t', limit = 3)
            if (f.size == 3 && f[2].isNotBlank()) Said(f[1], f[2].trim()) else null
        }
        return SayResult(offset + whole.toByteArray(Charsets.UTF_8).size, said)
    }

    fun cancelCopyMode(tty: String) = clientSid(tty) + "tmux send-keys -t \"\$sid:\" -X cancel"

    /**
     * Programs that draw full-screen, take no mouse events and ignore arrow keys for scrolling,
     * but page with PgUp/PgDn. Matched against the start of tmux's pane_current_command.
     */
    val PAGE_KEY_PROGRAMS = listOf("codex")

    /**
     * Scroll the pane the client looks at by [rowsDown] lines (negative = back in history).
     * Plain pane: tmux copy mode, which ends by itself at the bottom (-e).
     * Full-screen program: wheel events if it asked for the mouse; PgUp/PgDn for the programs in
     * [PAGE_KEY_PROGRAMS] (one page per 60 % of the pane height dragged, the rest is remembered
     * in a pane option between calls); arrow keys for everything else.
     */
    fun scroll(tty: String, rowsDown: Int): String {
        val n = minOf(kotlin.math.abs(rowsDown), 300)
        val d = if (rowsDown < 0) "up" else "down"
        val pagers = PAGE_KEY_PROGRAMS.joinToString("|") { "$it*" }
        return clientSid(tty) + "n=$n; d=$d; " +
            "set -- \$(tmux display-message -p -t \"\$sid:\" '#{pane_in_mode} #{alternate_on} #{mouse_any_flag} #{mouse_sgr_flag} #{pane_height} #{pane_current_command}'); " +
            "if [ \"\$1\" != 1 ] && [ \"\$2\" = 1 ]; then " +
            "if [ \"\$3\" = 1 ] && [ \"\$4\" = 1 ]; then " +
            "if [ \$d = up ]; then b=34; else b=35; fi; " +
            "tmux send-keys -t \"\$sid:\" -N \$n -H 1b 5b 3c 36 \$b 3b 31 3b 31 4d; " +
            "else case \"\$6\" in $pagers) " +
            "step=\$((\$5*6/10)); [ \$step -ge 4 ] || step=4; " +
            "acc=\$(tmux show-options -p -q -v -t \"\$sid:\" @tmuxremote_scroll 2>/dev/null); acc=\${acc:-0}; " +
            "if [ \$d = up ]; then acc=\$((acc-n)); else acc=\$((acc+n)); fi; " +
            "p=\$((acc/step)); acc=\$((acc-p*step)); " +
            "tmux set-option -p -t \"\$sid:\" @tmuxremote_scroll \$acc 2>/dev/null; " +
            "if [ \$p -lt 0 ]; then tmux send-keys -t \"\$sid:\" -N \$((0-p)) PageUp; " +
            "elif [ \$p -gt 0 ]; then tmux send-keys -t \"\$sid:\" -N \$p PageDown; fi ;; " +
            "*) if [ \$d = up ]; then k=Up; else k=Down; fi; tmux send-keys -t \"\$sid:\" -N \$n \$k ;; esac; fi; " +
            "elif [ \$d = up ]; then tmux copy-mode -e -t \"\$sid:\" \\; send-keys -t \"\$sid:\" -X -N \$n scroll-up; " +
            "elif [ \"\$1\" = 1 ]; then tmux send-keys -t \"\$sid:\" -X -N \$n scroll-down; fi"
    }

    fun parseState(out: String, tty: String): TmuxState {
        val sessions = ArrayList<TmuxSession>()
        val windows = ArrayList<TmuxWindow>()
        var current: String? = null
        var hostTime = 0L
        for (line in out.lines()) {
            val f = line.split('\t')
            when (f[0]) {
                "S" -> if (f.size >= 4) sessions.add(TmuxSession(f[1], f[2], f[3].toIntOrNull() ?: 1))
                "C" -> if (f.size >= 3 && f[1] == tty) current = f[2]
                "T" -> if (f.size >= 2) hostTime = f[1].trim().toLongOrNull() ?: 0L
                "W" -> if (f.size >= 6) windows.add(
                    TmuxWindow(f[1], f[2].toIntOrNull() ?: 0, f[3], f[4] == "1", f[5] == "1",
                        f.getOrNull(6)?.toLongOrNull() ?: 0L, f.getOrNull(7) == "1")
                )
            }
        }
        sessions.sortWith(compareBy(NATURAL) { it.name })
        return TmuxState(sessions, windows, current, hostTime)
    }

    /** The byte of a "C-b" style prefix; anything else keeps the tmux default (Ctrl-b). */
    fun parsePrefix(p: String): Int {
        val m = Regex("^C-([a-zA-Z\\[\\]\\\\^_@ ])$").find(p) ?: return 2
        val ch = m.groupValues[1][0]
        return if (ch == ' ' || ch == '@') 0 else ch.uppercaseChar().code and 0x1f
    }

    /** The authorized_keys line for a raw 32-byte ed25519 public key. */
    fun ed25519PublicKeyLine(pub: ByteArray, comment: String): String {
        val blob = ByteArrayOutputStream()
        fun putString(b: ByteArray) {
            blob.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
            blob.write(b)
        }
        putString("ssh-ed25519".toByteArray())
        putString(pub)
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob.toByteArray()) + " " + comment
    }

    /** Sorts "2-hw" before "10-x" and ignores case. */
    val NATURAL = Comparator<String> { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val nx = x.toBigIntegerOrNull()
            val ny = y.toBigIntegerOrNull()
            val cmp = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y, ignoreCase = true)
            if (cmp != 0) return@Comparator cmp
        }
        ra.size - rb.size
    }
}
