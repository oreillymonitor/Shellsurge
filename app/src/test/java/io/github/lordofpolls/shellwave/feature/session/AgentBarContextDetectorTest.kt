package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.terminal.AgentBarChoice
import io.github.lordofpolls.shellwave.terminal.AgentBarContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBarContextDetectorTest {

    @Test
    fun `blank and idle shell prompt return Default`() {
        assertEquals(AgentBarContext.Default, detectAgentBarContext(""))
        assertEquals(AgentBarContext.Default, detectAgentBarContext("   \n  "))
        assertEquals(
            AgentBarContext.Default,
            detectAgentBarContext("user@server:~/repo$ git status\nnothing to commit\nuser@server:~/repo$ "),
        )
    }

    @Test
    fun `claude numbered approval menu returns NumberedChoices`() {
        val output = """
            Allow tool Bash(git diff)?
            ❯ 1. Yes
              2. Yes, allow all edits in this session
              3. No
            Esc to cancel
        """.trimIndent()

        val result = detectAgentBarContext(output)
        assertTrue(result is AgentBarContext.NumberedChoices)
        val choices = (result as AgentBarContext.NumberedChoices).choices
        assertEquals(
            listOf(
                AgentBarChoice("1: Yes", "1"),
                AgentBarChoice("2: Yes, allow all edits…", "2"),
                AgentBarChoice("3: No", "3"),
            ),
            choices,
        )
    }

    @Test
    fun `markdown prose numbered list does not trigger NumberedChoices`() {
        val prose = """
            Here are the steps I found in the codebase:
            1. First we parse the input buffer cleanly — then normalize whitespace.
            2. Next we run the verification suite to confirm behavior.
            user@server:~/repo$
        """.trimIndent()

        assertEquals(AgentBarContext.Default, detectAgentBarContext(prose))
    }

    @Test
    fun `yes no prompt variants detect defaultYes accurately`() {
        assertEquals(
            AgentBarContext.YesNoPrompt(defaultYes = true),
            detectAgentBarContext("Do you want to continue? [Y/n]"),
        )
        assertEquals(
            AgentBarContext.YesNoPrompt(defaultYes = false),
            detectAgentBarContext("Overwrite existing config [y/N]?"),
        )
        assertEquals(
            AgentBarContext.YesNoPrompt(defaultYes = null),
            detectAgentBarContext("Proceed with deployment (y/n)?"),
        )
    }

    @Test
    fun `password and passphrase prompts return PasswordPrompt`() {
        assertEquals(
            AgentBarContext.PasswordPrompt,
            detectAgentBarContext("[sudo] password for morningbus:"),
        )
        assertEquals(
            AgentBarContext.PasswordPrompt,
            detectAgentBarContext("Enter passphrase for key '/home/u/.ssh/id_ed25519':"),
        )
    }

    @Test
    fun `spinner or interrupt hint returns Running`() {
        assertEquals(
            AgentBarContext.Running,
            detectAgentBarContext("Analyzing repository\n⠙\nesc to interrupt"),
        )
        assertEquals(
            AgentBarContext.Running,
            detectAgentBarContext("Thinking…"),
        )
    }

    @Test
    fun `numbered choices take precedence over running marker when both present`() {
        val output = """
            Thinking...
            Approve editing MainActivity.kt?
            ❯ 1. Yes
              2. No
        """.trimIndent()

        val result = detectAgentBarContext(output)
        assertTrue(result is AgentBarContext.NumberedChoices)
        assertEquals(listOf("1", "2"), (result as AgentBarContext.NumberedChoices).choices.map { it.sendText })
    }
}
