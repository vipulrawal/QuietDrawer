package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface UpdateCheckResult {
    data object Idle : UpdateCheckResult
    data object Checking : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class UpdateAvailable(
        val latestVersion: String,
        val releaseNotes: String,
        val releaseUrl: String,
        val publishedAt: String?,
    ) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Play-policy-compliant update checker.
 *
 * Queries either a GitHub Releases endpoint (when given "owner/repo") or a custom JSON release
 * manifest URL over HTTPS, compares semantic versions against [BuildConfig.VERSION_NAME], and
 * opens the release page via [Intent.ACTION_VIEW] for one-click seamless in-place updates.
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

        val resolvedUrl = resolveEndpointUrl(source)
            ?: return@withContext UpdateCheckResult.Error(
                "Enter a valid https:// URL or GitHub owner/repo."
            )

        val request = Request.Builder()
            .url(resolvedUrl)
            .header("Accept", "application/vnd.github+json, application/json")
            .header("User-Agent", "QuietDrawer/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val hint = if (response.code == 404 && resolvedUrl.startsWith("https://api.github.com/")) {
                        "Release check returned HTTP 404 (repo is private, name is misspelled, or no release has been published yet)."
                    } else {
                        "Release check returned HTTP ${response.code}."
                    }
                    return@withContext UpdateCheckResult.Error(hint)
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext UpdateCheckResult.Error("Empty response from update feed.")
                }
                parseUpdateResponse(body, source)
            }
        } catch (e: IOException) {
            UpdateCheckResult.Error("Network error: ${e.localizedMessage ?: "Unable to reach server"}")
        } catch (e: Exception) {
            UpdateCheckResult.Error("Could not parse update feed: ${e.localizedMessage ?: "Invalid format"}")
        }
    }

    private fun resolveEndpointUrl(source: String): String? {
        val cleaned = source.trim().trimEnd('/')
        // Normalize full GitHub web URLs (e.g. https://github.com/owner/repo[/releases...])
        val githubWebMatch = Regex(
            "^https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/.*)?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(cleaned)
        if (githubWebMatch != null) {
            val owner = githubWebMatch.groupValues[1]
            val repo = githubWebMatch.groupValues[2]
            return "https://api.github.com/repos/$owner/$repo/releases/latest"
        }

        if (cleaned.startsWith("https://", ignoreCase = true)) {
            return cleaned
        }

        // Support shorthand "owner/repo" (also tolerating trailing .git or leading/trailing slashes)
        val trimmedRepo = cleaned.trim('/').removeSuffix(".git")
        val githubRepoPattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        if (githubRepoPattern.matches(trimmedRepo)) {
            return "https://api.github.com/repos/$trimmedRepo/releases/latest"
        }
        return null
    }

    private fun parseUpdateResponse(body: String, fallbackSource: String): UpdateCheckResult {
        val json = JSONObject(body)

        // Supports both GitHub Releases schema and simple custom manifest schema
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
            json.optString("downloadUrl").ifBlank {
                json.optString("url").ifBlank {
                    if (fallbackSource.startsWith("https://")) fallbackSource
                    else "https://github.com/$fallbackSource/releases/latest"
                }
            }
        }.trim()

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
                publishedAt = publishedAt,
            )
        } else {
            UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
        }
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

        fun openReleaseUrl(context: Context, url: String): Boolean {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" && uri.scheme != "http") return false
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
