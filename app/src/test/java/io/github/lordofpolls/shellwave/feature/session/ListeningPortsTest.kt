package io.github.lordofpolls.shellwave.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningPortsTest {

    @Test
    fun `parses ss output with ipv4, ipv6, wildcard, and process metadata`() {
        val sample = """
            __SS__
            LISTEN 0 511 0.0.0.0:22 0.0.0.0:* users:(("sshd",pid=512,fd=3))
            LISTEN 0 511 [::1]:5173 [::]:* users:(("node",pid=4210,fd=21))
            LISTEN 0 128 *:8000 *:* users:(("uvicorn",pid=9001,fd=7))
            LISTEN 0 128 0.0.0.0:3000 0.0.0.0:*
            LISTEN 0 128 127.0.0.1:3000 0.0.0.0:* users:(("next-server",pid=7100,fd=18))
            LISTEN 0 128 [::ffff:127.0.0.1]:8080 [::]:*
        """.trimIndent()

        val ports = parseListeningPorts(sample)
        assertEquals(listOf(3000, 5173, 8000, 8080), ports.map { it.port })

        val port3000 = ports.first { it.port == 3000 }
        assertEquals("next-server", port3000.processName)
        assertEquals(7100, port3000.pid)
        assertEquals("127.0.0.1", port3000.bindAddress)

        val port5173 = ports.first { it.port == 5173 }
        assertEquals("node", port5173.processName)
        assertEquals("::1", port5173.bindAddress)
    }

    @Test
    fun `parses lsof output and ignores privileged ports`() {
        val sample = """
            __LSOF__
            COMMAND   PID  USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
            sshd      100  root   3u  IPv4 12345  0t0      TCP *:22 (LISTEN)
            vite      812  dev    19u IPv6 23456  0t0      TCP [::1]:5174 (LISTEN)
            python3   920  dev    5u  IPv4 34567  0t0      TCP 127.0.0.1:8088 (LISTEN)
        """.trimIndent()

        val ports = parseListeningPorts(sample)
        assertEquals(listOf(5174, 8088), ports.map { it.port })
        assertEquals("vite", ports[0].processName)
        assertEquals(812, ports[0].pid)
    }

    @Test
    fun `parses netstat output`() {
        val sample = """
            __NETSTAT__
            Active Internet connections (only servers)
            Proto Recv-Q Send-Q Local Address           Foreign Address         State       PID/Program name
            tcp        0      0 127.0.0.1:4321          0.0.0.0:*               LISTEN      1440/astro
            tcp6       0      0 :::9090                 :::*                    LISTEN      -
        """.trimIndent()

        val ports = parseListeningPorts(sample)
        assertEquals(listOf(4321, 9090), ports.map { it.port })
        assertEquals("astro", ports[0].processName)
        assertEquals(1440, ports[0].pid)
        assertEquals(null, ports[1].processName)
    }

    @Test
    fun `returns empty list when no port inspection tool exists`() {
        assertTrue(parseListeningPorts("__NONE__\n").isEmpty())
    }
}
