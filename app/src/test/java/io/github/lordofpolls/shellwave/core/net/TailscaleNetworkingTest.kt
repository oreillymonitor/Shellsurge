package io.github.lordofpolls.shellwave.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleNetworkingTest {

    @Test
    fun `ipMatchesCidr matches IPv4 and IPv6 subnets while rejecting default routes and hostnames`() {
        assertTrue(ipMatchesCidr("192.168.1.42", "192.168.1.0/24"))
        assertTrue(ipMatchesCidr("10.20.30.40", "10.0.0.0/8"))
        assertTrue(ipMatchesCidr("172.16.5.9", "172.16.5.9/32"))
        assertFalse(ipMatchesCidr("192.168.2.1", "192.168.1.0/24"))
        assertFalse(ipMatchesCidr("192.168.1.42", "0.0.0.0/0"))
        assertFalse(ipMatchesCidr("example.com", "192.168.1.0/24"))

        assertTrue(ipMatchesCidr("fd00:1234::5", "fd00:1234::/64"))
        assertTrue(ipMatchesCidr("[fd00:1234::5]", "fd00:1234::/64"))
        assertFalse(ipMatchesCidr("fd00:9999::1", "fd00:1234::/64"))
        assertFalse(ipMatchesCidr("fd00:1234::5", "::/0"))
    }

    @Test
    fun `findMatchingSubnetPeer matches only online subnet routers`() {
        val offlineRouter = TailscalePeer(
            id = "r1",
            hostName = "offline-pi",
            dnsName = "offline-pi.ts.net.",
            ips = listOf("100.64.0.2"),
            os = "linux",
            online = false,
            sshHostKeys = false,
            primaryRoutes = listOf("192.168.10.0/24"),
        )
        val onlineRouter = TailscalePeer(
            id = "r2",
            hostName = "home-pi",
            dnsName = "home-pi.ts.net.",
            ips = listOf("100.64.0.3"),
            os = "linux",
            online = true,
            sshHostKeys = true,
            primaryRoutes = listOf("192.168.1.0/24", "fd00:abcd::/64"),
        )
        val peers = listOf(offlineRouter, onlineRouter)

        assertNull(findMatchingSubnetPeer("192.168.10.5", peers))
        val matched = findMatchingSubnetPeer("192.168.1.77", peers)
        assertNotNull(matched)
        assertEquals("home-pi", matched?.first?.hostName)
        assertEquals("192.168.1.0/24", matched?.second)
        assertNull(findMatchingSubnetPeer("home-pi.ts.net", peers))
    }

    @Test
    fun `parseTailscaleWebTarget and rewriteUrlToLoopback preserve paths, queries, and ports`() {
        val isTailnet: (String) -> Boolean = { host ->
            host.endsWith(".ts.net", ignoreCase = true) || host.startsWith("100.64.")
        }

        val defaultPortTarget = parseTailscaleWebTarget("https://devbox.tailnet.ts.net/dashboard?tab=logs#top", isTailnet)
        assertNotNull(defaultPortTarget)
        assertEquals("https", defaultPortTarget?.scheme)
        assertEquals("devbox.tailnet.ts.net", defaultPortTarget?.host)
        assertEquals(443, defaultPortTarget?.port)

        val customPortTarget = parseTailscaleWebTarget("http://100.64.1.9:5173/app?id=3", isTailnet)
        assertNotNull(customPortTarget)
        assertEquals(5173, customPortTarget?.port)
        assertEquals(
            "http://127.0.0.1:49152/app?id=3",
            rewriteUrlToLoopback(customPortTarget!!.originalUrl, 49152),
        )
        assertEquals(
            "https://127.0.0.1:50000/dashboard?tab=logs#top",
            rewriteUrlToLoopback(defaultPortTarget!!.originalUrl, 50000),
        )

        assertNull(parseTailscaleWebTarget("http://127.0.0.1:3000", isTailnet))
        assertNull(parseTailscaleWebTarget("https://github.com/org/repo", isTailnet))
    }

    @Test
    fun `parseTailscalePingJson and formatPeerPathBadge classify Direct and DERP`() {
        val direct = parseTailscalePingJson(
            """{"ip":"100.64.1.2","nodeName":"box","latencyMs":14.4,"direct":true,"endpoint":"1.2.3.4:41641"}""",
        )
        assertTrue(direct.isSuccess)
        assertEquals("Direct · 14 ms", direct.summaryLabel)

        val derp = parseTailscalePingJson(
            """{"ip":"100.64.1.2","nodeName":"box","latencyMs":88.6,"direct":false,"derpRegionCode":"nyc","derpRegionId":1}""",
        )
        assertTrue(derp.isSuccess)
        assertEquals("DERP NYC · 89 ms", derp.summaryLabel)

        val err = parseTailscalePingJson("""{"error":"timeout"}""")
        assertFalse(err.isSuccess)
        assertEquals("timeout", err.summaryLabel)

        assertEquals("Direct" to true, formatPeerPathBadge("1.2.3.4:41641", "nyc"))
        assertEquals("DERP NYC" to false, formatPeerPathBadge("", "nyc"))
        assertNull(formatPeerPathBadge("", ""))
    }
}
