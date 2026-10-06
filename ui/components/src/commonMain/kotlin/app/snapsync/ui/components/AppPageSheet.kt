package app.snapsync.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A page that rises over the screen as a sheet (capability `manage-membership`: the event's settings): as tall as the
 * screen allows less a strip at the top, where the screen beneath shows dimmed. Its only chrome is the drag handle —
 * no title, no close button — and every way out is the same [onDismiss]: swiping it down, going back, or tapping that
 * strip. The [content] scrolls inside it, so a page taller than the phone is never cut off.
 *
 * Pinned to the frozen scheme for the reason [AppTextPromptSheet] is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPageSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.background,
        contentColor = scheme.onBackground,
        // Below the status bar AND a strip of the screen beneath: the strip is what a tap closes the sheet on.
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .padding(top = PAGE_SHEET_STRIP),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

/** How much of the screen beneath stays visible above the sheet: about its title row. */
private val PAGE_SHEET_STRIP = 48.dp
