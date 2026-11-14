package dev.shinglem.hermes.feature.session

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.sessions_archive_body
import dev.shinglem.hermes.resources.sessions_archive_cancel
import dev.shinglem.hermes.resources.sessions_archive_ok
import dev.shinglem.hermes.resources.sessions_archive_title
import dev.shinglem.hermes.resources.sessions_delete_body
import dev.shinglem.hermes.resources.sessions_delete_cancel
import dev.shinglem.hermes.resources.sessions_delete_ok
import dev.shinglem.hermes.resources.sessions_delete_title
import dev.shinglem.hermes.resources.sessions_rename_cancel
import dev.shinglem.hermes.resources.sessions_rename_hint
import dev.shinglem.hermes.resources.sessions_rename_ok
import dev.shinglem.hermes.resources.sessions_rename_title
import dev.shinglem.hermes.ui.component.ConfirmDialog
import org.jetbrains.compose.resources.stringResource
@Composable
internal fun RenameSessionDialog(
    title: String,
    onTitleChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sessions_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                singleLine = true,
                label = { Text(stringResource(Res.string.sessions_rename_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = title.isNotBlank()) {
                Text(stringResource(Res.string.sessions_rename_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sessions_rename_cancel)) }
        },
    )
}

@Composable
internal fun ConfirmDeleteDialog(
    sessionTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(Res.string.sessions_delete_title),
        message = stringResource(Res.string.sessions_delete_body, sessionTitle),
        confirmText = stringResource(Res.string.sessions_delete_ok),
        dismissText = stringResource(Res.string.sessions_delete_cancel),
        danger = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ConfirmArchiveDialog(
    sessionTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(Res.string.sessions_archive_title),
        message = stringResource(Res.string.sessions_archive_body, sessionTitle),
        confirmText = stringResource(Res.string.sessions_archive_ok),
        dismissText = stringResource(Res.string.sessions_archive_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

