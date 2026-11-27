package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit
import io.github.lordofpolls.shellwave.feature.settings.AppReleaseInfo

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_AUTO_CHECK_ENABLED = "update_auto_check_enabled"
private const val KEY_LAST_CHECK_AT_MS = "update_last_check_at_ms"
private const val KEY_DISMISSED_VERSION = "update_dismissed_version"
private const val KEY_CACHED_TAG = "update_cached_tag"
private const val KEY_CACHED_VERSION = "update_cached_version"
private const val KEY_CACHED_NAME = "update_cached_name"
private const val KEY_CACHED_HTML_URL = "update_cached_html_url"
private const val KEY_CACHED_APK_URL = "update_cached_apk_url"
private const val KEY_CACHED_NOTES = "update_cached_notes"

/**
 * Stores automatic GitHub release check settings, throttle timestamp, cached latest release
 * metadata, and dismissed banner version in `shellwave_prefs` so [rememberPrefState] reacts
 * immediately across Home and Settings.
 */
object UpdatePreferences {

    fun isAutoCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHECK_ENABLED, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_CHECK_ENABLED, enabled) }
    }

    fun getLastCheckAtMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CHECK_AT_MS, 0L)

    fun setLastCheckAtMs(context: Context, timestampMs: Long) {
        prefs(context).edit { putLong(KEY_LAST_CHECK_AT_MS, timestampMs) }
    }

    fun getDismissedVersion(context: Context): String? =
        prefs(context).getString(KEY_DISMISSED_VERSION, null)

    fun setDismissedVersion(context: Context, version: String?) {
        prefs(context).edit {
            if (version.isNullOrBlank()) {
                remove(KEY_DISMISSED_VERSION)
            } else {
                putString(KEY_DISMISSED_VERSION, version)
            }
        }
    }

    fun getCachedRelease(context: Context): AppReleaseInfo? {
        val p = prefs(context)
        val tagName = p.getString(KEY_CACHED_TAG, null)?.takeIf { it.isNotBlank() } ?: return null
        val versionName = p.getString(KEY_CACHED_VERSION, null)?.takeIf { it.isNotBlank() } ?: return null
        val releaseName = p.getString(KEY_CACHED_NAME, null)?.takeIf { it.isNotBlank() } ?: tagName
        val htmlUrl = p.getString(KEY_CACHED_HTML_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return AppReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            releaseName = releaseName,
            htmlUrl = htmlUrl,
            apkDownloadUrl = p.getString(KEY_CACHED_APK_URL, null)?.takeIf { it.isNotBlank() },
            notesPreview = p.getString(KEY_CACHED_NOTES, null)?.takeIf { it.isNotBlank() },
        )
    }

    fun saveCachedRelease(context: Context, release: AppReleaseInfo) {
        prefs(context).edit {
            putString(KEY_CACHED_TAG, release.tagName)
                .putString(KEY_CACHED_VERSION, release.versionName)
                .putString(KEY_CACHED_NAME, release.releaseName)
                .putString(KEY_CACHED_HTML_URL, release.htmlUrl)
                .putString(KEY_CACHED_APK_URL, release.apkDownloadUrl)
                .putString(KEY_CACHED_NOTES, release.notesPreview)
        }
    }

    fun clearCachedRelease(context: Context) {
        prefs(context).edit {
            remove(KEY_CACHED_TAG)
                .remove(KEY_CACHED_VERSION)
                .remove(KEY_CACHED_NAME)
                .remove(KEY_CACHED_HTML_URL)
                .remove(KEY_CACHED_APK_URL)
                .remove(KEY_CACHED_NOTES)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
