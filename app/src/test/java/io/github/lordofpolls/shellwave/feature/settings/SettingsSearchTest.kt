package io.github.lordofpolls.shellwave.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    @Test
    fun `blank query returns every settings section`() {
        assertEquals(SettingsSection.entries.toSet(), matchingSettingsSections(""))
        assertEquals(SettingsSection.entries.toSet(), matchingSettingsSections("   "))
    }

    @Test
    fun `single keyword matches target section case-insensitively`() {
        assertEquals(setOf(SettingsSection.TAILSCALE), matchingSettingsSections("HeAdScAlE"))
        assertEquals(setOf(SettingsSection.HOSTS_SECURITY), matchingSettingsSections("biometric"))
        assertEquals(setOf(SettingsSection.HOSTS_SECURITY), matchingSettingsSections("recents"))
        assertEquals(setOf(SettingsSection.ON_DEVICE_AI), matchingSettingsSections("nano"))
        assertEquals(setOf(SettingsSection.ABOUT), matchingSettingsSections("apk"))
    }

    @Test
    fun `multi-token query requires all tokens within same section`() {
        assertEquals(setOf(SettingsSection.HOSTS_SECURITY), matchingSettingsSections("app lock"))
        assertEquals(setOf(SettingsSection.HOSTS_SECURITY), matchingSettingsSections("mobile data"))
        assertEquals(setOf(SettingsSection.CODING_AGENTS), matchingSettingsSections("claude codex"))
    }

    @Test
    fun `unmatched query returns empty set`() {
        assertTrue(matchingSettingsSections("xyznonexistent").isEmpty())
    }
}
