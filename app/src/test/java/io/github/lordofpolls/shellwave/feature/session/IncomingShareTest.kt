package io.github.lordofpolls.shellwave.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareTest {

    @Test
    fun `normalizeSharedContent trims whitespace and deduplicates matching subject`() {
        assertEquals(
            "Issue #42" to "https://github.com/org/repo/issues/42",
            normalizeSharedContent("  Issue #42  ", "  https://github.com/org/repo/issues/42 \n"),
        )
        assertEquals(
            null to "Issue #42: crash on startup",
            normalizeSharedContent("issue #42", "Issue #42: crash on startup"),
        )
        assertEquals(
            null to null,
            normalizeSharedContent("   ", "\n\t"),
        )
    }

    @Test
    fun `isImageAttachment matches image mime types and extensions`() {
        assertTrue(isImageAttachment("image/png", "upload"))
        assertTrue(isImageAttachment("IMAGE/JPEG; charset=utf-8", null))
        assertTrue(isImageAttachment("application/octet-stream", "photo.webp"))
        assertTrue(isImageAttachment(null, "IMG_001.HEIC"))

        assertFalse(isImageAttachment("application/pdf", "spec.pdf"))
        assertFalse(isImageAttachment("text/plain", "crash.log"))
        assertFalse(isImageAttachment(null, null))
    }

    @Test
    fun `resolveEffectiveMimeType falls back when resolver returns generic mime`() {
        assertEquals("image/png", resolveEffectiveMimeType("image/png", "image/*"))
        assertEquals("application/pdf", resolveEffectiveMimeType("*/*", "application/pdf"))
        assertEquals("image/jpeg", resolveEffectiveMimeType("application/octet-stream", "image/jpeg"))
        assertEquals("text/plain", resolveEffectiveMimeType(null, "text/plain; charset=utf-8"))
        assertNull(resolveEffectiveMimeType(null, "*/*"))
    }

    @Test
    fun `formatAttachmentSize formats unknown, bytes, kilobytes, and megabytes`() {
        assertNull(formatAttachmentSize(-1L))
        assertEquals("0 B", formatAttachmentSize(0L))
        assertEquals("420 B", formatAttachmentSize(420L))
        assertEquals("1 KB", formatAttachmentSize(1024L))
        assertEquals("18 KB", formatAttachmentSize(18_900L))
        assertEquals("2.5 MB", formatAttachmentSize(2_621_440L))
    }

    @Test
    fun `buildSharedPrompt handles text, attachments, framing presets, and notes`() {
        assertEquals(
            "/tmp/shellsurge-1-shot.png",
            buildSharedPrompt(
                subject = null,
                text = null,
                uploadedPaths = listOf(" /tmp/shellsurge-1-shot.png ", ""),
                preset = ShareFramingPreset.NONE,
            ),
        )

        assertEquals(
            "/tmp/shellsurge-1-a.png /tmp/shellsurge-2-b.log",
            buildSharedPrompt(
                subject = null,
                text = "   ",
                uploadedPaths = listOf("/tmp/shellsurge-1-a.png", "/tmp/shellsurge-2-b.log"),
                preset = ShareFramingPreset.NONE,
            ),
        )

        assertEquals(
            "Investigate this and explain the root cause:\n\nBug title\nhttps://example.com/1",
            buildSharedPrompt(
                subject = "Bug title",
                text = "https://example.com/1",
                uploadedPaths = emptyList(),
                preset = ShareFramingPreset.INVESTIGATE,
            ),
        )

        assertEquals(
            "Diagnose and fix this issue:\n\nCheck the layout overlap\n\nAttached: /tmp/shellsurge-1-shot.png",
            buildSharedPrompt(
                subject = null,
                text = null,
                uploadedPaths = listOf("/tmp/shellsurge-1-shot.png"),
                preset = ShareFramingPreset.FIX,
                note = "  Check the layout overlap  ",
            ),
        )

        assertEquals(
            "Implement this in the current codebase:\n\nPriority item\n\nSpec\nhttps://example.com/spec\n\nAttached:\n/tmp/shellsurge-1-a.png\n/tmp/shellsurge-2-b.pdf",
            buildSharedPrompt(
                subject = "Spec",
                text = "https://example.com/spec",
                uploadedPaths = listOf("/tmp/shellsurge-1-a.png", "/tmp/shellsurge-2-b.pdf"),
                preset = ShareFramingPreset.IMPLEMENT,
                note = "Priority item",
            ),
        )
    }
}
