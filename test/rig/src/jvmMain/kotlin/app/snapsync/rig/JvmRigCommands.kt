package app.snapsync.rig

import app.snapsync.mock.MockedSystem
import app.snapsync.model.ConfigRead
import app.snapsync.model.Layer
import app.snapsync.services.config.ConfigService

// The JVM host's `/device` writes, its gallery read, and what it refuses of the shared vocabulary (`docs/testing.md`,
// "One control protocol, served by two hosts"). The operator levers and reads are the shared table (`MockLevers.kt`)
// over this host's world — every system a mock, the backend aside when it is the real `api/`; the shared commands take
// the same parameters and answer the same shape as on the app host. What the app does in answer is observed through the
// screen and those same records.

/** The JVM host's world: every mock of its device, of which the app runs over all — the backend's unless it is `api/`. */
internal fun jvmWorld(rig: JvmRig, os: PlayedOs): MockWorld = MockWorld(
    device = rig.mocks,
    mocked = MockedSystem.entries.toSet() - setOfNotNull(MockedSystem.BACKEND.takeIf { rig.backend.operator(rig.mocks) == null }),
    reach = rig.reach,
    reachRefusal = "",
    operatorRefusal = { lever -> rig.backend.unavailable(lever) },
    version = rig.version,
    os = os,
    ownDeviceId = { rig.mocks.ownDeviceId },
    joinedEventId = { joinedEventId(rig) },
    setInviteLinkHints = { rig.mocks.devControls.operator.inviteLinkHints = it },
)

/** The JVM host's `/device` write commands. */
internal fun jvmDeviceCommands(rig: JvmRig): Map<String, RigCommand> = rig.world.honouredLevers() + mapOf(
    "reset" to resetCommand(reset = { rig.mocks.devControls.operator.reset() }),
    // The per-uploader switch, on the mocked development controls. This host composes both uploaders, as a phone does,
    // and both run; a test about one of them switches the other off here, since both would take the same photos.
    "uploaders" to uploadersCommand(
        pin = { rig.mocks.devControls.operator.pin },
        setPin = { rig.mocks.devControls.operator.pin = it },
        osSupportsOsDrivenUpload = { false },
        permission = { rig.app.core.photoPermission.value },
        reconcile = { rig.app.core.uploadTransitions.onOverrideChanged() },
    ),
    "gallery/seed" to seedCommand { n, kind -> rig.world.seedMockLibrary(n, kind) },
    // Process death and a cold foreground launch: the new app's host is assembled — its subscriptions installed, the
    // startup sweep among them — and shown, as a phone brings a scene up. `scene=false` is a cold BACKGROUND launch
    // instead — the operating system starting the process for a wake, with no scene: nothing builds a screen, and
    // nothing that reads the screen (`/device/state`, `/user`) may be asked before an `onForeground` brings one up.
    "relaunch" to RigCommand { params, _ ->
        val scene = params["scene"]?.toBoolean() ?: true
        rig.app.relaunch()
        if (scene) rig.showScreen()
        CommandResult.ok("""{"relaunched":true,"scene":$scene}""")
    },
    // Deleting the app and installing it again: its files, databases and user defaults are gone, the Keychain, the
    // photo library and the backend keep theirs (capability `receiving-photos`, what a reinstall forgets), then a cold
    // foreground launch as `relaunch` makes one.
    "reinstall" to RigCommand { _, _ ->
        rig.mocks.uninstallApp()
        rig.app.relaunch()
        rig.showScreen()
        CommandResult.ok("""{"reinstalled":true}""")
    },
)

/**
 * The joined event: the one the screen's host currently holds, or — where no screen was built — the one the
 * membership file on the device's disk names. `null` when neither does. A screen that was shown anything proves its
 * host assembled, so asking assembles no screen a background launch never built.
 *
 * The host's state, never what the screen was last shown: the screen is handed each state by a collector the host
 * launches, so it can lag the state `/device/state` reports — and a test that has just made the membership file
 * unreadable would then find no joined event at all.
 */
internal fun joinedEventId(rig: JvmRig): String? {
    val screenBuilt = rig.mocks.screen.operator.shown.value != null
    return (if (screenBuilt) rig.app.host.container.stateFlow.value.layer as? Layer.Joined else null)?.membership?.eventId
        ?: (ConfigService(rig.mocks.disk.port(), rig.mocks.clock.port()).read() as? ConfigRead.Joined)?.config?.eventId
}

/** What the JVM host refuses of the shared vocabulary, each with its reason. */
internal fun jvmRefusals(rig: JvmRig): Map<String, String> = rig.world.leverRefusals() + RigVocabulary.adapterRefusals + buildMap {
    put(
        "device/gallery/wipe",
        "a mocked photo library is fresh for every host, so there is nothing to wipe; the wipe's answer is PhotoKit's " +
            "source, album and folder census, which a mocked library has no counterpart for",
    )
    put(
        "device/process-metrics",
        "process-metric reports are MetricKit's; the JVM runs no process the operating system measures",
    )
    put("device/disk", "the JVM host's files are the mocked disk's (POST /device/staging lists its staging)")
    RigVocabulary.appHostCommands.filter { it.startsWith("device/upload-") }.forEach {
        put(it, "the mocked upload-job queue is operated by the jobs verbs, not by an operating system to play")
    }
    put(
        RigVocabulary.CONTRACT,
        "host JVM runs no contract in-process: JVM contract bindings run under Gradle in the canonical check",
    )
}
