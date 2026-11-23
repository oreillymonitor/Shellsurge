package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

private const val KEY_GITHUB_UPDATE_TOKEN = "github_update_token"

/**
 * Stores an optional read-only GitHub Personal Access Token for checking and downloading releases
 * from a private repository. Excluded from ConfigExport because ConfigExport uses an explicit field
 * allowlist.
 */
object UpdatePreferences {
    fun getGithubToken(context: Context): String? =
        sharedPrefs(context).getString(KEY_GITHUB_UPDATE_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun setGithubToken(context: Context, token: String?) {
        val cleaned = token?.trim().orEmpty()
        sharedPrefs(context).edit {
            if (cleaned.isEmpty()) {
                remove(KEY_GITHUB_UPDATE_TOKEN)
            } else {
                putString(KEY_GITHUB_UPDATE_TOKEN, cleaned)
            }
        }
    }

    fun maskedPreview(token: String?): String? {
        val t = token?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (t.length <= 8) {
            "••••${t.takeLast(2)}"
        } else {
            "${t.take(4)}••••${t.takeLast(4)}"
        }
    }
}
