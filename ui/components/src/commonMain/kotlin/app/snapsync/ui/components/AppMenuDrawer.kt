package app.snapsync.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app menu's side drawer (capability `sync-status`), drawn over [content] from the leading edge.
 *
 * [open] is the state's answer, not the drawer's own: the drawer follows it, and every way the member closes it —
 * the scrim, a swipe, the system back — is reported as [onDismiss] so the state says so too. Swiping it OPEN is off:
 * the menu opens from its button only, so an edge swipe meant for something else never opens it.
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
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = open,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                drawerContainerColor = scheme.surface,
                drawerContentColor = scheme.onSurface,
            ) {
                // The rows exist only while the drawer is open or on its way: a closed drawer is still composed (off
                // screen), and rows left in it would be read out by a screen reader and found by a test that asked
                // whether the menu is showing.
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

/** The drawer's header: the app's name, as the nav label spells it. */
@Composable
fun AppMenuHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp).semantics { heading() },
    )
}

/** What a menu row is about, which picks its glyph — the screens name the meaning, the design system the icon. */
enum class AppMenuIcon(internal val vector: ImageVector) {
    REPORT(Icons.Outlined.Feedback),
    WEBSITE(Icons.Outlined.Language),
    PRIVACY(Icons.Outlined.PrivacyTip),
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
