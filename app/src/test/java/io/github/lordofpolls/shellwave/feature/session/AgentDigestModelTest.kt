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
        assertEquals("Approval needed", result.headlineFact)
    }

    @Test
    fun `extractTerminalFacts pulls headline, first error, and touched files`() {
        val buildOutput = """
            > Task :app:compileFossDebugKotlin FAILED
            Diff app/MainActivity.kt (+10/-2)
            e: app/MainActivity.kt:142:9 Unresolved reference 'foo'
            BUILD FAILED in 8s
        """.trimIndent()

        val facts = AgentDigestModel.extractTerminalFacts(buildOutput)
        assertTrue(facts.isError)
        assertEquals("app/MainActivity.kt:142:9 Unresolved reference 'foo'", facts.firstError)
        assertTrue(facts.touchedFiles.contains("app/MainActivity.kt"))
        assertTrue(facts.headlineFact?.contains("BUILD FAILED") == true)

        val fallback = AgentDigestModel.fallbackAnalyze(buildOutput)
        assertEquals(facts.firstError, fallback.firstError)
        assertEquals(facts.touchedFiles, fallback.touchedFiles)
    }

    @Test
    fun `isNanoRefusal catches meta-refusals without false positives on real summaries`() {
        assertTrue(AgentDigestModel.isNanoRefusal("the user provided no facts or turn."))
        assertTrue(AgentDigestModel.isNanoRefusal("No terminal output was provided."))
        assertTrue(AgentDigestModel.isNanoRefusal("I cannot summarize without terminal output."))
        assertTrue(AgentDigestModel.isNanoRefusal("Please provide the text to summarize."))
        assertTrue(AgentDigestModel.isNanoRefusal(""))

        assertFalse(AgentDigestModel.isNanoRefusal("All 12 unit tests passed with no errors."))
        assertFalse(AgentDigestModel.isNanoRefusal("Command produced no output."))
        assertFalse(AgentDigestModel.isNanoRefusal("Build failed in AgentDigestCapsule.kt."))
    }

    @Test
    fun `buildDigestPromptInput uses plain text without XML tags`() {
        val facts = TerminalFacts(
            headlineFact = "BUILD SUCCESSFUL",
            touchedFiles = listOf("AgentDigestCapsule.kt"),
        )
        val prompt = AgentDigestModel.buildDigestPromptInput(facts, "Tests completed cleanly.")
        assertFalse(prompt.contains("<facts>"))
        assertFalse(prompt.contains("</facts>"))
        assertFalse(prompt.contains("<turn>"))
        assertFalse(prompt.contains("</turn>"))
        assertTrue(prompt.contains("Terminal output:"))
        assertTrue(prompt.contains("Tests completed cleanly."))
    }

    @Test
    fun `extractTerminalFacts distinguishes prose file mentions from actual edits`() {
        val proseMention = "Consider updating AgentDigestCapsule.kt next."
        val proseFacts = AgentDigestModel.extractTerminalFacts(proseMention)
        assertTrue(proseFacts.touchedFiles.contains("AgentDigestCapsule.kt"))
        assertEquals(null, proseFacts.headlineFact)

        val actualDiff = "Diff AgentDigestCapsule.kt (+4/-1)"
        val diffFacts = AgentDigestModel.extractTerminalFacts(actualDiff)
        assertTrue(diffFacts.touchedFiles.contains("AgentDigestCapsule.kt"))
        assertEquals("Updated 1 file", diffFacts.headlineFact)
    }

    @Test
    fun `extractNumberedOptions ignores markdown recommendation lists and trailing questions`() {
        val brainstormOutput = """
            ### Top 4 Highest-ROI Picks to Build First
            If you want maximum daily impact with clean fit into your existing codebase:
            1. Prompt Queue — plugs directly into TerminalOutputWatcher and PromptComposerDialog.
            2. Risk-Scored Approval Cards — supercharges your existing approval capsule and pipeline.
            3. Context Pills — eliminates mobile typing annoyance for paths and error snippets.
            4. Dev Server Preview — turns ShellSurge into a full end-to-end mobile studio.

            Want to dive deeper into any of these, or pick 1-2 to design and prototype?
        """.trimIndent()
        assertTrue(AgentDigestModel.extractNumberedOptions(brainstormOutput).isEmpty())

        val bareMarkdownList = """
            Steps completed:
            1. Cleaned output sanitizer
            2. Added regression tests
        """.trimIndent()
        assertTrue(AgentDigestModel.extractNumberedOptions(bareMarkdownList).isEmpty())

        val approvalWithNavFooter = """
            Allow bash tool?
            ❯ 1. Allow once
              2. Allow for session
              3. Deny
            Esc to cancel
        """.trimIndent()
        val actions = AgentDigestModel.extractNumberedOptions(approvalWithNavFooter)
        assertEquals(3, actions.size)
        assertEquals("1", actions[0].sendText)
        assertEquals("2", actions[1].sendText)
        assertEquals("3", actions[2].sendText)
    }
}
