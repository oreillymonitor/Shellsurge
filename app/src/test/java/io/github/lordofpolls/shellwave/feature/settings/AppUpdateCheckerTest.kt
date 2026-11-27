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
    fun `parseLatestReleaseJson extracts apk asset url and preview`() {
        val json = """
            {
              "tag_name": "v1.10.36",
              "name": "ShellSurge v1.10.36",
              "html_url": "https://github.com/oreillymonitor/Shellsurge/releases/tag/v1.10.36",
              "body": "Automated build from master.\n\n### Latest Change\nAdded update link.",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://example.invalid/checksums.txt"
                },
                {
                  "name": "shellsurge-1.10.36.apk",
                  "browser_download_url": "https://github.com/oreillymonitor/Shellsurge/releases/download/v1.10.36/shellsurge-1.10.36.apk"
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
            "https://github.com/oreillymonitor/Shellsurge/releases/tag/v1.10.36",
            parsed.htmlUrl,
        )
        assertEquals(
            "https://github.com/oreillymonitor/Shellsurge/releases/download/v1.10.36/shellsurge-1.10.36.apk",
            parsed.apkDownloadUrl,
        )
        assertEquals("Automated build from master.", parsed.notesPreview)
    }

    @Test
    fun `parseLatestReleaseJson handles missing apk asset and malformed json`() {
        val noAssetJson = """
            {
              "tag_name": "v1.10.36",
              "html_url": "https://github.com/oreillymonitor/Shellsurge/releases/tag/v1.10.36",
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

    @Test
    fun `shouldAutoCheckForUpdates respects enabled flag and 12h interval`() {
        val now = 1_700_000_000_000L
        assertFalse(shouldAutoCheckForUpdates(autoCheckEnabled = false, lastCheckedAtMs = 0L, nowMs = now))
        assertTrue(shouldAutoCheckForUpdates(autoCheckEnabled = true, lastCheckedAtMs = 0L, nowMs = now))
        assertFalse(
            shouldAutoCheckForUpdates(
                autoCheckEnabled = true,
                lastCheckedAtMs = now - AUTO_UPDATE_CHECK_INTERVAL_MS + 1_000L,
                nowMs = now,
            )
        )
        assertTrue(
            shouldAutoCheckForUpdates(
                autoCheckEnabled = true,
                lastCheckedAtMs = now - AUTO_UPDATE_CHECK_INTERVAL_MS,
                nowMs = now,
            )
        )
    }

    @Test
    fun `shouldShowUpdateBanner checks version and dismissal`() {
        val release = AppReleaseInfo(
            tagName = "1.16.2",
            versionName = "1.16.2",
            releaseName = "ShellSurge 1.16.2",
            htmlUrl = "https://github.com/oreillymonitor/Shellsurge/releases/tag/1.16.2",
            apkDownloadUrl = "https://github.com/oreillymonitor/Shellsurge/releases/download/1.16.2/shellsurge-1.16.2.apk",
            notesPreview = "New release",
        )
        assertFalse(shouldShowUpdateBanner(null, "1.16.1", null))
        assertFalse(shouldShowUpdateBanner(release, "1.16.2", null))
        assertFalse(shouldShowUpdateBanner(release, "1.17.0", null))
        assertTrue(shouldShowUpdateBanner(release, "1.16.1", null))
        assertFalse(shouldShowUpdateBanner(release, "1.16.1", "1.16.2"))
        assertFalse(shouldShowUpdateBanner(release, "1.16.1", "v1.16.2"))
        assertTrue(shouldShowUpdateBanner(release, "1.16.1", "1.16.1"))
    }
}
