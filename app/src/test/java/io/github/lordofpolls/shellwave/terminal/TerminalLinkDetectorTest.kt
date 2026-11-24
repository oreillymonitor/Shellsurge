package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLinkDetectorTest {

    @Test
    fun `detects URLs and IPs without duplicating URL path segments`() {
        val line = "See https://github.com/oreillymonitor/Shellsurge or 192.168.1.42:8080/health"
        val links = findLinksInLine(line, row = 3)

        assertEquals(LinkType.URL, links[0].type)
        assertEquals("https://github.com/oreillymonitor/Shellsurge", links[0].text)
        assertEquals(LinkType.IP, links[1].type)
        assertEquals("192.168.1.42:8080", links[1].text)
        assertTrue(links.none { it.type == LinkType.PATH && it.text.contains("oreillymonitor") })
    }

    @Test
    fun `extracts line numbers for absolute and relative compiler paths`() {
        val absError = "e: /home/user/repo/MainActivity.kt:142:9 Unresolved reference"
        val absLinks = findLinksInLine(absError, row = 0)
        assertEquals(1, absLinks.size)
        assertEquals(LinkType.PATH, absLinks[0].type)
        assertEquals("/home/user/repo/MainActivity.kt", absLinks[0].text)
        assertEquals(142, absLinks[0].lineNumber)
        assertEquals(absError, absLinks[0].sourceLine)

        val relError = "src/feature/session/SessionsScreen.kt:88: error: type mismatch"
        val relLinks = findLinksInLine(relError, row = 1)
        assertEquals(1, relLinks.size)
        assertEquals(LinkType.PATH, relLinks[0].type)
        assertEquals("src/feature/session/SessionsScreen.kt", relLinks[0].text)
        assertEquals(88, relLinks[0].lineNumber)
        assertTrue(relLinks[0].endCol >= relLinks[0].startCol)
    }

    @Test
    fun `does not match ordinary dotted prose as relative code paths`() {
        val prose = "Update configuration e.g. timeout settings and retry count."
        assertTrue(findLinksInLine(prose, row = 0).isEmpty())
    }
}
