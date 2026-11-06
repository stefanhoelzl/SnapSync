package app.snapsync.presentation

import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.request.CreateRequest
import app.snapsync.query.RequestStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDateTime

/** The create form's own taps: its name as typed, Create, and the back-dated sheet's answer. */
class CreateFlow internal constructor(
    private val cells: ScreenCells,
    private val display: Display,
    private val cutoffFormatter: CutoffFormatter,
    private val commands: UserCommands,
    private val creationStatus: StateFlow<RequestStatus<CreateResult>>,
    private val gate: JoinGate,
) {
    private val local = cells.local

    /**
     * Create a new event with [name], over `[startsAt, endsAt]`: it is minted and, once minted, opens the same join
     * gate a scanned invite does. Permission is not consulted here — a missing grant surfaces afterward. The in-flight
     * and failure outcomes arrive back through the creation status; nothing is reduced here.
     *
     * [startsAt] and [endsAt] arrive as the screen's **local** wall-clock picks and are converted here, through the same
     * [CutoffFormatter] the join surface uses — so the app has exactly one origin of "now" and one local→UTC
     * conversion, and `:ui:screens` stays free of any clock or timezone knowledge. [openUntil] is the day guests may join
     * until, for an event created after its end (the back-dated sheet's choice); `null` otherwise.
     */
    fun onCreateEvent(name: String, startsAt: LocalDateTime, endsAt: LocalDateTime, openUntil: OpenUntil? = null) =
        cells.runner.guarded(Guarded.Create) {
            val id = cells.ids.next()
            local.update { it.copy(createRequest = id) }
            commands.create.fire(
                CreateRequest(
                    id,
                    name,
                    EventStart(cutoffFormatter.toCutoff(startsAt)),
                    EventEnd(cutoffFormatter.toCutoff(endsAt)),
                    openUntil,
                    cutoffFormatter.zoneId(),
                ),
            )
            // The guard is held until the create is answered: one at a time, or a second tap mints a second event.
            // A create that minted nothing is told on the form, from the status; only a minted event opens the gate.
            val created = creationStatus.answerTo(id)
            local.update { it.copy(createRequest = null) }
            if (created is CreateResult.Created) gate.eventCreated(created.eventId, created.key)
        }

    /**
     * The name as typed so far. Applied at once rather than as an intent, so a Create tapped right after the last
     * keystroke submits it.
     */
    fun onName(name: String) = local.update { it.copy(create = it.create.copy(name = name)) }

    /**
     * Create the event the form describes — inert unless the draft may be created: complete, inside the window. A range
     * already over when Create is tapped first asks how long guests have to join (decision record
     * `changes/back-dated-events`, D9); the answer, [onKeepOpenFor], creates it.
     */
    fun onConfirm() {
        val draft = local.value.create
        val end = display.creatableEnd(draft) ?: return
        if (cutoffFormatter.toCutoff(end) < cutoffFormatter.nowCutoff()) {
            local.update { it.copy(create = it.create.copy(askingHowLong = true)) }
            return
        }
        onCreateEvent(draft.name, draft.range.from, end)
    }

    /**
     * The back-dated sheet's answer: create the event the form describes, guests joining for [days] more days. Inert
     * unless the sheet is asking and [days] is one of its choices — a tap that outlived the sheet creates nothing. The
     * sheet closes either way, so a create that fails returns to the form and the next Create asks again.
     */
    fun onKeepOpenFor(days: Int) {
        val draft = local.value.create
        if (!draft.askingHowLong || days !in KEEP_OPEN_DAYS) return
        onKeepOpenDismiss()
        val end = display.creatableEnd(draft) ?: return
        onCreateEvent(draft.name, draft.range.from, end, cutoffFormatter.openFor(days))
    }

    /** The back-dated sheet closed unchosen: the form stays exactly as the host left it, and nothing is created. */
    fun onKeepOpenDismiss() = local.update { it.copy(create = it.create.copy(askingHowLong = false)) }
}
