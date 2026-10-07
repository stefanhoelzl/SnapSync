package app.snapsync.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.snapsync.model.Arrow
import app.snapsync.model.DeviceRefusal
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.cannot_verify_detail
import app.snapsync.ui.components.resources.cannot_verify_title
import app.snapsync.ui.components.resources.cannot_verify_title_modified
import app.snapsync.ui.components.resources.cannot_verify_title_not_genuine
import app.snapsync.ui.components.resources.cannot_verify_title_unverifiable
import app.snapsync.ui.components.resources.network_blocked
import app.snapsync.ui.components.resources.network_offline
import app.snapsync.ui.components.resources.status_allow_access
import app.snapsync.ui.components.resources.status_allow_access_settings
import app.snapsync.ui.components.resources.status_in_sync
import app.snapsync.ui.components.resources.status_inactive
import app.snapsync.ui.components.resources.status_not_started
import app.snapsync.ui.components.resources.status_receiving
import app.snapsync.ui.components.resources.status_sharing
import app.snapsync.ui.components.resources.status_sync_ongoing
import app.snapsync.ui.components.resources.status_sync_pending
import app.snapsync.ui.components.resources.status_syncing
import app.snapsync.ui.components.resources.status_waiting_wifi
import org.jetbrains.compose.resources.stringResource

/** One half-cycle of the in-flight arrow's pulse, in milliseconds. */
private const val PULSE_MILLIS = 700

/**
 * The joined-layer sync health, rendered as the single status line. A sealed semantic value (runtime
 * data), not a set of components — the call site passes only the health and, for the attention state,
 * an `onClick`; never appearance. The counts are not here: they are the screen's own line beneath this one.
 */
sealed interface AppSyncStatus {
    /** Joined but persisted state not read yet — a neutral first frame. */
    data object Loading : AppSyncStatus

    /** Everything shared and received — settled (no arrows). */
    data object InSync : AppSyncStatus

    /**
     * Work remaining; each arrow is [Arrow.HIDDEN]/[Arrow.STATIC]/[Arrow.PULSING]. [waitingForWifi]: the photos wait
     * for Wi-Fi because the member keeps them off mobile data (capability `mobile-data`).
     */
    data class Syncing(val upload: Arrow, val download: Arrow, val waitingForWifi: Boolean = false) : AppSyncStatus

    /**
     * The event has not begun (capability `sync-status`). Informational, not actionable: flat (no
     * background) and NOT tappable. It says what the wait MEANS — sharing starts with the event — and not
     * when: the joined screen's dates line says that, and time is said in one place.
     */
    data object NotStarted : AppSyncStatus

    /**
     * The member switched both sharing and receiving off (capability `sync-status`): they stay in the event and
     * nothing moves. Informational and flat like [NotStarted], NOT tappable — the explanation's rows already lead to
     * the event's settings.
     */
    data object Inactive : AppSyncStatus

    /**
     * Photo access is off — the sole attention state; the only one with a background, tappable.
     * [prompt] distinguishes the never-asked case (tap requests) from the denied case (tap → Settings),
     * so the copy matches the action.
     */
    data class NeedsAccess(val prompt: AccessPrompt) : AppSyncStatus

    /**
     * Sharing is blocked because this device could not verify itself with the backend — it is offline, or
     * the backend is refusing it. Rendered like [NeedsAccess] (an attention line with a background), but
     * NOT tappable: unlike a permission prompt, there is no action the user can take. It clears itself the
     * moment verification succeeds.
     *
     * A user should essentially never see this: opening the app re-verifies, so merely looking at this
     * screen normally clears it. It survives only when that re-verification keeps failing — which is the
     * one case worth showing, because the alternative is a screen reporting "Syncing" while nothing can
     * upload at all.
     *
     * [cause] is why the service refused this phone, when it did: the headline then names it (capability
     * `sync-status`). `null` — no verdict — keeps the cause-less headline. Still never tappable.
     */
    data class CannotVerifyDevice(val cause: DeviceRefusal? = null) : AppSyncStatus

    /**
     * The device gives the app no usable network (capability `sync-status`) — [AppNetworkNotice] in the status-line
     * slot: tappable when [blocked], since the member can allow it in Settings; not when offline, since only
     * connecting helps.
     */
    data class NoNetwork(val blocked: Boolean) : AppSyncStatus
}

/** Which permission action the attention line offers: request the initial grant, or open Settings. */
enum class AccessPrompt { ALLOW, SETTINGS }

// The attention line's amber lives in the palette ([appAttentionText] / [appAttentionContainer] in
// AppTheme.kt), with the contrast measurements that chose those values. This component keeps the
// BEHAVIOUR — which status carries a pill, which is tappable — and holds no colour values of its own,
// so the design system has exactly one place a colour can be changed.
private val IconSize = 20.dp

