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
import androidx.compose.runtime.getValue
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

// Skin-local semantic colors for the attention line (never seen by screens). Amber is not an M3
// colorScheme token, so its light/dark split is picked by hand via [appIsDark] — the same rule the scheme
// itself is chosen by.
//
// Light was failing WCAG: #E8820C text on a 0x22 amber pill measured ~2.24:1 (needs 4.5:1 at 16sp), and
// the pill barely separated from the page (1.13:1). Light now uses a dark amber for text/icons (5.0:1 on
// the pill) over a stronger 0x3D fill (pill/page ~1.25:1). Dark already passed (5.2–6.0:1) and is kept.
private val AmberTextLight = Color(0xFF8A4B00)
private val AmberTextDark = Color(0xFFE8820C)
private val AmberContainerLight = Color(0x3DE8820C)
private val AmberContainerDark = Color(0x22E8820C)

@Composable
private fun amberText(): Color = if (appIsDark()) AmberTextDark else AmberTextLight

@Composable
private fun amberContainer(): Color = if (appIsDark()) AmberContainerDark else AmberContainerLight

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
                ArrowIcon(Icons.Filled.ArrowUpward, "uploading", status.upload)
                ArrowIcon(Icons.Filled.ArrowDownward, "downloading", status.download)
                // Label tracks live activity: any pulsing (in-flight) arrow reads "ongoing", else "pending".
                val ongoing = status.upload == Arrow.PULSING || status.download == Arrow.PULSING
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
                color = amberContainer(),
                contentColor = amberText(),
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
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(IconSize))
                }
            }

        AppSyncStatus.CannotVerifyDevice ->
            // The same attention treatment as NeedsAccess — but NOT tappable, and with no chevron: there
            // is no action the user can take. It clears itself as soon as the device can reach the backend.
            Surface(
                color = amberContainer(),
                contentColor = amberText(),
                shape = RoundedCornerShape(999.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(IconSize))
                    // Headline plus a recovery hint: this is the one attention state with no tap action,
                    // so the detail line names what actually clears it — opening the app is a re-verify
                    // wake, and the failure is otherwise being offline. (Colour is inherited contentColor;
                    // no scheme line is touched here.)
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "Can't verify this device — sharing is paused",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Reopen the app or check your connection.",
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

@Composable
private fun ArrowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    level: Arrow,
) {
    if (level == Arrow.HIDDEN) return
    val pulsing = level == Arrow.PULSING
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
