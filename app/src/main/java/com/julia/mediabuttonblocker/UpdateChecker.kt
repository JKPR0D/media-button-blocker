package com.julia.mediabuttonblocker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Information about a release that the in-app updater can offer to the user.
 */
data class UpdateInfo(
    /** Cleaned version string from the GitHub tag, e.g. "1.2" (no leading "v"). */
    val version: String,
    /** Direct browser download URL of the APK asset. */
    val apkUrl: String,
    /** Human-readable file name of the APK asset, used as the local file name. */
    val apkFileName: String,
    /** GitHub release page URL, used as a fallback if download/install fails. */
    val releasePageUrl: String,
)

/**
 * Talks to the GitHub Releases API and decides whether a newer published release
 * exists than the version currently installed.
 *
 * No authentication is needed because the repository is public; the unauthenticated
 * rate limit (60 requests / hour / IP) is more than enough for a once-per-launch check.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val OWNER = "JKPR0D"
    private const val REPO = "media-button-blocker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /**
     * Returns an [UpdateInfo] when the latest GitHub release is strictly newer
     * than [currentVersion], otherwise returns `null`. Network/parse errors are
     * logged and swallowed — a failed update check should never crash or block
     * the rest of the UI.
     */
    suspend fun fetchLatestRelease(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7_000
                readTimeout = 7_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "media-button-blocker-android")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "GitHub API responded ${connection.responseCode}")
                    return@withContext null
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").ifEmpty { return@withContext null }
                val pageUrl = json.optString("html_url")
                val assets = json.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                var apkName: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        apkName = name
                        break
                    }
                }
                if (apkUrl.isNullOrEmpty() || apkName.isNullOrEmpty()) {
                    Log.w(TAG, "Latest release has no .apk asset")
                    return@withContext null
                }
                val remoteVersion = tag.removePrefix("v").trim()
                if (!isStrictlyNewer(currentVersion, remoteVersion)) return@withContext null
                UpdateInfo(
                    version = remoteVersion,
                    apkUrl = apkUrl,
                    apkFileName = apkName,
                    releasePageUrl = pageUrl,
                )
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Update check failed", t)
            null
        }
    }

    /**
     * Compares two dotted numeric version strings (e.g. "1.10", "2.0.1"). Returns
     * `true` when [remote] represents a strictly newer version than [current].
     * Non-numeric segments fall back to lexicographic comparison so unexpected
     * tag formats don't trigger spurious updates.
     */
    internal fun isStrictlyNewer(current: String, remote: String): Boolean =
        compareVersions(current, remote) < 0

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-')
        val pb = b.split('.', '-')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val sa = pa.getOrNull(i) ?: "0"
            val sb = pb.getOrNull(i) ?: "0"
            val ia = sa.toIntOrNull()
            val ib = sb.toIntOrNull()
            val cmp = if (ia != null && ib != null) ia.compareTo(ib) else sa.compareTo(sb)
            if (cmp != 0) return cmp
        }
        return 0
    }
}
