package app.snapsync.rig

import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.event.EventId
import app.snapsync.domain.invite.InviteKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.format.EventKeyText
import app.snapsync.format.InviteLinkText
import app.snapsync.mock.MockedSystem
import app.snapsync.ui.state.Layer

// The JVM host's world, its own services and what it refuses of the shared vocabulary (`docs/testing.md`, "One control
// protocol, served by two hosts"). The typed services act on this host's world (`LeverDispatch.kt`) — every system a
// mock, the backend aside when it is the real `api/`.

/** The JVM host's world: every mock of its device, of which the app runs over all — the backend's unless it is `api/`. */
internal fun jvmWorld(rig: JvmRig, os: PlayedOs): MockWorld = MockWorld(
    device = rig.mocks,
    mocked = MockedSystem.entries.toSet() - setOfNotNull(
        MockedSystem.BACKEND.takeIf {
            rig.backend.operator(rig.mocks) == null
        },
    ),
    reach = rig.reach,
    reachRefusal = "",
    operatorRefusal = { lever -> rig.backend.unavailable(lever) },
    version = rig.version,
    store = rig.store,
    os = os,
    ownDeviceId = { rig.mocks.ownDeviceId },
    joinedEventId = { joinedEventId(rig) },
    controls = rig.mocks.devControls.operator,
    clockOwner = "the JVM host's loop owns the clock: `world.advanceTo` moves it, firing every timer due on the way",
    metricsRefusal = null,
)

/**
 * The joined event: the one the screen's host currently holds, or — where no screen was built — the one the
 * membership file on the device's disk names. `null` when neither does. A screen that was shown anything proves its
 * host assembled, so asking assembles no screen a background launch never built.
 *
 * The host's state, never what the screen was last shown: the screen is handed each state by a collector the host
 * launches, so it can lag the state `host.state()` reports — and a test that has just made the databases
 * unreadable would then find no joined event at all.
 */
internal fun joinedEventId(rig: JvmRig): String? {
    val screenBuilt = rig.mocks.screen.shown.value != null
    val shown = if (screenBuilt) rig.app.composed.host.screen.value.layer as? Layer.Joined else null
    return shown?.membership?.eventId
        ?: (
            MembershipRepository(SharedDatabase(rig.mocks.databases.inspectorPort(), legacy = null)).read()
                as? MembershipRead.Joined
            )?.membership?.event?.id?.value
}

/** What the JVM host refuses of the shared vocabulary, each with its reason. */
internal fun jvmRefusals(
    rig: JvmRig,
): Map<String, String> = rig.world.serviceRefusals(rig.host)

/**
 * The JVM host's own services (`HostServices.kt`): the app's process is this JVM's, so it relaunches and reinstalls it;
 * its library is the mock's, its upload-job queue the mock's own operator plays, and it composes no launch-time adapters
 * and runs no contract in-process.
 */
internal fun jvmHostServices(rig: JvmRig, hooks: RigHooks): List<HostService<*>> = listOf(
    rig.world.screenService(rig.lane, ui = null, shown = { rig.mocks.screen.shown.value }),
    hostService(
        hooks,
        core = { rig.app.composed.core },
        host = { rig.app.composed.host },
        // Process death and a cold foreground launch: the new app's host is assembled — its subscriptions installed, the
        // startup sweep among them — and shown, as a phone brings a scene up. A cold BACKGROUND launch builds no screen.
        relaunch = honoured { scene ->
            rig.app.relaunch()
            if (scene) rig.showScreen()
        },
        // Deleting the app and installing it again: its files, databases and user defaults are gone, the Keychain, the
        // photo library and the backend keep theirs (what a reinstall forgets), then a cold foreground launch.
        reinstall = honoured {
            rig.mocks.uninstallApp()
            rig.app.relaunch()
            rig.showScreen()
        },
        // This world recorded every key the app drew and the event each keyed create made, so the invite carries it.
        invite = honoured { event ->
            val id = EventId.parse(event) ?: throw IllegalArgumentException("'$event' is not an event id")
            val key = rig.mocks.eventKeys.keyOf(event)?.let(EventKeyText::encode)
            InviteLinkText.format(InviteLink(id, key?.let(::InviteKey)))
        },
        disk = refusing(
            "the JVM host's files are the mocked disk's, which a test reads through the app's own outcomes",
        ),
        processMetrics = refusing(
            "the JVM host plays process-metric reports through the typed service `process-metrics`, `deliver`",
        ),
    ),
    galleryService(
        seed = honoured { n, kind -> rig.world.seedMockLibrary(n, kind) },
        wipe = refusing(
            "a mocked photo library is fresh for every host, so there is nothing to wipe; the wipe's answer is PhotoKit's " +
                "source, album and folder census, which a mocked library has no counterpart for",
        ),
        addMoving = refusing(
            "a mocked photo library holds no media bytes for a camera's files to become; add a moving photo through the " +
                "typed `library` service's `add` of kind `live-photo`",
        ),
        read = honoured(rig.world.mockGalleryReader()),
    ),
    refusedAdaptersService(
        "the JVM root's caller chooses real or mock per port when it composes the app (JvmApp), so there is no launch " +
            "adapter choice to read or write; an app host's rig build reads one at every start",
    ),
    contractService(
        emptyList(),
        refusal = "host JVM runs no contract in-process: JVM contract bindings run under Gradle in the canonical check",
    ),
    uploadJobService(
        process = refusing("the mocked upload-job queue is the host's own; job sets cannot be handed in"),
        perform = refusing(
            "the mocked upload-job queue is played by its typed service (`upload-queue`), not by an operating system",
        ),
        extensionRecord = refusing("the extension's registration is mocked on this host; its record is the mock's"),
    ),
)
