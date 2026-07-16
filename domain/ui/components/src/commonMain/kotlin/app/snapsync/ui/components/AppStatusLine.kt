package app.snapsync.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime

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

    /** Work remaining; each arrow is [ArrowLevel.HIDDEN]/[ArrowLevel.STATIC]/[ArrowLevel.PULSING]. */
    data class Syncing(val upload: ArrowLevel, val download: ArrowLevel) : AppSyncStatus

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

/** A direction arrow's render level: absent, shown-idle, or shown-and-animating. */
enum class ArrowLevel { HIDDEN, STATIC, PULSING }

// Skin-local semantic colors for the status line (never seen by screens).
private val Amber = Color(0xFFE8820C)
private val AmberContainer = Color(0x22E8820C)
private val IconSize = 20.dp
private val StaticAlpha = 0.38f

/**
 * Renders the one-line sync health. `InSync`/`Syncing`/`Loading`/`NotStarted` are flat text-with-glyph
 * (no background — e.g. a bare green check for `InSync`, a clock for `NotStarted`); `NeedsAccess` and
 * `CannotVerifyDevice` carry a background, and only `NeedsAccess` is tappable ([onAttentionClick]) —
 * `CannotVerifyDevice` offers the user no action, because there is none. A `Pulsing` arrow animates its
 * opacity; a `Static` arrow is shown dimmed without motion. No counts are shown.
 */
@Composable
fun AppStatusLine(status: AppSyncStatus, onAttentionClick: () -> Unit = {}) {
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
                Arrow(Icons.Filled.ArrowUpward, "uploading", status.upload)
                Arrow(Icons.Filled.ArrowDownward, "downloading", status.download)
                // Label tracks live activity: any pulsing (in-flight) arrow reads "ongoing", else "pending".
                val ongoing = status.upload == ArrowLevel.PULSING || status.download == ArrowLevel.PULSING
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
                color = AmberContainer,
                contentColor = Amber,
                shape = RoundedCornerShape(999.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
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
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(IconSize))
                }
            }

        AppSyncStatus.CannotVerifyDevice ->
            // The same attention treatment as NeedsAccess — but NOT tappable, and with no chevron: there
            // is no action the user can take. It clears itself as soon as the device can reach the backend.
            Surface(
                color = AmberContainer,
                contentColor = Amber,
                shape = RoundedCornerShape(999.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(IconSize))
                    Text(
                        text = "Can't verify this device — sharing is paused",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
    }
}

@Composable
private fun LineText(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = color)
}

@Composable
private fun Arrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    level: ArrowLevel,
) {
    if (level == ArrowLevel.HIDDEN) return
    val pulsing = level == ArrowLevel.PULSING
    // Pulsing (in-flight) arrows use the brand primary and fade; static arrows are a muted gray, no motion.
    val tint = if (pulsing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // Reduce-motion drops the fade, never the meaning: the primary tint above already says "in flight",
    // so a non-animating pulsing arrow is still unmistakably not a static one. It renders at full alpha —
    // the fade's own bright end — so the only thing lost is the motion.
    val animate = pulsing && !LocalReduceMotion.current
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val a by transition.animateFloat(
            initialValue = StaticAlpha,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulse-alpha",
        )
        a
    } else {
        1f
    }
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.size(IconSize).alpha(alpha),
    )
}
