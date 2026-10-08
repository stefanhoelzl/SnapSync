package app.snapsync.ui

import androidx.compose.runtime.Composable
import app.snapsync.model.ReportDestination
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
import app.snapsync.ui.resources.report_body_developer
import app.snapsync.ui.resources.report_body_device
import app.snapsync.ui.resources.report_seed_device_unverifiable
import app.snapsync.ui.resources.report_send
import app.snapsync.ui.resources.save
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
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

/**
 * A sentence chosen but not yet said: its resource and the values it carries. A plain mapping function picks one —
 * testable with no Compose at all — and [text] says it, once (`docs/architecture.md`, "Coverage": a composable renders,
 * the decision lives outside it). A value that is itself a [Phrase] is said first, so a sentence may carry another.
 */
internal sealed interface Phrase {
    val args: List<Any>

    class Of(val res: StringResource, override val args: List<Any> = emptyList()) : Phrase

    class Counted(val res: PluralStringResource, val count: Int, override val args: List<Any>) : Phrase
}

/** [this] as the screen says it. */
// Compose Resources takes a sentence's values only as varargs; the copy is of at most a couple of values.
@Suppress("SpreadOperator")
@Composable
internal fun Phrase.text(): String {
    val said = args.map { if (it is Phrase) it.text() else it }.toTypedArray()
    return when (this) {
        is Phrase.Of -> stringResource(res, *said)
        is Phrase.Counted -> pluralStringResource(res, count, *said)
    }
}

/** "2 weeks", "5 days", "3 hours" — the create screen's duration. */
internal fun CoarseDuration.phrase(): Phrase = when (this) {
    is CoarseDuration.Weeks -> Phrase.Counted(Res.plurals.duration_weeks, count, listOf(count))
    is CoarseDuration.Days -> Phrase.Counted(Res.plurals.duration_days, count, listOf(count))
    is CoarseDuration.Hours -> Phrase.Counted(Res.plurals.duration_hours, count, listOf(count))
    is CoarseDuration.Minutes -> Phrase.Counted(Res.plurals.duration_minutes, count, listOf(count))
    CoarseDuration.UnderAMinute -> Phrase.Of(Res.string.duration_under_a_minute)
}

/** "2 days", "5 hours", "12 min" — what is left of an event, on the joined screen. */
internal fun TimeLeft.phrase(): Phrase = when (this) {
    is TimeLeft.Days -> Phrase.Counted(Res.plurals.duration_days, count, listOf(count))
    is TimeLeft.Hours -> Phrase.Counted(Res.plurals.duration_hours, count, listOf(count))
    is TimeLeft.Minutes -> Phrase.Counted(Res.plurals.duration_minutes_short, count, listOf(count))
    TimeLeft.UnderAMinute -> Phrase.Of(Res.string.duration_under_a_minute)
}

/** What the bug-report sheet says that depends on where a report goes: its body, and its confirm. */
internal class ReportCopy(val body: StringResource, val confirm: StringResource)

/**
 * The bug-report sheet's words for [destination]. A build that reports nowhere keeps the report on the phone, and says
 * so — it never suggests a destination it does not have (capability `privacy-security`).
 */
internal fun reportCopy(destination: ReportDestination): ReportCopy = when (destination) {
    ReportDestination.DEVELOPER -> ReportCopy(Res.string.report_body_developer, Res.string.report_send)
    ReportDestination.THIS_DEVICE -> ReportCopy(Res.string.report_body_device, Res.string.save)
}
