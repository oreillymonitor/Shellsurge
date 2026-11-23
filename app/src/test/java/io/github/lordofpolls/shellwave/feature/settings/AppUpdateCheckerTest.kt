package io.github.lordofpolls.shellwave.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun `normalizeVersion strips leading v prefix and whitespace`() {
        assertEquals("1.10.36", normalizeVersion("v1.10.36"))
        assertEquals("1.10.36", normalizeVersion(" V1.10.36 "))
        assertEquals("1.10.36", normalizeVersion("1.10.36"))
    }

    @Test
    fun `compareVersions and isNewerVersion handle numeric segments`() {
        assertTrue(isNewerVersion("v1.10.36", "1.10.35"))
        assertTrue(isNewerVersion("1.10.36", "1.10.9"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
        assertEquals(0, compareVersions("1.10.35", "v1.10.35"))
        assertFalse(isNewerVersion("1.10.35", "1.10.35"))
        assertFalse(isNewerVersion("1.10.34", "1.10.35"))
    }

    @Test
    fun `parseLatestReleaseJson extracts apk asset and preview`() {
        val json = """
            {
              "tag_name": "v1.10.36",
              "name": "ShellSurge v1.10.36",
              "html_url": "https://github.com/oreillymonitor/Shellwave/releases/tag/v1.10.36",
              "body": "Automated build from master.\n\n### Latest Change\nAdded update link.",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://example.invalid/checksums.txt"
                },
                {
                  "name": "shellsurge-1.10.36.apk",
                  "browser_download_url": "https://github.com/oreillymonitor/Shellwave/releases/download/v1.10.36/shellsurge-1.10.36.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = parseLatestReleaseJson(json)
        assertNotNull(parsed)
        parsed!!
        assertEquals("v1.10.36", parsed.tagName)
        assertEquals("1.10.36", parsed.versionName)
        assertEquals("ShellSurge v1.10.36", parsed.releaseName)
        assertEquals(
            "https://github.com/oreillymonitor/Shellwave/releases/tag/v1.10.36",
            parsed.htmlUrl,
        )
        assertEquals(
            "https://github.com/oreillymonitor/Shellwave/releases/download/v1.10.36/shellsurge-1.10.36.apk",
            parsed.apkDownloadUrl,
        )
        assertEquals("Automated build from master.", parsed.notesPreview)
    }

    @Test
    fun `parseLatestReleaseJson handles missing apk asset and malformed json`() {
        val noAssetJson = """
            {
              "tag_name": "v1.10.36",
              "html_url": "https://github.com/oreillymonitor/Shellwave/releases/tag/v1.10.36",
              "assets": []
            }
        """.trimIndent()

        val parsed = parseLatestReleaseJson(noAssetJson)
        assertNotNull(parsed)
        assertNull(parsed!!.apkDownloadUrl)
        assertNull(parsed.notesPreview)

        assertNull(parseLatestReleaseJson("not json at all"))
        assertNull(parseLatestReleaseJson("{}"))
    }
}
