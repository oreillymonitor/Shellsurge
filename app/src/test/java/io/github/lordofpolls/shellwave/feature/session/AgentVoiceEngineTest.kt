package io.github.lordofpolls.shellwave.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentVoiceEngineTest {

    @Test
    fun `resolveQuickReply maps confirmation and numbered options`() {
        assertEquals("y", resolveQuickReply("Yes"))
        assertEquals("y", resolveQuickReply("  proceed "))
        assertEquals("n", resolveQuickReply("no"))
        assertEquals("n", resolveQuickReply("Cancel"))
        assertEquals("1", resolveQuickReply("option 1"))
        assertEquals("2", resolveQuickReply("second"))
        assertEquals("3", resolveQuickReply("3"))
        assertEquals("4", resolveQuickReply("fourth"))
        assertEquals("", resolveQuickReply("enter"))
    }

    @Test
    fun `resolveQuickReply rejects arbitrary unrecognized speech`() {
        assertNull(resolveQuickReply("rm -rf /"))
        assertNull(resolveQuickReply("delete everything in the repo"))
        assertNull(resolveQuickReply("what is the weather"))
    }
}
