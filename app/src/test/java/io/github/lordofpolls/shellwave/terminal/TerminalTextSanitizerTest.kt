package io.github.lordofpolls.shellwave.terminal

import io.github.lordofpolls.shellwave.feature.session.AgentDigestModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTextSanitizerTest {

    @Test
    fun stripsAnsiColorsAndCursorEscapes() {
        val raw = "\u001B[32mSuccess\u001B[0m: Process completed \u001B[2K\u001B[1Aline"
        val clean = TerminalTextSanitizer.sanitize(raw)
        assertEquals("Success: Process completed line", clean)
    }

    @Test
    fun collapsesSpinnersAndDuplicateBlankLines() {
        val raw = """
            Starting build...
            ⠋
            ⠙
            ⠹
            
            
            Done!
        """.trimIndent()
        val clean = TerminalTextSanitizer.sanitize(raw)
        assertEquals("Starting build...\n\nDone!", clean)
    }

    @Test
    fun limitsMaxLinesTail() {
        val raw = (1..50).joinToString("\n") { "Line $it" }
        val clean = TerminalTextSanitizer.sanitize(raw, maxLines = 5)
        assertEquals("Line 46\nLine 47\nLine 48\nLine 49\nLine 50", clean)
    }

    @Test
    fun fallbackDetectsYesNoPrompt() = runBlocking {
        val raw = """
            Git workspace modified 3 files.
            Allow agent to push to master? [y/N]
        """.trimIndent()
        val result = AgentDigestModel.analyzeTerminalTail(raw)
        assertTrue(result.hasQuestion)
        assertTrue(result.quickOptions.contains("Yes"))
        assertTrue(result.quickOptions.contains("No"))
        assertFalse(result.isError)
    }

    @Test
    fun fallbackDetectsBuildSuccess() = runBlocking {
        val raw = """
            > Task :app:compileDebugKotlin
            BUILD SUCCESSFUL in 4s
            user@host:~$
        """.trimIndent()
        val result = AgentDigestModel.analyzeTerminalTail(raw)
        assertFalse(result.hasQuestion)
        assertFalse(result.isError)
        assertTrue(result.spokenSummary.contains("success", ignoreCase = true))
    }

    @Test
    fun fallbackDetectsBuildFailure() = runBlocking {
        val raw = """
            > Task :app:compileDebugKotlin FAILED
            e: SessionsScreen.kt: Unresolved reference 'KeyEvent'
            BUILD FAILED in 2s
        """.trimIndent()
        val result = AgentDigestModel.analyzeTerminalTail(raw)
        assertFalse(result.hasQuestion)
        assertTrue(result.isError)
        assertTrue(result.spokenSummary.contains("failed", ignoreCase = true))
    }

    @Test
    fun fallbackSummarizesMeaningfulAgentOutput() = runBlocking {
        val raw = """
            claude> Refactored session auth handler.
            All unit tests passed.
            user@host:~/project$ 
        """.trimIndent()
        val result = AgentDigestModel.analyzeTerminalTail(raw)
        assertFalse(result.hasQuestion)
        assertFalse(result.spokenSummary.contains("idle", ignoreCase = true))
        assertTrue(result.spokenSummary.contains("passed", ignoreCase = true))
    }
}
