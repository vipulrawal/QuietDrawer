package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.backup.CredentialSignInResult
import com.example.backup.DriveAuthResult
import com.example.backup.GoogleAuthManager
import com.example.ui.PendingDriveAction
import com.example.ui.QuietDrawerViewModel
import com.example.ui.util.TimeFormatter
import kotlinx.coroutines.launch

@Composable
fun GoogleDriveBackupCard(
    viewModel: QuietDrawerViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()

    val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    val backupState by viewModel.driveBackupState.collectAsStateWithLifecycle()

    var pendingAction by remember { mutableStateOf<PendingDriveAction?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    val isSignedIn = !prefs.signedInEmail.isNullOrBlank()

    // Handles Google Play Services AuthorizationClient consent screen result
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { activityResult ->
        val action = pendingAction ?: PendingDriveAction.SIGN_IN_AND_CHECK
        pendingAction = null

        val tokenResult = GoogleAuthManager.extractAccessTokenFromIntent(context, activityResult.data)
        tokenResult
            .onSuccess { accessToken ->
                viewModel.completeDriveAction(action = action, accessToken = accessToken)
            }
            .onFailure { error ->
                viewModel.setDriveBackupError(
                    error.localizedMessage ?: "Google Drive authorization was cancelled."
                )
            }
    }

    // Zero-permission SAF local JSON backup export
    val localExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportLocalBackupToUri(uri)
        }
    }

    // Zero-permission SAF local JSON backup restore
    val localImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalBackupFromUri(uri)
        }
    }

    fun startDriveAction(action: PendingDriveAction, requireSignInFirst: Boolean) {
        val hostActivity = activity
        if (hostActivity == null) {
            viewModel.setDriveBackupError("Unable to access Activity context for Google Sign-In.")
            return
        }

        scope.launch {
            viewModel.setDriveBackupBusy(
                when (action) {
                    PendingDriveAction.SIGN_IN_AND_CHECK -> context.getString(R.string.backup_signing_in)
                    PendingDriveAction.BACKUP_NOW -> context.getString(R.string.backup_in_progress)
                    PendingDriveAction.RESTORE_NOW -> context.getString(R.string.backup_restoring)
                }
            )

            if (requireSignInFirst) {
                when (val credResult = GoogleAuthManager.signInWithCredentialManager(hostActivity)) {
                    is CredentialSignInResult.Success -> {
                        viewModel.recordSignedInAccount(
                            email = credResult.email,
                            displayName = credResult.displayName,
                        )
                    }
                    CredentialSignInResult.Cancelled -> {
                        viewModel.clearDriveBusyState()
                        return@launch
                    }
                    is CredentialSignInResult.FallbackToAuthClient -> {
                        // Proceed directly to Play Services AuthorizationClient which also handles account selection
                    }
                }
            }

            when (val authResult = GoogleAuthManager.authorizeDriveAccess(hostActivity)) {
                is DriveAuthResult.Authorized -> {
                    viewModel.completeDriveAction(action = action, accessToken = authResult.accessToken)
                }
                is DriveAuthResult.NeedsConsent -> {
                    pendingAction = action
                    try {
                        consentLauncher.launch(
                            IntentSenderRequest.Builder(authResult.intentSender).build()
                        )
                    } catch (e: Exception) {
                        pendingAction = null
                        viewModel.setDriveBackupError(
                            e.localizedMessage ?: "Could not open Google consent prompt."
                        )
                    }
                }
                is DriveAuthResult.Error -> {
                    viewModel.setDriveBackupError(authResult.message)
                }
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        startDriveAction(PendingDriveAction.RESTORE_NOW, requireSignInFirst = false)
                    },
                    modifier = Modifier.testTag("drive_confirm_restore_button"),
                ) {
                    Text(stringResource(R.string.backup_restore_confirm_cta))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.settings_backup_section).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("google_drive_backup_card"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isSignedIn) {
                    // Signed-out Google Sign-In & Drive Backup card
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.backup_signed_out_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = stringResource(R.string.backup_signed_out_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Button(
                        onClick = {
                            startDriveAction(
                                action = PendingDriveAction.SIGN_IN_AND_CHECK,
                                requireSignInFirst = true,
                            )
                        },
                        enabled = !backupState.isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("google_sign_in_button"),
                    ) {
                        if (backupState.isBusy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(backupState.busyMessage ?: stringResource(R.string.backup_signing_in))
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_sign_in_cta))
                        }
                    }
                } else {
                    // Signed-in Account header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            val initial = (prefs.signedInDisplayName ?: prefs.signedInEmail ?: "G")
                                .trim()
                                .firstOrNull()
                                ?.uppercase() ?: "G"
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = prefs.signedInDisplayName ?: "Google Account",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = prefs.signedInEmail.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        IconButton(
                            onClick = { viewModel.signOutGoogleAccount() },
                            enabled = !backupState.isBusy,
                            modifier = Modifier.testTag("google_sign_out_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = stringResource(R.string.backup_sign_out),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Cloud backup status chip
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            val lastAt = prefs.lastDriveBackupAt
                            val cloudMeta = backupState.cloudBackup
                            val summaryText = when {
                                lastAt != null -> {
                                    "Last backup: ${TimeFormatter.formatCompactTimestamp(context, lastAt)}"
                                }
                                cloudMeta != null -> {
                                    val kb = (cloudMeta.sizeBytes / 1024L).coerceAtLeast(1L)
                                    "Drive backup available (${kb} KB)"
                                }
                                else -> stringResource(R.string.backup_no_cloud_copy)
                            }
                            Text(
                                text = summaryText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Primary Backup & Restore buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = {
                                startDriveAction(
                                    action = PendingDriveAction.BACKUP_NOW,
                                    requireSignInFirst = false,
                                )
                            },
                            enabled = !backupState.isBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("drive_backup_now_button"),
                        ) {
                            if (backupState.isBusy) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = backupState.busyMessage ?: stringResource(R.string.backup_in_progress),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.backup_now_cta))
                            }
                        }

                        OutlinedButton(
                            onClick = { showRestoreConfirm = true },
                            enabled = !backupState.isBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("drive_restore_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.backup_restore_cta))
                        }
                    }
                }

                // Inline status or error feedback
                backupState.statusMessage?.let { status ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                backupState.errorMessage?.let { err ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(16.dp),
                        )
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Privacy assurance note
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.backup_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Zero-permission local JSON export/import fallback
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = { localExportLauncher.launch("quietdrawer-backup.json") },
                        enabled = !backupState.isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("local_backup_export_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.backup_local_export))
                    }

                    TextButton(
                        onClick = { localImportLauncher.launch(arrayOf("application/json", "*/*")) },
                        enabled = !backupState.isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("local_backup_import_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.backup_local_import))
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
