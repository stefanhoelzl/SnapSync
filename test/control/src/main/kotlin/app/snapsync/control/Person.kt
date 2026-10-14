package app.snapsync.control

import app.snapsync.levers.ScreenLevers
import app.snapsync.model.Direction
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * **What a person does on the screen** (`docs/testing.md`, "Typed services"): every finger's act is one [UiIntent], handed
 * to the host's screen ([ScreenLevers.tap]) — the host knows no other verb. The forms a person fills in several taps
 * are named here, on the caller's side, as the taps they are ([Taps]); only [reconfigure] waits between taps, for the
 * settings to be on screen, as a person waits — through [awaitScreen], which each caller answers its own way (an event
 * on the world's stream, or a poll where a host serves none).
 */
interface Person {
    /** The host's screen. */
    val screen: ScreenLevers

    /** The zone a person's wall-clock bounds are read in — the device's own. */
    val zone: TimeZone get() = TimeZone.currentSystemDefault()

    /** Returns once the screen shows what [shown] asks for — [what] names it when it never does. */
    suspend fun awaitScreen(what: String, shown: (UiState) -> Boolean)

    /** Each of [intents], in order, as a person taps them. */
    suspend fun tap(vararg intents: UiIntent) = intents.forEach { screen.tap(it) }

    /** Each of [intents], in order. */
    suspend fun tap(intents: List<UiIntent>) = intents.forEach { screen.tap(it) }

    /**
     * The create form, filled as a person fills it, then Create — [startsAt] and [endsAt] as ISO local date-times. A range
     * already over asks how long others may join first: [openUntilDays] answers it (inert for any other range).
     */
    suspend fun createEvent(
        name: String,
        startsAt: String,
        endsAt: String,
        openUntilDays: Int = DEFAULT_OPEN_UNTIL_DAYS,
    ) =
        tap(Taps.create(name, LocalDateTime.parse(startsAt), LocalDateTime.parse(endsAt), openUntilDays))

    /** The join form's [choices] set, then Join. */
    suspend fun confirmJoin(choices: Choices = Choices()) = tap(Taps.choices(choices, zone) + UiIntent.ConfirmJoin)

    /** The join form's [choices] set, without committing — what the join gate's shareable-count preview answers. */
    suspend fun setRange(choices: Choices) = tap(Taps.choices(choices, zone))

    /**
     * The event's settings, opened, each of [choices] applied — and, where it withdraws photos, "Stop sharing these
     * photos?" confirmed right after it — then closed.
     */
    suspend fun reconfigure(choices: Choices) {
        tap(UiIntent.OpenReconfigure)
        // The calendar opens on what the settings show, so a range is chosen only once they are on screen.
        awaitScreen("the event's settings") { (it.layer as? Layer.Joined)?.surface is JoinedSurface.Reconfigure }
        Taps.choices(choices, zone).forEach { tap(it, UiIntent.ConfirmStopSharing) }
        tap(UiIntent.CancelReconfigure)
    }

    /** The joined event renamed — or [event], named: a rename the dialog opened for an event since replaced. */
    suspend fun rename(name: String, event: String? = null) {
        val id = event ?: (screen.shown()?.layer as? Layer.Joined)?.membership?.eventId
            ?: error("there is no joined event to rename, and no `event` was named")
        tap(UiIntent.RenameEvent(id, name))
    }
}

/**
 * A member's choices on a join form or in an event's settings; `null` leaves a choice as the form shows it. [cutoff] and
 * [until] are ISO-8601 instants, set as the custom range a person picks; [range] a preset.
 */
data class Choices(
    val direction: Direction? = null,
    val saveToAlbum: Boolean? = null,
    val cutoff: String? = null,
    val until: String? = null,
    val range: RangeChoice? = null,
)

/** The forms a person fills in several taps, as those taps. */
object Taps {
    /** The create form: the name typed, the days swept, each wheel settled — then Create, and how long others may join. */
    fun create(name: String, from: LocalDateTime, until: LocalDateTime, openUntilDays: Int): List<UiIntent> = listOf(
        UiIntent.CreateName(name),
        UiIntent.RangeSweep(from.date, until.date),
        UiIntent.RangeFromHour(from.hour),
        UiIntent.RangeFromMinute(from.minute),
        UiIntent.RangeUntilHour(until.hour),
        UiIntent.RangeUntilMinute(until.minute),
        UiIntent.ConfirmCreate,
        UiIntent.KeepOpenFor(openUntilDays),
    )

    /**
     * The taps that set [choices] on a form, in the order a person makes them. A custom range is set as a person sets
     * one: the calendar opened on what is picked, the named bound's day dragged to and its wheels settled — read in
     * [zone], the device's — and OK; either bound may be named alone.
     */
    fun choices(choices: Choices, zone: TimeZone): List<UiIntent> = buildList {
        choices.range?.let { add(UiIntent.RangePreset(it)) }
        choices.direction?.let {
            add(UiIntent.ShareOn(it.includesUpload))
            add(UiIntent.ReceiveOn(it.includesDownload))
        }
        choices.saveToAlbum?.let { add(UiIntent.SaveToAlbum(it)) }
        val from = choices.cutoff?.let { Instant.parse(it).toLocalDateTime(zone) }
        val until = choices.until?.let { Instant.parse(it).toLocalDateTime(zone) }
        if (from != null || until != null) {
            add(UiIntent.RangePickerOpen)
            from?.let {
                addAll(
                    listOf(
                        UiIntent.RangeDragStart(it.date),
                        UiIntent.RangeFromHour(it.hour),
                        UiIntent.RangeFromMinute(it.minute),
                    ),
                )
            }
            until?.let {
                addAll(
                    listOf(
                        UiIntent.RangeDragEnd(it.date),
                        UiIntent.RangeUntilHour(it.hour),
                        UiIntent.RangeUntilMinute(it.minute),
                    ),
                )
            }
            add(UiIntent.RangePickerConfirm)
        }
    }
}

/** How long others may still join an event [Person.createEvent] makes after its end, when the caller does not say. */
const val DEFAULT_OPEN_UNTIL_DAYS: Int = 7

/**
 * A person on this host whose screen is read by polling the host's state — what a caller does where the host serves no
 * world events to wait on (a device), or where it waits by polling still.
 */
fun RigClient.person(zone: TimeZone = TimeZone.currentSystemDefault()): Person = object : Person {
    override val screen: ScreenLevers = levers(ScreenLevers)
    override val zone: TimeZone = zone

    override suspend fun awaitScreen(what: String, shown: (UiState) -> Boolean) {
        awaitState { shown(it.ui) }
    }
}
