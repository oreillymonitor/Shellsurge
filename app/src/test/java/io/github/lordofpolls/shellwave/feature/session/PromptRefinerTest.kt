package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.core.prefs.AgentCli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `cleanRefinedOutput strips XML wrappers, labels, and quotes while keeping filenames`() {
        assertEquals(
            "Check the error log for database_connector.js.",
            PromptRefiner.cleanRefinedOutput("Output:\n<draft>Check the error log for database_connector.js.</draft>"),
        )
        assertEquals(
            "Fix timeout in api/client.ts",
            PromptRefiner.cleanRefinedOutput("\"Fix timeout in api/client.ts\""),
        )
        assertEquals(
            "Refactor AuthService to retry once on 401.",
            PromptRefiner.cleanRefinedOutput("Original:\num refactor auth service retry 401\n\nRewritten:\nRefactor AuthService to retry once on 401."),
        )
        assertEquals(
            "Run ./scripts/check.sh before merging.",
            PromptRefiner.cleanRefinedOutput("Cleaned: \"\"\"Run ./scripts/check.sh before merging.\"\"\""),
        )
    }

    @Test
    fun `build user prompt helpers emit clean plain text without XML tags or instruction duplication`() {
        val refinePrompt = PromptRefiner.buildRefineUserPrompt("fix the failing test in auth.kt")
        assertTrue(refinePrompt.contains("Original:\nfix the failing test in auth.kt"))
        assertTrue(refinePrompt.endsWith("Rewritten:"))
        assertFalse(refinePrompt.contains("<draft>"))
        assertFalse(refinePrompt.contains("Rewrite the user's"))

        val shellPrompt = PromptRefiner.buildShellCmdUserPrompt("find large log files")
        assertTrue(shellPrompt.contains("Task:\nfind large log files"))
        assertTrue(shellPrompt.endsWith("Command:"))
        assertFalse(shellPrompt.contains("<request>"))
        assertFalse(shellPrompt.contains("Translate the user's"))
    }

    @Test
    fun `isPromptEcho detects instruction echoes while preserving legitimate rewrites`() {
        val userDraft = "uh basically check app/Main.kt and fix the crash"
        assertTrue(
            PromptRefiner.isPromptEcho(
                candidate = PromptRefiner.REFINE_SYSTEM_INSTRUCTION,
                originalInput = userDraft,
                systemInstruction = PromptRefiner.REFINE_SYSTEM_INSTRUCTION,
            ),
        )
        assertTrue(
            PromptRefiner.isPromptEcho(
                candidate = "Rewrite the user's rough draft into a clear, concise prompt for a coding agent. Keep every exact file path, identifier, flag, and slash command.",
                originalInput = userDraft,
                systemInstruction = PromptRefiner.REFINE_SYSTEM_INSTRUCTION,
            ),
        )
        assertTrue(
            PromptRefiner.isPromptEcho(
                candidate = "Rewrite the user's draft into clear, concise English without spoken filler words and preserve every file path verbatim.",
                originalInput = userDraft,
                systemInstruction = PromptRefiner.REFINE_SYSTEM_INSTRUCTION,
            ),
        )
        assertTrue(
            PromptRefiner.isPromptEcho(
                candidate = PromptRefiner.SHELL_CMD_SYSTEM_INSTRUCTION,
                originalInput = "list disk usage by folder",
                systemInstruction = PromptRefiner.SHELL_CMD_SYSTEM_INSTRUCTION,
            ),
        )
        assertFalse(
            PromptRefiner.isPromptEcho(
                candidate = "Check app/Main.kt and fix the crash.",
                originalInput = userDraft,
                systemInstruction = PromptRefiner.REFINE_SYSTEM_INSTRUCTION,
            ),
        )
        assertFalse(
            PromptRefiner.isPromptEcho(
                candidate = "du -sh */ | sort -h",
                originalInput = "list disk usage by folder",
                systemInstruction = PromptRefiner.SHELL_CMD_SYSTEM_INSTRUCTION,
            ),
        )
    }

    @Test
    fun `isPromptEcho allows output when user draft intentionally mentions filler words or slash commands`() {
        val draftWithTerms = "Please remove spoken filler words and check our /plan slash command handler."
        assertFalse(
            PromptRefiner.isPromptEcho(
                candidate = "Remove spoken filler words and verify the /plan slash command handler.",
                originalInput = draftWithTerms,
                systemInstruction = PromptRefiner.REFINE_SYSTEM_INSTRUCTION,
            ),
        )
    }

    @Test
    fun `system instructions contain no hardcoded repo filenames or example bleed`() {
        val prompts = listOf(
            PromptRefiner.REFINE_SYSTEM_INSTRUCTION,
            PromptRefiner.SHELL_CMD_SYSTEM_INSTRUCTION,
            AgentDigestModel.DIGEST_SYSTEM_INSTRUCTION,
        )
        for (prompt in prompts) {
            assertFalse(prompt.contains("SessionManager"))
            assertFalse(prompt.contains("PromptRefiner"))
            assertFalse(prompt.contains("gradlew"))
            assertFalse(prompt.contains("<facts>"))
            assertFalse(prompt.contains("<turn>"))
            assertFalse(prompt.contains("<draft>"))
            assertFalse(prompt.contains("<request>"))
        }
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
