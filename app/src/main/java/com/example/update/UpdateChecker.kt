package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.BuildConfig
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

sealed interface UpdateCheckResult {
    data object Idle : UpdateCheckResult
    data object Checking : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class UpdateAvailable(
        val latestVersion: String,
        val releaseNotes: String,
        val releaseUrl: String,
        val apkDownloadUrl: String? = null,
        val apkFileName: String? = null,
        val apkSizeBytes: Long? = null,
        val publishedAt: String? = null,
    ) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

private sealed interface ResolvedUpdateSource {
    data class GitHubRepo(val owner: String, val repo: String) : ResolvedUpdateSource {
        val latestReleaseApiUrl: String
            get() = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val releasesListApiUrl: String
            get() = "https://api.github.com/repos/$owner/$repo/releases?per_page=1"
        val releasesWebUrl: String
            get() = "https://github.com/$owner/$repo/releases"
    }

    data class CustomFeedUrl(val url: String) : ResolvedUpdateSource
}

/**
 * Play-policy-compliant update checker.
 *
 * Queries either a GitHub Releases endpoint (when given "owner/repo" or a github.com URL) or a
 * custom HTTPS JSON release manifest, compares semantic versions against [BuildConfig.VERSION_NAME],
 * extracts any direct `.apk` release asset URL (no zipping required), and opens the download/release
 * URL via [Intent.ACTION_VIEW].
 */
class UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(rawSource: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val source = rawSource.trim()
        if (source.isBlank()) {
            return@withContext UpdateCheckResult.Error(
                "Enter a GitHub repo (owner/repo) or release JSON URL first."
            )
        }

        val resolved = resolveSource(source)
            ?: return@withContext UpdateCheckResult.Error(
                "Enter a valid https:// URL or GitHub owner/repo."
            )

        try {
            when (resolved) {
                is ResolvedUpdateSource.GitHubRepo -> checkGitHubRepo(resolved)
                is ResolvedUpdateSource.CustomFeedUrl -> checkCustomFeed(resolved.url)
            }
        } catch (e: IOException) {
            UpdateCheckResult.Error("Network error: ${e.localizedMessage ?: "Unable to reach server"}")
        } catch (e: Exception) {
            UpdateCheckResult.Error("Could not parse update feed: ${e.localizedMessage ?: "Invalid format"}")
        }
    }

