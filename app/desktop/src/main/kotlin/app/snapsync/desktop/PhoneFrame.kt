package app.snapsync.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The capture/inspect anchor for the phone pane — see `:test:harness-driver` (`PHONE_TAG`). */
const val PHONE_TAG: String = "phone"

/** Fixed iPhone-portrait-sized frame so the shared UI is previewed at ship proportions. */
@Composable
fun PhoneFrame(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(width = 390.dp + BEZEL * 2, height = 844.dp + BEZEL * 2)
            .border(width = BEZEL, color = Color.DarkGray, shape = RoundedCornerShape(24.dp))
            .padding(BEZEL)
            .clip(RoundedCornerShape(16.dp))
            // Names the pane so the headless driver can capture / dump JUST the app's own frame,
            // rather than the whole window (the inspector chrome dwarfs it). Inert at runtime.
            .testTag(PHONE_TAG),
    ) {
        content()
    }
}

private val BEZEL = 8.dp
