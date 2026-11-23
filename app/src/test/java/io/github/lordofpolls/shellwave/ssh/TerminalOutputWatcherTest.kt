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

        assertEquals("New terminal output is ready to inspect.", extractOutputSnippet("user@dev:~$"))
    }

    @Test
    fun `classifyOutputTitle identifies question error success and generic output`() {
        assertEquals(
            "dev · Waiting for input",
            classifyOutputTitle("dev", "Apply these changes? [y/N]"),
        )
        assertEquals(
            "dev · Command error",
            classifyOutputTitle("dev", "BUILD FAILED in 12s"),
        )
        assertEquals(
            "dev · Command succeeded",
            classifyOutputTitle("dev", "BUILD SUCCESSFUL in 8s"),
        )
        assertEquals(
            "dev · Output ready",
            classifyOutputTitle("dev", "total 24\ndrwxr-xr-x 2 user user 4096"),
        )
    }

    @Test
    fun `suppresses quick empty enter echo`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<OutputAlertEvent>()
            var clock = 1_000L
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 40L },
                onAlert = { alerts += it },
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
    fun `notifies after settle delay on substantial output`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<OutputAlertEvent>()
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { true },
                idleDelayMs = { 40L },
                onAlert = { alerts += it },
            )

            watcher.onUserWrite("./gradlew test\r".toByteArray())
            watcher.onRawOutput("Running unit tests across 14 suites...\r\nBUILD SUCCESSFUL in 4s\r\n".toByteArray())
            delay(120L)

            assertEquals(1, alerts.size)
            assertNull(alerts.first().explicitTitle)
            assertNull(alerts.first().explicitBody)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `notifies immediately on OSC 133 finish and OSC 9 without duplicate settle alert`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<OutputAlertEvent>()
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "prod",
                isEnabled = { true },
                idleDelayMs = { 40L },
                onAlert = { alerts += it },
            )

            watcher.onUserWrite("make build\r".toByteArray())
            watcher.onRawOutput("\u001b]133;C\u0007Compiling sources...\r\n\u001b]133;D;0\u0007".toByteArray())
            delay(120L)

            assertEquals(1, alerts.size)
            assertEquals("prod · Command succeeded", alerts[0].explicitTitle)
            assertNull(alerts[0].explicitBody)

            watcher.onRawOutput("\u001b]9;Agent waiting for confirmation\u0007".toByteArray())
            delay(120L)

            assertEquals(2, alerts.size)
            assertEquals("prod · Terminal alert", alerts[1].explicitTitle)
            assertEquals("Agent waiting for confirmation", alerts[1].explicitBody)
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `respects disabled flag for both OSC and settle alerts`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val alerts = mutableListOf<OutputAlertEvent>()
            val watcher = TerminalOutputWatcher(
                scope = scope,
                label = "dev",
                isEnabled = { false },
                idleDelayMs = { 40L },
                onAlert = { alerts += it },
            )

            watcher.onUserWrite("ls\r".toByteArray())
            watcher.onRawOutput("a very long directory listing with more than forty eight characters\r\n".toByteArray())
            watcher.onRawOutput("\u001b]133;D;1\u0007".toByteArray())
            watcher.onRawOutput("\u001b]9;Ignored\u0007".toByteArray())
            delay(120L)

            assertTrue(alerts.isEmpty())
            watcher.cancel()
        } finally {
            scope.cancel()
        }
    }
}
