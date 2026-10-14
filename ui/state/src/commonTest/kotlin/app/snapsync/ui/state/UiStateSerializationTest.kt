package app.snapsync.ui.state

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `UiState` round-trips through JSON — every layer, not just the easy ones.
 *
 * This is not decoration. `UiState` is `@Serializable` because the control channel serves it as
 * `host.state()`, which is how every on-device check in this repo reads what the screen is showing; a
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

    private val membership = JoinedEvent(
        eventId = "11111111-1111-4111-8111-111111111111",
        name = "Anna's Birthday",
        startsAt = LocalDateTime(2026, 7, 6, 14, 32),
        endsAt = LocalDateTime(2099, 12, 31, 0, 0),
        sharesFrom = LocalDateTime(2026, 7, 6, 14, 32),
        sharesUntil = LocalDateTime(2026, 7, 13, 14, 32),
        shares = true,
        receives = true,
        savesToAlbum = false,
    )

    private val today = LocalDate(2026, 7, 9)

    private fun roundTrip(state: UiState) {
        val encoded = json.encodeToString(UiState.serializer(), encodedInput(state))
        assertEquals(state, json.decodeFromString(UiState.serializer(), encoded))
    }

    // Identity — kept as its own step so a failure reads as "did not round-trip", not "was rebuilt".
    private fun encodedInput(state: UiState) = state

    @Test
    fun the_update_required_layer_round_trips() {
        roundTrip(
            UiState(
                Layer.UpdateRequired(
                    minimumVersion = "0.4",
                    store = StoreName.APP_STORE,
                ),
            ),
        )
        // And with both absences, which are the states the screen renders differently.
        roundTrip(UiState(Layer.UpdateRequired(minimumVersion = null, store = null)))
    }

    @Test
    fun the_create_layers_round_trip() {
        roundTrip(UiState(Layer.CreateEvent(FRESH_CREATE_FORM)))
        roundTrip(UiState(Layer.CreateEvent(FRESH_CREATE_FORM, error = ScreenMessage.CREATE_FAILED)))
        roundTrip(
            UiState(
                Layer.CreateEvent(
                    FRESH_CREATE_FORM.copy(
                        name = "Party",
                        draft = 3,
                        nextStep = CreateStep.Complete(CoarseDuration.Days(2)),
                    ),
                ),
            ),
        )
        roundTrip(
            UiState(
                Layer.CreateEvent(
                    FRESH_CREATE_FORM,
                    error = ScreenMessage.CREATE_FAILED,
                    network = NetworkNotice.OFFLINE,
                ),
            ),
        )
        roundTrip(UiState(Layer.CreateEvent(FRESH_CREATE_FORM, network = NetworkNotice.BLOCKED)))
        roundTrip(UiState(Layer.CreatingEvent))
    }

    @Test
    fun the_joining_layer_round_trips() {
        roundTrip(
            UiState(
                Layer.JoiningEvent(
                    eventId = "11111111-1111-4111-8111-111111111111",
                    stage = JoinStage.Loaded(
                        JoinPhase.Detailed("Anna's Birthday", JoinPhase.Detailed.Step.Ready),
                        range(ShareCount.Unavailable),
                    ),
                ),
            ),
        )
        for (service in ServiceNotice.entries) {
            roundTrip(
                UiState(
                    Layer.JoiningEvent(
                        eventId = "E",
                        stage = JoinStage.Unloaded(JoinPhase.LoadFailed),
                        service = service,
                    ),
                ),
            )
        }
        for (phase in listOf(JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed)) {
            roundTrip(UiState(Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(phase))))
        }
        roundTrip(
            UiState(
                Layer.JoiningEvent(
                    eventId = "E",
                    stage = JoinStage.Unloaded(JoinPhase.Loading),
                    notice = ScreenMessage.INVALID_LINK,
                ),
            ),
        )
        roundTrip(
            UiState(
                Layer.JoiningEvent(
                    eventId = "E",
                    stage = JoinStage.Unloaded(JoinPhase.Loading),
                    asksAccessOnJoin = true,
                ),
            ),
        )
        roundTrip(
            UiState(
                Layer.JoiningEvent(
                    eventId = "E",
                    stage = JoinStage.Unloaded(JoinPhase.LoadFailed),
                    network = NetworkNotice.OFFLINE,
                ),
            ),
        )
        roundTrip(
            UiState(
                Layer.JoiningEvent(
                    eventId = "E",
                    stage = JoinStage.Unloaded(JoinPhase.Loading),
                    network = NetworkNotice.BLOCKED,
                ),
            ),
        )
    }

    @Test
    fun the_joined_layer_round_trips_with_its_overlays() {
        roundTrip(
            UiState(
                Layer.Joined(
                    membership = membership,
                    inviteUrl = "https://snapsync.stho.net/join#v=3&d=x",
                    health = SyncHealth.InSync,
                    today = today,
                    timing = EventTiming.Upcoming(TimeLeft.Hours(5)),
                    counts = SyncCounts(DirectionCount.Off, DirectionCount.Progress(40, 52)),
                ),
                Overlays(confirmingLeave = true, renaming = true, reportingBug = true),
            ),
        )
        roundTrip(
            UiState(
                Layer.Joined(
                    membership = membership,
                    inviteUrl = "https://snapsync.stho.net/join#v=3&d=x",
                    health = SyncHealth.NeedsAccess(canAsk = false),
                    today = today,
                    timing = EventTiming.Ended,
                    canChoosePhotos = true,
                    notice = ScreenMessage.INVALID_LINK,
                ),
            ),
        )
    }

    private fun phase(step: JoinPhase.Detailed.Step) = JoinPhase.Detailed("Anna's Birthday", step)

    /** A range that is NOT an untouched one: a custom pick, receiving and the album off, on a folder-album phone. */
    private fun range(count: ShareCount) = RangeView(
        shareOn = true,
        receiveOn = false,
        saveToAlbum = false,
        albumHolds = AlbumHolds.RECEIVED,
        preset = RangeChoice.CUSTOM,
        from = LocalDateTime(2026, 7, 7, 9, 30),
        until = LocalDateTime(2026, 7, 12, 18, 0),
        windowStart = LocalDateTime(2026, 7, 6, 0, 0),
        windowEnd = LocalDateTime(2026, 7, 13, 0, 0),
        nowAvailable = false,
        today = LocalDate(2026, 7, 7),
        commitEnabled = true,
        shareCount = count,
    )

    @Test
    fun the_join_gate_round_trips_with_its_choices_and_every_share_count() {
        for (count in listOf(ShareCount.Counting, ShareCount.Unavailable, ShareCount.Ready(42))) {
            roundTrip(
                UiState(
                    Layer.JoiningEvent(
                        eventId = "E",
                        stage = JoinStage.Loaded(phase(JoinPhase.Detailed.Step.Ready), range(count)),
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
                    today = today,
                    pendingSwitch = PendingSwitch("F", phase(JoinPhase.Detailed.Step.CommitFailed)),
                    renameState = RenameState.Failed(ScreenMessage.RENAME_NAME_REFUSED),
                    surface = JoinedSurface.Reconfigure(range(ShareCount.Ready(7)), saveFailed = true),
                ),
            ),
        )
    }

    @Test
    fun every_health_and_rename_state_round_trips() {
        val healths = listOf(
            SyncHealth.NoNetwork(NetworkNotice.OFFLINE),
            SyncHealth.NoNetwork(NetworkNotice.BLOCKED),
            SyncHealth.NotStarted,
            SyncHealth.Unattested(),
            SyncHealth.Unattested(RefusalCause.DEVICE_MODIFIED),
            SyncHealth.ServiceFailing(ServiceNotice.TROUBLE),
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
                            today = today,
                            renameState = rename,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun a_single_user_choice_survives_the_round_trip() {
        // One field off its default at a time: a state differing in only that field must still arrive whole, so
        // no field is dropped by an encoder that omits defaults.
        val overlays = listOf(Overlays(confirmingLeave = true), Overlays(renaming = true), Overlays(reportingBug = true)) +
            Overlays(menuOpen = true) + ReportNotice.entries.map { Overlays(reportNotice = it) }
        for (overlay in overlays) {
            roundTrip(UiState(Layer.CreateEvent(FRESH_CREATE_FORM), overlay))
        }
        // The build constants the menu's footer and the report sheet read.
        roundTrip(
            UiState(
                Layer.CreateEvent(FRESH_CREATE_FORM),
                reportDestination = ReportTarget.THIS_DEVICE,
                build = BuildLine("0.12", "2140"),
            ),
        )
    }

    @Test
    fun the_join_phase_helpers_read_only_a_loaded_phase() {
        for (phase in listOf(JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed)) {
            assertNull(phase.step)
        }
        // Read through the phase, as the helpers' callers hold it — on a `Detailed` it would be its own member.
        val loaded: JoinPhase = phase(JoinPhase.Detailed.Step.Committing)
        assertEquals(JoinPhase.Detailed.Step.Committing, loaded.step)
    }

    @Test
    fun a_phase_names_itself_in_a_report_as_its_case_does() {
        val names = listOf(
            JoinPhase.Loading,
            JoinPhase.NotFound,
            JoinPhase.LoadFailed,
            JoinPhase.Closed,
            JoinPhase.WrongLink,
            phase(JoinPhase.Detailed.Step.Ready),
        ).map { it.screenName }
        assertEquals(listOf("Loading", "NotFound", "LoadFailed", "Closed", "WrongLink", "Detailed"), names)
    }

    @Test
    fun the_join_surface_reads_its_phase_from_the_stage_and_a_range_only_once_loaded() {
        val unloaded = Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(JoinPhase.LoadFailed))
        assertEquals(JoinPhase.LoadFailed, unloaded.phase)
        assertNull(unloaded.range)
        val ready = phase(JoinPhase.Detailed.Step.Ready)
        val loaded = Layer.JoiningEvent(eventId = "E", stage = JoinStage.Loaded(ready, range(ShareCount.Counting)))
        assertEquals(ready, loaded.phase)
        assertEquals(range(ShareCount.Counting), loaded.range)
    }

    @Test
    fun a_stage_decoded_without_what_it_must_carry_is_refused_never_defaulted() {
        // A loaded stage holds its range by construction; on the wire a client cannot hand the screen one without it.
        val loaded = JoinStage.Loaded(phase(JoinPhase.Detailed.Step.Ready), range(ShareCount.Counting))
        val encoded = json.encodeToString(JoinStage.serializer(), loaded)
        val withoutRange = encoded.replace(Regex(""","range":\{.*}}$"""), "}")
        assertFailsWith<SerializationException> { json.decodeFromString(JoinStage.serializer(), withoutRange) }
        val withoutPhase = """{"type":"app.snapsync.ui.state.JoinStage.Unloaded"}"""
        assertFailsWith<SerializationException> { json.decodeFromString(JoinStage.serializer(), withoutPhase) }
    }
}
