package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.core.prefs.AgentCli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRefinerTest {

    @Test
    fun `applyVoiceMacros expands slash commands and formatting phrases`() {
        val spoken = "slash plan review the diff new line bullet point check tests bullet point fix lint"
        val expanded = PromptRefiner.applyVoiceMacros(spoken)
        assertTrue(expanded.contains("/plan"))
        assertTrue(expanded.contains("\n"))
        assertTrue(expanded.contains("\n- "))
    }

    @Test
    fun `applyVoiceMacros adapts slash model to active CLI agent`() {
        assertEquals("/models", PromptRefiner.applyVoiceMacros("slash model", AgentCli.OPENCODE))
        assertEquals("/model", PromptRefiner.applyVoiceMacros("slash model", AgentCli.CLAUDE))
        assertEquals("/model", PromptRefiner.applyVoiceMacros("slash models", AgentCli.AGY))
        assertEquals("/diff", PromptRefiner.applyVoiceMacros("slash diff", AgentCli.CODEX))
        assertEquals("/clear", PromptRefiner.applyVoiceMacros("slash clear", AgentCli.CLAUDE))
        assertEquals("/undo", PromptRefiner.applyVoiceMacros("slash undo", AgentCli.OPENCODE))
    }

    @Test
    fun `stripShellCommandFormatting removes code fences, backticks, and shell prompt prefix`() {
        assertEquals(
            "find . -name '*.log' -mtime +7 -delete",
            PromptRefiner.stripShellCommandFormatting("```bash\n$ find . -name '*.log' -mtime +7 -delete\n```"),
        )
        assertEquals(
            "git status -sb",
            PromptRefiner.stripShellCommandFormatting("`git status -sb`"),
        )
    }

    @Test
    fun `smartClean strips filler words and formats multi-step instructions`() {
        val raw = "hey um can you update the readme and also run the unit tests"
        val cleaned = PromptRefiner.smartClean(raw)
        assertEquals("Update the readme:\n- Run the unit tests", cleaned)
    }

    @Test
    fun `smartClean capitalizes single-step prompts cleanly`() {
        val cleaned = PromptRefiner.smartClean("so basically fix the scrollbar offset")
        assertEquals("Fix the scrollbar offset", cleaned)
    }
}
