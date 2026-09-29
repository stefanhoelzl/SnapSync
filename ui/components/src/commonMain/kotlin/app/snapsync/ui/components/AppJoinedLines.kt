package app.snapsync.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle

// The joined screen's short supporting lines (capability `sync-status`): the joined statement and the
// dates under the event's name, and the counts beneath the status line. Each is one centered line of body
// type; they differ only in how loud they are, which is the design system's to decide.

/** The accent statement beneath a heading — "You've joined this event". */
@Composable
fun AppHeadingStatement(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
}

/**
 * The dates line: the [range] muted, then — when there is one — the [phrase] saying where the event is in its
 * life ("ends in 2 days"), set in the body colour so it is the part the eye lands on.
 */
@Composable
fun AppDatesLine(range: String, phrase: String?) {
    val emphasis = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    Text(
        text = buildAnnotatedString {
            append(range)
            if (phrase != null) {
                append(" · ")
                withStyle(emphasis) { append(phrase) }
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** A quiet line beneath the status line — the counts, and the ended event's waiting note. */
@Composable
fun AppStatusDetail(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
