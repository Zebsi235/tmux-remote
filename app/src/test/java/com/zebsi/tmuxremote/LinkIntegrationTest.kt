package com.zebsi.tmuxremote

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.crypto.PEMDecoder
import com.trilead.ssh2.crypto.keys.Ed25519KeyPairGenerator
import com.trilead.ssh2.crypto.keys.Ed25519PrivateKey
import com.trilead.ssh2.crypto.keys.Ed25519PublicKey
import java.io.File
import java.security.KeyPair
import java.security.SecureRandom
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Runs the app's real command texts against a real host with tmux.
 * Needs: TMUXREMOTE_TEST_HOST, TMUXREMOTE_TEST_USER, TMUXREMOTE_TEST_KEY (unencrypted ed25519
 * private key file with its .pub next to it, authorized on the host). Skipped without them.
 * It only touches tmux sessions named zz-tmuxremote-*.
 */
class LinkIntegrationTest {

    private fun exec(c: Connection, script: String): Pair<String, Int> {
        val s = c.openSession()
        try {
            s.execCommand(TmuxScripts.wrap(script))
            val out = String(s.stdout.readBytes())
            s.stderr.readBytes()
            s.waitForCondition(ChannelCondition.EXIT_STATUS, 2000)
            return out to (s.exitStatus ?: -1)
        } finally {
            s.close()
        }
    }

    @Test
    fun realHost() {
        val hostName = System.getenv("TMUXREMOTE_TEST_HOST")
        val user = System.getenv("TMUXREMOTE_TEST_USER")
        val keyPath = System.getenv("TMUXREMOTE_TEST_KEY")
        Assume.assumeTrue(!hostName.isNullOrBlank() && !user.isNullOrBlank() && !keyPath.isNullOrBlank())

        // Keys: the imported-key path, and the way the app stores and rebuilds its own key.
        val pem = File(keyPath).readText()
        val kp = PEMDecoder.decode(pem.toCharArray(), null)
        val seed = (kp.private as Ed25519PrivateKey).seed
        val pubBytes = (kp.public as Ed25519PublicKey).abyte
        val rebuilt = KeyPair(Ed25519PublicKey(pubBytes), Ed25519PrivateKey(seed))
        val line = TmuxScripts.ed25519PublicKeyLine(pubBytes, "x")
        assertEquals(File("$keyPath.pub").readText().trim().split(" ")[1], line.split(" ")[1])
        val made = Ed25519KeyPairGenerator().apply { initialize(255, SecureRandom()) }.generateKeyPair()
        assertEquals(32, (made.private as Ed25519PrivateKey).seed.size)
        assertEquals(32, (made.public as Ed25519PublicKey).abyte.size)

        var hostKeyAlg = ""
        val trust = ServerHostKeyVerifier { _, _, alg, _ -> hostKeyAlg = alg; true }

        val c2 = Connection(hostName, 22)
        c2.connect(trust, 8000, 15000)
        assertTrue("imported key login", c2.authenticateWithPublicKey(user, pem.toCharArray(), null))
        c2.close()

        val c = Connection(hostName, 22)
        c.connect(trust, 8000, 15000)
        assertTrue("rebuilt key login", c.authenticateWithPublicKey(user, rebuilt))
        println("host key algorithm: $hostKeyAlg")

        val a = "zz-tmuxremote-a"
        val b = "zz-tmuxremote-b"
        try {
            val probe = exec(c, TmuxScripts.PROBE)
            assertEquals(0, probe.second)
            println("prefix: '${probe.first.trim()}' -> ${TmuxScripts.parsePrefix(probe.first.trim())}")

            exec(c, "tmux kill-session -t =$a 2>/dev/null; tmux kill-session -t =$b 2>/dev/null; " +
                "tmux new-session -d -s $a -x 80 -y 24 'seq 1 300; exec sh' && tmux new-session -d -s $b")
            assertTrue(exec(c, TmuxScripts.LIST_NAMES).first.lines().containsAll(listOf(a, b)))

            // Main channel, as the app opens it.
            val main = c.openSession()
            main.requestPTY("xterm-256color", 80, 24, 0, 0, null)
            main.execCommand(TmuxScripts.wrap(TmuxScripts.attach(a)))
            val seen = StringBuffer()
            var ended = false
            val reader = thread {
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = main.stdout.read(buf)
                        if (n < 0) break
                        seen.append(String(buf, 0, n, Charsets.ISO_8859_1))
                    }
                } catch (e: Exception) {
                }
                ended = true
            }
            fun waitFor(what: String, cond: () -> Boolean) {
                val until = System.currentTimeMillis() + 6000
                while (!cond()) {
                    assertTrue("timeout: $what", System.currentTimeMillis() < until)
                    Thread.sleep(50)
                }
            }
            waitFor("tty line") { seen.indexOf(TmuxScripts.MARK).let { it >= 0 && seen.indexOf("\n", it) > 0 } }
            val i = seen.indexOf(TmuxScripts.MARK)
            val tty = seen.substring(i + TmuxScripts.MARK.length, seen.indexOf("\n", i)).trim()
            println("tty: $tty, marker at byte $i")
            assertTrue(tty.startsWith("/dev/"))

