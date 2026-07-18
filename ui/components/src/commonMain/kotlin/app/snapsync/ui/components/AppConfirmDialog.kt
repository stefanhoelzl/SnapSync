package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A semantic confirm/cancel dialog. The call site passes only the [title] text, the two button
 * labels, and the [onConfirm] / [onDismiss] callbacks — the dialog's visual form (the modal scrim,
 * button placement, typography) is the skin's. The two sit **side by side** and share one button
 * geometry, differing only in emphasis: cancel is the [SecondaryButton] (outlined) on the left, and
 * the confirm action is the [PrimaryButton] (filled) on the right — it completes the intent the user
 * already initiated. Dismissing via the scrim or back gesture routes to [onDismiss], identical to cancel.
 *
 * For an **irreversible** confirmation use [AppDestructiveConfirmDialog] instead — a distinct component,
 * not a flag here, since destructiveness is a design-time choice of the call site (per the `PrimaryButton`
 * convention).
 */
@Composable
fun AppConfirmDialog(
    title: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialogScaffold(title = title, cancelLabel = cancelLabel, onDismiss = onDismiss) {
        PrimaryButton(label = confirmLabel, onClick = onConfirm)
    }
}

/**
 * The destructive sibling of [AppConfirmDialog], signature-identical to it: the confirm action is
 * **irreversible** (e.g. leaving an event). The skin renders its confirm button filled with the error
 * accent while the cancel button stays the outlined secondary — the destructive treatment is the skin's,
 * never a parameter. Picking this component (rather than a flag) is how the call site expresses that the
 * confirmation is destructive, per the design system's "emphasis is a distinct component" convention.
 */
@Composable
fun AppDestructiveConfirmDialog(
    title: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialogScaffold(title = title, cancelLabel = cancelLabel, onDismiss = onDismiss) {
        DestructiveButton(label = confirmLabel, onClick = onConfirm)
    }
}

/**
 * The shared dialog body owned by both confirm dialogs: the modal scrim, the title, and the two buttons
 * side by side sharing one geometry — the outlined [SecondaryButton] cancel on the left, and the given
 * [confirm] composable on the right. Only the confirm button differs between the two public dialogs, so
 * scrim, layout, and cancel can never diverge.
 */
@Composable
private fun ConfirmDialogScaffold(
    title: String,
    cancelLabel: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) { SecondaryButton(label = cancelLabel, onClick = onDismiss) }
                Box(Modifier.weight(1f)) { confirm() }
            }
        },
    )
}

/**
 * The destructive filled button — [PrimaryButton]'s geometry (full-width, 52dp tall, 16dp corners),
 * but colored with the skin's **error** accent to mark an irreversible action. An internal skin helper:
 * it has no screen-level caller (screens' destructive affordance is the flat leave icon, and the confirm
 * button is owned inside the dialog), so it is deliberately not part of the public `App*` inventory.
 */
@Composable
private fun DestructiveButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
