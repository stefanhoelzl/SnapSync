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
     *  inbound port `PlatformEntries` with the same argument shapes. */
    val appEntries: List<String> = listOf(
        "onForeground", "onBackground", "onPushToken", "onPushTokenFailure", "onSceneContinueActivity",
        "onSilentPush", "onBackgroundTask", "onBackgroundTransfers",
    ).map { "os/app/$it" }

    /** The upload extension root's entry points. */
    val extensionEntries: List<String> = listOf("processRawValue", "onTerminate").map { "os/photokit-ext/$it" }

    /** The device reads. */
    val reads: List<String> = listOf("device/state", "device/logs", "device/gallery")

    /** Device writes whose subject both hosts have: durable sync state and a photo library. */
    val sharedCommands: List<String> = listOf("device/reset", "device/gallery/seed", "device/gallery/wipe")

    /** Device writes that name an operating-system facility only the app host has. */
    val appHostCommands: List<String> = listOf(
        "device/uploaders", "device/process-metrics", "device/upload-jobs/perform", "device/upload-extension/record",
    )

    /**
     * The world's operator levers (`docs/testing.md`'s inspector set) — what only a host whose
     * backend, OS and other members are simulated can pull. Named for what they do, not for the world, so a test
     * that pulls one does not name its host; the app host refuses them.
     */
    val worldLevers: List<String> = listOf(
        "device/backend/offline",
        "device/backend/objects",
        "device/jobs",
        "device/jobs/limit",
        "device/jobs/complete",
        "device/jobs/fail",
        "device/import/fail-next",
        "device/membership/unreadable",
        "device/permission",
        "device/downloads/stage",
        "device/downloads/reconcile",
        "device/album/place",
        "device/foreign-device",
        "device/status/refresh",
        // The integration surface's observable reads of the world's simulated systems (capability
        // `docs/testing.md`, "The seam-to-UI-state integration surface") — what the backend, the crash
        // reporter and the push service recorded.
        "device/backend/union",
        "device/backend/manifest",
        "device/backend/device-config",
        "device/backend/event",
        "device/backend/departed",
        "device/backend/publishes",
        "device/backend/pushes",
        "device/diagnostics/sent",
        // ...and the levers that put those systems, the photo library and the operating system into the states a
        // test starts from.
        "device/backend/min-app-version",
        "device/backend/sweep",
        "device/backend/hold-leave",
        "device/backend/release-leave",
        "device/backend/fail-listing",
        "device/backend/deposit",
        "device/backend/legacy-event",
        "device/backend/refuse-credential",
        "device/clock/advance",
        "device/app-version",
        "device/relaunch",
        "device/selection/change",
        "device/gallery/add",
        "device/gallery/fail-next-enumeration",
        "device/import/suspend-next",
        "device/import/resume",
        "device/import/await-parked",
        "device/logs/append",
        "device/staging/seed-legacy-backlog",
    )

    /**
     * Device facts both kinds of host have — the staging directory's files, the photo library's albums — that the
     * app host does not wire yet. The JVM host reads them off its world; the app host refuses each with
     * [appHostUnwiredRefusals], which says exactly that.
     */
    val deviceFacts: List<String> = listOf("device/staging", "device/album/contents")

    /** Why the app host refuses each [deviceFacts] entry. */
    val appHostUnwiredRefusals: Map<String, String> = deviceFacts.associateWith {
        "the app host has this fact but does not wire a read of it yet; the JVM host reads it off its world"
    }

    /** The port-contract verb (`GET /contract`, `POST /contract/<name>`). */
    const val CONTRACT: String = "contract"

    val entries: Set<String> =
        (appEntries + extensionEntries + reads + sharedCommands + appHostCommands + worldLevers + deviceFacts + CONTRACT)
            .toSet()

    /** Why the app host refuses every world lever — one reason, because they share one cause. */
    val worldLeverRefusals: Map<String, String> = worldLevers.associateWith {
        "a real device's backend, operating system and fellow members are not simulated, so there is nothing " +
            "for this lever to move; it is served by the JVM host, whose world simulates all three"
    }
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
