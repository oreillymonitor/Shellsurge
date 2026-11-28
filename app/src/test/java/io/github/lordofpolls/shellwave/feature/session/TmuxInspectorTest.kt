package io.github.lordofpolls.shellwave.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxInspectorTest {

    @Test
    fun `detects missing tmux binary and no active server`() {
        assertEquals(
            TmuxInspectResult.NotInstalled,
            parseTmuxInspectOutput("__NO_TMUX__\n"),
        )
        assertEquals(
            TmuxInspectResult.NoServer,
            parseTmuxInspectOutput("__NO_SERVER__\n"),
        )
    }

    @Test
    fun `returns error when command fails without markers`() {
        val result = parseTmuxInspectOutput(stdout = "", stderr = "permission denied", exitCode = 1)
        assertEquals(TmuxInspectResult.Error("permission denied"), result)
    }

    @Test
    fun `parses sessions, windows with flags, and panes`() {
        val sample = listOf(
            "__SESSIONS__",
            "work\t0",
            "shellsurge\t1",
            "__WINDOWS__",
            "shellsurge\t0\tclaude\t1\t0\t1",
            "shellsurge\t1\ttests\t0\t1\t0",
            "work\t0\tapi\t1\t0\t0",
            "__PANES__",
            "shellsurge\t0\t0\tClaude Code\tnode\t/srv/app\t1",
            "shellsurge\t0\t1\tlogs\ttail\t/srv/app\t0",
            "shellsurge\t1\t0\tgradle\tbash\t/srv/app\t1",
            "work\t0\t0\tserver\tpython\t/srv/api\t1",
        ).joinToString("\n")

        val parsed = parseTmuxInspectOutput(sample) as TmuxInspectResult.Available
        assertEquals(2, parsed.sessions.size)

        // Attached session is sorted first.
        val attachedSession = parsed.sessions[0]
        assertEquals("shellsurge", attachedSession.name)
        assertTrue(attachedSession.attached)
        assertEquals(2, attachedSession.windows.size)

        val win0 = attachedSession.windows[0]
        assertEquals(0, win0.index)
        assertEquals("claude", win0.name)
        assertTrue(win0.active)
        assertFalse(win0.bellFlag)
        assertTrue(win0.activityFlag)
        assertEquals(2, win0.panes.size)
        assertEquals("node", win0.panes[0].currentCommand)
        assertTrue(win0.panes[0].active)
        assertFalse(win0.panes[1].active)

        val win1 = attachedSession.windows[1]
        assertTrue(win1.bellFlag)
        assertFalse(win1.active)

        val detachedSession = parsed.sessions[1]
        assertEquals("work", detachedSession.name)
        assertFalse(detachedSession.attached)
    }

    @Test
    fun `builds posix-quoted tmux commands`() {
        assertEquals(
            "tmux select-window -t 'my session:2'",
            tmuxSelectWindowCommand("my session", 2),
        )
        assertEquals(
            "tmux select-pane -t 'dev:1.3'",
            tmuxSelectPaneCommand("dev", 1, 3),
        )
        assertEquals(
            "tmux rename-window -t 'dev:0' 'build & test'",
            tmuxRenameWindowCommand("dev", 0, "build & test"),
        )
        assertEquals(
            "tmux new-window -t 'dev:' -n 'logs'",
            tmuxNewWindowCommand("dev", "logs"),
        )
        assertEquals(
            "tmux new-session -d -s 'api-box'",
            tmuxNewSessionCommand(" api-box "),
        )
    }
}
