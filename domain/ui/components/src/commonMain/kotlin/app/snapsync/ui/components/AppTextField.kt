package app.snapsync.ui.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A single-line text input — the app's first. Semantic, appearance-free: it carries the current
 * [value], a change callback, a [placeholder], an [enabled] flag, a [maxLength] cap, and an optional
 * [errorText] shown beneath the field; no colors, shapes, text styles, or `Modifier` in the
 * signature, and the Material 3 text-field containment lives inside the component (design-system
 * containment rule). The cap is enforced here — input that would exceed [maxLength] is refused (the
 * callback is not invoked), so callers never see an over-length value. A disabled field never invokes
 * [onValueChange] (Material 3 swallows the edit). When [errorText] is non-null the field renders its
 * error treatment with the message beneath it.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    maxLength: Int = Int.MAX_VALUE,
    errorText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { proposed -> if (proposed.length <= maxLength) onValueChange(proposed) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}
