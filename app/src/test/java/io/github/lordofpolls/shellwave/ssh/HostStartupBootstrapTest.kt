package io.github.lordofpolls.shellwave.ssh

import io.github.lordofpolls.shellwave.core.prefs.HostAutoLaunchPreset
import io.github.lordofpolls.shellwave.core.prefs.HostStartupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStartupBootstrapTest {

    @Test
    fun `quoteStartupDirectory expands home tilde and quotes paths safely`() {
        assertEquals("\"\$HOME\"", quoteStartupDirectory("~"))
        assertEquals("\"\$HOME\"/'projects/app'", quoteStartupDirectory("~/projects/app"))
        assertEquals("\"\$HOME\"/'My Projects/it'\\''s'", quoteStartupDirectory("~/My Projects/it's"))
        assertEquals("'/srv/app'", quoteStartupDirectory("/srv/app"))
        assertNull(quoteStartupDirectory(""))
        assertNull(quoteStartupDirectory("   "))
    }

    @Test
    fun `buildStartupBootstrap handles plain shell combinations`() {
        assertNull(buildStartupBootstrap(resilientSession = false, startupDirectory = null, autoLaunchCommand = null))
        assertEquals(
            "cd \"\$HOME\"/'app'",
            buildStartupBootstrap(resilientSession = false, startupDirectory = "~/app", autoLaunchCommand = null),
        )
        assertEquals(
            "claude -c",
            buildStartupBootstrap(resilientSession = false, startupDirectory = null, autoLaunchCommand = "claude -c"),
        )
        assertEquals(
            "cd \"\$HOME\"/'app' && claude -c",
            buildStartupBootstrap(resilientSession = false, startupDirectory = "~/app", autoLaunchCommand = "claude -c"),
        )
    }

    @Test
    fun `buildStartupBootstrap handles resilient tmux sessions without re-running on attach`() {
        assertEquals(
            RESILIENT_SESSION_BOOTSTRAP,
            buildStartupBootstrap(resilientSession = true, startupDirectory = null, autoLaunchCommand = null),
        )

        val dirOnly = buildStartupBootstrap(resilientSession = true, startupDirectory = "~/app", autoLaunchCommand = null)!!
        assertTrue(dirOnly.contains("tmux has-session -t shellsurge 2>/dev/null && ") || dirOnly.contains("if tmux has-session -t shellsurge 2>/dev/null; then exec tmux attach -t shellsurge;"))
        assertTrue(dirOnly.contains("cd \"\$HOME\"/'app' && exec tmux new -s shellsurge"))
        assertTrue(dirOnly.contains("staying in a plain shell"))

        val dirAndLaunch = buildStartupBootstrap(resilientSession = true, startupDirectory = "~/app", autoLaunchCommand = "claude")!!
        assertTrue(dirAndLaunch.contains("if tmux has-session -t shellsurge 2>/dev/null; then exec tmux attach -t shellsurge;"))
        assertTrue(dirAndLaunch.contains("tmux new -d -s shellsurge"))
        assertTrue(dirAndLaunch.contains("tmux send-keys -t shellsurge -l 'claude'"))
        assertTrue(dirAndLaunch.contains("tmux send-keys -t shellsurge C-m"))
        assertTrue(dirAndLaunch.contains("exec tmux attach -t shellsurge"))
        assertTrue(dirAndLaunch.contains("cd \"\$HOME\"/'app' && claude"))
    }

    @Test
    fun `HostStartupConfig resolves preset and custom launch commands`() {
        assertNull(HostStartupConfig().resolvedLaunchCommand)
        assertTrue(HostStartupConfig().isEmpty)

        val claude = HostStartupConfig(startupDirectory = "~/repo", autoLaunchPreset = HostAutoLaunchPreset.CLAUDE)
        assertEquals("claude", claude.resolvedLaunchCommand)
        assertFalse(claude.isEmpty)

        val customBlank = HostStartupConfig(autoLaunchPreset = HostAutoLaunchPreset.CUSTOM, customCommand = "   ")
        assertNull(customBlank.resolvedLaunchCommand)
        assertFalse(customBlank.isEmpty)

        val custom = HostStartupConfig(autoLaunchPreset = HostAutoLaunchPreset.CUSTOM, customCommand = "  htop  ")
        assertEquals("htop", custom.resolvedLaunchCommand)

        assertEquals(HostAutoLaunchPreset.OPENCODE, HostAutoLaunchPreset.fromId("opencode"))
        assertEquals(HostAutoLaunchPreset.NONE, HostAutoLaunchPreset.fromId("unknown-id"))
        assertEquals(HostAutoLaunchPreset.NONE, HostAutoLaunchPreset.fromId(null))
    }
}
