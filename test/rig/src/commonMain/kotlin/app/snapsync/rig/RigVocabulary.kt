package app.snapsync.rig

import app.snapsync.levers.DeviceAdvertisement

/**
 * The control protocol's **closed vocabulary** (`docs/testing.md`, "One control protocol, served by two hosts"): every
 * typed service's method, as `<service>.<method>`, derived from its interface.
 *
 * Each host classifies every entry: **honoured** (it serves it) or **refused, with a reason** ([RigHooks.refusals] and
 * each served service's own). A refused entry answers `LeverRefused` with its reason; `GET /device` answers the
 * classification; an entry a host leaves unclassified makes `GET /device` fail naming it. That is what lets a test ask
 * a host what it supports instead of guessing from which host it is — the same move the port contracts make with
 * `Unreachable`.
 */
object RigVocabulary {
    /** The typed services' methods (`LeverDispatch.kt`), each `<service>.<method>`, served over kRPC. */
    val entries: Set<String> = SERVICE_ENTRIES.toSet()
}

/** Classify this host's hooks against [RigVocabulary]. */
internal fun RigHooks.advertise(host: String): DeviceAdvertisement {
    // Every typed service this host serves says, method by method, what it refuses: a service whose need is unmet
    // refuses all of it; a host's own service, the methods the host refuses. The world's events are the host's own.
    val served = services + world.service
    val refused = refusals + served.flatMap { it.refused.entries }.associate { it.key to it.value }
    // Only the services this host serves: a host's own service it serves no implementation of stays unclassified.
    val wired = served.flatMap { it.service.entries }.toSet()
    val honoured = wired.intersect(RigVocabulary.entries).minus(refused.keys)
    return DeviceAdvertisement(
        host = host,
        honoured = honoured.sorted(),
        refused = refused.entries.sortedBy { it.key }.associate { it.key to it.value },
        unclassified = (RigVocabulary.entries - wired - refused.keys).sorted(),
        outsideVocabulary = (wired - RigVocabulary.entries).sorted(),
    )
}
