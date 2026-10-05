package app.snapsync.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.menu
import org.jetbrains.compose.resources.stringResource

/**
 * Owns the screen's convention-bearing structure: edge insets, the small app-name nav label, an
 * optional prominent [heading] beneath it (the joined event's name), the vertical centering of the
 * body content (the screen is a glanceable status display), and a bottom action cluster centered
 * across the width. Screens supply one or more action composables; this container row-arranges them
 * centered with consistent spacing, so the screen never hardcodes anchor or row geometry (spec: docs/architecture.md).
 *
 * [onMenu] opens the app menu (capability `sync-status`) from a button at the start of the title row; `null` draws no
 * button — the screens that withhold the menu — while the row keeps its height, so the label never moves between
 * them.
 *
 * [onEditHeading] is the heading's edit affordance (capability `manage-membership`). It is the deliberate
 * OPPOSITE of [onTitleDoubleTap]: a visible control with click semantics and an accessibility label,
 * because renaming an event is something a member should be able to find. The two never collide — they
 * sit on different slots (the app-name label and the heading), and only one of them is a control.
 *
 * The background `Surface` fills the whole screen (painting edge-to-edge under the iOS notch /
 * home indicator), while the content `Column` insets past the safe-area before applying the
 * uniform 24.dp margin — except at the bottom under [contentPinsActionCluster], where the
 * safe-area itself becomes the margin.
 */
@Composable
fun ScreenLayout(
    title: String,
    heading: ScreenHeading?,
    bottomActions: (@Composable () -> Unit)?,
    contentPinsActionCluster: Boolean,
    onTitleDoubleTap: (() -> Unit)?,
    onMenu: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    if (contentPinsActionCluster) {
                        WindowInsets.safeDrawing.union(WindowInsets(bottom = 12.dp))
                    } else {
                        WindowInsets.safeDrawing
                    },
                )
                .padding(
                    start = 24.dp,
                    // The title row is a full touch target tall (see [TitleRow]); this keeps the label where a
                    // 24.dp margin over a bare label put it.
                    top = TITLE_ROW_TOP,
                    end = 24.dp,
                    bottom = if (contentPinsActionCluster) 0.dp else 24.dp,
                ),
        ) {
            TitleRow(title, onTitleDoubleTap, onMenu)
            if (heading != null) {
                Heading(heading, bottomPadding = if (heading.details == null) 12.dp else 4.dp)
                heading.details?.let { details ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        content = details,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
            if (bottomActions != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        bottomActions()
                    }
                }
            }
        }
    }
}

/** The padding above the title row: a 24.dp margin over the label, less the touch target's slack above it. */
private val TITLE_ROW_TOP = 10.dp

/**
 * The title row: the app-name label, centred across the full width whatever sits beside it, and the menu button at
 * its start. One touch target tall ([TITLE_ROW_HEIGHT]) on every screen, button or not.
 */
@Composable
private fun TitleRow(title: String, onDoubleTap: (() -> Unit)?, onMenu: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = TITLE_ROW_HEIGHT), contentAlignment = Alignment.Center) {
        NavTitle(title, onDoubleTap)
        if (onMenu != null) {
            // Pulled into the margin by the icon's own inset, so the glyph — not the touch target — lines up with
            // the screen's 24.dp edge.
            IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterStart).offset(x = (-12).dp)) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(Res.string.menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val TITLE_ROW_HEIGHT = 48.dp

/**
 * The small app-name nav label — always present, top-anchored (mockup `.navtitle`).
 *
 * [onDoubleTap] is the hidden second way to the report sheet (capability `privacy-security`), and it is
 * deliberately a raw pointer-input gesture rather than `combinedClickable`: that would add click
 * semantics and a role to a label that must stay invisible to assistive tech and to a UI test that has
 * not been told where to look.
 */
@Composable
private fun NavTitle(title: String, onDoubleTap: (() -> Unit)?) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .then(
                onDoubleTap?.let { tap ->
                    Modifier.pointerInput(tap) {
                        detectTapGestures(onDoubleTap = { tap() })
                    }
                } ?: Modifier,
            ),
    )
}

/**
 * The prominent heading (the joined event's name), directly beneath the nav label.
 *
 * With an [onEdit] affordance it becomes a centered row: the name keeps its own centering (it is the
 * thing being read) and the control sits beside it rather than displacing it. Unlike [NavTitle]'s hidden
 * double-tap, this one MUST read as a control and MUST appear in the accessibility tree, which is why it
 * is an `IconButton` and not a gesture.
 */
@Composable
private fun Heading(heading: ScreenHeading, bottomPadding: Dp) {
    val text = heading.text
    val onEdit = heading.onEdit
    val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    // At most two lines, then an ellipsis: a name may run to 100 characters, and wrapped in full it took four
    // lines and pushed the invite down the screen. The whole name stays one tap away in the rename dialog.
    if (onEdit == null) {
        Text(
            text = text,
            style = style,
            textAlign = TextAlign.Center,
            maxLines = HEADING_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `fill = false`: a short name keeps its natural width (and so its centering), while a long one
            // yields the space the edit control needs rather than pushing it off the screen.
            Text(
                text = text,
                style = style,
                textAlign = TextAlign.Center,
                maxLines = HEADING_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = heading.editDescription,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The prominent heading, when a screen has one: the text, and whether it can be edited.
 *
 * One object rather than three parameters because they are one concept and were only ever set together —
 * an [onEdit] with no text has nothing to sit beside, and an [editDescription] means nothing without the
 * control it describes. Passing the whole thing as null is how a screen says it has no heading, which
 * previously took a null text plus two arguments nobody would read.
 */
class ScreenHeading(
    val text: String,
    /** `null` for a heading with no edit control — stated at the construction site, never defaulted. */
    val onEdit: (() -> Unit)?,
    val editDescription: String = "",
    /** Short lines about the heading, centered beneath it (the joined event's "joined" and dates lines). */
    val details: (@Composable ColumnScope.() -> Unit)? = null,
)

/** How many lines the heading may wrap to before it is cut with an ellipsis. */
private const val HEADING_MAX_LINES = 2
