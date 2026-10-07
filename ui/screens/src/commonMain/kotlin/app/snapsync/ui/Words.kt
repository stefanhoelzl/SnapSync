package app.snapsync.ui

import androidx.compose.runtime.Composable
import app.snapsync.model.ScreenMessage
import app.snapsync.model.TimeLeft
import app.snapsync.presentation.CoarseDuration
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.duration_days
import app.snapsync.ui.resources.duration_hours
import app.snapsync.ui.resources.duration_minutes
import app.snapsync.ui.resources.duration_minutes_short
import app.snapsync.ui.resources.duration_under_a_minute
import app.snapsync.ui.resources.duration_weeks
import app.snapsync.ui.resources.message_app_not_genuine
import app.snapsync.ui.resources.message_create_dates_refused
import app.snapsync.ui.resources.message_create_failed
import app.snapsync.ui.resources.message_create_name_refused
import app.snapsync.ui.resources.message_device_modified
import app.snapsync.ui.resources.message_device_unverifiable
import app.snapsync.ui.resources.message_invalid_link
import app.snapsync.ui.resources.message_rename_failed
import app.snapsync.ui.resources.message_rename_name_refused
import app.snapsync.ui.resources.report_seed_device_unverifiable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The facts the state carries, in words (`docs/architecture.md`, "Localization"): the domain names WHAT is true,
// and only here does it become a sentence — so a translation is a strings file, never a code change.

/** A [ScreenMessage] as the screen says it. */
@Composable
internal fun ScreenMessage.text(): String = stringResource(
    when (this) {
        ScreenMessage.INVALID_LINK -> Res.string.message_invalid_link
        ScreenMessage.CREATE_NAME_REFUSED -> Res.string.message_create_name_refused
        ScreenMessage.CREATE_DATES_REFUSED -> Res.string.message_create_dates_refused
        ScreenMessage.CREATE_FAILED -> Res.string.message_create_failed
        ScreenMessage.RENAME_NAME_REFUSED -> Res.string.message_rename_name_refused
        ScreenMessage.RENAME_FAILED -> Res.string.message_rename_failed
        ScreenMessage.DEVICE_MODIFIED -> Res.string.message_device_modified
        ScreenMessage.DEVICE_UNVERIFIABLE -> Res.string.message_device_unverifiable
        ScreenMessage.APP_NOT_GENUINE -> Res.string.message_app_not_genuine
    },
)

/** The description a report the app offered for [this] opens with, or `null` where it offers none. */
@Composable
internal fun ScreenMessage.reportSeed(): String? = when (this) {
    ScreenMessage.DEVICE_UNVERIFIABLE -> stringResource(Res.string.report_seed_device_unverifiable)
    else -> null
}

/** "2 weeks", "5 days", "3 hours" — the create screen's duration. */
@Composable
internal fun CoarseDuration.text(): String = when (this) {
    is CoarseDuration.Weeks -> pluralStringResource(Res.plurals.duration_weeks, count, count)
    is CoarseDuration.Days -> pluralStringResource(Res.plurals.duration_days, count, count)
    is CoarseDuration.Hours -> pluralStringResource(Res.plurals.duration_hours, count, count)
    is CoarseDuration.Minutes -> pluralStringResource(Res.plurals.duration_minutes, count, count)
    CoarseDuration.UnderAMinute -> stringResource(Res.string.duration_under_a_minute)
}

/** "2 days", "5 hours", "12 min" — what is left of an event, on the joined screen. */
@Composable
internal fun TimeLeft.text(): String = when (this) {
    is TimeLeft.Days -> pluralStringResource(Res.plurals.duration_days, count, count)
    is TimeLeft.Hours -> pluralStringResource(Res.plurals.duration_hours, count, count)
    is TimeLeft.Minutes -> pluralStringResource(Res.plurals.duration_minutes_short, count, count)
    TimeLeft.UnderAMinute -> stringResource(Res.string.duration_under_a_minute)
}
