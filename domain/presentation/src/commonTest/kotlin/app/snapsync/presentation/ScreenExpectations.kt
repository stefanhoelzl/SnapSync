package app.snapsync.presentation

import app.snapsync.model.EventConfig
import app.snapsync.ui.state.CreateForm
import app.snapsync.ui.state.JoinedEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiState
import kotlinx.datetime.TimeZone
import org.orbitmvi.orbit.test.OrbitScopedTestContextInternal
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The next state the container reduced, as the screen shows it. */
internal suspend fun OrbitScopedTestContextInternal<ScreenModel, *, *, *>.awaitShown(): UiState = awaitInternalState().ui

/** The next state the container reduced shows [expected]. */
internal suspend fun OrbitScopedTestContextInternal<ScreenModel, *, *, *>.expectShown(expected: UiState) =
    assertEquals(expected, awaitShown())

/** [this] membership as the joined screen states it, its moments in [zone] — as the container's own display does. */
internal fun EventConfig.shown(zone: TimeZone = TimeZone.UTC): JoinedEvent =
    Display(
        CutoffFormatter(now = { Instant.DISTANT_PAST }, zone = zone),
    ) { _, _, _ -> error("no range here") }.event(this)

/** The create form as a fresh draft — [draft] the draft's number — shows it on [clock]. */
internal fun freshCreateForm(clock: CutoffFormatter, draft: Int = 0): CreateForm =
    Display(clock) { _, _, _ -> error("the create form resolves no event window") }
        .create(CreateDraft.fresh(CreateDraftSession(epoch = draft), clock.nowToTheMinute()))

/** The create layer on [clock] as a fresh draft — [draft] the draft's number — shows it. */
internal fun createLayerAt(
    clock: CutoffFormatter,
    error: ScreenMessage? = null,
    network: NetworkNotice? = null,
    draft: Int = 0,
) = Layer.CreateEvent(freshCreateForm(clock, draft), error, network)
