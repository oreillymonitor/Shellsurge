package io.github.lordofpolls.shellwave.feature.session

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