            fun state() = TmuxScripts.parseState(exec(c, TmuxScripts.STATE).first, tty)
            fun pane(fmt: String) = exec(c, TmuxScripts.clientSid(tty) + "tmux display-message -p -t \"\$sid:\" '$fmt'").first.trim()

            waitFor("attached to a") { state().current?.name == a }
            val idA = state().sessions.first { it.name == a }.id
            val idB = state().sessions.first { it.name == b }.id

            // Switch sessions.
            assertEquals(0, exec(c, TmuxScripts.switchTo(tty, idB)).second)
            assertEquals(b, state().current?.name)
            assertEquals(0, exec(c, TmuxScripts.switchTo(tty, idA)).second)
            assertEquals(a, state().current?.name)

            // Scroll back and forth in a plain pane.
            assertFalse(state().scrolledBack)
            exec(c, TmuxScripts.scroll(tty, -5))
            assertEquals("1 5", pane("#{pane_in_mode} #{scroll_position}"))
            assertTrue(state().scrolledBack)
            exec(c, TmuxScripts.scroll(tty, 2))
            assertEquals("1 3", pane("#{pane_in_mode} #{scroll_position}"))
            exec(c, TmuxScripts.scroll(tty, 10))
            assertEquals("0", pane("#{pane_in_mode}"))
            exec(c, TmuxScripts.scroll(tty, 4)) // down at the bottom: nothing happens
            assertEquals("0", pane("#{pane_in_mode}"))
            exec(c, TmuxScripts.scroll(tty, -3))
            exec(c, TmuxScripts.cancelCopyMode(tty))
            assertEquals("0", pane("#{pane_in_mode}"))

            // Full-screen program: no copy mode, keys go to the program.
            exec(c, "tmux send-keys -t ${TmuxScripts.q("$idA:")} 'seq 1 500 | less' Enter")
            waitFor("less on the alternate screen") { pane("#{alternate_on}") == "1" }
            exec(c, TmuxScripts.scroll(tty, 7))
            assertEquals("0 1", pane("#{pane_in_mode} #{alternate_on}"))
            Thread.sleep(300)
            val shot = exec(c, "tmux capture-pane -p -t ${TmuxScripts.q("$idA:")}").first.lines().first().trim()
            assertEquals("less moved 7 lines", "8", shot)
            exec(c, "tmux send-keys -t ${TmuxScripts.q("$idA:")} q")

            // New, rename, close.
            assertEquals(0, exec(c, TmuxScripts.newSession(tty, "zz-tmuxremote-c")).second)
            assertEquals("zz-tmuxremote-c", state().current?.name)
            val idC = state().currentId!!
            assertEquals(0, exec(c, TmuxScripts.rename(idC, "zz-tmuxremote-d")).second)
            assertEquals("zz-tmuxremote-d", state().current?.name)
            assertEquals(0, exec(c, TmuxScripts.switchThenKill(tty, idA, idC)).second)
            assertEquals(a, state().current?.name)
            assertFalse(state().sessions.any { it.id == idC })
            assertEquals(0, exec(c, TmuxScripts.newWindow(tty)).second)
            assertEquals(2, state().windows.count { it.sessionId == idA })

            // Host time and per-window output time come through.
            val timed = state()
            assertTrue("host time", timed.hostTime > 1_700_000_000L)
            assertTrue("window activity", timed.windows.first { it.sessionId == idA }.activity > 1_700_000_000L)

            // Resize reaches tmux.
            main.resizePTY(100, 30, 0, 0)
            waitFor("resize") { exec(c, "tmux list-clients -F '#{client_tty} #{client_width}x#{client_height}'").first.contains("$tty 100x30") }

            // Typing reaches the pane.
            main.stdin.write("echo typed-ok\r".toByteArray())
            main.stdin.flush()
            waitFor("typed text") { seen.contains("typed-ok") }

            // The last attached session closes: the main channel ends and says so.
            exec(c, TmuxScripts.kill(idB))
            exec(c, TmuxScripts.kill(idA))
            waitFor("main channel end") { ended }
            reader.join(1000)
            assertNotNull(seen)
            println("tail: " + seen.takeLast(40).toString().replace("\u001b", "ESC").trim())
            assertTrue(seen.takeLast(300).contains("[exited]"))
        } finally {
            runCatching { exec(c, "for s in a b c d; do tmux kill-session -t =zz-tmuxremote-\$s 2>/dev/null; done; true") }
            c.close()
        }
    }
}
