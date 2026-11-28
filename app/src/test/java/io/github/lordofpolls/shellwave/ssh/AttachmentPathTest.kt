package io.github.lordofpolls.shellwave.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPathTest {

    @Test
    fun `preserves clean pdf and markdown filenames`() {
        assertEquals(
            "/tmp/shellsurge-1700000000000-design-spec.pdf",
            buildRemoteAttachmentPath("design-spec.pdf", "application/pdf", 1700000000000L),
        )
        assertEquals(
            "/tmp/shellsurge-1700000000000-NOTES_v2.md",
            buildRemoteAttachmentPath("NOTES_v2.md", "text/markdown", 1700000000000L),
        )
    }

    @Test
    fun `sanitizes spaces, punctuation, and shell metacharacters while keeping extension`() {
        assertEquals(
            "/tmp/shellsurge-42-Quarterly_Report_v2_final.pdf",
            buildRemoteAttachmentPath(
                "Quarterly Report (v2) \$;\"final\".PDF",
                "application/pdf",
                42L,
            ),
        )
    }

    @Test
    fun `strips directory traversal and windows paths to basename`() {
        assertEquals(
            "/tmp/shellsurge-10-passwd",
            buildRemoteAttachmentPath("../../etc/passwd", null, 10L),
        )
        assertEquals(
            "/tmp/shellsurge-10-notes.md",
            buildRemoteAttachmentPath("dir\\sub\\notes.md", "text/markdown", 10L),
        )
    }

    @Test
    fun `falls back to mime type when displayName is missing or extensionless`() {
        assertEquals(
            "/tmp/shellsurge-99-file.pdf",
            buildRemoteAttachmentPath(null, "application/pdf", 99L),
        )
        assertEquals(
            "/tmp/shellsurge-99-file.md",
            buildRemoteAttachmentPath("   ", "text/markdown; charset=utf-8", 99L),
        )
        assertEquals(
            "/tmp/shellsurge-99-file",
            buildRemoteAttachmentPath(null, "application/octet-stream", 99L),
        )
        assertEquals(
            "/tmp/shellsurge-99-notes.md",
            buildRemoteAttachmentPath("notes", "text/markdown", 99L),
        )
    }

    @Test
    fun `truncates overlong filename to 64 chars while preserving extension`() {
        val longName = "${"a".repeat(100)}.pdf"
        val path = buildRemoteAttachmentPath(longName, "application/pdf", 123L)
        val fileName = path.removePrefix("/tmp/shellsurge-123-")
        assertTrue(fileName.length <= 64)
        assertTrue(fileName.endsWith(".pdf"))
        assertEquals("${"a".repeat(60)}.pdf", fileName)
    }

    @Test
    fun `avoids dot-only or dot-prefixed hidden filenames`() {
        assertEquals(
            "/tmp/shellsurge-5-file",
            buildRemoteAttachmentPath("...", null, 5L),
        )
        assertEquals(
            "/tmp/shellsurge-5-hidden",
            buildRemoteAttachmentPath(".hidden", null, 5L),
        )
    }
}
