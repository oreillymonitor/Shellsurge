package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionLinkGroupingTest {

    private fun urlLink(url: String, label: String? = null) = TerminalLink(
        type = LinkType.URL,
        text = url,
        startCol = 0,
        endCol = 10,
        row = 0,
        displayLabel = label,
    )

    @Test
    fun `classifies and groups web, file, and other links while preserving order`() {
        val w1 = urlLink("https://example.com/docs")
        val f1 = urlLink("file:///home/dev/Foo.kt")
        val o1 = urlLink("ssh://prod.internal:2222")
        val w2 = urlLink("http://localhost:3000")
        val f2 = urlLink("file://localhost/tmp/report%201.md")

        assertEquals(SessionLinkCategory.WEB, classifyLinkScheme(w1.text))
        assertEquals(SessionLinkCategory.WEB, classifyLinkScheme("HTTP://EXAMPLE.COM"))
        assertEquals(SessionLinkCategory.FILE, classifyLinkScheme(f1.text))
        assertEquals(SessionLinkCategory.OTHER, classifyLinkScheme(o1.text))

        val grouped = groupSessionLinks(listOf(w1, f1, o1, w2, f2))
        assertEquals(listOf(w1, w2), grouped.web)
        assertEquals(listOf(f1, f2), grouped.files)
        assertEquals(listOf(o1), grouped.other)
    }

    @Test
    fun `extracts and decodes file URI paths cleanly`() {
        assertEquals("/home/user/Foo.kt", extractFileUriPath("file:///home/user/Foo.kt"))
        assertEquals("/tmp/my doc+v1.md", extractFileUriPath("file://localhost/tmp/my%20doc+v1.md"))
        assertEquals("/a/b.kt", extractFileUriPath("file:///a/b.kt#L42"))
        assertEquals("/a/b.kt", extractFileUriPath("file:///a/b.kt:18:4"))
        assertNull(extractFileUriPath("https://example.com/a/b.kt"))
    }

    @Test
    fun `picks basename for file links, label for OSC 8 links, and host for web links`() {
        assertEquals("Foo.kt", linkDisplayTitle(urlLink("file:///home/user/Foo.kt")))
        assertEquals("README.md", linkDisplayTitle(urlLink("file:///repo/README.md#L10")))
        assertEquals("Docs", linkDisplayTitle(urlLink("https://example.com/a", label = "Docs")))
        assertEquals("example.com", linkDisplayTitle(urlLink("https://example.com/a")))
    }
}