// The dimmed opacity a `Static` arrow renders at, and the floor a `Pulsing` one animates from.
private const val STATIC_ALPHA = 0.38f

/**
 * Renders the one-line sync health. `InSync`/`Syncing`/`Loading`/`NotStarted` are flat text-with-glyph
 * (no background — e.g. a bare green check for `InSync`, a clock for `NotStarted`); `NeedsAccess` and
 * `CannotVerifyDevice` carry a background, and only `NeedsAccess` is tappable ([onAttentionClick]) —
 * `CannotVerifyDevice` offers the user no action, because there is none. A `Pulsing` arrow animates its
 * opacity; a `Static` arrow is shown dimmed without motion. `NoNetwork` is [AppNetworkNotice]: tappable only when
 * blocked.
 */
@Composable
fun AppStatusLine(
    status: AppSyncStatus,
    onAttentionClick: () -> Unit = {},
) {
    StatusBody(status, onAttentionClick)
}

/** The one-line status content itself. */
@Composable
private fun StatusBody(status: AppSyncStatus, onAttentionClick: () -> Unit) {
    when (status) {
        AppSyncStatus.Loading ->
            LineText(stringResource(Res.string.status_syncing), MaterialTheme.colorScheme.onSurfaceVariant)

        AppSyncStatus.InSync ->
            IconLine(
                icon = Icons.Filled.Check, // a bare checkmark, no filled disc behind it
                tint = MaterialTheme.colorScheme.primary,
                text = stringResource(Res.string.status_in_sync),
            )

        is AppSyncStatus.Syncing -> SyncingLine(status)

        AppSyncStatus.NotStarted ->
            IconLine(
                icon = Icons.Filled.Schedule, // a clock: the event exists, it simply has not begun
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                text = stringResource(Res.string.status_not_started),
            )

        AppSyncStatus.Inactive ->
            IconLine(
                icon = Icons.Outlined.PauseCircle, // paused: the membership stands, nothing moves
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                text = stringResource(Res.string.status_inactive),
            )

        is AppSyncStatus.NeedsAccess -> AttentionButton(
            text = when (status.prompt) {
                AccessPrompt.ALLOW -> stringResource(Res.string.status_allow_access)
                AccessPrompt.SETTINGS -> stringResource(Res.string.status_allow_access_settings)
            },
            onClick = onAttentionClick,
        )

        is AppSyncStatus.CannotVerifyDevice -> CannotVerifyDeviceLine(status.cause)

        is AppSyncStatus.NoNetwork -> AppNetworkNotice(blocked = status.blocked, onOpenSettings = onAttentionClick)
    }
}

/**
 * An icon and a word: the shape **In sync** and **Not started** both are, differing only in which icon,
 * which tint and which words. They were written out twice.
 */
@Composable
private fun IconLine(icon: ImageVector, tint: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSize))
        LineText(text, MaterialTheme.colorScheme.onSurface)
    }
}

