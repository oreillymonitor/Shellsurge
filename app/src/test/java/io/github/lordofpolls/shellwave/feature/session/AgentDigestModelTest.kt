package io.github.lordofpolls.shellwave.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDigestModelTest {

    @Test
    fun `extractStructuredResult strips markdown and conversational preambles`() {
        val raw = """
            Sure, here's what happened:
            **All unit tests passed** and `MainActivity.kt` compiled cleanly.
        """.trimIndent()

        val result = AgentDigestModel.extractStructuredResult(raw)
        assertFalse(result.isError)
        assertFalse(result.hasQuestion)
        assertFalse(result.spokenSummary.startsWith("Sure"))
        assertTrue(result.spokenSummary.contains("All unit tests passed"))
        assertFalse(result.spokenSummary.contains("**"))
    }

    @Test
    fun `extractStructuredResult detects yes-no questions and errors`() {
        val questionResult = AgentDigestModel.extractStructuredResult(
            "Updated config files. Apply these changes? [y/N]",
        )
        assertTrue(questionResult.hasQuestion)
        assertEquals(listOf("Yes", "No"), questionResult.quickOptions)

        val errorResult = AgentDigestModel.extractStructuredResult(
            "Build failed with 2 compilation errors in SessionsScreen.",
        )
        assertTrue(errorResult.isError)
    }

    @Test
    fun `fallbackAnalyze handles idle, confirmation prompt, failure, and success`() {
        val idle = AgentDigestModel.fallbackAnalyze("   \n  ")
        assertFalse(idle.hasQuestion)
        assertTrue(idle.spokenSummary.contains("idle", ignoreCase = true))

        val prompt = AgentDigestModel.fallbackAnalyze("Applying patch to app/build.gradle.kts\nProceed with changes? [y/N]")
        assertTrue(prompt.hasQuestion)
        assertEquals(listOf("Yes", "No"), prompt.quickOptions)

        val failure = AgentDigestModel.fallbackAnalyze("Compiling :app\nFAILURE: Build failed with an exception.")
        assertTrue(failure.isError)
        assertTrue(failure.spokenSummary.contains("failed", ignoreCase = true))

        val success = AgentDigestModel.fallbackAnalyze("Running checks\nBUILD SUCCESSFUL in 4s")
        assertFalse(success.isError)
        assertTrue(success.spokenSummary.contains("successfully", ignoreCase = true))
    }

    @Test
    fun `extractNumberedOptions detects Claude and generic numbered approval menus`() {
        val claudeMenu = """
            Allow bash command `./gradlew test`?
            ❯ 1. Allow once
              2. Allow for this session
              3. Deny and tell Claude what to do instead
        """.trimIndent()

        val claudeActions = AgentDigestModel.extractNumberedOptions(claudeMenu)
        assertEquals(3, claudeActions.size)
        assertEquals("1", claudeActions[0].sendText)
        assertEquals("2", claudeActions[1].sendText)
        assertEquals("3", claudeActions[2].sendText)
        assertTrue(claudeActions[0].label.startsWith("1: Allow once"))

        val parenAndBrackets = """
            Select deployment target:
            [1] staging
            [2] production
        """.trimIndent()
        val bracketActions = AgentDigestModel.extractNumberedOptions(parenAndBrackets)
        assertEquals(2, bracketActions.size)
        assertEquals("1", bracketActions[0].sendText)
        assertEquals("2", bracketActions[1].sendText)

        val singleItemProse = """
            Build completed:
            1. Check the release notes.
            Done.
        """.trimIndent()
        assertTrue(AgentDigestModel.extractNumberedOptions(singleItemProse).isEmpty())
    }

    @Test
    fun `fallbackAnalyze promotes numbered menus to quickActions`() {
        val menuOutput = """
            Edit app/build.gradle.kts?
            1) Yes, allow this edit
            2) Yes, allow all edits
            3) No, cancel
        """.trimIndent()

        val result = AgentDigestModel.fallbackAnalyze(menuOutput)
        assertTrue(result.hasQuestion)
        assertEquals(3, result.quickActions.size)
        assertEquals("1", result.quickActions[0].sendText)
        assertEquals("3", result.quickActions[2].sendText)
    }
}
