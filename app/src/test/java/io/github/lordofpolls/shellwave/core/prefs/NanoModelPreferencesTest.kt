package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoModelPreferencesTest {

    private val prefs = FakeSharedPreferences()
    private val context: Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun `defaults use Full tier, Stable channel, thinking off, warmup on`() {
        assertEquals(NanoModelTier.FULL, NanoModelPreferences.getModelTier(context))
        assertEquals(NanoReleaseChannel.STABLE, NanoModelPreferences.getReleaseChannel(context))
        assertFalse(NanoModelPreferences.isThinkingModeEnabled(context))
        assertTrue(NanoModelPreferences.isWarmupEnabled(context))
    }

    @Test
    fun `round trips persisted Nano preferences`() {
        NanoModelPreferences.setModelTier(context, NanoModelTier.FAST)
        NanoModelPreferences.setReleaseChannel(context, NanoReleaseChannel.PREVIEW)
        NanoModelPreferences.setThinkingModeEnabled(context, true)
        NanoModelPreferences.setWarmupEnabled(context, false)

        assertEquals(NanoModelTier.FAST, NanoModelPreferences.getModelTier(context))
        assertEquals(NanoReleaseChannel.PREVIEW, NanoModelPreferences.getReleaseChannel(context))
        assertTrue(NanoModelPreferences.isThinkingModeEnabled(context))
        assertFalse(NanoModelPreferences.isWarmupEnabled(context))
    }
}
