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
        assertEquals(NotificationFilter.ALL, NotificationPreferences.getNotificationFilter(context))
        assertEquals(OutputIdleDelay.NORMAL_5S, NotificationPreferences.getIdleDelay(context))
        assertFalse(NotificationPreferences.isNotifyOnActiveTab(context))
        assertTrue(NotificationPreferences.isAutoDigestOnReturn(context))
    }

    @Test
    fun `round trips persisted notification preferences`() {
        NotificationPreferences.setCommandNotifyEnabled(context, false)
        NotificationPreferences.setNotificationFilter(context, NotificationFilter.IMPORTANT_ONLY)
        NotificationPreferences.setIdleDelay(context, OutputIdleDelay.EXPLICIT_ONLY)
        NotificationPreferences.setNotifyOnActiveTab(context, true)
        NotificationPreferences.setAutoDigestOnReturn(context, false)

        assertFalse(NotificationPreferences.isCommandNotifyEnabled(context))
        assertEquals(NotificationFilter.IMPORTANT_ONLY, NotificationPreferences.getNotificationFilter(context))
        assertEquals(OutputIdleDelay.EXPLICIT_ONLY, NotificationPreferences.getIdleDelay(context))
        assertTrue(NotificationPreferences.isNotifyOnActiveTab(context))
        assertFalse(NotificationPreferences.isAutoDigestOnReturn(context))
    }

    @Test
    fun `shouldNotifyForOutcome filters by urgency tier`() {
        assertTrue(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.ALL, isResponseNeeded = false, isError = false))
        assertTrue(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.ALL, isResponseNeeded = false, isError = true))
        assertTrue(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.ALL, isResponseNeeded = true, isError = false))

        assertFalse(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.IMPORTANT_ONLY, isResponseNeeded = false, isError = false))
        assertTrue(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.IMPORTANT_ONLY, isResponseNeeded = false, isError = true))
        assertTrue(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.IMPORTANT_ONLY, isResponseNeeded = true, isError = false))

        assertFalse(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.APPROVALS_ONLY, isResponseNeeded = false, isError = false))
        assertFalse(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.APPROVALS_ONLY, isResponseNeeded = false, isError = true))
        assertTrue(NotificationPreferences.shouldNotifyForOutcome(NotificationFilter.APPROVALS_ONLY, isResponseNeeded = true, isError = false))
    }
}
