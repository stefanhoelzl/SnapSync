package app.snapsync.rig

import app.snapsync.levers.DeviceAdvertisement

/**
 * The control protocol's **closed vocabulary** (`docs/testing.md`, "One control protocol, served by
 * two hosts"): every typed service's method (`<service>.<method>`, derived from its interface), and every `/device`
 * verb and `/os` entry point not yet a typed service, as the route without its leading slash.
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
     *  shapes. The typed `os` service makes the same deliveries; these string verbs go with the string protocol. */
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
     * expired), the string form of `played-os`. A real operating system's expiry is its own, so a host honours it only
     * where the background-time holds are mocked — always on the JVM host, and on the app host when its adapter choice
     * mocks them.
     */
    val playedOsEntries: List<String> = listOf("os/app/onExpiry")

    /** The upload extension root's entry points. */
    val extensionEntries: List<String> = listOf("processRawValue", "onTerminate").map { "os/photokit-ext/$it" }

    /**
     * The operator levers over the mocked systems (`docs/testing.md`'s inspector set) — what only a host whose
     * backend, OS and other members are mocked can pull. Named for what they do, not for the host, so a test that pulls
     * one does not name its host. Each is honoured wherever the systems it moves are mocks (`MockLevers.kt`): always on
     * the JVM host, and on the app host for the systems its adapter choice mocks (`docs/testing.md`, "Launch-time
     * adapters") — refused, naming the real system, everywhere else.
     */
    val worldLevers: List<String> = listOf(
        // The integration surface's observable reads of the world's simulated systems (`docs/testing.md`, "The
        // seam-to-UI-state integration surface"): what the operating system recorded of the app, for the systems not yet
        // typed services — the screen, the transfer sessions, the databases and the staged files. The backend's, the
        // crash reporter's, the system UI's, the photo library's and the operating system's own are their services'.
        "device/os-record",
    )

    /**
     * The typed services' methods (`LeverDispatch.kt`), each `<service>.<method>`, served over kRPC — derived from the
     * interfaces, never listed here. Honoured like [worldLevers], by what each service needs.
     */
    val serviceVerbs: List<String> = SERVICE_ENTRIES

    val entries: Set<String> =
        (
            appEntries + playedOsEntries + extensionEntries + worldLevers + serviceVerbs
            )
            .toSet()
}

/** Classify this host's hooks against [RigVocabulary]. */
internal fun RigHooks.advertise(host: String): DeviceAdvertisement {
    // Every typed service this host serves says, method by method, what it refuses: a service whose need is unmet
    // refuses all of it; a host's own service, the methods the host refuses. The world's events are the host's own.
    val served = services + world.service
    val refused = refusals + served.flatMap { it.refused.entries }.associate { it.key to it.value }
    val wired = buildSet {
        triggerGroups.forEach { (root, group) -> group.wired.keys.forEach { add("os/$root/$it") } }
        deviceCommands.keys.forEach { add("device/$it") }
        // Only the services this host serves: a host's own service it serves no implementation of stays unclassified.
        served.forEach { addAll(it.service.entries) }
    }
    val honoured = wired.intersect(RigVocabulary.entries).minus(refused.keys)
    return DeviceAdvertisement(
        host = host,
        honoured = honoured.sorted(),
        refused = refused.entries.sortedBy { it.key }.associate { it.key to it.value },
        unclassified = (RigVocabulary.entries - wired - refused.keys).sorted(),
        outsideVocabulary = (wired - RigVocabulary.entries).sorted(),
    )
}
