package io.github.lordofpolls.shellwave.feature.settings

import io.github.lordofpolls.shellwave.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal const val RELEASES_PAGE_URL = "$SOURCE_REPOSITORY_URL/releases/latest"
private const val LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/oreillymonitor/Shellwave/releases/latest"
private const val GITHUB_API_VERSION = "2022-11-28"
private const val HTTP_TIMEOUT_MS = 8_000

/** Parsed metadata for the latest GitHub Release of this repository. */
data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val notesPreview: String?,
    val apkAssetApiUrl: String? = null,
)

/** UI state for checking GitHub Releases from Settings. */
sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val currentVersion: String, val release: AppReleaseInfo) : UpdateCheckState
    data class UpdateAvailable(val currentVersion: String, val release: AppReleaseInfo) : UpdateCheckState
    data class NoReleases(val message: String) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

internal fun normalizeVersion(raw: String): String =
    raw.trim().removePrefix("v").removePrefix("V").trim()

internal fun compareVersions(a: String, b: String): Int {
    val partsA = normalizeVersion(a).substringBefore('-').substringBefore('+').split('.')
        .map { it.toIntOrNull() ?: 0 }
    val partsB = normalizeVersion(b).substringBefore('-').substringBefore('+').split('.')
        .map { it.toIntOrNull() ?: 0 }
    val count = maxOf(partsA.size, partsB.size)
    for (i in 0 until count) {
        val va = partsA.getOrElse(i) { 0 }
        val vb = partsB.getOrElse(i) { 0 }
        if (va != vb) return va.compareTo(vb)
    }
    return 0
}

internal fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean =
    compareVersions(remoteVersion, currentVersion) > 0

internal fun parseLatestReleaseJson(jsonBody: String): AppReleaseInfo? =
    runCatching {
        val json = JSONObject(jsonBody)
        val tagName = json.optString("tag_name", "").trim()
        if (tagName.isEmpty()) return@runCatching null

        val versionName = normalizeVersion(tagName)
        val releaseName = json.optString("name", "").trim().ifEmpty { tagName }
        val htmlUrl = json.optString("html_url", "").trim().ifEmpty { RELEASES_PAGE_URL }

        var apkUrl: String? = null
        var apkApiUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    val downloadUrl = asset.optString("browser_download_url", "").trim()
                    val apiUrl = asset.optString("url", "").trim()
                    if (downloadUrl.isNotEmpty() || apiUrl.isNotEmpty()) {
                        apkUrl = downloadUrl.ifEmpty { null }
                        apkApiUrl = apiUrl.ifEmpty { null }
                        break
                    }
                }
            }
        }

        val body = json.optString("body", "")
        val notesPreview = body.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() && !line.startsWith(">") && !line.startsWith("#")
            }
            ?.let { line ->
                if (line.length > 140) line.take(139) + "…" else line
            }

        AppReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            releaseName = releaseName,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkUrl,
            notesPreview = notesPreview,
            apkAssetApiUrl = apkApiUrl,
        )
    }.getOrNull()

/** Queries GitHub's latest-release endpoint and compares its tag against [currentVersion]. */
suspend fun checkForUpdate(
    currentVersion: String = BuildConfig.VERSION_NAME,
    githubToken: String? = null,
): UpdateCheckState =
    withContext(Dispatchers.IO) {
        val token = githubToken?.trim()?.takeIf { it.isNotEmpty() }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
                setRequestProperty("User-Agent", "ShellSurge/$currentVersion")
                if (token != null) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_UNAUTHORIZED ->
                    UpdateCheckState.Error("GitHub token was rejected (HTTP 401). Update or clear the token.")

                HttpURLConnection.HTTP_NOT_FOUND ->
                    if (token == null) {
                        UpdateCheckState.NoReleases(
                            "Private repo or no releases visible. Add a GitHub token below or open the Releases page in your browser.",
                        )
                    } else {
                        UpdateCheckState.NoReleases(
                            "No releases found (or token lacks Contents: Read access to this repository).",
                        )
                    }

                HttpURLConnection.HTTP_FORBIDDEN ->
                    UpdateCheckState.Error(
                        "Access denied or rate limit reached (HTTP 403). Check token permissions or open Releases page.",
                    )

                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val release = parseLatestReleaseJson(body)
                        ?: return@withContext UpdateCheckState.Error("Invalid release response.")
                    if (isNewerVersion(release.versionName, currentVersion)) {
                        UpdateCheckState.UpdateAvailable(currentVersion, release)
                    } else {
                        UpdateCheckState.UpToDate(currentVersion, release)
                    }
                }

                else -> UpdateCheckState.Error("Update check failed (HTTP $code).")
            }
        } catch (e: Exception) {
            UpdateCheckState.Error(e.message ?: "Network error while checking for updates.")
        } finally {
            connection?.disconnect()
        }
    }

/**
 * Resolves a short-lived signed browser-download URL for a private GitHub release asset when an
 * API token is configured; falls back to [AppReleaseInfo.apkDownloadUrl] or [AppReleaseInfo.htmlUrl].
 */
suspend fun resolveApkDownloadUrl(
    release: AppReleaseInfo,
    githubToken: String?,
    currentVersion: String = BuildConfig.VERSION_NAME,
): String =
    withContext(Dispatchers.IO) {
        val token = githubToken?.trim()?.takeIf { it.isNotEmpty() }
        val assetApiUrl = release.apkAssetApiUrl?.takeIf { it.isNotBlank() }
        if (token != null && assetApiUrl != null) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(assetApiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    setRequestProperty("User-Agent", "ShellSurge/$currentVersion")
                }
                if (connection.responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")?.trim()?.takeIf { it.isNotEmpty() }
                    if (location != null) return@withContext location
                }
            } catch (_: Exception) {
                // Fall back to the browser URL below.
            } finally {
                connection?.disconnect()
            }
        }
        release.apkDownloadUrl ?: release.htmlUrl
    }
