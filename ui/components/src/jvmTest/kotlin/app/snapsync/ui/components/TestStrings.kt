package app.snapsync.ui.components

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/*
 * The words a test looks for, read from the same resources the screen renders (`docs/architecture.md`,
 * "Localization"): a test finds the node a string names, and says nothing about the copy itself.
 */

/** [res], formatted with [args], as the screen shows it. */
internal fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }

/** [res] for [quantity], formatted with [args], as the screen shows it. */
internal fun plural(res: PluralStringResource, quantity: Int, vararg args: Any): String =
    runBlocking { getPluralString(res, quantity, *args) }
