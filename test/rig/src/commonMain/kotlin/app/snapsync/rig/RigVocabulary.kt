package app.snapsync.rig

import kotlinx.serialization.Serializable

/**
 * The control protocol's **closed vocabulary** (capability `testing-architecture`, "One control protocol, served by
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
     * The world's operator levers (capability `full-stack-harness`'s inspector set) — what only a host whose
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
    )

    /** The port-contract verb (`GET /contract`, `POST /contract/<name>`). */
    const val CONTRACT: String = "contract"

    val entries: Set<String> =
        (appEntries + extensionEntries + reads + sharedCommands + appHostCommands + worldLevers + CONTRACT).toSet()

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
