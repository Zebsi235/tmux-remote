package com.zebsi.tmuxremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules behind the tab marks and the "went quiet" notification. */
class ActivityTrackerTest {

    private fun st(now: Long, cur: String?, vararg acts: Pair<String, Long>, bell: String? = null) = TmuxState(
        acts.map { TmuxSession(it.first, it.first, 1) },
        acts.map { TmuxWindow(it.first, 0, "w", true, false, it.second, it.first == bell) },
        cur, now,
    )

    @Test
    fun activityTracker() {
        val t = ActivityTracker(quietSecs = 20, burstPolls = 3, busySecs = 6)
        // First sight: nothing is marked, nothing alerts.
        assertTrue(t.update(st(100, "a", "a" to 100, "b" to 90), "a").isEmpty())
        assertEquals(Mark.NONE, t.mark("b", "a"))
        // b prints in three polls: busy, no alert yet.
        for (now in listOf(105L, 110L, 115L)) assertTrue(t.update(st(now, "a", "a" to 100, "b" to now), "a").isEmpty())
        assertEquals(Mark.BUSY, t.mark("b", "a"))
        assertTrue(t.pending(listOf("b")))
        // b stops: after 20 s one QUIET alert, mark turns to unseen, and only once.
        assertTrue(t.update(st(130, "a", "a" to 100, "b" to 115), "a").isEmpty())
        assertEquals(Mark.UNSEEN, t.mark("b", "a"))
        assertEquals(listOf(Alert("b", "b", AlertKind.QUIET)), t.update(st(135, "a", "a" to 100, "b" to 115), "a"))
        assertTrue(t.update(st(140, "a", "a" to 100, "b" to 115), "a").isEmpty())
        assertFalse(t.pending(listOf("b")))
        // Looking at b clears the mark.
        t.update(st(145, "b", "a" to 100, "b" to 115), "b")
        assertEquals(Mark.NONE, t.mark("b", "a"))
        // A single blip (clock in a status line) never alerts.
        t.update(st(200, "a", "a" to 100, "b" to 200), "a")
        assertTrue(t.update(st(225, "a", "a" to 100, "b" to 200), "a").isEmpty())
        t.update(st(260, "a", "a" to 100, "b" to 260), "a")
        assertTrue(t.update(st(285, "a", "a" to 100, "b" to 260), "a").isEmpty())
        // The session on screen never alerts; in the background (viewing = null) it does.
        for (now in listOf(300L, 305L, 310L)) t.update(st(now, "a", "a" to now, "b" to 260), "a")
        assertTrue(t.update(st(335, "a", "a" to 310, "b" to 260), "a").isEmpty())
        for (now in listOf(400L, 405L, 410L)) t.update(st(now, "a", "a" to now, "b" to 260), null)
        assertEquals(listOf(Alert("a", "a", AlertKind.QUIET)), t.update(st(431, "a", "a" to 410, "b" to 260), null))
        // Bell: once per ring.
        assertEquals(listOf(Alert("b", "b", AlertKind.BELL)), t.update(st(440, "a", "a" to 410, "b" to 260, bell = "b"), "a"))
        assertTrue(t.update(st(445, "a", "a" to 410, "b" to 260, bell = "b"), "a").isEmpty())
    }

    @Test
    fun aSessionThatDisappearsIsForgotten() {
        val t = ActivityTracker()
        t.update(st(100, "a", "a" to 100, "b" to 100), "a")
        t.update(st(103, "a", "a" to 100, "b" to 103), "a")
        assertEquals(Mark.BUSY, t.mark("b", "a"))
        t.update(st(106, "a", "a" to 100), "a")
        assertEquals(Mark.NONE, t.mark("b", "a"))
        assertFalse(t.pending(listOf("b")))
    }

    @Test
    fun aBellMarksTheTabUntilLookedAt() {
        val t = ActivityTracker()
        t.update(st(100, "a", "a" to 50, "b" to 50), "a")
        t.update(st(105, "a", "a" to 50, "b" to 50, bell = "b"), "a")
        assertEquals(Mark.UNSEEN, t.mark("b", "a"))
        t.update(st(110, "b", "a" to 50, "b" to 50), "b")
        assertEquals(Mark.NONE, t.mark("b", "a"))
    }
}