    private fun checkGitHubRepo(repo: ResolvedUpdateSource.GitHubRepo): UpdateCheckResult {
        val latestRequest = buildRequest(repo.latestReleaseApiUrl)
        client.newCall(latestRequest).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return UpdateCheckResult.Error("Empty response from GitHub Releases.")
                }
                return parseUpdateResponse(JSONObject(body), repo.releasesWebUrl)
            }

            if (response.code != 404) {
                return UpdateCheckResult.Error("Release check returned HTTP ${response.code}.")
            }
        }

        // Fallback to /releases?per_page=1 to handle pre-releases or detect zero published releases
        val listRequest = buildRequest(repo.releasesListApiUrl)
        client.newCall(listRequest).execute().use { fallbackResponse ->
            if (fallbackResponse.isSuccessful) {
                val body = fallbackResponse.body?.string().orEmpty()
                val releases = JSONArray(body)
                if (releases.length() == 0) {
                    return UpdateCheckResult.Error(
                        "Connected to ${repo.owner}/${repo.repo}, but no GitHub Releases have been published yet."
                    )
                }
                return parseUpdateResponse(releases.getJSONObject(0), repo.releasesWebUrl)
            }

            return if (fallbackResponse.code == 404) {
                UpdateCheckResult.Error(
                    "Release check returned HTTP 404 (repo is private, name is misspelled, or no release exists yet)."
                )
            } else {
                UpdateCheckResult.Error("Release check returned HTTP ${fallbackResponse.code}.")
            }
        }
    }

    private fun checkCustomFeed(url: String): UpdateCheckResult {
        val request = buildRequest(url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return UpdateCheckResult.Error("Release check returned HTTP ${response.code}.")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return UpdateCheckResult.Error("Empty response from update feed.")
            }
            return parseUpdateResponse(JSONObject(body), url)
        }
    }

    private fun buildRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json, application/json")
            .header("User-Agent", "QuietDrawer/${BuildConfig.VERSION_NAME}")
            .build()
    }

    private fun resolveSource(source: String): ResolvedUpdateSource? {
        val cleaned = source.trim().trimEnd('/')

        val githubWebMatch = Regex(
            "^https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/.*)?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(cleaned)
        if (githubWebMatch != null) {
            return ResolvedUpdateSource.GitHubRepo(
                owner = githubWebMatch.groupValues[1],
                repo = githubWebMatch.groupValues[2],
            )
        }

        val githubApiMatch = Regex(
            "^https://api\\.github\\.com/repos/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/.*)?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(cleaned)
        if (githubApiMatch != null) {
            return ResolvedUpdateSource.GitHubRepo(
                owner = githubApiMatch.groupValues[1],
                repo = githubApiMatch.groupValues[2],
            )
        }

        if (cleaned.startsWith("https://", ignoreCase = true)) {
            return ResolvedUpdateSource.CustomFeedUrl(cleaned)
        }

        val trimmedRepo = cleaned.trim('/').removeSuffix(".git")
        val githubRepoPattern = Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$")
        val repoMatch = githubRepoPattern.matchEntire(trimmedRepo)
        if (repoMatch != null) {
            return ResolvedUpdateSource.GitHubRepo(
                owner = repoMatch.groupValues[1],
                repo = repoMatch.groupValues[2],
            )
        }
        return null
    }

    private fun parseUpdateResponse(json: JSONObject, fallbackWebUrl: String): UpdateCheckResult {
        val remoteVersion = json.optString("tag_name").ifBlank {
            json.optString("versionName").ifBlank { json.optString("version") }
        }.trim()

        val remoteVersionCode = json.optInt("versionCode", -1)

        if (remoteVersion.isBlank() && remoteVersionCode <= 0) {
            return UpdateCheckResult.Error("Feed JSON is missing tag_name or versionName.")
        }

        val releaseNotes = json.optString("body").ifBlank {
            json.optString("releaseNotes").ifBlank { "Bug fixes and improvements." }
        }.trim()

        val releaseUrl = json.optString("html_url").ifBlank {
            json.optString("releaseUrl").ifBlank { fallbackWebUrl }
        }.trim()

        // Extract direct raw .apk asset URL so users never have to browse GitHub or unzip an artifact
        val apkAsset = extractApkAsset(json)
        val directApkUrl = apkAsset?.downloadUrl
            ?: json.optString("apkUrl").trim().takeIf { isValidHttpsApkUrl(it) }
            ?: json.optString("downloadUrl").trim().takeIf { isValidHttpsApkUrl(it) }

        val publishedAt = json.optString("published_at").ifBlank {
            json.optString("publishedAt")
        }.takeIf { it.isNotBlank() }

        val isNewer = if (remoteVersionCode > 0) {
            remoteVersionCode > BuildConfig.VERSION_CODE
        } else {
            isNewerSemVer(remoteVersion, BuildConfig.VERSION_NAME)
        }

        return if (isNewer) {
            UpdateCheckResult.UpdateAvailable(
                latestVersion = remoteVersion.ifBlank { "Build $remoteVersionCode" },
                releaseNotes = releaseNotes,
                releaseUrl = releaseUrl,
                apkDownloadUrl = directApkUrl,
                apkFileName = apkAsset?.fileName,
                apkSizeBytes = apkAsset?.sizeBytes,
                publishedAt = publishedAt,
            )
        } else {
            UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
        }
    }

    private data class ApkAssetInfo(
        val fileName: String,
        val downloadUrl: String,
        val sizeBytes: Long?,
    )

    private fun extractApkAsset(json: JSONObject): ApkAssetInfo? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "").trim()
            val url = asset.optString("browser_download_url", "").trim()
            if (name.endsWith(".apk", ignoreCase = true) && isValidHttpsUrl(url)) {
                val size = asset.optLong("size", -1L).takeIf { it > 0L }
                return ApkAssetInfo(
                    fileName = name,
                    downloadUrl = url,
                    sizeBytes = size,
                )
            }
        }
        return null
    }

    private fun isValidHttpsUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val uri = Uri.parse(url)
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    private fun isValidHttpsApkUrl(url: String): Boolean {
        if (!isValidHttpsUrl(url)) return false
        val path = Uri.parse(url).path.orEmpty()
        return path.endsWith(".apk", ignoreCase = true)
    }

    companion object {
        fun isNewerSemVer(remote: String, current: String): Boolean {
            val remoteParts = parseVersionParts(remote)
            val currentParts = parseVersionParts(current)
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseVersionParts(version: String): List<Int> {
            return version
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-")
                .split(".")
                .map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        }

        fun formatApkSize(bytes: Long?): String? {
            if (bytes == null || bytes <= 0L) return null
            val mb = bytes / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB", mb)
        }

        fun openReleaseUrl(context: Context, url: String): Boolean {
            val uri = Uri.parse(url)
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            return try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
