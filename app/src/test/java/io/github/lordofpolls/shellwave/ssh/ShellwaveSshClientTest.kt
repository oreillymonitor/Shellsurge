package io.github.lordofpolls.shellwave.ssh

import io.github.lordofpolls.shellwave.core.net.TailscaleSocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellwaveSshClientTest {

    @Test
    fun `ShellwaveSshClient leaves MagicDNS unresolved when TailscaleSocketFactory is active`() {
        val client = ShellwaveSshClient()
        client.socketFactory = TailscaleSocketFactory(proxyPort = 10805, username = "u", password = "p")

        val addr = client.makeInetSocketAddress("workstation.tail1234.ts.net.", 22)
        assertTrue(addr.isUnresolved)
        assertEquals("workstation.tail1234.ts.net", addr.hostString)
        assertEquals(22, addr.port)

        val shortAddr = client.makeInetSocketAddress("devbox", 2222)
        assertTrue(shortAddr.isUnresolved)
        assertEquals("devbox", shortAddr.hostString)
        assertEquals(2222, shortAddr.port)
    }

    @Test
    fun `ShellwaveSshClient uses standard resolution without TailscaleSocketFactory`() {
        val client = ShellwaveSshClient()
        val addr = client.makeInetSocketAddress("127.0.0.1", 22)
        assertFalse(addr.isUnresolved)
        assertEquals("127.0.0.1", addr.address?.hostAddress)
    }
}
