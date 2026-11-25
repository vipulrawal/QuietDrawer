package com.example.backup

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GoogleUserProfile(
    val email: String,
    val displayName: String?,
)

data class DriveBackupMetadata(
    val fileId: String,
    val modifiedTimeIso: String?,
    val sizeBytes: Long,
)

/**
 * Direct HTTPS client for Google Drive REST API v3 targeting ONLY the private `appDataFolder`
 * space (`https://www.googleapis.com/auth/drive.appdata`).
 *
 * Access tokens are passed per-request in memory and are never persisted or logged.
 */
class GoogleDriveBackupClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetchUserProfile(accessToken: String): GoogleUserProfile? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(USERINFO_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null
                val json = JSONObject(body)
                val email = json.optString("email", "").trim()
                if (email.isBlank()) return@withContext null
                val name = json.optString("name", "").trim().takeIf { it.isNotBlank() }
                GoogleUserProfile(email = email, displayName = name)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun findLatestBackupMetadata(accessToken: String): DriveBackupMetadata? =
        withContext(Dispatchers.IO) {
            val url = DRIVE_FILES_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "name = '$BACKUP_FILE_NAME' and trashed = false")
                .addQueryParameter("fields", "files(id,name,modifiedTime,size)")
                .addQueryParameter("orderBy", "modifiedTime desc")
                .addQueryParameter("pageSize", "1")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Google Drive check failed (HTTP ${response.code}).")
                }
                val body = response.body?.string().orEmpty()
                val files = JSONObject(body).optJSONArray("files") ?: return@withContext null
                if (files.length() == 0) return@withContext null
                val fileObj = files.getJSONObject(0)
                DriveBackupMetadata(
                    fileId = fileObj.getString("id"),
                    modifiedTimeIso = fileObj.optString("modifiedTime", "").takeIf { it.isNotBlank() },
                    sizeBytes = fileObj.optString("size", "0").toLongOrNull() ?: 0L,
                )
            }
        }

    suspend fun uploadBackup(
        accessToken: String,
        jsonPayload: String,
    ): DriveBackupMetadata = withContext(Dispatchers.IO) {
        val existing = findLatestBackupMetadata(accessToken)
        if (existing != null) {
            updateExistingFile(accessToken, existing.fileId, jsonPayload)
        } else {
            createNewFileInAppData(accessToken, jsonPayload)
        }
    }

    private fun updateExistingFile(
        accessToken: String,
        fileId: String,
        jsonPayload: String,
    ): DriveBackupMetadata {
        val safeId = fileId.trim()
        require(FILE_ID_PATTERN.matches(safeId)) { "Invalid Drive file identifier." }

        val url = "$DRIVE_UPLOAD_ENDPOINT/$safeId".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", "id,modifiedTime,size")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .patch(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Google Drive upload failed (HTTP ${response.code}).")
            }
            val body = response.body?.string().orEmpty()
            val fileObj = JSONObject(body)
            return DriveBackupMetadata(
                fileId = fileObj.optString("id", safeId),
                modifiedTimeIso = fileObj.optString("modifiedTime", "").takeIf { it.isNotBlank() },
                sizeBytes = fileObj.optString("size", jsonPayload.toByteArray().size.toString())
                    .toLongOrNull() ?: jsonPayload.toByteArray().size.toLong(),
            )
        }
    }

    private fun createNewFileInAppData(
        accessToken: String,
        jsonPayload: String,
    ): DriveBackupMetadata {
        val boundary = "quietdrawer_${System.currentTimeMillis()}"
        val metadataJson = JSONObject().apply {
            put("name", BACKUP_FILE_NAME)
            put("mimeType", "application/json")
            put("parents", JSONArray().put("appDataFolder"))
        }.toString()

        val multipartPayload = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadataJson)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(jsonPayload)
            append("\r\n--$boundary--")
        }

        val url = DRIVE_UPLOAD_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,modifiedTime,size")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(multipartPayload.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Google Drive backup creation failed (HTTP ${response.code}).")
            }
            val body = response.body?.string().orEmpty()
            val fileObj = JSONObject(body)
            return DriveBackupMetadata(
                fileId = fileObj.getString("id"),
                modifiedTimeIso = fileObj.optString("modifiedTime", "").takeIf { it.isNotBlank() },
                sizeBytes = fileObj.optString("size", jsonPayload.toByteArray().size.toString())
                    .toLongOrNull() ?: jsonPayload.toByteArray().size.toLong(),
            )
        }
    }

    suspend fun downloadBackup(
        accessToken: String,
        fileId: String,
    ): String = withContext(Dispatchers.IO) {
        val safeId = fileId.trim()
        require(FILE_ID_PATTERN.matches(safeId)) { "Invalid Drive file identifier." }

        val url = "$DRIVE_FILES_ENDPOINT/$safeId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Google Drive download failed (HTTP ${response.code}).")
            }
            val bytes = response.body?.bytes()
                ?: throw IOException("Empty backup response from Google Drive.")
            if (bytes.size > BackupSerializer.MAX_BACKUP_BYTES) {
                throw IOException("Backup file exceeds maximum supported size.")
            }
            bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        private const val BACKUP_FILE_NAME = "quietdrawer-backup-v1.json"
        private const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo"
        private const val DRIVE_FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val FILE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")
    }
}
