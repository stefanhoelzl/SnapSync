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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.snapsync.model.Arrow
import kotlinx.datetime.LocalDateTime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue


/** One half-cycle of the in-flight arrow's pulse, in milliseconds. */
private const val PULSE_MILLIS = 700


/**
 * The joined-layer sync health, rendered as the single status line. A sealed semantic value (runtime
 * data), not a set of components — the call site passes only the health and, for the attention state,
 * an `onClick`; never appearance. There are no numeric counts here.
 */
sealed interface AppSyncStatus {
    /** Joined but persisted state not read yet — a neutral first frame. */
    data object Loading : AppSyncStatus

    /** Everything shared and received — settled (no arrows). */
    data object InSync : AppSyncStatus

    /** Work remaining; each arrow is [Arrow.HIDDEN]/[Arrow.STATIC]/[Arrow.PULSING]. */
    data class Syncing(val upload: Arrow, val download: Arrow) : AppSyncStatus

    /**
     * The event has not begun (capability `sync-status-screen`). [startsAt] is the event's start as a
     * plain local wall-clock value — the component owns the copy and the date formatting, as it already
     * does for "In sync".
     *
     * Informational, not actionable: flat (no background) and NOT tappable. It carries the start instant
     * because a bare "not started yet" invites exactly the question it fails to answer.
     */
    data class NotStarted(val startsAt: LocalDateTime) : AppSyncStatus

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
     */
    data object CannotVerifyDevice : AppSyncStatus
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
 * opacity; a `Static` arrow is shown dimmed without motion. No counts are shown.
 */
@Composable
fun AppStatusLine(status: AppSyncStatus, ended: Boolean = false, onAttentionClick: () -> Unit = {}) {
    // The event's declared end has passed (capability `sync-status-screen`): an informational "Event ended"
    // marker sits on its OWN line ABOVE the regular status. Purely a marker: it changes no arrow, count,
    // or health value, and sync continues.
    //
    // It was once an inline `Event ended · <status>` prefix in the single status slot. Two things broke
    // that. It reads as ONE sentence — "Event ended · Synchronization pending…" parses as a claim about
    // the syncing, when the two are unrelated facts (the window closed; the transfer is still going). And
    // on a phone-width line the pair wraps mid-phrase, so the break lands wherever the text happens to
    // run out. Stacking states each fact once, and lets the status keep the full width it was designed
    // for. The marker is styled DOWN from the status it labels, so the health stays the thing you read.
    if (ended) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Event ended",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusBody(status, onAttentionClick)
        }
    } else {
        StatusBody(status, onAttentionClick)
    }
}

/** The one-line status content itself, without the ended marker. Extracted so the marker can prefix it. */
@Composable
private fun StatusBody(status: AppSyncStatus, onAttentionClick: () -> Unit) {
    when (status) {
        AppSyncStatus.Loading ->
            LineText("Syncing…", MaterialTheme.colorScheme.onSurfaceVariant)

        AppSyncStatus.InSync ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Check, // a bare checkmark, no filled disc behind it
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(IconSize),
                )
                LineText("In sync", MaterialTheme.colorScheme.onSurface)
            }

        is AppSyncStatus.Syncing ->
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
                ArrowIcon(Icons.Filled.ArrowUpward, "uploading", status.upload, pulseAlpha)
                ArrowIcon(Icons.Filled.ArrowDownward, "downloading", status.download, pulseAlpha)
                val label = if (ongoing) "Synchronization ongoing…" else "Synchronization pending…"
                LineText(label, MaterialTheme.colorScheme.onSurface)
            }

        is AppSyncStatus.NotStarted ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Schedule, // a clock: the event exists, it simply has not begun
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize),
                )
                LineText(
                    "Starts ${formatStartShort(status.startsAt)}",
                    MaterialTheme.colorScheme.onSurface,
                )
            }

        is AppSyncStatus.NeedsAccess ->
            Surface(
                onClick = onAttentionClick,
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
                    Text(
                        text = when (status.prompt) {
                            AccessPrompt.ALLOW -> "Allow photo access"
                            AccessPrompt.SETTINGS -> "Turn on full access in Settings"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize),
                    )
                }
            }

        AppSyncStatus.CannotVerifyDevice ->
            // The same attention treatment as NeedsAccess — but NOT tappable, and with no chevron: there
            // is no action the user can take. It clears itself as soon as the device can reach the backend.
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
                        Text(
                            text = "Can't verify this device — sharing is paused",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Still retrying — your photos aren't lost.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
