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
}
