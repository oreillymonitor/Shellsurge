package io.github.lordofpolls.shellwave.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputWatcherTest {

    private data class RecordedAlert(
        val kind: OutputPromptKind,
        val title: String,
        val body: String,
    )

    @Test
    fun `containsCommandSubmit detects CR and LF`() {
        assertTrue(containsCommandSubmit("\r".toByteArray()))
        assertTrue(containsCommandSubmit("ls -la\n".toByteArray()))
        assertFalse(containsCommandSubmit("ls -la".toByteArray()))
    }

    @Test
    fun `extractOutputSnippet strips prompts and dividers and caps length`() {
        val tail = """
            Compiling module...
            ────────────────────
            All 42 tests passed cleanly
            user@dev:~/repo$
        """.trimIndent()

        assertEquals("Compiling module...\nAll 42 tests passed cleanly", extractOutputSnippet(tail))

        val longTail = "A".repeat(300)
        val capped = extractOutputSnippet(longTail, maxChars = 40)
        assertEquals(40, capped.length)
        assertTrue(capped.endsWith("…"))

        assertEquals("Command finished.", extractOutputSnippet("user@dev:~$"))
    }

    @Test
    fun `classifyPromptKind limits alerts strictly to RESPONSE_NEEDED or COMPLETED`() {
        assertEquals(
            OutputPromptKind.RESPONSE_NEEDED,
            classifyPromptKind("Apply these changes? [y/N]"),
        )
        assertEquals(
            OutputPromptKind.RESPONSE_NEEDED,
            classifyPromptKind("Allow running bash command?"),
        )
        assertEquals(
            OutputPromptKind.COMPLETED,
            classifyPromptKind("BUILD SUCCESSFUL in 8s\nuser@dev:~$"),
        )
        assertEquals(
            OutputPromptKind.COMPLETED,
            classifyPromptKind("BUILD FAILED in 12s"),
        )
        assertEquals(
            OutputPromptKind.COMPLETED,
            classifyPromptKind("total 24\ndrwxr-xr-x 2 user user 4096\nuser@dev:~$"),
        )
        // Intermediate streaming output with no prompt or completion marker must return null.
        assertNull(
            classifyPromptKind("Streaming response paragraph 1...\nStill writing code block"),
        )
        // Active thinking / in-progress indicator must return null.
        assertNull(
            classifyPromptKind("Analyzing repository\nThinking... (esc to interrupt)"),
        )
    }

    @Test
    fun `suppresses quick empty enter echo`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var clock = 1_000L
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { "user@dev:~$" },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
                nowMs = { clock },
            )

            watcher.onUserWrite("\r".toByteArray())
            clock += 20L
            watcher.onRawOutput("\r\nuser@dev:~$ ".toByteArray())
            delay(120L)

            assertTrue(alerts.isEmpty())
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `notifies only once across multi-chunk output and subsequent TUI redraws`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var currentTail = "Compiling module 1 of 5...\nThinking..."
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { currentTail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onUserWrite("./gradlew test\r".toByteArray())
            // Chunk 1: intermediate output (still thinking/running) -> must NOT notify on pause
            watcher.onRawOutput("Compiling module 1 of 5...\nThinking...\r\n".toByteArray())
            delay(100L)
            assertTrue(alerts.isEmpty())

            // Chunk 2: command completes and returns to prompt -> notifies once
            currentTail = "Running unit tests across 14 suites...\nBUILD SUCCESSFUL in 4s\nuser@dev:~$"
            watcher.onRawOutput("Running unit tests across 14 suites...\r\nBUILD SUCCESSFUL in 4s\r\nuser@dev:~$ ".toByteArray())
            delay(100L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.COMPLETED, alerts[0].kind)
            assertEquals("dev · Completed", alerts[0].title)

            // Chunks 3, 4, 5: TUI / tmux status bar redraws of the same completed screen -> 0 extra alerts
            repeat(4) {
                watcher.onRawOutput("status bar redraw clock 14:00:0$it                  \r\n".toByteArray())
                delay(80L)
            }
            assertEquals(1, alerts.size)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deduplicates response needed prompt redraws and re-arms on single-key user answer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var currentTail = "Allow running command 'git status'? [y/n]"
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "agent",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { currentTail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onUserWrite("check git status\r".toByteArray())
            watcher.onRawOutput("Allow running command 'git status'? [y/n]".toByteArray())
            delay(100L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.RESPONSE_NEEDED, alerts[0].kind)
            assertEquals("agent · Response needed", alerts[0].title)

            // TUI redraws while waiting for user -> no duplicate Response needed notifications
            watcher.onRawOutput("Allow running command 'git status'? [y/n]".toByteArray())
            delay(100L)
            assertEquals(1, alerts.size)

            // User taps 'y' (single key without Enter) -> re-arms turn for final completion
            watcher.onUserWrite("y".toByteArray())
            currentTail = "On branch master\nnothing to commit, working tree clean\n❯"
            watcher.onRawOutput("On branch master\r\nnothing to commit, working tree clean\r\n❯ ".toByteArray())
            delay(100L)

            assertEquals(2, alerts.size)
            assertEquals(OutputPromptKind.COMPLETED, alerts[1].kind)
            assertEquals("agent · Completed", alerts[1].title)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `notifies immediately on OSC 133 finish and OSC 9 with strictly two prompt titles`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var currentTail = "Build output line\nuser@prod:~$"
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "prod",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { currentTail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onUserWrite("make build\r".toByteArray())
            watcher.onRawOutput("\u001b]133;C\u0007Compiling sources...\r\n\u001b]133;D;0\u0007".toByteArray())
            delay(100L)

            assertEquals(1, alerts.size)
            assertEquals("prod · Completed", alerts[0].title)

            currentTail = "Approve file edit? [y/n]"
            watcher.onRawOutput("\u001b]9;Approve file edit? [y/n]\u0007".toByteArray())
            delay(100L)

            assertEquals(2, alerts.size)
            assertEquals("prod · Response needed", alerts[1].title)
            assertEquals("Approve file edit? [y/n]", alerts[1].body)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `respects disabled flag for both OSC and settle alerts`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { false },
                idleDelayMs = { 40L },
                readTail = { "BUILD SUCCESSFUL\nuser@dev:~$" },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onUserWrite("ls\r".toByteArray())
            watcher.onRawOutput("a very long directory listing with more than forty eight characters\r\n".toByteArray())
            watcher.onRawOutput("\u001b]133;D;1\u0007".toByteArray())
            watcher.onRawOutput("\u001b]9;Ignored\u0007".toByteArray())
            delay(100L)

            assertTrue(alerts.isEmpty())
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `emits AgentTurnState transitions across running, response needed, completed, and cancel`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val states = mutableListOf<AgentTurnState>()
            var currentTail = "Allow edit to MainActivity.kt? [y/N]"
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { currentTail },
                onAlert = { _, _, _ -> },
                onStateChange = { states += it },
            )

            watcher.onUserWrite("claude fix bug\r".toByteArray())
            watcher.onRawOutput("Allow edit to MainActivity.kt? [y/N]\r\n".toByteArray())
            delay(100L)

            assertEquals(listOf(AgentTurnState.RUNNING, AgentTurnState.RESPONSE_NEEDED), states)

            watcher.onUserWrite("y".toByteArray())
            currentTail = "Updated MainActivity.kt cleanly\nuser@dev:~$"
            watcher.onRawOutput("Updated MainActivity.kt cleanly\r\nuser@dev:~$ ".toByteArray())
            delay(100L)

            assertEquals(
                listOf(
                    AgentTurnState.RUNNING,
                    AgentTurnState.RESPONSE_NEEDED,
                    AgentTurnState.RUNNING,
                    AgentTurnState.COMPLETED,
                ),
                states,
            )

            watcher.cancel()
            assertEquals(AgentTurnState.IDLE, states.last())
        } finally {
            scope.cancel()
        }
    }
}
