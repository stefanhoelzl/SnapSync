package app.snapsync.rig

import kotlinx.serialization.Serializable

/**
 * The control protocol's **closed vocabulary** (`docs/testing.md`, "One control protocol, served by
 * two hosts"): every `/device` verb, `/os` entry point and the contract verb either host may serve, as the route
 * without its leading slash. `/user` is not listed: both hosts invoke the one shared table, so it cannot differ.
 *
 * Each host classifies every entry: **honoured** (it wires it) or **refused, with a reason** ([RigHooks.refusals]).
 * A refused entry answers `409` with its reason; `GET /device` answers the classification; an entry a host leaves
 * unclassified makes `GET /device` fail naming it. That is what lets a test ask a host what it supports instead of
 * guessing from which host it is — the same move the port contracts make with `Unreachable`.
 *
 * Adding an entry is a deliberate act: every host's hook must then classify it, or its `GET /device` fails.
 */
object RigVocabulary {

    /** The app root's wired entry points — the iOS shell's `SnapSyncRoot` names, which the JVM host maps onto the
     *  entry ports (`Lifecycle`, `Links`, `PushNotifications`, `Wake`, the transfer sessions) with the same argument
     *  shapes. */
    val appEntries: List<String> = listOf(
        "onForeground",
        "onBackground",
        "onPushToken",
        "onPushTokenFailure",
        "onSceneContinueActivity",
        "onSilentPush",
        "onBackgroundTask",
        "onBackgroundTransfers",
    ).map { "os/app/$it" }

    /**
     * What only an operating system that is PLAYED can deliver: its expiry — every completion handler it holds and every
     * background-time hold told their time is up (`os/app/onExpiry`; `?arg=next` hands the next handler over already
     * expired). A real operating system's expiry is its own, so a host honours it only where the background-time holds
     * are mocked — always on the JVM host, and on the app host when its adapter choice mocks them.
     */
    val playedOsEntries: List<String> = listOf("os/app/onExpiry")

    /** The upload extension root's entry points. */
    val extensionEntries: List<String> = listOf("processRawValue", "onTerminate").map { "os/photokit-ext/$it" }

    /** The device reads. */
    val reads: List<String> = listOf("device/state", "device/logs", "device/gallery")

    /** Device writes whose subject both hosts have: durable sync state and a photo library. */
    val sharedCommands: List<String> = listOf("device/reset", "device/gallery/seed", "device/gallery/wipe")

    /** Device writes that name an operating-system facility only the app host has. */
    val appHostCommands: List<String> = listOf(
        "device/uploaders",
        "device/process-metrics",
        "device/upload-jobs/perform",
        "device/upload-extension/record",
        "device/disk",
    )

    /**
     * The operator levers over the mocked systems (`docs/testing.md`'s inspector set) — what only a host whose
     * backend, OS and other members are mocked can pull. Named for what they do, not for the host, so a test that pulls
     * one does not name its host. Each is honoured wherever the systems it moves are mocks (`MockLevers.kt`): always on
     * the JVM host, and on the app host for the systems its adapter choice mocks (`docs/testing.md`, "Launch-time
     * adapters") — refused, naming the real system, everywhere else.
     */
    val worldLevers: List<String> = listOf(
        "device/backend/offline",
        "device/backend/objects",
        "device/jobs",
        "device/jobs/limit",
        "device/jobs/complete",
        "device/jobs/fail",
        "device/uploads",
        "device/uploads/complete",
        "device/import/fail-next",
        "device/membership/unreadable",
        "device/permission",
        "device/downloads/stage",
        "device/album/place",
        "device/album/hold-adds",
        "device/album/kind",
        "device/album/delete",
        "device/invite-link-hints",
        "device/encrypt-new-events",
        "device/foreign-device",
        // The integration surface's observable reads of the world's simulated systems (capability
        // `docs/testing.md`, "The seam-to-UI-state integration surface") — what the backend, the crash
        // reporter and the push service recorded.
        "device/backend/union",
        "device/backend/manifest",
        "device/backend/device-config",
        "device/backend/event",
        "device/backend/departed",
        "device/backend/publishes",
        "device/backend/reads",
        "device/backend/pushes",
        "device/diagnostics/sent",
        // What the operating system recorded of the app: the completion handlers it handed over and got back, the
        // screen, the selection observer, the heartbeat requests, the background-time holds, the push registrations
        // and the transfer sessions.
        "device/os-record",
        // ...and the levers that put those systems, the photo library and the operating system into the states a
        // test starts from.
        "device/backend/min-app-version",
        "device/backend/sweep",
        "device/backend/hold",
        "device/backend/next-event-id",
        "device/backend/complete",
        "device/backend/fail-listing",
        "device/backend/deposit",
        "device/backend/legacy-event",
        "device/backend/refuse-credential",
        "device/backend/refuse-attestation",
        "device/backend/wipe-bytes",
        "device/clock/advance",
        "device/network",
        "device/conditions",
        "device/app-version",
        "device/relaunch",
        "device/reinstall",
        "device/selection/change",
        "device/gallery/add",
        "device/gallery/remove",
        "device/gallery/fail-next-enumeration",
        "device/gallery/hold-enumeration",
        "device/import/suspend-next",
        "device/import/resume",
        "device/import/await-parked",
        "device/logs/append",
        "device/staging/seed-legacy-backlog",
    )

