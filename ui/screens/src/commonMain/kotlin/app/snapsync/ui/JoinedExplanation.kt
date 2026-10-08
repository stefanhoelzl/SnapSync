package app.snapsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snapsync.model.EventConfig
import app.snapsync.model.Layer
import app.snapsync.model.SyncHealth
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.ui.components.AppExplainRow
import app.snapsync.ui.components.AppEyebrow
import app.snapsync.ui.components.CaptionLink
import app.snapsync.ui.components.ExplainAction
import app.snapsync.ui.components.ExplainState
import app.snapsync.ui.components.ExplainSubject
import app.snapsync.ui.components.EyebrowTone
import app.snapsync.ui.components.appDateRangeLabel
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.allow_full_access
import app.snapsync.ui.resources.choose_more_photos
import app.snapsync.ui.resources.explain_access_link
import app.snapsync.ui.resources.explain_eyebrow
import app.snapsync.ui.resources.explain_hint_caption
import app.snapsync.ui.resources.explain_hint_title
import app.snapsync.ui.resources.explain_receive_album
import app.snapsync.ui.resources.explain_receive_blocked_caption
import app.snapsync.ui.resources.explain_receive_blocked_title
import app.snapsync.ui.resources.explain_receive_no_album
import app.snapsync.ui.resources.explain_receive_off_caption
import app.snapsync.ui.resources.explain_receive_off_title
import app.snapsync.ui.resources.explain_receive_title
import app.snapsync.ui.resources.explain_settings_link
import app.snapsync.ui.resources.explain_share_blocked_caption
import app.snapsync.ui.resources.explain_share_blocked_title
import app.snapsync.ui.resources.explain_share_caption
import app.snapsync.ui.resources.explain_share_limited_caption
import app.snapsync.ui.resources.explain_share_limited_title
import app.snapsync.ui.resources.explain_share_off_caption
import app.snapsync.ui.resources.explain_share_off_title
import app.snapsync.ui.resources.explain_share_title
import org.jetbrains.compose.resources.stringResource

// The joined screen's explanation of how the event works for this member (capability `sync-status`).

/**
 * "How it works" (capability `sync-status`): what happens to the member's photos, to the group's, and what to do
 * when photos are not arriving. Each row follows the membership's current choices and the grant; a row about
 * something that is not happening says how to change it, with the route there. A closed event shares nothing
 * any more, so it keeps only the receiving row and the hint.
 */
@Composable
internal fun Explanation(
    state: Layer.Joined,
    cutoff: CutoffFormatter,
    access: AccessActions,
    onOpenEventSettings: () -> Unit,
) {
    val membership = state.membership
    val blocked = state.health as? SyncHealth.NeedsAccess
    val settingsLink = CaptionLink(stringResource(Res.string.explain_settings_link), onOpenEventSettings)
    val accessText = stringResource(Res.string.explain_access_link)
    val accessLink = blocked?.let { CaptionLink(accessText, accessAction(it, access)) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AppEyebrow(stringResource(Res.string.explain_eyebrow), EyebrowTone.Accent)
        }
        if (!state.closed) {
            SharingRow(
                membership = membership,
                range = sharedRange(membership, cutoff),
                limited = state.canChoosePhotos,
                accessLink = accessLink,
                settingsLink = settingsLink,
                access = access,
            )
        }
        ReceivingRow(membership, accessLink, settingsLink)
        AppExplainRow(
            subject = ExplainSubject.HINT,
            state = ExplainState.ON,
            title = stringResource(Res.string.explain_hint_title),
            caption = stringResource(Res.string.explain_hint_caption),
        )
    }
}

/** The member's own shared range, as the dates line words a range — the same sentence before, during and after. */
@Composable
private fun sharedRange(membership: EventConfig, cutoff: CutoffFormatter): String {
    val from = cutoff.toLocal(membership.minPhotoDate)
    return appDateRangeLabel(
        start = from,
        end = cutoff.toLocal(membership.maxPhotoDate),
        today = cutoff.nowLocal().date,
    )
}

@Composable
private fun SharingRow(
    membership: EventConfig,
    range: String,
    limited: Boolean,
    accessLink: CaptionLink?,
    settingsLink: CaptionLink,
    access: AccessActions,
) {
    // The partial-grant resting affordances (capability `photo-access`): beside the row that says what is
    // shared, because widening the selection widens exactly that. Widen the selection (the cheaper step) above,
    // switch the grant itself below; neither is an attention state.
    val choices: (@Composable ColumnScope.() -> Unit)? = if (limited) {
        {
            ExplainAction(label = stringResource(Res.string.choose_more_photos), onClick = access.onChoosePhotos)
            ExplainAction(label = stringResource(Res.string.allow_full_access), onClick = access.onOpenSettings)
        }
    } else {
        null
    }
    when {
        accessLink != null -> AppExplainRow(
            subject = ExplainSubject.SHARING,
            state = ExplainState.BLOCKED,
            title = stringResource(Res.string.explain_share_blocked_title),
            caption = stringResource(Res.string.explain_share_blocked_caption, accessLink.text, range),
            link = accessLink,
        )
        !membership.direction.includesUpload -> AppExplainRow(
            subject = ExplainSubject.SHARING,
            state = ExplainState.OFF,
            title = stringResource(Res.string.explain_share_off_title),
            caption = stringResource(Res.string.explain_share_off_caption, settingsLink.text, range),
            link = settingsLink,
            trailing = choices,
        )
        limited -> AppExplainRow(
            subject = ExplainSubject.SHARING,
            state = ExplainState.ON,
            title = stringResource(Res.string.explain_share_limited_title),
            caption = stringResource(Res.string.explain_share_limited_caption, range),
            trailing = choices,
        )
        else -> AppExplainRow(
            subject = ExplainSubject.SHARING,
            state = ExplainState.ON,
            title = stringResource(Res.string.explain_share_title),
            caption = stringResource(Res.string.explain_share_caption, range),
        )
    }
}

@Composable
private fun ReceivingRow(membership: EventConfig, accessLink: CaptionLink?, settingsLink: CaptionLink) {
    when {
        accessLink != null -> AppExplainRow(
            subject = ExplainSubject.RECEIVING,
            state = ExplainState.BLOCKED,
            title = stringResource(Res.string.explain_receive_blocked_title),
            caption = stringResource(Res.string.explain_receive_blocked_caption, accessLink.text),
            link = accessLink,
        )
        !membership.direction.includesDownload -> AppExplainRow(
            subject = ExplainSubject.RECEIVING,
            state = ExplainState.OFF,
            title = stringResource(Res.string.explain_receive_off_title),
            caption = stringResource(Res.string.explain_receive_off_caption, settingsLink.text),
            link = settingsLink,
        )
        else -> AppExplainRow(
            subject = ExplainSubject.RECEIVING,
            state = ExplainState.ON,
            title = stringResource(Res.string.explain_receive_title),
            caption = if (membership.saveToAlbum) {
                stringResource(Res.string.explain_receive_album, membership.name)
            } else {
                stringResource(Res.string.explain_receive_no_album)
            },
        )
    }
}
