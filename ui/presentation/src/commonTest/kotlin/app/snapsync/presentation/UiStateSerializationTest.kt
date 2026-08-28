package app.snapsync.presentation

import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.deletesAt
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `UiState` round-trips through JSON — every layer, not just the easy ones.
 *
 * This is not decoration. `UiState` is `@Serializable` because the control channel serves it as
 * `/device/state`, which is how every on-device check in this repo reads what the screen is showing; a
 * layer that failed to round-trip would make the app undriveable at exactly the moment someone needed
 * to see it. The encoder is compiler-generated, so what this really pins is that the sealed tree stays
 * serializable as it grows — a new layer carrying a non-serializable field fails HERE rather than on a
 * phone.
 *
 * It also retires a claim this module's coverage note used to make — that the generated serializer
 * accessors on the sealed tree are unreachable by any test. They are reachable; this reaches them.
 */
class UiStateSerializationTest {

    private val json = Json

    private val membership = EventConfig(
        eventId = "11111111-1111-4111-8111-111111111111",
        name = "Anna's Birthday",
        minPhotoDate = captureCutoff("2026-07-06T14:32:11Z"),
        maxPhotoDate = captureCeiling("2026-07-13T14:32:11Z"),
    )

    private fun roundTrip(state: UiState) {
        val encoded = json.encodeToString(UiState.serializer(), encodedInput(state))
        assertEquals(state, json.decodeFromString(UiState.serializer(), encoded))
    }

    // Identity — kept as its own step so a failure reads as "did not round-trip", not "was rebuilt".
    private fun encodedInput(state: UiState) = state

    @Test
    fun the_update_required_layer_round_trips() {
        roundTrip(UiState(Layer.UpdateRequired(minimumVersion = "0.4", storeUrl = "https://apps.apple.com/de/app/id1")))
        // And with both absences, which are the states the screen renders differently.
        roundTrip(UiState(Layer.UpdateRequired(minimumVersion = null, storeUrl = null)))
    }

    @Test
    fun the_create_layers_round_trip() {
        roundTrip(UiState(Layer.CreateEvent()))
        roundTrip(UiState(Layer.CreateEvent(error = "Couldn't reach the server")))
        roundTrip(UiState(Layer.CreatingEvent))
    }

    @Test
    fun the_joining_layer_round_trips() {
        roundTrip(
            UiState(
                Layer.JoiningEvent(
                    eventId = "11111111-1111-4111-8111-111111111111",
                    phase = joinPhase(
                        JoinPhase.Detailed.Step.Ready,
                        EventDetails(
                            "Anna's Birthday",
                            eventStart("2026-07-06T00:00:00Z"),
                            eventEnd("2026-07-13T00:00:00Z"),
                            deletesAt("2026-08-05T00:00:00Z"),
                        ),
                    ),
                ),
            ),
        )
        for (phase in listOf(JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed)) {
            roundTrip(UiState(Layer.JoiningEvent(eventId = "E", phase = phase)))
        }
    }

    @Test
    fun the_joined_layer_round_trips_with_its_overlays() {
        roundTrip(
            UiState(
                Layer.Joined(
                    membership = membership,
                    inviteUrl = "https://snapsync.stho.net/join#v=3&d=x",
                    health = SyncHealth.InSync,
                ),
                Overlays(confirmingLeave = true, renaming = true, reportingBug = true),
            ),
        )
        roundTrip(
            UiState(
                Layer.Joined(
                    membership = membership,
                    inviteUrl = "https://snapsync.stho.net/join#v=3&d=x",
                    health = SyncHealth.NeedsAccess(PermissionStatus.DENIED),
                    ended = true,
                    canChoosePhotos = true,
                    notice = "something worth saying",
                ),
            ),
        )
    }
}
