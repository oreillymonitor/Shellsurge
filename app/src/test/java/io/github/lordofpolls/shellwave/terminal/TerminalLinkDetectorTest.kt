package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `joins soft-wrapped multi-line Google OAuth URL across all rows`() {
        val fullUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
            "client_id=1234567890-abcdef.apps.googleusercontent.com&" +
            "redirect_uri=http%3A%2F%2Flocalhost%3A8085%2Foauth2callback&" +
            "response_type=code&scope=openid%20email&state=xyz987654321"
        val width = 40
        val chunks = fullUrl.chunked(width)
        assertTrue(chunks.size >= 5)

        val snapshots = listOf(
            TerminalRowSnapshot(externalRow = 0, text = "Open this URL to continue:", wrappedToNext = false, columns = width),
        ) + chunks.mapIndexed { idx, chunk ->
            TerminalRowSnapshot(
                externalRow = idx + 1,
                text = chunk,
                wrappedToNext = idx < chunks.lastIndex,
                columns = width,
            )
        } + listOf(
            TerminalRowSnapshot(externalRow = chunks.size + 1, text = "Waiting for login...", wrappedToNext = false, columns = width),
        )

        val links = findLinksInSnapshots(snapshots)
        val urlLinks = links.filter { it.type == LinkType.URL }
        assertEquals(1, urlLinks.size)
        assertEquals(fullUrl, urlLinks[0].text)
        assertEquals(chunks.size, urlLinks[0].spans.size)

        // Tapping any wrapped row (first, middle, last) must resolve the full URL
        for (idx in chunks.indices) {
            val row = idx + 1
            val hit = urlLinks.firstOrNull { link ->
                link.spans.any { it.row == row && 5 in it.startCol..it.endCol }
            }
            assertNotNull("Expected hit on wrapped row $row", hit)
            assertEquals(fullUrl, hit!!.text)
        }
    }

    @Test
    fun `joins hard-wrapped TUI boxed OAuth URL and skips box borders`() {
        val part1 = "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc123&"
        val part2 = "redirect_uri=http%3A%2F%2Flocalhost%3A41234%2Fcb&response_type="
        val part3 = "code&scope=openid%20email"
        val snapshots = listOf(
            TerminalRowSnapshot(0, "│ Please visit:                                                  │", false),
            TerminalRowSnapshot(1, "│ $part1│", false),
            TerminalRowSnapshot(2, "│ $part2│", false),
            TerminalRowSnapshot(3, "│ $part3                                        │", false),
            TerminalRowSnapshot(4, "│                                                                │", false),
            TerminalRowSnapshot(5, "│ Waiting for browser...                                         │", false),
        )

        val links = findLinksInSnapshots(snapshots)
        val urlLinks = links.filter { it.type == LinkType.URL }
        assertEquals(1, urlLinks.size)
        assertEquals(part1 + part2 + part3, urlLinks[0].text)
        assertEquals(listOf(1, 2, 3), urlLinks[0].spans.map { it.row })
        // Box border column (col 0) is excluded from span
        assertTrue(urlLinks[0].spans.all { it.startCol >= 2 })
    }

    @Test
    fun `does not stitch trailing prose onto a non-wrapped URL`() {
        val snapshots = listOf(
            TerminalRowSnapshot(0, "Open https://example.com/login", wrappedToNext = false),
            TerminalRowSnapshot(1, "and paste the code here:", wrappedToNext = false),
        )
        val urlLinks = findLinksInSnapshots(snapshots).filter { it.type == LinkType.URL }
        assertEquals(1, urlLinks.size)
        assertEquals("https://example.com/login", urlLinks[0].text)
    }

    @Test
    fun `trims trailing sentence punctuation while keeping balanced parentheses`() {
        val line1 = "Check (see https://en.wikipedia.org/wiki/SSH_(protocol))."
        val links1 = findLinksInLine(line1, row = 0).filter { it.type == LinkType.URL }
        assertEquals(1, links1.size)
        assertEquals("https://en.wikipedia.org/wiki/SSH_(protocol)", links1[0].text)

        val line2 = "URL is https://example.com/auth?foo=1, then press Enter."
        val links2 = findLinksInLine(line2, row = 0).filter { it.type == LinkType.URL }
        assertEquals("https://example.com/auth?foo=1", links2[0].text)
    }

    @Test
    fun `extractUrlFromSelection joins wrapped boxed selection and ignores plain prose`() {
        val selected = """
            │ https://accounts.google.com/o/oauth2/v2/auth?client_id=123& │
            │ redirect_uri=http%3A%2F%2Flocalhost%3A8085 │
        """.trimIndent()
        assertEquals(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=123&redirect_uri=http%3A%2F%2Flocalhost%3A8085",
            extractUrlFromSelection(selected),
        )
        assertNull(extractUrlFromSelection("just some terminal text\nwithout a link"))
    }

    @Test
    fun `detectLocalhostCallback extracts OAuth redirect and direct localhost ports`() {
        val oauthUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc&" +
            "redirect_uri=http%3A%2F%2Flocalhost%3A8085%2Fcallback&response_type=code"
        val info = detectLocalhostCallback(oauthUrl)
        assertNotNull(info)
        assertEquals(8085, info!!.port)
        assertEquals("localhost", info.host)
        assertTrue(info.isOAuthRedirect)

        val doubleEncoded = "https://auth.example.com/login?next=1&redirect_uri=http%253A%252F%252F127.0.0.1%253A41234%252Fcb"
        val info2 = detectLocalhostCallback(doubleEncoded)
        assertNotNull(info2)
        assertEquals(41234, info2!!.port)
        assertEquals("127.0.0.1", info2.host)
        assertTrue(info2.isOAuthRedirect)

        val direct = detectLocalhostCallback("http://localhost:3000/auth")
        assertNotNull(direct)
        assertEquals(3000, direct!!.port)
        assertTrue(!direct.isOAuthRedirect)

        assertNull(detectLocalhostCallback("https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fexample.com%2Fcb"))
        assertNull(detectLocalhostCallback("http://localhost:80/callback"))
    }

    @Test
    fun `matches OSC 8 hyperlink labels over visible terminal text`() {
        val targetUri = "https://accounts.google.com/o/oauth2/v2/auth?client_id=xyz&redirect_uri=http%3A%2F%2Flocalhost%3A9000"
        val osc8 = listOf(Osc8Hyperlink(uri = targetUri, label = "Sign in with Google", id = "auth1"))
        val snapshots = listOf(
            TerminalRowSnapshot(0, "Click [Sign in with Google] to authenticate.", wrappedToNext = false),
        )

        val links = findLinksInSnapshots(snapshots, osc8)
        assertEquals(1, links.size)
        assertEquals(LinkType.URL, links[0].type)
        assertEquals(targetUri, links[0].text)
        assertEquals("Sign in with Google", links[0].displayLabel)
        assertEquals(7, links[0].startCol)
        assertEquals(25, links[0].endCol)
    }
}
