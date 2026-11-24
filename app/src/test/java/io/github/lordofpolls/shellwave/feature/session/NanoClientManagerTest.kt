package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.core.prefs.NanoModelTier
import io.github.lordofpolls.shellwave.core.prefs.NanoReleaseChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoClientManagerTest {

    @Test
    fun `trimTailLinesToCharBudget keeps newest terminal lines and falls back on long single line`() {
        val log = (1..10).joinToString("\n") { "line-$it-output" }
        val trimmed = trimTailLinesToCharBudget(log, maxChars = 32)
        assertTrue(trimmed.contains("line-10-output"))
        assertFalse(trimmed.contains("line-1-output"))
        assertTrue(trimmed.length <= 32)

        val singleHuge = "x".repeat(100) + "TAIL"
        val singleTrimmed = trimTailLinesToCharBudget(singleHuge, maxChars = 10)
        assertEquals(10, singleTrimmed.length)
        assertTrue(singleTrimmed.endsWith("TAIL"))
    }

    @Test
    fun `combineSystemAndUserPrompt and formatModelLabel behave cleanly`() {
        val combined = combineSystemAndUserPrompt("System rules.", "User input.")
        assertEquals("System rules.\n\nUser input.", combined)

        assertEquals(
            "gemini-nano-v3",
            formatModelLabel("gemini-nano-v3", NanoModelTier.FULL, NanoReleaseChannel.PREVIEW),
        )
        assertEquals(
            "Gemini Nano (Fast/Stable)",
            formatModelLabel(null, NanoModelTier.FAST, NanoReleaseChannel.STABLE),
        )
    }

    @Test
    fun `unbounded Int MAX_VALUE token limit does not overflow char budget or wipe prompt`() {
        assertNull(sanitizeTokenLimit(Int.MAX_VALUE))
        assertNull(sanitizeTokenLimit(0))
        assertEquals(4096, sanitizeTokenLimit(4096))

        val maxTokens = computeMaxInputTokens(Int.MAX_VALUE, maxOutputTokens = 256)
        val charBudget = computeMaxInputCharBudget(Int.MAX_VALUE, maxOutputTokens = 256)
        assertEquals(32384, maxTokens)
        assertTrue(charBudget > 0)
        assertEquals(
            "fix login timeout",
            trimTailLinesToCharBudget("fix login timeout", charBudget),
        )
    }
}
