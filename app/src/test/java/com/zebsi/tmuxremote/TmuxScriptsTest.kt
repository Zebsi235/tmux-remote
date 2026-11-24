package com.zebsi.tmuxremote

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The command texts sent to the host and the parsers for their output. */
class TmuxScriptsTest {

    @Test
    fun pureHelpers() {
        assertEquals(2, TmuxScripts.parsePrefix("C-b"))
        assertEquals(1, TmuxScripts.parsePrefix("C-a"))
        assertEquals(0, TmuxScripts.parsePrefix("C-Space".replace("Space", " ")))
        assertEquals(2, TmuxScripts.parsePrefix("M-x"))
        assertEquals("'it'\\''s'", TmuxScripts.q("it's"))
        assertEquals(listOf("0-overview", "1-fw", "2-hw", "10-x", "13", "zeta"),
            listOf("zeta", "10-x", "13", "2-hw", "0-overview", "1-fw").sortedWith(TmuxScripts.NATURAL))
        val st = TmuxScripts.parseState("S\t\$1\tb\t2\nS\t\$0\ta\t1\nC\t/dev/pts/3\t\$1\nC\t/dev/pts/9\t\$0\nW\t\$1\t0\tzsh\t1\t1\nW\t\$1\t1\tvim\t0\t0\n", "/dev/pts/3")
        assertEquals(listOf("a", "b"), st.sessions.map { it.name })
        assertEquals("b", st.current?.name)
        assertTrue(st.scrolledBack)
    }

    @Test
    fun stateWithTimeActivityAndBell() {
        // Host time and the new fields parse.
        val p = TmuxScripts.parseState("T\t500\nS\t\$1\tb\t1\nW\t\$1\t0\tzsh\t1\t0\t490\t1\n", "")
        assertEquals(500L, p.hostTime)
        assertEquals(490L, p.windows[0].activity)
        assertTrue(p.windows[0].bell)
        assertFalse(p.scrolledBack)
    }

    @Test
    fun stateIgnoresBrokenLines() {
        val p = TmuxScripts.parseState("garbage\nS\tonly-two\nW\t\$1\n\nC\t/dev/pts/1\n", "/dev/pts/1")
        assertTrue(p.sessions.isEmpty())
        assertTrue(p.windows.isEmpty())
        assertEquals(null, p.currentId)
    }

    @Test
    fun publicKeyLine() {
        // 32 zero bytes: the blob is "ssh-ed25519" and the key, each with a 4-byte length.
        val line = TmuxScripts.ed25519PublicKeyLine(ByteArray(32), "me@phone")
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA me@phone", line)
    }

    @Test
    fun namesWithQuotesAndSpacesStayOneArgument() {
        assertTrue(TmuxScripts.attach("it's mine").endsWith("-s 'it'\\''s mine'"))
        assertTrue(TmuxScripts.rename("\$3", " new name ").endsWith("'new name'"))
        assertTrue(TmuxScripts.wrap("echo 'x'").startsWith("exec sh -c '"))
    }

    @Test
    fun scrollIsCappedAndKnowsItsDirection() {
        assertTrue(TmuxScripts.scroll("/dev/pts/1", -5).contains("n=5; d=up;"))
        assertTrue(TmuxScripts.scroll("/dev/pts/1", 100000).contains("n=300; d=down;"))
    }

    /** Every script must at least be valid shell (sh -n parses without running). */
    @Test
    fun scriptsAreValidShell() {
        val tty = "/dev/pts/1"
        val scripts = listOf(
            TmuxScripts.PROBE, TmuxScripts.LIST_NAMES, TmuxScripts.STATE, TmuxScripts.attach("a b"),
            TmuxScripts.switchTo(tty, "\$1"), TmuxScripts.newSession(tty, ""), TmuxScripts.newSession(tty, "x y"),
            TmuxScripts.rename("\$1", "n"), TmuxScripts.kill("\$1"), TmuxScripts.switchThenKill(tty, "\$1", "\$2"),
            TmuxScripts.newWindow(tty), TmuxScripts.cancelCopyMode(tty), TmuxScripts.scroll(tty, -3), TmuxScripts.scroll(tty, 3),
        )
        for (s in scripts) {
            val p = ProcessBuilder("sh", "-n", "-c", s).redirectErrorStream(true).start()
            val out = String(p.inputStream.readBytes())
            assertTrue("sh did not finish", p.waitFor(5, TimeUnit.SECONDS))
            assertEquals("not valid shell: $s\n$out", 0, p.exitValue())
        }
    }
}
