package app.snapsync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/** What an explanation row is about (capability `sync-status`): one glyph per subject. */
enum class ExplainSubject { SHARING, RECEIVING, HINT }

/**
 * Whether the row's subject is happening. [OFF] is a choice the member made, [BLOCKED] a problem to fix
 * (missing photo access): both draw the subject's own glyph slashed, and only the colour tells them apart —
 * one glyph per subject, so a member never learns two symbols for "not happening".
 */
enum class ExplainState { ON, OFF, BLOCKED }

/**
 * An inline link inside a caption: [text] is the linked words, which the caption already contains — the call
 * site formats them into its sentence, so a translation moves the link with its grammar.
 */
class CaptionLink(val text: String, val onClick: () -> Unit)

/**
 * One row of the joined screen's explanation of how the event works (capability `sync-status`): a glyph, a
 * [title] saying what is happening, and a [caption] saying how or why. With a [link], the link's words in the
 * caption become a real, accessible link — a control in the semantics tree, not styled text.
 * [trailing] sits beneath the caption (the limited-access choices).
 */
@Composable
fun AppExplainRow(
    subject: ExplainSubject,
    state: ExplainState,
    title: String,
    caption: String,
    link: CaptionLink? = null,
    trailing: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.padding(top = 2.dp)) { ExplainGlyph(subject, state) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = captionText(caption, link),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            trailing?.invoke(this)
        }
    }
}

/**
 * A small action beneath an explanation row's caption (the limited-access choices, capability `photo-access`):
 * outlined in the accent, as wide as its label and aligned with the text above it, so it reads as part of the
 * row rather than as one of the screen's main actions. Never an attention state.
 */
@Composable
fun ExplainAction(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, appAccentText()),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = appAccentText()),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.padding(top = 6.dp).heightIn(min = 36.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun captionText(caption: String, link: CaptionLink?) = buildAnnotatedString {
    if (link == null) {
        append(caption)
        return@buildAnnotatedString
    }
    val at = caption.indexOf(link.text)
    if (at < 0) {
        append(caption)
        return@buildAnnotatedString
    }
    append(caption.substring(0, at))
    val style = TextLinkStyles(
        SpanStyle(
            color = appAccentText(),
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
        ),
    )
    withLink(LinkAnnotation.Clickable(tag = link.text, styles = style) { link.onClick() }) { append(link.text) }
    append(caption.substring(at + link.text.length))
}

@Composable
private fun ExplainGlyph(subject: ExplainSubject, state: ExplainState) {
    val tint = when (state) {
        ExplainState.ON ->
            if (subject == ExplainSubject.HINT) MaterialTheme.colorScheme.onSurfaceVariant else appAccentText()
        ExplainState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        ExplainState.BLOCKED -> appAttentionText()
    }
    // The page colour cuts a gap either side of the slash, so it reads over any glyph.
    val gap = MaterialTheme.colorScheme.background
    Icon(
        imageVector = subject.glyph(),
        // The title beside it says the same thing in words; describing the glyph would read it twice.
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(GLYPH).then(if (state == ExplainState.ON) Modifier else Modifier.slashed(tint, gap)),
    )
}

private fun ExplainSubject.glyph(): ImageVector = when (this) {
    ExplainSubject.SHARING -> Icons.Outlined.PhotoCamera
    ExplainSubject.RECEIVING -> Icons.Outlined.PhotoLibrary
    ExplainSubject.HINT -> Icons.Outlined.TouchApp
}

private fun Modifier.slashed(color: Color, gap: Color) = drawWithContent {
    drawContent()
    val from = Offset(size.width * SLASH_INSET, size.height * SLASH_INSET)
    val to = Offset(size.width * (1 - SLASH_INSET), size.height * (1 - SLASH_INSET))
    drawLine(gap, from, to, strokeWidth = size.width * SLASH_GAP_WIDTH, cap = StrokeCap.Round)
    drawLine(color, from, to, strokeWidth = size.width * SLASH_WIDTH, cap = StrokeCap.Round)
}

private val GLYPH = 22.dp
private const val SLASH_INSET = 0.08f
private const val SLASH_WIDTH = 0.09f
private const val SLASH_GAP_WIDTH = 0.22f
