package app.snapsync.journeys

import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.UiState
import app.snapsync.wire.api.Limits
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.fail

// What every journey reads: the screen's facts, and the backend's public surface as a member reads it.

/** An address the program passes as `snapsync.journey.<name>`; the journeys fail, never skip, without one. */
internal fun address(name: String): String = System.getProperty("snapsync.journey.$name")
    ?: fail("snapsync.journey.$name is not set — the journeys run only against the addresses the `journeys` program passes")

/** The event the joined screen shows, or `null` off it. */
internal fun joinedTo(ui: UiState): String? = (ui.layer as? Layer.Joined)?.membership?.eventId

/** The joined screen's shared count — nothing counted where the screen shows none. */
internal fun sharedCount(ui: UiState): DirectionCount.Progress = counts(ui) { it.shared }

/** The joined screen's received count — nothing counted where the screen shows none. */
internal fun receivedCount(ui: UiState): DirectionCount.Progress = counts(ui) { it.received }

private fun counts(ui: UiState, direction: (SyncCounts) -> DirectionCount): DirectionCount.Progress =
    (ui.layer as? Layer.Joined)?.counts?.let(direction) as? DirectionCount.Progress ?: DirectionCount.Progress(0, 0)

/** At least [n] through, and nothing left: the count a journey's act should end in. */
internal fun DirectionCount.Progress.reached(n: Int): Boolean = done >= n && complete

/** The event's union at [backend] — the backend's public surface, read as a member reads it. */
internal suspend fun union(http: HttpClient, backend: String, event: String): List<JsonObject> {
    val body = http.get("$backend/events/$event/files") { header(Limits.VERSION_HEADER, SERVED_VERSION) }.bodyAsText()
    return runCatching { Json.parseToJsonElement(body).jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
}

/** The app version a member's requests declare: above every gate the backend sets. */
internal const val SERVED_VERSION = "99.0"
