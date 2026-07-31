package app.snapsync.ui.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A text input — the app's first. Semantic, appearance-free: it carries the current [value], a change
 * callback, a [placeholder], an [enabled] flag, a [maxLength] cap, a [singleLine] mode, and an optional
 * [errorText] shown beneath the field; no colors, shapes, text styles, or `Modifier` in the
 * signature, and the Material 3 text-field containment lives inside the component (design-system
 * containment rule). The cap is enforced here — input that would exceed [maxLength] is refused (the
 * callback is not invoked), so callers never see an over-length value. A disabled field never invokes
 * [onValueChange] (Material 3 swallows the edit). When [errorText] is non-null the field renders its
 * error treatment with the message beneath it.
 *
 * [singleLine] defaults to `true`, so every existing call site is unchanged. A multi-line field wraps
 * and shows several lines at rest, which is what a written account needs — one line would scroll the
 * beginning of the sentence out of view while it is still being typed. Line count is a property of the
 * same input, not grounds for a second component.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    maxLength: Int = Int.MAX_VALUE,
    singleLine: Boolean = true,
    errorText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { proposed -> if (proposed.length <= maxLength) onValueChange(proposed) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        singleLine = singleLine,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        shape = RoundedCornerShape(14.dp),
        modifier = if (singleLine) {
            Modifier.fillMaxWidth()
        } else {
            // Room for a few lines at rest, and a ceiling so a long account scrolls within the field
            // rather than growing the sheet under the keyboard.
            Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 160.dp)
        },
    )
}
