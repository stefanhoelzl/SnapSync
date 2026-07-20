package app.snapsync.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A **titled on/off section** whose header is a switch and whose body states, in words, the consequence
 * of that switch being on or off. The two big participation choices on the join surface — "share my
 * photos" and "save what arrives" — are each one of these, so the guest reads a section as a single
 * sentence: *this is on, and here is what that does to my phone*.
 *
 * The whole header **row** is the tap target (`toggleable`, [Role.Switch]); the [Switch] itself is inert
 * (`onCheckedChange = null`). Two live targets in one row double-fire the toggle — it flips and flips
 * back, reading as a dead control — so there is exactly one node, and assistive tech announces one
 * on/off switch for the section.
 *
 * Appearance-free: a [title], the [checked] state, a change callback, and a [content] slot for the
 * consequence lines. No colors, shapes, text styles, or `Modifier` cross the signature. The card frame,
 * the switch's pinned colours, and the divider between header and body are owned here.
 *
 * The switch's **off** colours are pinned deliberately. The stock `surface`-on-`outlineVariant` pairing
 * inverts in dark mode (the thumb ends up darker than the track), so off reads as a hole rather than a
 * switch. `outline` thumb on a `surfaceVariant` track keeps the thumb the lighter element in both themes.
 */
@Composable
fun AppToggleSection(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                    .heightIn(min = 56.dp)
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // The row owns the gesture and the semantics; the switch is drawing only.
                SectionSwitch(checked = checked)
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content,
            )
        }
    }
}

/**
 * The **second level** of a section: a recessed well inside an [AppToggleSection] holding the section's
 * checkmark rows. Every section reads the same two-level grammar — the switch turns the section on, the
 * checkmark rows inside the well configure it — so the screen's two idioms are a hierarchy, not a mix.
 *
 * Recessed by using the app *background* against the card's *surface*: the sub-level sits visually
 * "into" the card in both themes (lighter card, darker well in light; darker card, darker-still well in
 * dark) without introducing any new colour — the palette stays frozen.
 */
@Composable
fun AppSubSection(content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    // The well recesses by contrast against the card's `surface`. In dark the app `background` (darker than
    // the card) already reads clearly; in light `background` (#F4F6F8 on #FFFFFF ≈ 1.08:1) was nearly
    // invisible, so the light well uses the deeper `surfaceVariant` (≈1.19:1). A faint `outlineVariant`
    // inner border then defines the edge in BOTH themes, so the two-level grammar reads equally.
    val wellFill = if (appIsDark()) scheme.background else scheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            .background(color = wellFill, shape = shape)
            .border(width = 1.dp, color = scheme.outlineVariant, shape = shape),
        content = content,
    )
}

/**
 * The section switch, hand-drawn to iOS metrics: a 51×31 capsule track, a 27dp white thumb with a soft
 * shadow, no border. Material 3's `Switch` is visually Android — a thicker track and an *outlined,
 * undersized* thumb when off — and these two switches are the most prominent controls on the join
 * surface, so they are the worst possible place for the app to read as a port. The white thumb is
 * deliberate in both themes (it is the iOS look), and the off-track is derived from an existing token,
 * so the frozen palette stays frozen.
 *
 * Drawing only: the enclosing row carries the `toggleable`/`Role.Switch` semantics and the gesture.
 * The thumb slide honours reduce-motion by snapping ([LocalReduceMotion]).
 */
@Composable
private fun SectionSwitch(checked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val target = if (checked) 22.dp else 2.dp
    val thumbOffset by animateDpAsState(target)
    val offset = if (LocalReduceMotion.current) target else thumbOffset
    // Off-track: onSurfaceVariant@30% measured only ~1.5:1 on the light surface — a track that barely
    // registers. Light is raised to 65% (~2.8:1 non-text) so the off state reads as a control; it stays a
    // muted neutral, never the brand green, so it cannot be mistaken for "on". Dark already read (the thumb
    // slide plus the green on-fill carry the state) and is left at 30%.
    val offAlpha = if (appIsDark()) 0.30f else 0.65f
    Box(
        modifier = Modifier
            .size(width = 51.dp, height = 31.dp)
            .background(
                color = if (checked) scheme.primary else scheme.onSurfaceVariant.copy(alpha = offAlpha),
                shape = RoundedCornerShape(percent = 50),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = offset)
                .size(27.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(color = Color.White, shape = CircleShape),
        )
    }
}

/**
 * A **minor section**: the same card frame as [AppToggleSection] but with no switch header — its content
 * is a second-level row (a checkmark toggle) standing alone. For the preferences that rank below the
 * switch sections but belong to neither of them: the album opt-in feeds from BOTH share and receive
 * (capability `event-album`), so nesting it under either switch would be a false statement, while a
 * switch section of its own gave a minor preference the same weight as a consent decision. A minor
 * section is the rank in between: present, honest, quiet.
 */
@Composable
fun AppMinorSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            content = content,
        )
    }
}

/**
 * A plain consequence sentence inside an [AppToggleSection] — muted, small, the surface's quiet voice.
 * Used for "nothing of yours leaves this phone", the origin-exclusions note, and where arriving photos
 * land. Owns its own left/right padding so it aligns with the section header above it.
 */
@Composable
fun AppSectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),
    )
}

/**
 * The **cutoff instant** as the section's boldest statement — the one line that decides which photos
 * leave the phone, so it is rendered in the heaviest type the surface carries. Shown only when sharing.
 */
@Composable
fun AppSectionValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 12.dp),
    )
}
