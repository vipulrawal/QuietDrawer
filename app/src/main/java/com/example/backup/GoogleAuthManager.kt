package com.example.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.BuildConfig
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface CredentialSignInResult {
    data class Success(val email: String, val displayName: String?) : CredentialSignInResult
    data object Cancelled : CredentialSignInResult
    data class FallbackToAuthClient(val reason: String) : CredentialSignInResult
}

sealed interface DriveAuthResult {
    data class Authorized(val accessToken: String) : DriveAuthResult
    data class NeedsConsent(val intentSender: IntentSender) : DriveAuthResult
    data object Cancelled : DriveAuthResult
    data class Error(val message: String) : DriveAuthResult
}

/**
 * Coordinates modern Android Google Sign-In ([CredentialManager]) and least-privilege
 * Google Drive App Data authorization ([Identity.getAuthorizationClient]).
 */
object GoogleAuthManager {

    private val DRIVE_BACKUP_SCOPES = listOf(
        Scope("https://www.googleapis.com/auth/drive.appdata"),
        Scope("https://www.googleapis.com/auth/userinfo.email"),
        Scope("https://www.googleapis.com/auth/userinfo.profile"),
    )

    suspend fun signInWithCredentialManager(activity: Activity): CredentialSignInResult {
        val serverClientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
        if (serverClientId.isBlank()) {
            return CredentialSignInResult.FallbackToAuthClient("OAuth client ID not configured")
        }

        return try {
            val credentialManager = CredentialManager.create(activity)
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId)
                .setNonce(generateSecureNonce())
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val response = credentialManager.getCredential(
                context = activity,
                request = request,
            )

            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                CredentialSignInResult.Success(
                    email = googleCred.id,
                    displayName = googleCred.displayName,
                )
            } else {
                CredentialSignInResult.FallbackToAuthClient("Unexpected credential type")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            CredentialSignInResult.Cancelled
        } catch (e: GetCredentialException) {
            CredentialSignInResult.FallbackToAuthClient(e.localizedMessage ?: "Credential Manager unavailable")
        } catch (e: Exception) {
            CredentialSignInResult.FallbackToAuthClient(e.localizedMessage ?: "Sign-in fallback")
        }
    }

    suspend fun authorizeDriveAccess(activity: Activity): DriveAuthResult =
        suspendCancellableCoroutine { cont ->
            try {
                val authRequest = AuthorizationRequest.builder()
                    .setRequestedScopes(DRIVE_BACKUP_SCOPES)
                    .build()

                Identity.getAuthorizationClient(activity)
                    .authorize(authRequest)
                    .addOnCompleteListener(activity) { task ->
                        if (!cont.isActive) return@addOnCompleteListener

                        if (task.isCanceled) {
                            cont.resume(DriveAuthResult.Cancelled)
                            return@addOnCompleteListener
                        }

                        if (!task.isSuccessful) {
                            val ex = task.exception
                            val msg = if (ex is ApiException) {
                                friendlyApiErrorMessage(ex.statusCode)
                            } else {
                                ex?.localizedMessage ?: "Unable to authorize Google Drive."
                            }
                            cont.resume(DriveAuthResult.Error(msg))
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
                                    DriveAuthResult.Error("Google Drive authorization did not return an access token.")
                                )
                            }
                        }
                    }
            } catch (e: Exception) {
                if (cont.isActive) {
                    val msg = if (e is ApiException) {
                        friendlyApiErrorMessage(e.statusCode)
                    } else {
                        e.localizedMessage ?: "Unable to start Google Drive authorization."
                    }
                    cont.resume(DriveAuthResult.Error(msg))
                }
            }
        }

    fun extractAccessTokenFromIntent(
        context: Context,
        resultCode: Int,
        intent: Intent?,
    ): Result<String> {
        if (resultCode != Activity.RESULT_OK && intent == null) {
            return Result.failure(IllegalStateException("Google Drive sign-in was cancelled."))
        }
        return try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(intent)
            val token = result.accessToken
            if (token.isNullOrBlank()) {
                Result.failure(IllegalStateException("Google Drive authorization was cancelled."))
            } else {
                Result.success(token)
            }
        } catch (e: ApiException) {
            Result.failure(IllegalStateException(friendlyApiErrorMessage(e.statusCode)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signOut(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Safe to ignore if no credential state was cached
        }
    }

    private fun generateSecureNonce(byteLength: Int = 32): String {
        val randomBytes = ByteArray(byteLength)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(
            randomBytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )
    }

    private fun friendlyApiErrorMessage(statusCode: Int): String {
        return when (statusCode) {
            10 -> "Google OAuth app fingerprint not yet linked in Cloud Console (status 10). Add package com.aistudio.quietdrawer.mrvkxp & SHA-1 to an Android OAuth Client in your project, or use Save/Restore local copy below."
            16 -> "Google sign-in was cancelled."
            7 -> "Network error while contacting Google. Please check your connection."
            else -> "Google authorization failed (code $statusCode). Please try again."
        }
    }
}
