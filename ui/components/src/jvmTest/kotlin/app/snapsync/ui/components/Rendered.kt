package app.snapsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import app.snapsync.model.DateFormats

/** What [text] composes to under [formats] — for a label that is a composable because its words are resources. */
@OptIn(ExperimentalTestApi::class)
internal fun rendered(formats: DateFormats = dateFormats("en-GB"), text: @Composable () -> String): String {
    var out: String? = null
    runComposeUiTest {
        setContent { CompositionLocalProvider(LocalDateFormats provides formats) { out = text() } }
        waitForIdle()
    }
    return checkNotNull(out) { "the label never composed" }
}
