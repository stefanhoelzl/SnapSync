package app.snapsync.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.menu_close
import org.jetbrains.compose.resources.stringResource

/**
 * The share of the screen's width the open drawer takes, and the most it ever takes: what is left beside it stays
 * visibly the screen beneath, so a tap there or a swipe reads as the way back. M3's own sheet runs to 360dp, which
 * on a 375pt iPhone SE left a 15pt sliver.
 */
private const val DRAWER_WIDTH_FRACTION = 0.8f
private val DrawerMaxWidth = 320.dp

/**
 * The app menu's side drawer (capability `sync-status`), drawn over [content] from the leading edge.
 *
 * [open] is the state's answer, not the drawer's own: the drawer follows it, and every way the member closes it —
 * the scrim, a swipe, the system back, the header's close button — is reported as [onDismiss] so the state says so
 * too. Swiping it OPEN is off: the menu opens from its button only, so an edge swipe meant for something else never
 * opens it. It takes [DRAWER_WIDTH_FRACTION] of the width, at most [DrawerMaxWidth], so the screen stays in view.
 *
 * Pinned to the frozen scheme for the reason [AppTextPromptSheet] is: an unpinned M3 surface falls back to the
 * Material baseline tonal surface.
 */
@Composable
fun AppMenuDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    menu: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val drawerState = rememberDrawerState(
        initialValue = if (open) DrawerValue.Open else DrawerValue.Closed,
        confirmStateChange = { value ->
            if (value == DrawerValue.Closed) dismiss()
            true
        },
    )
    LaunchedEffect(open) {
        if (open) drawerState.open() else drawerState.close()
    }
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints {
        val sheetWidth = min(maxWidth * DRAWER_WIDTH_FRACTION, DrawerMaxWidth)
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = open,
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawerState,
                    modifier = Modifier.width(sheetWidth),
                    drawerContainerColor = scheme.surface,
                    drawerContentColor = scheme.onSurface,
                ) {
                    // The rows exist only while the drawer is open or on its way: a closed drawer is still composed
                    // (off screen), and rows left in it would be read out by a screen reader and found by a test that
                    // asked whether the menu is showing.
                    if (drawerState.currentValue == DrawerValue.Open || drawerState.targetValue == DrawerValue.Open) {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .safeDrawingPadding()
                                .padding(horizontal = 12.dp, vertical = 16.dp),
                            content = menu,
                        )
                    }
                }
            },
            content = content,
        )
    }
}

/** The drawer's header: the app's name, as the nav label spells it, and the button that closes the menu. */
@Composable
fun AppMenuHeader(title: String, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 16.dp).semantics { heading() },
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(Res.string.menu_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What a menu row is about, which picks its glyph — the screens name the meaning, the design system the icon. */
enum class AppMenuIcon(internal val vector: ImageVector) {
    REPORT(Icons.Outlined.Feedback),
    WEBSITE(Icons.Outlined.Language),
    PRIVACY(Icons.Outlined.PrivacyTip),
    MOBILE_DATA(Icons.Outlined.SignalCellularAlt),
}

/** One row of the menu: an [icon], a [label], and what tapping it does. */
@Composable
fun AppMenuItem(icon: AppMenuIcon, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NavigationDrawerItem(
        icon = { Icon(imageVector = icon.vector, contentDescription = null) },
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurface,
        ),
    )
}

/**
 * A menu row that is a setting: an [icon], a [label] and the switch as ONE toggleable row ([Role.Switch]), the same
 * switch the cards draw, with [note] — what the setting currently means — beneath the label, and [error] in place of
 * it when the last change could not be saved. The row lines up with [AppMenuItem]'s icon and label.
 */
@Composable
fun AppMenuSwitch(
    icon: AppMenuIcon,
    label: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    error: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon.vector, contentDescription = null, tint = scheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            Text(
                text = error ?: note,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) scheme.error else scheme.onSurfaceVariant,
            )
        }
        // The row owns the gesture and the semantics; the switch is drawing only.
        SectionSwitch(checked = checked)
    }
}

/** Sets the rows above it apart from the rows below. */
@Composable
fun AppMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** The menu's last line — shown, never a control — pushed to the drawer's foot. */
@Composable
fun ColumnScope.AppMenuFooter(text: String) {
    Spacer(Modifier.weight(1f))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}
