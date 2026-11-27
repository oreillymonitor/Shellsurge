package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Osc8LinkTrackerTest {

    @Test
    fun `captures BEL and ST terminated OSC 8 hyperlinks`() {
        val tracker = Osc8LinkTracker()
        val payload = "\u001B]8;;https://example.com/docs\u0007Read docs\u001B]8;;\u0007\n" +
            "\u001B]8;id=oauth1;https://accounts.google.com/o/oauth2/v2/auth?foo=1\u001B\\Sign in\u001B]8;;\u001B\\"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        tracker.scan(bytes, bytes.size)

        val links = tracker.snapshot()
        assertEquals(2, links.size)
        // Most recent first
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?foo=1", links[0].uri)
        assertEquals("Sign in", links[0].label)
        assertEquals("oauth1", links[0].id)

        assertEquals("https://example.com/docs", links[1].uri)
        assertEquals("Read docs", links[1].label)
    }

    @Test
    fun `handles chunk boundaries and intervening SGR styling codes`() {
        val tracker = Osc8LinkTracker()
        val payload = "Prefix \u001B]8;;https://example.com/login\u001B\\" +
            "\u001B[34;4mOpen \u001B[1mLogin\u001B[0m" +
            "\u001B]8;;\u001B\\ suffix"
        val bytes = payload.toByteArray(Charsets.UTF_8)

        // Feed 3 bytes at a time across ESC/OSC/SGR boundaries
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + 3).coerceAtMost(bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            tracker.scan(chunk, chunk.size)
            offset = end
        }

        val links = tracker.snapshot()
        assertEquals(1, links.size)
        assertEquals("https://example.com/login", links[0].uri)
        assertEquals("Open Login", links[0].label)
    }

    @Test
    fun `captures long OAuth URLs over 1024 bytes and clears cleanly`() {
        val tracker = Osc8LinkTracker()
        val longParam = "a".repeat(1400)
        val longUrl = "https://accounts.google.com/o/oauth2/v2/auth?state=$longParam"
        val bytes = "\u001B]8;;$longUrl\u0007Authorize\u001B]8;;\u0007".toByteArray(Charsets.UTF_8)
        tracker.scan(bytes)

        val links = tracker.snapshot()
        assertEquals(1, links.size)
        assertEquals(longUrl, links[0].uri)
        assertEquals("Authorize", links[0].label)

        tracker.clear()
        assertTrue(tracker.snapshot().isEmpty())
    }
}