/** Two arrows and a label, both arrows fading on ONE shared phase. */
@Composable
private fun SyncingLine(status: AppSyncStatus.Syncing) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Label tracks live activity: any pulsing (in-flight) arrow reads "ongoing", else "pending".
        val ongoing = status.upload == Arrow.PULSING || status.download == Arrow.PULSING
        // ONE phase for BOTH arrows, hoisted here — above either arrow — because an animation's
        // phase starts when it enters composition, and the two arrows do not enter together. The
        // upload arm starts at join; the download arm's total is populated only by the later
        // reconcile, so the download arrow essentially always begins pulsing mid-fade. Owning a
        // fade each, they settled into opposite halves of it and visibly beat against one another
        // — reported from a device as "arrows are not pulsing in sync", and measured at ~90% of
        // full opposition for a 366 ms offset.
        //
        // Sharing the VALUE, not merely the transition, is deliberate. An `InfiniteTransition`
        // does share one play time, so a second `animateFloat` added later snaps into phase — but
        // one frame late, rendering once at `STATIC_ALPHA` before it does: a dim flash on the arrow
        // that just appeared. One value has no such frame, and no second animation computing an
        // identical number.
        //
        // Built only while something actually pulses, and never under reduce-motion (which drops
        // the fade, not the meaning — see `ArrowIcon`). When the last pulse stops the phase ends,
        // and a later resume starts a fresh one; that is fine, because both arrows resume on it
        // together, which is the whole of what was wrong.
        val pulseAlpha = if (ongoing && !LocalReduceMotion.current) {
            val transition = rememberInfiniteTransition(label = "pulse")
            val a by transition.animateFloat(
                initialValue = STATIC_ALPHA,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
                label = "pulse-alpha",
            )
            a
        } else {
            1f
        }
        ArrowIcon(Icons.Filled.ArrowUpward, stringResource(Res.string.status_sharing), status.upload, pulseAlpha)
        ArrowIcon(Icons.Filled.ArrowDownward, stringResource(Res.string.status_receiving), status.download, pulseAlpha)
        val label = when {
            ongoing -> stringResource(Res.string.status_sync_ongoing)
            status.waitingForWifi -> stringResource(Res.string.status_waiting_wifi)
            else -> stringResource(Res.string.status_sync_pending)
        }
        LineText(label, MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Why the app has no network (capability `sync-status`) — the same pill on every screen that says it: the joined
 * status line, the create screen's line below Create, the join screen. [blocked]: the member can allow the network
 * for SnapSync, so the pill is a button opening Settings ([onOpenSettings]); offline: only connecting helps, so it
 * offers nothing.
 */
@Composable
fun AppNetworkNotice(blocked: Boolean, onOpenSettings: () -> Unit) {
    if (blocked) {
        AttentionButton(stringResource(Res.string.network_blocked), onOpenSettings)
    } else {
        AttentionNotice(stringResource(Res.string.network_offline))
    }
}

/** The tappable attention pill: something the member can fix, so it carries a chevron and a button role. */
@Composable
private fun AttentionButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = appAttentionContainer(),
        contentColor = appAttentionText(),
        shape = RoundedCornerShape(999.dp),
        // The tappable attention line is a button: give assistive tech the role it lacked, and
        // guarantee the ≥44dp iOS touch target the 9dp padding alone did not reach.
        modifier = Modifier.semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(IconSize))
            // The text yields width, never the chevron: a label that wraps keeps the pill reading as a button.
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
            )
        }
    }
}

/** The attention pill with nothing to tap: a state the member is told about but cannot act on from here. */
@Composable
private fun AttentionNotice(text: String) {
    Surface(
        color = appAttentionContainer(),
        contentColor = appAttentionText(),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(IconSize))
            Text(text = text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The same attention treatment as [AttentionButton] — but NOT tappable, and with no chevron: there is no
 * action the user can take. It clears itself as soon as the device can reach the backend.
 */
@Composable
private fun CannotVerifyDeviceLine(cause: DeviceRefusal?) {
    Surface(
        color = appAttentionContainer(),
        contentColor = appAttentionText(),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(IconSize))
            // Headline plus a reassurance — NOT a remedy, because this is the one attention
            // state with no action to offer. The detail line used to read "Reopen the app or
            // check your connection", which failed twice over: reopening the app is what fired
            // the re-verify the member is already waiting on, and "your connection" is one of
            // two causes this single state absorbs — a member whose device the backend is
            // refusing was being told to check a connection that was fine. One state may
            // collapse several causes, but then it may only say what is true of every one of
            // them: the app keeps trying, and no photo is lost. (Colour is inherited
            // contentColor; no scheme line is touched here.)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // A definite refusal names its cause; the detail below stays true of every cause.
                Text(
                    text = stringResource(
                        when (cause) {
                            null -> Res.string.cannot_verify_title
                            DeviceRefusal.DEVICE_MODIFIED -> Res.string.cannot_verify_title_modified
                            DeviceRefusal.DEVICE_UNVERIFIABLE -> Res.string.cannot_verify_title_unverifiable
                            DeviceRefusal.APP_NOT_GENUINE -> Res.string.cannot_verify_title_not_genuine
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.cannot_verify_detail),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LineText(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = color)
}

/**
 * One direction arrow. It holds NO animation state: [pulseAlpha] is the one phase the caller shares
 * across both arrows, so two pulsing arrows cannot drift apart however far apart they began. A newly
 * shown arrow therefore adopts the fade already in progress — including at its dim end — rather than
 * starting its own, because a fade of its own is precisely the drift this removes.
 */
@Composable
private fun ArrowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    level: Arrow,
    pulseAlpha: Float,
) {
    if (level == Arrow.HIDDEN) return
    val pulsing = level == Arrow.PULSING
    // Pulsing (in-flight) arrows use the brand primary and fade; static arrows are a muted gray, no motion.
    val tint = if (pulsing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // Only a pulsing arrow takes the fade. Under reduce-motion the caller passes a flat 1f, which drops
    // the motion and never the meaning: the primary tint above already says "in flight", so a
    // non-animating pulsing arrow is still unmistakably not a static one, and it renders at the fade's
    // own bright end.
    val alpha = if (pulsing) pulseAlpha else 1f
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.size(IconSize).alpha(alpha),
    )
}
