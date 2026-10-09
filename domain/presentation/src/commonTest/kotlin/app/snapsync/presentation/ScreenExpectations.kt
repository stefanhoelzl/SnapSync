package app.snapsync.presentation

import app.snapsync.model.EventConfig
import app.snapsync.model.JoinedEvent
import app.snapsync.model.UiState
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
