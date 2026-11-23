package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalNotificationScannerTest {

    @Test
    fun `detects OSC 133 command start and finish with exit codes`() {
        val scanner = TerminalNotificationScanner()
        val stream = "\u001b]133;C\u0007hello\r\n\u001b]133;D;0\u0007".toByteArray()
        val first = scanner.scan(stream)

        assertEquals(
            listOf(
                TerminalSignal.CommandStarted,
                TerminalSignal.CommandFinished(0),
            ),
            first.signals,
        )
        assertEquals("hello".length, first.printableBytes)

        val failSt = "\u001b]133;D;2\u001b\\".toByteArray()
        val second = scanner.scan(failSt)
        assertEquals(listOf(TerminalSignal.CommandFinished(2)), second.signals)

        val bareFinish = "\u001b]133;D\u0007".toByteArray()
        val third = scanner.scan(bareFinish)
        assertEquals(listOf(TerminalSignal.CommandFinished(null)), third.signals)
    }

    @Test
    fun `detects OSC 9 and OSC 777 notifications while ignoring OSC 9 progress`() {
        val scanner = TerminalNotificationScanner()
        val payload = (
            "\u001b]9;4;1;50\u0007" +
                "\u001b]9;Build finished\u0007" +
                "\u001b]777;notify;Agent;Task done\u001b\\"
            ).toByteArray()

        val result = scanner.scan(payload)
        assertEquals(
            listOf(
                TerminalSignal.OscNotification(title = null, body = "Build finished"),
                TerminalSignal.OscNotification(title = "Agent", body = "Task done"),
            ),
            result.signals,
        )
        assertEquals(0, result.printableBytes)
    }

    @Test
    fun `handles sequences split across chunk boundaries and ignores CSI escapes in byte count`() {
        val scanner = TerminalNotificationScanner()
        val part1 = "\u001b[32mOK\u001b[0m \u001b]777;not".toByteArray()
        val part2 = "ify;OpenCode;Ready for ".toByteArray()
        val part3 = "review\u0007".toByteArray()

        val r1 = scanner.scan(part1)
        assertTrue(r1.signals.isEmpty())
        assertEquals("OK ".length, r1.printableBytes)

        val r2 = scanner.scan(part2)
        assertTrue(r2.signals.isEmpty())
        assertEquals(0, r2.printableBytes)

        val r3 = scanner.scan(part3)
        assertEquals(
            listOf(TerminalSignal.OscNotification(title = "OpenCode", body = "Ready for review")),
            r3.signals,
        )
        assertEquals(0, r3.printableBytes)
    }
}
