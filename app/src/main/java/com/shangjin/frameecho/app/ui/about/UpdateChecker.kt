package com.shangjin.frameecho.app.ui.about

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Represents a GitHub Release.
 *
 * @param tagName The tag name of the release (e.g., "v1.2.0").
 * @param name The human-readable name of the release.
 * @param htmlUrl The URL to the release page on GitHub.
 * @param body The release notes / changelog.
 */
data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String
) {
    /** Extracts the version string by stripping the leading 'v' if present. */
    val versionName: String
        get() = tagName.removePrefix("v").removePrefix("V")
}

/**
 * Checks for new releases of the app on GitHub.
 *
 * Uses the public GitHub API (no authentication required) to fetch
 * the latest release. Compares against the current app version using
 * simple semantic version comparison.
 */
object UpdateChecker {

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/Shangjin-Xiao/FrameEcho/releases/latest"

    /**
     * Fetches the latest release from GitHub.
     *
     * @return [ReleaseInfo] if a release was found, or `null` on any error.
     */
    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(responseBody)
            ReleaseInfo(
                tagName = json.optString("tag_name", ""),
                name = json.optString("name", ""),
                htmlUrl = json.optString("html_url", ""),
                body = json.optString("body", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns `true` when [remoteVersion] is strictly newer than [currentVersion].
     *
     * Both versions are expected to be in semantic-versioning format (e.g., "1.2.3").
     * Non-numeric suffixes (e.g., "-beta") are ignored during comparison.
     */
    fun isNewerVersion(currentVersion: String, remoteVersion: String): Boolean {
        val current = parseVersion(currentVersion)
        val remote = parseVersion(remoteVersion)

        for (i in 0 until maxOf(current.size, remote.size)) {
            val c = current.getOrElse(i) { 0 }
            val r = remote.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun parseVersion(version: String): List<Int> =
        version
            .removePrefix("v").removePrefix("V")
            .split(".")
            .map { segment ->
                // Strip non-numeric suffixes like "-beta", "-rc1"
                segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
}
