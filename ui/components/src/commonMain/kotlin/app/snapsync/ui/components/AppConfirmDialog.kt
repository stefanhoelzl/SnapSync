package app.snapsync.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * A semantic confirm/cancel dialog, drawn as an **iOS-style alert**. The call site passes only the [title]
 * text, an optional [body] line, the two button labels, and the [onConfirm] / [onDismiss] callbacks — the
 * dialog's visual form is the skin's. It is a narrow (~270dp) **centre-aligned** card: a bold centred title,
 * a muted centred body, then a hairline, then the two actions **stacked full-width** and separated by a
 * hairline — the confirm on top, and the **cancel bold at the bottom** (the iOS convention: the safe,
 * emphasised way out sits last). Dismissing via the scrim or back gesture routes to [onDismiss], identical
 * to cancel.
 *
 * The [title] is a short question; the optional [body] states, in one muted line, the consequence of
 * confirming.
 *
 * For an **irreversible** confirmation use [AppDestructiveConfirmDialog] instead — a distinct component,
 * not a flag here, since destructiveness is a design-time choice of the call site.
 */
@Composable
fun AppConfirmDialog(
    title: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
) {
    ConfirmDialogScaffold(
        title = title,
        body = body,
        onDismiss = onDismiss,
        actions = listOf(
            DialogAction(confirmLabel, DialogActionKind.Default, onConfirm),
            DialogAction(cancelLabel, DialogActionKind.Cancel, onDismiss),
        ),
    )
}

/**
 * The destructive sibling of [AppConfirmDialog], signature-identical to it: the confirm action is
 * **irreversible** (e.g. leaving an event). The skin renders that action as **red text** (never a filled
 * red pill — the most prominent element must not be the dangerous one, the iOS convention), sitting above
 * the **bold cancel** at the bottom. The destructive treatment is the skin's, never a parameter; picking
 * this component (rather than a flag) is how the call site expresses that the confirmation is destructive.
 */
@Composable
fun AppDestructiveConfirmDialog(
    title: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
) {
    ConfirmDialogScaffold(
        title = title,
        body = body,
        onDismiss = onDismiss,
        actions = listOf(
            DialogAction(confirmLabel, DialogActionKind.Destructive, onConfirm),
            DialogAction(cancelLabel, DialogActionKind.Cancel, onDismiss),
        ),
    )
}

/** How an alert action renders: [Default]/[Cancel] both bold on the accent tint; [Destructive] red. */
private enum class DialogActionKind { Default, Cancel, Destructive }

private class DialogAction(val label: String, val kind: DialogActionKind, val onClick: () -> Unit)

/**
 * The shared iOS-alert body owned by both confirm dialogs: the scrim + narrow centred card, the bold
 * centred title, the muted centred body, and the stacked full-width text actions separated by hairlines.
 * Only the action list differs between the two public dialogs, so the card, layout, and cancel can never
 * diverge.
 *
 * The container stays an [BasicAlertDialog] (the modal scrim + outside-tap dismiss); [DialogProperties]
 * drops the platform default width so the card can take the ~270dp iOS proportion. Every colour is **pinned
 * to the frozen scheme** — an unpinned M3 surface falls back to the Material baseline tonal surface (a
 * lavender cast on our green palette, the recurring "unpinned = baseline violet" bug) — so `surface`,
 * `onSurface`, `onSurfaceVariant`, `primary`, `error`, and `outlineVariant` are all named here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmDialogScaffold(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
    actions: List<DialogAction>,
) {
    val scheme = MaterialTheme.colorScheme
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = scheme.surface,
            contentColor = scheme.onSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(min = 270.dp, max = 300.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    if (body != null) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                HorizontalDivider(color = scheme.outlineVariant)
                actions.forEachIndexed { index, action ->
                    if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
                    DialogActionButton(action)
                }
            }
        }
    }
}

/**
 * One stacked alert action: a full-width, ≥44dp text button, centred, whose colour and weight carry its
 * emphasis. Cancel and the default confirm are **bold** on the accent tint; a destructive action is drawn
 * in **red text** at regular weight — so the dangerous action is never the boldest thing on the card.
 */
@Composable
private fun DialogActionButton(action: DialogAction) {
    val scheme = MaterialTheme.colorScheme
    val color = when (action.kind) {
        DialogActionKind.Destructive -> scheme.error
        DialogActionKind.Default, DialogActionKind.Cancel -> scheme.primary
    }
    val weight = when (action.kind) {
        DialogActionKind.Destructive -> FontWeight.Normal
        DialogActionKind.Default, DialogActionKind.Cancel -> FontWeight.SemiBold
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = action.onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = weight),
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}
