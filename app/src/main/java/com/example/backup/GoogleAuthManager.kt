package com.example.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface DriveAuthResult {
    data class Authorized(val accessToken: String) : DriveAuthResult
    data class NeedsConsent(val intentSender: IntentSender) : DriveAuthResult
    data class Error(
        val message: String,
        val isOAuthConfigError: Boolean = false,
    ) : DriveAuthResult
}

class DriveTokenFailure(
    override val message: String,
    val isOAuthConfigError: Boolean = false,
) : IllegalStateException(message)

/**
 * Coordinates single-step Google Account Sign-In and least-privilege Google Drive App Data
 * authorization via Play Services [Identity.getAuthorizationClient].
 *
 * Using AuthorizationClient directly avoids double account-picker prompts and silent
 * Credential Manager cancellations when an APK signing SHA-1 needs to be registered.
 */
object GoogleAuthManager {

    private val DRIVE_BACKUP_SCOPES = listOf(
        Scope("https://www.googleapis.com/auth/drive.appdata"),
        Scope("https://www.googleapis.com/auth/userinfo.email"),
        Scope("https://www.googleapis.com/auth/userinfo.profile"),
    )

    suspend fun authorizeDriveAccess(activity: Activity): DriveAuthResult =
        suspendCancellableCoroutine { cont ->
            try {
                val authRequest = AuthorizationRequest.builder()
                    .setRequestedScopes(DRIVE_BACKUP_SCOPES)
                    .build()

                // Do NOT scope addOnCompleteListener to `activity` — doing so drops the callback
                // if Activity.onStop() fires while Play Services shows a system dialog.
                Identity.getAuthorizationClient(activity)
                    .authorize(authRequest)
                    .addOnCompleteListener { task ->
                        if (!cont.isActive) return@addOnCompleteListener

                        if (task.isCanceled) {
                            cont.resume(
                                DriveAuthResult.Error(
                                    message = "Google Sign-In did not complete. If it closed right after selecting your account, add this app's SHA-1 fingerprint to your Google Cloud Android OAuth Client.",
                                    isOAuthConfigError = true,
                                )
                            )
                            return@addOnCompleteListener
                        }

                        if (!task.isSuccessful) {
                            val ex = task.exception
                            val (msg, isConfig) = if (ex is ApiException) {
                                friendlyApiError(ex.statusCode)
                            } else {
                                (ex?.localizedMessage ?: "Unable to authorize Google Drive.") to false
                            }
                            cont.resume(DriveAuthResult.Error(message = msg, isOAuthConfigError = isConfig))
                            return@addOnCompleteListener
                        }

                        val result = task.result
                        val pendingIntent = result.pendingIntent
                        val accessToken = result.accessToken

                        when {
                            result.hasResolution() && pendingIntent != null -> {
                                cont.resume(DriveAuthResult.NeedsConsent(pendingIntent.intentSender))
                            }
                            !accessToken.isNullOrBlank() -> {
                                cont.resume(DriveAuthResult.Authorized(accessToken))
                            }
                            else -> {
                                cont.resume(
                                    DriveAuthResult.Error(
                                        message = "Google Drive authorization did not return an access token.",
                                        isOAuthConfigError = true,
                                    )
                                )
                            }
                        }
                    }
            } catch (e: Exception) {
                if (cont.isActive) {
                    val (msg, isConfig) = if (e is ApiException) {
                        friendlyApiError(e.statusCode)
                    } else {
                        (e.localizedMessage ?: "Unable to start Google Drive authorization.") to false
                    }
                    cont.resume(DriveAuthResult.Error(message = msg, isOAuthConfigError = isConfig))
                }
            }
        }

    fun extractAccessTokenFromIntent(
        context: Context,
        resultCode: Int,
        intent: Intent?,
    ): Result<String> {
        if (intent == null) {
            return Result.failure(
                DriveTokenFailure(
                    message = if (resultCode == Activity.RESULT_CANCELED) {
                        "Google Sign-In closed before granting access. If you tapped your Gmail account and it immediately dismissed, add this APK's SHA-1 fingerprint below to an Android OAuth Client in Google Cloud Console."
                    } else {
                        "Google Drive authorization returned no data (result $resultCode)."
                    },
                    isOAuthConfigError = true,
                )
            )
        }

        return try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(intent)
            val token = result.accessToken
            if (token.isNullOrBlank()) {
                Result.failure(
                    DriveTokenFailure(
                        message = "Google Sign-In did not return a Drive access token. Verify the Android OAuth Client SHA-1 below matches this build.",
                        isOAuthConfigError = true,
                    )
                )
            } else {
                Result.success(token)
            }
        } catch (e: ApiException) {
            val (msg, isConfig) = friendlyApiError(e.statusCode)
            Result.failure(DriveTokenFailure(message = msg, isOAuthConfigError = isConfig))
        } catch (e: Exception) {
            Result.failure(
                DriveTokenFailure(
                    message = e.localizedMessage ?: "Google Drive authorization failed.",
                    isOAuthConfigError = false,
                )
            )
        }
    }

    /**
     * Computes this installed APK's public signing certificate SHA-1 fingerprint so the user
     * can copy and register it in Google Cloud Console if needed.
     */
    @Suppress("DEPRECATION")
    fun getAppSigningSha1(context: Context): String? {
        return try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                val signingInfo = pkgInfo.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                ).signatures
            }

            val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return null
            val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
            digest.joinToString(":") { byte -> "%02X".format(byte) }
        } catch (_: Exception) {
            null
        }
    }

    private fun friendlyApiError(statusCode: Int): Pair<String, Boolean> {
        return when (statusCode) {
            10 -> Pair(
                "Google OAuth rejected this build's signing key (status 10). Add the package name and SHA-1 fingerprint below as an Android OAuth Client in Google Cloud Console.",
                true,
            )
            16 -> Pair(
                "Google Sign-In was cancelled (status 16). If you selected your Gmail account and it closed immediately, register the SHA-1 fingerprint below in Google Cloud Console.",
                true,
            )
            8 -> Pair(
                "Google Play Services returned an internal error (status 8). Verify the SHA-1 fingerprint below is registered in Google Cloud Console and your account is listed as a Test User.",
                true,
            )
            7 -> Pair(
                "Network error while contacting Google (status 7). Check your internet connection and try again.",
                false,
            )
            else -> Pair(
                "Google authorization failed (code $statusCode). If this persists, check that the SHA-1 fingerprint below is registered in Google Cloud Console.",
                true,
            )
        }
    }
}
