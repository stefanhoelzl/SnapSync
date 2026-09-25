package app.snapsync.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        roundTrip(UiState(Layer.JoiningEvent(eventId = "E", phase = JoinPhase.Loading, notice = "That QR code wasn't valid.")))
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

    private val details = EventDetails(
        "Anna's Birthday",
        eventStart("2026-07-06T00:00:00Z"),
        eventEnd("2026-07-13T00:00:00Z"),
        deletesAt("2026-08-05T00:00:00Z"),
    )

    /** A resolved range with every optional field set, so its serializer's nullable paths are crossed too. */
    private fun range(count: ShareCount) = ResolvedRange(
        windowStart = LocalDateTime(2026, 7, 6, 0, 0),
        windowEnd = LocalDateTime(2026, 7, 13, 0, 0),
        from = LocalDateTime(2026, 7, 7, 9, 30),
        until = LocalDateTime(2026, 7, 12, 18, 0),
        chosenFrom = captureCutoff("2026-07-07T07:30:00Z"),
        chosenUntil = captureCeiling("2026-07-12T16:00:00Z"),
        direction = Direction.UploadOnly,
        commitEnabled = true,
        nowAvailable = false,
        shareCount = count,
        deletesLocal = LocalDateTime(2026, 8, 5, 2, 0),
    )

    /** A form that is NOT the untouched default: custom picks on both ends, receiving and the album off. */
    private val customForm = RangeForm(
        shareOn = true,
        receiveOn = false,
        saveToAlbum = false,
        fromPreset = FromChoice.CUSTOM,
        fromCustom = LocalDateTime(2026, 7, 7, 9, 30),
        untilPreset = UntilChoice.CUSTOM,
        untilCustom = LocalDateTime(2026, 7, 12, 18, 0),
    )

    @Test
    fun the_join_gate_round_trips_with_its_form_and_every_share_count() {
        for (count in listOf(ShareCount.Counting, ShareCount.Unavailable, ShareCount.Ready(42))) {
            roundTrip(
                UiState(
                    Layer.JoiningEvent(
                        eventId = "E",
                        phase = joinPhase(JoinPhase.Detailed.Step.Ready, details),
                        form = customForm,
                        range = range(count),
                    ),
                ),
            )
        }
    }

    @Test
    fun the_joined_layer_round_trips_on_its_reconfigure_surface_with_a_pending_switch() {
        roundTrip(
            UiState(
                Layer.Joined(
                    membership = membership,
                    inviteUrl = "https://snapsync.stho.net/join#v=3&d=x",
                    health = SyncHealth.Syncing(upload = Arrow.PULSING, download = Arrow.STATIC),
                    pendingSwitch = PendingSwitch("F", joinPhase(JoinPhase.Detailed.Step.CommitFailed, details)),
                    renameState = RenameState.Failed("the name is taken"),
                    surface = JoinedSurface.Reconfigure(customForm, range(ShareCount.Ready(7)), saveFailed = true),
                ),
            ),
        )
    }

    @Test
    fun every_health_and_rename_state_round_trips() {
        val healths = listOf(
            SyncHealth.NotStarted(eventStart("2026-07-06T00:00:00Z")),
            SyncHealth.Unattested,
            SyncHealth.Loading,
            SyncHealth.Syncing(upload = Arrow.HIDDEN, download = Arrow.PULSING),
        )
        val renames = listOf(RenameState.Idle, RenameState.InFlight, RenameState.Succeeded)
        for (health in healths) {
            for (rename in renames) {
                roundTrip(
                    UiState(
                        Layer.Joined(
                            membership = membership,
                            inviteUrl = "https://snapsync.stho.net/join#v=3&d=x",
                            health = health,
                            renameState = rename,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun a_single_user_choice_survives_the_round_trip() {
        // One field off its default at a time: a form differing in only that field must still arrive whole, so
        // no field is dropped by an encoder that omits defaults.
        val one = listOf(
            RangeForm(shareOn = false),
            RangeForm(receiveOn = false),
            RangeForm(saveToAlbum = false),
            RangeForm(fromPreset = FromChoice.NOW),
            RangeForm(fromCustom = LocalDateTime(2026, 7, 7, 9, 30)),
            RangeForm(untilPreset = UntilChoice.CUSTOM),
            RangeForm(untilCustom = LocalDateTime(2026, 7, 12, 18, 0)),
        )
        for (form in one) {
            roundTrip(UiState(Layer.JoiningEvent(eventId = "E", phase = JoinPhase.Loading, form = form)))
        }
        val overlays = listOf(Overlays(confirmingLeave = true), Overlays(renaming = true), Overlays(reportingBug = true))
        for (overlay in overlays) {
            roundTrip(UiState(Layer.CreateEvent(), overlay))
        }
        // The range's two defaulted fields, at their defaults.
        roundTrip(
            UiState(
                Layer.JoiningEvent(
                    eventId = "E",
                    phase = joinPhase(JoinPhase.Detailed.Step.ExplainAccess, details),
                    range = range(ShareCount.Counting).copy(deletesLocal = null),
                ),
            ),
        )
    }

    @Test
    fun the_join_phase_helpers_read_only_a_loaded_phase() {
        for (phase in listOf(JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed)) {
            assertNull(phase.details)
            assertNull(phase.step)
        }
        val loaded = joinPhase(JoinPhase.Detailed.Step.Committing, details)
        assertEquals(JoinPhase.Detailed(details, JoinPhase.Detailed.Step.Committing), loaded)
        assertEquals(details, loaded.details)
        assertEquals(JoinPhase.Detailed.Step.Committing, loaded.step)
    }
}
