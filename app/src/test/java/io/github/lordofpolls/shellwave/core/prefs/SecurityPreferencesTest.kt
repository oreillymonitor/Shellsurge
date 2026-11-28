package io.github.lordofpolls.shellwave.core.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPreferencesTest {

    @Test
    fun `does not lock when disabled or missing background timestamp`() {
        assertFalse(shouldLockApp(enabled = false, backgroundedAtMs = 100L, nowMs = 500_000L, timeoutMs = 0L))
        assertFalse(shouldLockApp(enabled = true, backgroundedAtMs = null, nowMs = 500_000L, timeoutMs = 0L))
    }

    @Test
    fun `locks immediately when timeout is zero`() {
        assertTrue(shouldLockApp(enabled = true, backgroundedAtMs = 1_000L, nowMs = 1_001L, timeoutMs = 0L))
    }

    @Test
    fun `respects timeout threshold`() {
        assertFalse(shouldLockApp(enabled = true, backgroundedAtMs = 10_000L, nowMs = 69_999L, timeoutMs = 60_000L))
        assertTrue(shouldLockApp(enabled = true, backgroundedAtMs = 10_000L, nowMs = 70_000L, timeoutMs = 60_000L))
        assertTrue(shouldLockApp(enabled = true, backgroundedAtMs = 10_000L, nowMs = 200_000L, timeoutMs = 60_000L))
    }
}
