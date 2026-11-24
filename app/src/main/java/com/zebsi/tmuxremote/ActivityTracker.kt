package com.zebsi.tmuxremote

/** What a session tab shows next to its name. */
enum class Mark { NONE, BUSY, UNSEEN }

enum class AlertKind { QUIET, BELL, LINK_ENDED }

data class Alert(val sessionId: String, val sessionName: String, val kind: AlertKind)

/**
 * Follows the output of every tmux session from poll to poll (tmux reports the time of the last
 * output per window) and decides which tab gets a mark and when a session "went quiet".
 *
 * A session went quiet when it printed for a while (output seen in [burstPolls] polls) and then
 * nothing for [quietSecs]. Single blips, like a clock in a status line, never count as work.
 * No Android classes in here; src/test covers it.
 */
class ActivityTracker(private val quietSecs: Int = 20, private val burstPolls: Int = 3, private val busySecs: Int = 6) {

    private class Seen(var lastAct: Long, var seenAct: Long) {
        var advances = 0
        var notifiedAct = 0L
        var bellNotified = false
        var bell = false
    }

    private val sessions = HashMap<String, Seen>()
    private var hostTime = 0L

    @Synchronized
    fun clear() = sessions.clear()

    /**
     * Feed one poll. [viewingId] is the session on screen right now, or null when the app is in
     * the background. Returns the alerts that became due with this poll (for all sessions; the
     * caller keeps the ones the user asked for).
     */
    @Synchronized
    fun update(state: TmuxState, viewingId: String?): List<Alert> {
        hostTime = state.hostTime
        val alerts = ArrayList<Alert>()
        sessions.keys.retainAll(state.sessions.map { it.id }.toSet())
        for (s in state.sessions) {
            val wins = state.windows.filter { it.sessionId == s.id }
            val act = wins.maxOfOrNull { it.activity } ?: 0L
            val bell = wins.any { it.bell }
            // A session met for the first time counts as already looked at.
            val st = sessions.getOrPut(s.id) { Seen(act, act).also { it.notifiedAct = act } }
            if (act > st.lastAct) {
                st.advances++
                st.lastAct = act
            }
            st.bell = bell
            if (s.id == viewingId) {
                st.seenAct = act
                st.notifiedAct = act
                st.advances = 0
                st.bellNotified = bell
                continue
            }
            val quiet = hostTime - act >= quietSecs
            if (quiet) {
                if (st.advances >= burstPolls && act > st.notifiedAct) {
                    alerts.add(Alert(s.id, s.name, AlertKind.QUIET))
                    st.notifiedAct = act
                }
                st.advances = 0
            }
            if (bell && !st.bellNotified) alerts.add(Alert(s.id, s.name, AlertKind.BELL))
            st.bellNotified = bell
        }
        return alerts
    }

    @Synchronized
    fun mark(sessionId: String, viewingId: String?): Mark {
        val st = sessions[sessionId] ?: return Mark.NONE
        if (sessionId == viewingId) return Mark.NONE
        return when {
            hostTime - st.lastAct < busySecs -> Mark.BUSY
            st.lastAct > st.seenAct || st.bell -> Mark.UNSEEN
            else -> Mark.NONE
        }
    }

    /** True while one of these sessions is printing or has a burst that is not reported yet. */
    @Synchronized
    fun pending(sessionIds: Collection<String>): Boolean = sessionIds.any { id ->
        val st = sessions[id] ?: return@any false
        st.advances > 0 || hostTime - st.lastAct < quietSecs
    }
}
