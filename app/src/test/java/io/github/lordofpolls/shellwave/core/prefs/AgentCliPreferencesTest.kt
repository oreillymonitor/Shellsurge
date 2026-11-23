package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCliPreferencesTest {

    private val prefs = FakeSharedPreferences()
    private val context: Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun `defaults to Claude Code and OpenCode when unset`() {
        assertEquals(listOf(AgentCli.CLAUDE, AgentCli.OPENCODE), AgentCliPreferences.getEnabledAgents(context))
        assertTrue(AgentCliPreferences.isAgentEnabled(context, AgentCli.CLAUDE))
        assertTrue(AgentCliPreferences.isAgentEnabled(context, AgentCli.OPENCODE))
        assertFalse(AgentCliPreferences.isAgentEnabled(context, AgentCli.AGY))
        assertEquals(AgentCli.CLAUDE, AgentCliPreferences.getSelectedAgent(context))
    }

    @Test
    fun `enabling and disabling agents preserves enum order and updates fallback selection`() {
        AgentCliPreferences.setAgentEnabled(context, AgentCli.AIDER, true)
        AgentCliPreferences.setAgentEnabled(context, AgentCli.AGY, true)
        assertEquals(
            listOf(AgentCli.CLAUDE, AgentCli.OPENCODE, AgentCli.AIDER, AgentCli.AGY),
            AgentCliPreferences.getEnabledAgents(context),
        )

        AgentCliPreferences.setSelectedAgent(context, AgentCli.CLAUDE)
        assertEquals(AgentCli.CLAUDE, AgentCliPreferences.getSelectedAgent(context))

        // Disabling the currently selected agent falls back to the first remaining enabled agent.
        AgentCliPreferences.setAgentEnabled(context, AgentCli.CLAUDE, false)
        assertEquals(AgentCli.OPENCODE, AgentCliPreferences.getSelectedAgent(context))
    }

    @Test
    fun `disabling every agent persists empty list instead of reverting to defaults`() {
        AgentCliPreferences.DEFAULT_ENABLED.forEach { agent ->
            AgentCliPreferences.setAgentEnabled(context, agent, false)
        }
        assertEquals(emptyList<AgentCli>(), AgentCliPreferences.getEnabledAgents(context))
        assertNull(AgentCliPreferences.getSelectedAgent(context))
    }
}
