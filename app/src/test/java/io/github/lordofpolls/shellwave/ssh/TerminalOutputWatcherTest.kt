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

    @Test
    fun `detects TUI conversational questions above box chrome and selects smart quick actions`() {
        val tuiIdleScreen = """
            Would you like me to cut signed release 1.13.2 now with the upgraded digest pipeline?
            ╭────────────────────────────────────────────────────────╮
            │ > Type your message...                                 │
            ╰────────────────────────────────────────────────────────╯
            ? for shortcuts · model: gemini-3-pro
            [agy] 0:bash*                                      11:22
        """.trimIndent()

        // An idle TUI chat input box means the turn finished cleanly rather than blocking on a modal.
        assertEquals(OutputPromptKind.COMPLETED, classifyPromptKind(tuiIdleScreen))
        assertTrue(extractNotificationQuickActions(tuiIdleScreen, OutputPromptKind.COMPLETED).isEmpty())

        val snippet = extractOutputSnippet(tuiIdleScreen, maxChars = 320)
        assertTrue(snippet.contains("cut signed release 1.13.2"))
        assertFalse(snippet.contains("Type your message"))
        assertFalse(snippet.contains("? for shortcuts"))

        // Without an idle prompt box, explicit confirmation phrasing still triggers RESPONSE_NEEDED.
        val convPrompt = "Would you like me to cut signed release 1.13.2 now?"
        assertEquals(OutputPromptKind.RESPONSE_NEEDED, classifyPromptKind(convPrompt))
        val convActions = extractNotificationQuickActions(convPrompt, OutputPromptKind.RESPONSE_NEEDED)
        assertEquals(
            listOf(
                NotificationQuickAction("Yes", "yes"),
                NotificationQuickAction("No", "no"),
            ),
            convActions,
        )

        val ynActions = extractNotificationQuickActions(
            "Proceed with push? [y/N]",
            OutputPromptKind.RESPONSE_NEEDED,
        )
        assertEquals(
            listOf(
                NotificationQuickAction("Yes", "y"),
                NotificationQuickAction("No", "n"),
            ),
            ynActions,
        )

        val menuActions = extractNotificationQuickActions(
            "Allow tool?\n❯ 1. Allow once\n  2. Allow for session\n  3. Deny",
            OutputPromptKind.RESPONSE_NEEDED,
        )
        assertEquals(2, menuActions.size)
        assertEquals("1", menuActions[0].sendText)
        assertEquals("2", menuActions[1].sendText)
    }

    @Test
    fun `passively notifies when backgrounded agent output arrives without a fresh Enter press`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var currentTail = "CI finished cleanly.\nWould you like me to cut 1.13.2 now?"
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { currentTail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            // Simulate a background-resumed agent printing a new turn without any user write.
            watcher.onRawOutput("CI finished cleanly.\r\nWould you like me to cut 1.13.2 now?\r\n".toByteArray())
            delay(120L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.RESPONSE_NEEDED, alerts[0].kind)
            assertEquals("dev · Response needed", alerts[0].title)
            assertTrue(alerts[0].body.contains("cut 1.13.2"))

            // Subsequent identical TUI redraws must not fire duplicate alerts.
            watcher.onRawOutput("CI finished cleanly.\r\nWould you like me to cut 1.13.2 now?\r\n".toByteArray())
            delay(120L)
            assertEquals(1, alerts.size)

            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `tiny tmux clock ticks do not starve the idle settle notification`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var clock = 1_000L
            val currentTail = "Patch applied cleanly.\nWould you like me to run the release?"
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 80L },
                readTail = { currentTail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
                nowMs = { clock },
            )

            watcher.onUserWrite("proceed\r".toByteArray())
            clock += 2_000L
            val substantialPayload = "Patch applied cleanly and all 570 unit tests passed cleanly.\r\nWould you like me to run the release?"
            watcher.onRawOutput(substantialPayload.toByteArray())

            // Periodic 10-byte tmux status bar clock ticks every 30ms should not reset the 80ms timer.
            repeat(4) { tick ->
                delay(30L)
                watcher.onRawOutput("\u001b[24;75H11:22:0$tick".toByteArray())
            }
            delay(60L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.RESPONSE_NEEDED, alerts[0].kind)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `detects plain agent prose above TUI input box without question or completion keyword`() {
        val agentTui = """
            I updated the retry logic in NetworkMonitor.kt to handle DNS timeouts cleanly.
            ╭────────────────────────────────────────────────────────╮
            │ ❯ Try "run unit tests"                                 │
            ╰────────────────────────────────────────────────────────╯
            ? for shortcuts · shift+tab to accept edits
            [claude] 0:bash*                                   19:42
        """.trimIndent()

        assertEquals(OutputPromptKind.COMPLETED, classifyPromptKind(agentTui))
        val snippet = extractOutputSnippet(agentTui)
        assertTrue(snippet.contains("NetworkMonitor.kt"))
    }

    @Test
    fun `full 70-byte tmux status bar redraws do not starve idle settle notification`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var clock = 1_000L
            val currentTail = """
                Updated PromptRefiner.kt and added three regression tests.
                [0] 0:zsh*  "workstation" 19:42 24-Nov-26 · CPU 12% · MEM 4.2G
            """.trimIndent()
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 80L },
                readTail = { currentTail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
                nowMs = { clock },
            )

            watcher.onUserWrite("please fix the bug\r".toByteArray())
            clock += 2_000L
            watcher.onRawOutput("Updated PromptRefiner.kt and added three regression tests.\r\n".toByteArray())

            // Full 68-byte tmux status bar redraws every 30ms would reset a naive byte/timer check.
            repeat(4) { tick ->
                delay(30L)
                watcher.onRawOutput(
                    "\u001b[40;1H[0] 0:zsh*  \"workstation\" 19:42:0$tick 24-Nov-26 · CPU 12% · MEM 4.2G".toByteArray(),
                )
            }
            delay(80L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.COMPLETED, alerts[0].kind)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `notifies again on consecutive turns ending with identical output`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var clock = 1_000L
            val currentTail = "BUILD SUCCESSFUL in 4s\nuser@dev:~$"
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { currentTail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
                nowMs = { clock },
            )

            watcher.onUserWrite("./gradlew test\r".toByteArray())
            clock += 2_000L
            watcher.onRawOutput("BUILD SUCCESSFUL in 4s\r\nuser@dev:~$ ".toByteArray())
            delay(100L)
            assertEquals(1, alerts.size)

            // User submits another command whose final screen looks identical.
            watcher.onUserWrite("./gradlew test\r".toByteArray())
            clock += 2_000L
            watcher.onRawOutput("BUILD SUCCESSFUL in 4s\r\nuser@dev:~$ ".toByteArray())
            delay(100L)
            assertEquals(2, alerts.size)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `short passive approval prompt triggers notification without fresh Enter press`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "agent",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { "Allow bash? [y/N]" },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onRawOutput("Allow bash? [y/N]".toByteArray())
            delay(100L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.RESPONSE_NEEDED, alerts[0].kind)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `settled plain prose output and custom starship prompt both classify as COMPLETED`() {
        val starship = "Deployed service to staging.\n~/projects/app main ❯"
        assertEquals(OutputPromptKind.COMPLETED, classifyPromptKind(starship))

        val plainProse = "All migrations applied and schemas verified."
        assertNull(classifyPromptKind(plainProse, settledAfterIdle = false))
        assertEquals(
            OutputPromptKind.COMPLETED,
            classifyPromptKind(plainProse, settledAfterIdle = true),
        )
    }

    @Test
    fun `numbered markdown list above idle TUI input box classifies as COMPLETED and never loops`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var clockStr = "21:57"
            fun buildScreen(clock: String) = """
                ### Top 4 Highest-ROI Picks to Build First
                1. **Prompt Queue** — dispatches follow-ups once idle.
                2. **Risk-Scored Approval Cards** — color-codes bash tools.

                Want to dive deeper into any of these, or pick 1-2 to prototype?
                ╭────────────────────────────────────────────────────────╮
                │ > Type your message...                                 │
                ╰────────────────────────────────────────────────────────╯
                ? for shortcuts
                devbox · $clock 24-Nov-26
            """.trimIndent()

            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "agent",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { buildScreen(clockStr) },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onUserWrite("brainstorm features\r".toByteArray())
            watcher.onRawOutput(buildScreen(clockStr).toByteArray())
            delay(120L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.COMPLETED, alerts[0].kind)
            assertTrue(extractNotificationQuickActions(buildScreen(clockStr), alerts[0].kind).isEmpty())

            // Subsequent passive clock/tmux redraws must not re-fire any alert.
            listOf("21:58", "21:59", "22:00").forEach { nextClock ->
                clockStr = nextClock
                watcher.onRawOutput("\u001b[40;1Hdevbox · $nextClock 24-Nov-26\r\n".toByteArray())
                delay(100L)
            }
            assertEquals(1, alerts.size)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `response needed disarms watcher and ignores subsequent tmux clock redraws`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var clockStr = "21:57"
            fun screenWithClock(clock: String) = """
                Allow running command `./gradlew test`? [y/N]
                devbox · $clock
            """.trimIndent()

            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "agent",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { screenWithClock(clockStr) },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onUserWrite("run tests\r".toByteArray())
            watcher.onRawOutput(screenWithClock(clockStr).toByteArray())
            delay(120L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.RESPONSE_NEEDED, alerts[0].kind)

            // Repeated clock ticks (21:58, 21:59) change raw bytes but must not trigger another alert.
            listOf("21:58", "21:59").forEach { nextClock ->
                clockStr = nextClock
                watcher.onRawOutput("\u001b[24;1Hdevbox · $nextClock          \r\n".toByteArray())
                delay(100L)
            }
            assertEquals(1, alerts.size)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `acknowledgeCurrentOutput suppresses passive re-notification for the same screen`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<RecordedAlert>()
            var tail = "Apply config patch? [y/N]"
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "agent",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { tail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onRawOutput("Apply config patch? [y/N]".toByteArray())
            delay(120L)
            assertEquals(1, alerts.size)

            watcher.acknowledgeCurrentOutput()
            // Even a passive redraw with more than 12 bytes must stay silent once acknowledged.
            watcher.onRawOutput("redraw padding Apply config patch? [y/N]".toByteArray())
            delay(120L)
            assertEquals(1, alerts.size)

            // New user turn clears acknowledgement and notifies once for the next prompt.
            watcher.onUserWrite("y".toByteArray())
            tail = "Patch applied cleanly.\nuser@dev:~$"
            watcher.onRawOutput("Patch applied cleanly.\r\nuser@dev:~$ ".toByteArray())
            delay(120L)
            assertEquals(2, alerts.size)
            assertEquals(OutputPromptKind.COMPLETED, alerts[1].kind)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `compactSessionHostLabel strips user, port, and MagicDNS suffix`() {
        assertEquals("mobile-octagon", compactSessionHostLabel("morningbus@mobile-octagon.tail9498d.ts.net:22"))
        assertEquals("devbox", compactSessionHostLabel("devbox.ts.net"))
        assertEquals("192.168.1.50", compactSessionHostLabel("root@192.168.1.50:2222"))
        assertEquals("dev", compactSessionHostLabel("dev"))
    }

    @Test
    fun `suppresses mid-turn agent tool accordions and inline spinners until turn finishes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            assertNull(
                classifyPromptKind(
                    "● Ran 2 commands (+1 · grep -n -C 8 \"Tailsc…).\n● Explored 4 files (TerminalL…) (ctrl+o to expand)",
                    settledAfterIdle = true,
                ),
            )
            assertNull(
                classifyPromptKind(
                    "⠋ Running Gradle unit tests across :app...",
                    settledAfterIdle = true,
                ),
            )
            assertEquals(
                OutputPromptKind.COMPLETED,
                classifyPromptKind(
                    "● Ran 2 commands.\nUpdated TerminalOutputWatcher.kt cleanly.\n❯ Type a message",
                    settledAfterIdle = true,
                ),
            )

            val alerts = mutableListOf<RecordedAlert>()
            var tail = "● Ran 2 commands (+1 · grep).\n● Explored 4 files (ctrl+o to expand)"
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "morningbus@mobile-octagon.tail9498d.ts.net:22",
                isEnabled = { true },
                idleDelayMs = { 40L },
                readTail = { tail },
                onAlert = { kind, title, body -> alerts += RecordedAlert(kind, title, body) },
            )

            watcher.onUserWrite("inspect tailscale\r".toByteArray())
            watcher.onRawOutput("● Ran 2 commands (+1 · grep).\r\n● Explored 4 files (ctrl+o to expand)".toByteArray())
            delay(130L)
            assertTrue(alerts.isEmpty())

            tail = "● Explored 4 files\nDone checking Tailscale files.\n❯ Type a message"
            watcher.onRawOutput("\r\nDone checking Tailscale files.\r\n❯ Type a message".toByteArray())
            delay(130L)

            assertEquals(1, alerts.size)
            assertEquals(OutputPromptKind.COMPLETED, alerts[0].kind)
            assertEquals("mobile-octagon · Completed", alerts[0].title)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }
}