    /**
     * Device facts — the staging directory's files, the photo library's albums — read off the mocked disk and library,
     * so honoured like [worldLevers] wherever those are mocks.
     */
    val deviceFacts: List<String> = listOf("device/staging", "device/album/contents")

    /**
     * The launch-time adapters (`docs/testing.md`, "Launch-time adapters"): read the choice this launch runs
     * (`device/adapters/current`), write the next one and exit (`device/adapters`), or delete it with every mocked system's state
     * and exit (`device/adapters/clear`). The app host's alone: the JVM root's caller chooses real or mock per port as it
     * composes.
     */
    val adapterCommands: List<String> = listOf("device/adapters", "device/adapters/current", "device/adapters/clear")

    /** Why the JVM host refuses every [adapterCommands] entry. */
    val adapterRefusals: Map<String, String> = adapterCommands.associateWith {
        "the JVM root's caller chooses real or mock per port when it composes the app (JvmApp), so there is no launch " +
            "adapter choice to read or write; the app host's rig build reads one at every start"
    }

    /** The port-contract verb (`GET /contract`, `POST /contract/<name>`). */
    const val CONTRACT: String = "contract"

    val entries: Set<String> =
        (
            appEntries + playedOsEntries + extensionEntries + reads + sharedCommands + appHostCommands + worldLevers + deviceFacts +
                adapterCommands + CONTRACT
            )
            .toSet()
}

/** `GET /device`'s body: what this host honours and refuses, and anything it failed to classify. */
@Serializable
data class DeviceAdvertisement(
    val host: String,
    val honoured: List<String>,
    val refused: Map<String, String>,
    /** Vocabulary entries this host neither wires nor refuses. Non-empty answers `500`. */
    val unclassified: List<String>,
    /** Verbs this host wires that the vocabulary does not name. Non-empty answers `500`. */
    val outsideVocabulary: List<String>,
)

/** Classify this host's hooks against [RigVocabulary]. */
internal fun RigHooks.advertise(host: String, readsHonoured: Boolean = true): DeviceAdvertisement {
    val wired = buildSet {
        triggerGroups.forEach { (root, group) -> group.wired.keys.forEach { add("os/$root/$it") } }
        deviceCommands.keys.forEach { add("device/$it") }
        if (readsHonoured) addAll(RigVocabulary.reads)
        if (RigVocabulary.CONTRACT !in refusals) add(RigVocabulary.CONTRACT)
    }
    val honoured = wired.intersect(RigVocabulary.entries).minus(refusals.keys)
    return DeviceAdvertisement(
        host = host,
        honoured = honoured.sorted(),
        refused = refusals.entries.sortedBy { it.key }.associate { it.key to it.value },
        unclassified = (RigVocabulary.entries - wired - refusals.keys).sorted(),
        outsideVocabulary = (wired - RigVocabulary.entries).sorted(),
    )
}
