package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPreferencesTest {

    private val prefs = FakeSharedPreferences()
    private val context: Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun `defaults match expected initial settings`() {
        assertTrue(NotificationPreferences.isCommandNotifyEnabled(context))
        assertEquals(OutputIdleDelay.NORMAL_5S, NotificationPreferences.getIdleDelay(context))
        assertFalse(NotificationPreferences.isNotifyOnActiveTab(context))
    }

    @Test
    fun `round trips persisted notification preferences`() {
        NotificationPreferences.setCommandNotifyEnabled(context, false)
        NotificationPreferences.setIdleDelay(context, OutputIdleDelay.EXPLICIT_ONLY)
        NotificationPreferences.setNotifyOnActiveTab(context, true)

        assertFalse(NotificationPreferences.isCommandNotifyEnabled(context))
        assertEquals(OutputIdleDelay.EXPLICIT_ONLY, NotificationPreferences.getIdleDelay(context))
        assertTrue(NotificationPreferences.isNotifyOnActiveTab(context))
    }
}
