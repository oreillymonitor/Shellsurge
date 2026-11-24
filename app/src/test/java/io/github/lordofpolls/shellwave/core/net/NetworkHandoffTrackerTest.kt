package io.github.lordofpolls.shellwave.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkHandoffTrackerTest {

    @Test
    fun `first validated network returns NONE and repeated validations on same handle return NONE`() {
        val tracker = NetworkHandoffTracker()
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(100L))
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(100L))
    }

    @Test
    fun `switching to a different validated network returns NETWORK_SWITCHED`() {
        val tracker = NetworkHandoffTracker()
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(100L))
        assertEquals(NetworkHandoffEvent.NETWORK_SWITCHED, tracker.onNetworkValidated(200L))
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(200L))
    }

    @Test
    fun `stale onNetworkLost for previous interface does not mark current network lost`() {
        val tracker = NetworkHandoffTracker()
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(100L))
        assertEquals(NetworkHandoffEvent.NETWORK_SWITCHED, tracker.onNetworkValidated(200L))

        tracker.onNetworkLost(100L)
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(200L))
    }

    @Test
    fun `losing active network then validating returns NETWORK_RECOVERED`() {
        val tracker = NetworkHandoffTracker()
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(200L))
        tracker.onNetworkLost(200L)
        assertEquals(NetworkHandoffEvent.NETWORK_RECOVERED, tracker.onNetworkValidated(200L))

        tracker.onNetworkLost(200L)
        assertEquals(NetworkHandoffEvent.NETWORK_RECOVERED, tracker.onNetworkValidated(300L))
    }

    @Test
    fun `reset clears prior state so next validated network returns NONE`() {
        val tracker = NetworkHandoffTracker()
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(100L))
        tracker.reset()
        assertEquals(NetworkHandoffEvent.NONE, tracker.onNetworkValidated(200L))
    }
}
